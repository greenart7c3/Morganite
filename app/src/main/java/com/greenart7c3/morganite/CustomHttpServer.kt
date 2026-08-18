package com.greenart7c3.morganite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.greenart7c3.morganite.logs.MorganiteLog
import com.greenart7c3.morganite.models.SettingsManager
import com.greenart7c3.morganite.service.FileStore
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.downloadFirstEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.torproject.jni.TorService
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Downloads are copied (and hashed) in chunks of this size. The old 8 KiB buffer
// meant ~8x more loop iterations, stream syscalls and digest calls per blob than
// necessary, which is CPU time (battery) spent per megabyte downloaded.
private const val COPY_BUFFER_SIZE = 64 * 1024

// Tor needs far more patience than OkHttp's 10s defaults. For an .onion URL the
// SOCKS CONNECT only completes after Tor has built a circuit and finished the
// rendezvous handshake with the hidden service (typically 6 hops total, easily
// 10-30s, longer on cold mobile connections), so a short connect timeout fails
// before Tor even finishes. The call timeout only bounds runaway stalls — a
// healthy download should never reach it.
private const val TOR_CONNECT_TIMEOUT_MS = 60_000L
private const val TOR_READ_TIMEOUT_MS = 60_000L
private const val TOR_CALL_TIMEOUT_MS = 5 * 60_000L

// A timed-out fetch is retried once per server before giving up on it and moving
// to the next server. Only timeouts that happen before anything was sent to the
// local client are retried (see RetryableTimeoutException).
private const val MAX_FETCH_ATTEMPTS = 2
private const val RETRY_DELAY_MS = 1_000L

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** A fetch timed out before anything was sent to the local client, so the attempt can be retried. */
private class RetryableTimeoutException(cause: Throwable) : Exception(cause)

/**
 * True when this failure (or any of its causes) is a timeout. OkHttp surfaces its
 * connect/read/call timeouts as [SocketTimeoutException]; HTTP/2 stream timeouts
 * come from OkHttp's internal StreamTimeoutException.
 */
private fun Throwable.isTimeout(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is SocketTimeoutException) return true
        if (t::class.java.simpleName == "StreamTimeoutException") return true
        t = t.cause
    }
    return false
}

private fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[b ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
    }
    return String(out)
}

class CustomHttpServer(
    val fileStore: FileStore,
    val settingsManager: SettingsManager,
) {
    val isRunning = MutableStateFlow(value = false)
    val torStatus = MutableStateFlow(TorService.STATUS_OFF)

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private var rootClient = OkHttpClient.Builder().build()
    private var torClient = OkHttpClient.Builder().build()
    val socketBuilder = BasicOkHttpWebSocket.Builder { _ -> if (settingsManager.settings.value.useTor) torClient else rootClient }
    val nostrClient = NostrClient(socketBuilder)

    private val fallbackRelays = listOf(
        NormalizedRelayUrl("wss://nostr.land"),
        NormalizedRelayUrl("wss://nos.lol"),
        NormalizedRelayUrl("wss://relay.damus.io"),
    )

    // Number of author-server lookups currently in flight. downloadFirstEvent only
    // closes the subscription, not the relay socket, and NostrClient's RelayPool
    // keeps that socket alive with an auto-reconnect loop. We therefore disconnect
    // once the last lookup finishes so no WebSocket lingers (and reconnects) in the
    // background — the main source of battery drain while the app is idle.
    private val activeAuthorLookups = AtomicInteger(0)

    // Tor is started at app startup when the setting is enabled (so the first
    // tor-routed request doesn't pay the cold-bootstrap latency) and restarted on
    // demand by ensureTorReady() if it is ever found off. It is never stopped
    // automatically: it keeps running (and keeps its circuits warm) until the
    // user disables the setting or stops the server. The battery trade-off of
    // always-on Tor is accepted in exchange for instantly-available fetches —
    // every cold Tor start would otherwise re-add the circuit-build/rendezvous
    // latency that used to cause fetch timeouts.
    private val torStartMutex = Mutex()
    private val torBootstrapTimeoutMs = 60 * 1000L

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            MorganiteLog.d(Morganite.TAG, "Received Broadcast: ${intent?.action}")
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS) ?: TorService.STATUS_OFF
                    MorganiteLog.d(Morganite.TAG, "Tor connection status: $status")
                    // Rebuild the clients (they pick up TorService.socksPort) before
                    // the status flow flips: a waiter resumed by torStatus would
                    // otherwise grab a client still pointing at the default-port guess.
                    updateClients()
                    torStatus.value = status
                }
                TorService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(Intent.EXTRA_TEXT)
                    MorganiteLog.e(Morganite.TAG, "Tor connection error: $error")
                }
            }
        }
    }

    init {
        updateClients()
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(
            Morganite.instance,
            torStatusReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        Morganite.instance.scope.launch {
            // The settings StateFlow emits its current value on collect, so the
            // first iteration here (previousUseTor == null) is effectively "app
            // startup": if Tor was left enabled, it is brought up immediately.
            // Later emissions only act on actual transitions of useTor — toggling
            // an unrelated setting must not resurrect Tor after an idle stop.
            var previousUseTor: Boolean? = null
            settingsManager.settings.collect {
                if (it.useTor && previousUseTor != it.useTor && torStatus.value == TorService.STATUS_OFF) {
                    MorganiteLog.d(Morganite.TAG, "Tor enabled, starting service")
                    startTor()
                }
                previousUseTor = it.useTor
                // Tor is never stopped automatically; disabling the setting is an
                // explicit user action and the only thing that tears it down.
                if (!it.useTor && (torStatus.value != TorService.STATUS_OFF)) {
                    MorganiteLog.d(Morganite.TAG, "Tor disabled in settings, stopping service...")
                    stopTor()
                }
                updateClients()
            }
        }
    }

    private fun updateClients() {
        val settings = settingsManager.settings.value
        MorganiteLog.d(Morganite.TAG, "Updating clients. useTor: ${settings.useTor}, status: ${torStatus.value}")

        // Capture the clients we are about to replace so their idle connection
        // pools and dispatcher threads can be released afterwards. Without this,
        // every settings/Tor-status change leaks an OkHttpClient whose idle
        // sockets keep the radio warm until OkHttp's ~5 min eviction.
        val oldRootClient = rootClient
        val oldTorClient = torClient

        // Use default port 9050 if not yet reported by TorService
        val port = if (TorService.socksPort > 0) TorService.socksPort else 9050
        val torProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

        if (settings.useTor) {
            // Always configure torClient with proxy if useTor is enabled
            // This ensures no leaks for .onion even during bootstrap
            torClient = OkHttpClient.Builder()
                .proxy(torProxy)
                .connectTimeout(TOR_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TOR_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TOR_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            rootClient = if (settings.useTorForAllUrls) {
                MorganiteLog.d(Morganite.TAG, "Routing all traffic through Tor proxy")
                torClient
            } else {
                OkHttpClient.Builder().build()
            }
        } else {
            rootClient = OkHttpClient.Builder().build()
            torClient = rootClient
        }

        // Release each distinct replaced client that is not reused as a new one.
        // evictAll() closes idle sockets (network I/O) and updateClients() runs on
        // the main thread from the Tor status broadcast receiver, so do the release
        // on a background dispatcher to avoid NetworkOnMainThreadException. evictAll()
        // only closes idle connections and shutdown() is graceful, so any in-flight
        // request on an old client is left to finish on its own.
        val clientsToRelease = setOf(oldRootClient, oldTorClient)
            .filter { it !== rootClient && it !== torClient }
        if (clientsToRelease.isNotEmpty()) {
            Morganite.instance.scope.launch {
                for (old in clientsToRelease) {
                    old.connectionPool.evictAll()
                    old.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    suspend fun start() {
        if (::server.isInitialized) {
            MorganiteLog.d(Morganite.TAG, "Server already initialized. Starting")
            server.startSuspend()
            return
        }
        MorganiteLog.d(Morganite.TAG, "Starting CustomHttpServer")
        // Tor is started eagerly by the settings collector in init (initial
        // emission of the settings flow) and restarted on demand by ensureTorReady.
        updateClients()
        server = startKtorHttpServer()
        startMonitoring()
        server.startSuspend()
    }

    private fun startTor() {
        MorganiteLog.d(Morganite.TAG, "Starting Tor Service via Intent")
        val intent = Intent(Morganite.instance, TorService::class.java)
        intent.action = TorService.ACTION_START
        Morganite.instance.startService(intent)
    }

    private fun stopTor() {
        MorganiteLog.d(Morganite.TAG, "Stopping Tor Service via Intent")
        val intent = Intent(Morganite.instance, TorService::class.java)
        intent.action = TorService.ACTION_STOP
        Morganite.instance.startService(intent)
    }

    /**
     * Ensures Tor is running and bootstrapped before a tor-routed request proceeds.
     * Starts the service if it is off and suspends until it reports STATUS_ON (or the
     * bootstrap timeout elapses, in which case the request goes ahead and is allowed
     * to fail). Safe to call concurrently — the mutex makes only the first caller
     * start Tor while the rest wait for the same bootstrap.
     */
    private suspend fun ensureTorReady() {
        if (torStatus.value == TorService.STATUS_ON) return
        torStartMutex.withLock {
            if (torStatus.value == TorService.STATUS_ON) return
            if (torStatus.value != TorService.STATUS_STARTING) {
                startTor()
            }
            val ready = withTimeoutOrNull(torBootstrapTimeoutMs) {
                torStatus.first { it == TorService.STATUS_ON }
                true
            }
            if (ready == null) {
                MorganiteLog.w(Morganite.TAG, "Tor did not become ready within ${torBootstrapTimeoutMs}ms")
            }
        }
    }

    suspend fun stop() {
        MorganiteLog.d(Morganite.TAG, "Stopping CustomHttpServer")
        server.stopSuspend()
        nostrClient.disconnect()
        // User pressed "Stop" — explicit action, so Tor is torn down here too.
        if (torStatus.value != TorService.STATUS_OFF) {
            stopTor()
        }
    }

    fun buildUrl(server: String, hash: String, extension: String): String {
        // Onion services rarely serve TLS (Tor itself encrypts and authenticates
        // the connection), so scheme-less .onion servers must default to http://.
        val scheme = when {
            server.startsWith("http://") || server.startsWith("https://") -> ""
            server.contains(".onion") -> "http://"
            else -> "https://"
        }
        return "$scheme$server/$hash$extension"
    }

    suspend fun fetchInboxRelays(pubkey: String): List<NormalizedRelayUrl> {
        MorganiteLog.d(Morganite.TAG, "Fetching inbox relays for $pubkey")
        val event = nostrClient.downloadFirstEvent(
            filters = fallbackRelays.associateWith {
                listOf(
                    Filter(
                        kinds = listOf(AdvertisedRelayListEvent.KIND),
                        authors = listOf(pubkey),
                        limit = 1,
                    ),
                )
            },
        )

        val relays = (event as? AdvertisedRelayListEvent)?.readRelaysNorm() ?: emptyList()
        MorganiteLog.d(Morganite.TAG, "Found ${relays.size} inbox relays for $pubkey")
        return relays
    }

    suspend fun fetchAuthorServers(pubkey: String): List<String> {
        MorganiteLog.d(Morganite.TAG, "Fetching author servers for $pubkey")
        // The relay websocket is routed through Tor when the setting is on, so make
        // sure Tor is up first.
        val needsTor = settingsManager.settings.value.useTor
        activeAuthorLookups.incrementAndGet()
        try {
            if (needsTor) ensureTorReady()
            val inboxRelays = fetchInboxRelays(pubkey)
            val queryRelays = (inboxRelays + fallbackRelays).distinct()

            val event = nostrClient.downloadFirstEvent(
                filters = queryRelays.associateWith {
                    listOf(
                        Filter(
                            kinds = listOf(BlossomServersEvent.KIND),
                            authors = listOf(pubkey),
                            limit = 1,
                        ),
                    )
                },
            )

            val servers = (event as? BlossomServersEvent)?.servers() ?: emptyList()
            MorganiteLog.d(Morganite.TAG, "Found ${servers.size} servers for $pubkey")
            return servers
        } finally {
            // Close the relay socket once no lookup is still using it, so it does
            // not stay connected (and keep reconnecting) in the background.
            if (activeAuthorLookups.decrementAndGet() == 0) {
                MorganiteLog.d(Morganite.TAG, "No author lookups in flight, disconnecting nostr client")
                nostrClient.disconnect()
            }
        }
    }

    private suspend fun tryFetchAndSave(
        server: String,
        hash: String,
        extension: String,
    ): Boolean {
        val url = buildUrl(server, hash, extension)
        val useTor = url.contains(".onion") || settingsManager.settings.value.useTorForAllUrls
        MorganiteLog.d(Morganite.TAG, "Attempting to fetch and save from $url (Use Tor: $useTor)")

        val needsTor = settingsManager.settings.value.useTor && useTor
        return fetchWithRetry(url, needsTor, useTor) { client ->
            fetchAndSave(client, url, hash)
        }
    }

    /**
     * Runs a fetch, retrying it when it fails with a timeout before anything was
     * sent to the local client. A timeout on a Tor-routed request usually means
     * the circuit/rendezvous to the onion service was not established in time,
     * and a second attempt frequently succeeds once Tor has a warm circuit, so
     * each server gets up to [MAX_FETCH_ATTEMPTS] attempts before the caller
     * moves on to the next server. Non-timeout failures are not retried.
     */
    private suspend fun fetchWithRetry(
        url: String,
        needsTor: Boolean,
        useTor: Boolean,
        fetch: suspend (OkHttpClient) -> Boolean,
    ): Boolean {
        var attempt = 1
        while (true) {
            // Re-check Tor readiness and re-pick the client on every attempt: if
            // the first try raced Tor's bootstrap, the retry gets a client built
            // with the real SOCKS port instead of the default-port guess.
            if (needsTor) ensureTorReady()
            val client = if (useTor) torClient else rootClient
            try {
                return fetch(client)
            } catch (e: RetryableTimeoutException) {
                if (attempt >= MAX_FETCH_ATTEMPTS) {
                    MorganiteLog.w(Morganite.TAG, "Fetch from $url timed out $attempt times, giving up on this server")
                    return false
                }
                attempt++
                MorganiteLog.d(Morganite.TAG, "Fetch from $url timed out, retrying (attempt $attempt of $MAX_FETCH_ATTEMPTS)")
                delay(RETRY_DELAY_MS)
            }
        }
    }

    // Runs on Dispatchers.IO: OkHttp's execute() and the stream copy are blocking,
    // and parking a Ktor CIO event-loop thread on them stalls every other request
    // the server is handling for the duration of the download.
    private suspend fun fetchAndSave(
        client: OkHttpClient,
        url: String,
        hash: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    MorganiteLog.d(Morganite.TAG, "Fetch failed from $url: ${response.code}")
                    return@withContext false
                }

                val body = response.body

                val tempFile = File.createTempFile("download-", ".tmp")

                try {
                    // Hash while the bytes are already in hand instead of re-reading
                    // the finished file from flash for verification — one pass over
                    // the data instead of two.
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { inputStream ->
                        tempFile.outputStream().use { fileOut ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                digest.update(buffer, 0, bytesRead)
                                fileOut.write(buffer, 0, bytesRead)
                            }
                        }
                    }

                    val actualHash = digest.digest().toHex()
                    if (actualHash != hash) {
                        MorganiteLog.w(Morganite.TAG, "Hash mismatch from $url: expected $hash but got $actualHash")
                        tempFile.delete()
                        return@withContext false
                    }

                    fileStore.moveFile(tempFile, hash)
                    MorganiteLog.d(Morganite.TAG, "Successfully saved $hash from $url")
                    true
                } catch (e: Exception) {
                    MorganiteLog.e(Morganite.TAG, "Error while saving from $url", e)
                    if (tempFile.exists()) tempFile.delete()
                    // Nothing was sent to the local client, so a timeout is worth a retry.
                    if (e.isTimeout()) throw RetryableTimeoutException(e)
                    false
                }
            }
        } catch (e: RetryableTimeoutException) {
            throw e // Already handled below the fold; let the retry wrapper see it.
        } catch (e: Exception) {
            MorganiteLog.e(Morganite.TAG, "Network error fetching from $url", e)
            if (e.isTimeout()) throw RetryableTimeoutException(e)
            false
        }
    }

    private suspend fun tryFetchAndStream(
        server: String,
        hash: String,
        extension: String,
        call: ApplicationCall,
    ): Boolean {
        val url = buildUrl(server, hash, extension)
        val useTor = url.contains(".onion") || settingsManager.settings.value.useTorForAllUrls
        MorganiteLog.d(Morganite.TAG, "Attempting to fetch and stream from $url (Use Tor: $useTor)")

        val needsTor = settingsManager.settings.value.useTor && useTor
        return fetchWithRetry(url, needsTor, useTor) { client ->
            streamFromServer(client, url, hash, call)
        }
    }

    // Runs on Dispatchers.IO: OkHttp's execute() and the copy loop inside
    // respondOutputStream are blocking, and parking a Ktor CIO event-loop thread
    // on them stalls every other request the server is handling.
    private suspend fun streamFromServer(
        client: OkHttpClient,
        url: String,
        hash: String,
        call: ApplicationCall,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    MorganiteLog.d(Morganite.TAG, "Fetch failed from $url: ${response.code}")
                    return@withContext false // Try next server
                }

                val body = response.body
                val contentType = response.header("Content-Type")?.let { ContentType.parse(it) }
                    ?: ContentType.Application.OctetStream

                val tempFile = File.createTempFile("download-", ".tmp")
                val digest = MessageDigest.getInstance("SHA-256")

                response.headers.forEach { (name, value) ->
                    call.response.headers.appendIfAbsent(name, value)
                }

                try {
                    // Ktor streaming; the digest is fed in the same pass so the
                    // blob never has to be re-read from flash to verify it.
                    call.respondOutputStream(contentType, HttpStatusCode.fromValue(response.code)) {
                        body.byteStream().use { inputStream ->
                            tempFile.outputStream().use { fileOut ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    digest.update(buffer, 0, bytesRead)
                                    fileOut.write(buffer, 0, bytesRead)
                                    this.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }

                    // Finalize. Only cache the blob if its content matches the hash
                    // it was requested under; caching a corrupt/forged blob would
                    // serve bad data forever and make clients re-download it from
                    // remote servers over and over. The response has already been
                    // streamed to the client at this point, so on mismatch we still
                    // return true — there is no way to retry another server.
                    val actualHash = digest.digest().toHex()
                    if (actualHash != hash) {
                        MorganiteLog.w(Morganite.TAG, "Hash mismatch from $url: expected $hash but got $actualHash, not caching")
                        tempFile.delete()
                    } else {
                        fileStore.moveFile(tempFile, hash)
                        MorganiteLog.d(Morganite.TAG, "Successfully streamed and saved $hash from $url")
                    }
                    true // Signal SUCCESS to the loop
                } catch (e: Exception) {
                    MorganiteLog.e(Morganite.TAG, "Error while streaming from $url", e)
                    if (tempFile.exists()) tempFile.delete()

                    // If the user (the client) disconnected, throw to stop everything
                    if (e is java.io.IOException && e.message?.contains("Broken pipe") == true) {
                        throw e
                    }
                    false // Server error mid-stream, return false to try next server
                }
            }
        } catch (e: RetryableTimeoutException) {
            throw e // Nothing was sent to the local client; the retry wrapper decides.
        } catch (e: Exception) {
            MorganiteLog.e(Morganite.TAG, "Network error fetching from $url", e)
            // Timeouts here happened while connecting/fetching headers, before the
            // response body started — retryable. Timeouts once streaming has begun
            // are handled by the catch inside the respondOutputStream block (the
            // response is already committed and cannot be retried).
            if (e.isTimeout()) throw RetryableTimeoutException(e)
            false // Connection error, return false to try next server
        }
    }

    fun startMonitoring() {
        server.application.monitor.subscribe(ApplicationStarted) {
            isRunning.value = true
            MorganiteLog.d(Morganite.TAG, "Server started")
        }

        server.application.monitor.subscribe(ApplicationStopped) {
            isRunning.value = false
            MorganiteLog.d(Morganite.TAG, "Server stopped")
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startKtorHttpServer(host: String = "0.0.0.0", port: Int = 24242): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(
            CIO,
            port = port,
            host = host,
        ) {
            install(PartialContent)
            install(CachingHeaders)

            routing {
                route("/") {
                    options {
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "GET, HEAD, PUT, DELETE")
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Headers", "*")
                        call.response.headers.appendIfAbsent("Access-Control-Max-Age", "86400")
                        call.respond(HttpStatusCode.OK)
                    }

                    head {
                        call.respondText("")
                    }
                }

                route("/{path...}") {
                    options {
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Methods", "GET, HEAD, PUT, DELETE")
                        call.response.headers.appendIfAbsent("Access-Control-Allow-Headers", "*")
                        call.response.headers.appendIfAbsent("Access-Control-Max-Age", "86400")
                        call.respond(HttpStatusCode.OK)
                    }

                    get {
                        val path = call.request.path() // e.g., "/b1674...f553.pdf"
                        MorganiteLog.d(Morganite.TAG, "GET request: $path")
                        val regex = Regex("([0-9a-f]{64})(\\.[a-z0-9]+)?")
                        val match = regex.find(path) ?: run {
                            MorganiteLog.d(Morganite.TAG, "Invalid SHA-256 hash in path: $path")
                            return@get call.respond(HttpStatusCode.BadRequest, "Invalid SHA-256 hash")
                        }

                        val hash = match.groupValues[1]
                        val extension = match.groupValues.getOrNull(2) ?: ""

                        // Check if blob exists locally
                        val file = fileStore.getFileByHash(hash)
                        if (file != null && file.exists()) {
                            val clientETag = call.request.header(HttpHeaders.IfNoneMatch)
                            val hashETag = "\"$hash\""

                            if (clientETag == hashETag) {
                                MorganiteLog.d(Morganite.TAG, "Serving $hash (Not Modified)")
                                call.respond(HttpStatusCode.NotModified)
                                return@get
                            }

                            val mimeType = fileStore.detectMimeType(file)
                            MorganiteLog.d(Morganite.TAG, "Serving $hash from local storage ($mimeType)")
                            call.respondFile(file) {
                                call.response.headers.appendIfAbsent(HttpHeaders.ContentType, mimeType)
                                call.response.headers.appendIfAbsent(HttpHeaders.ETag, hash)
                            }
                            return@get
                        }

                        // Blob not found locally → attempt proxy retrieval using BUD-10 hints
                        val xsServers = call.request.queryParameters.getAll("xs") ?: emptyList()
                        val authorPubkeys = call.request.queryParameters.getAll("as") ?: emptyList()

                        MorganiteLog.d(Morganite.TAG, "$hash not found locally. Attempting proxy (xs: ${xsServers.size}, as: ${authorPubkeys.size})")

                        // Attempt retrieval from xs hints
                        for (server in xsServers) {
                            MorganiteLog.d(Morganite.TAG, "Trying xs hint server: $server")
                            val success = tryFetchAndStream(server, hash, extension, call)
                            if (success) return@get // Exit the route on first success
                        }

                        // Attempt retrieval from author server lists
                        for (pubkey in authorPubkeys) {
                            val servers = fetchAuthorServers(pubkey) // BUD-03 kind:10063
                            for (server in servers) {
                                MorganiteLog.d(Morganite.TAG, "Trying author server: $server for $pubkey")
                                val success = tryFetchAndStream(server, hash, extension, call)
                                if (success) return@get // Exit the route on first success
                            }
                        }

                        MorganiteLog.d(Morganite.TAG, "Resource $hash not found on any server")
                        call.respond(HttpStatusCode.NotFound)
                    }

                    head {
                        val path = call.request.path()
                        MorganiteLog.d(Morganite.TAG, "HEAD request: $path")
                        val regex = Regex("([0-9a-f]{64})(\\.[a-z0-9]+)?")
                        val match = regex.find(path) ?: run {
                            MorganiteLog.d(Morganite.TAG, "Invalid SHA-256 hash in path: $path")
                            call.respond(HttpStatusCode.BadRequest)
                            return@head
                        }

                        val hash = match.groupValues[1]
                        val extension = match.groupValues.getOrNull(2) ?: ""

                        var file = fileStore.getFileByHash(hash)
                        if (file == null || !file.exists()) {
                            // Blob not found locally → attempt proxy retrieval using BUD-10 hints
                            val xsServers = call.request.queryParameters.getAll("xs") ?: emptyList()
                            val authorPubkeys = call.request.queryParameters.getAll("as") ?: emptyList()

                            MorganiteLog.d(Morganite.TAG, "$hash not found locally for HEAD. Attempting proxy (xs: ${xsServers.size}, as: ${authorPubkeys.size})")

                            for (server in xsServers) {
                                MorganiteLog.d(Morganite.TAG, "Trying xs hint server: $server")
                                if (tryFetchAndSave(server, hash, extension)) {
                                    file = fileStore.getFileByHash(hash)
                                    break
                                }
                            }

                            if (file == null || !file.exists()) {
                                for (pubkey in authorPubkeys) {
                                    val servers = fetchAuthorServers(pubkey)
                                    var found = false
                                    for (server in servers) {
                                        MorganiteLog.d(Morganite.TAG, "Trying author server: $server for $pubkey")
                                        if (tryFetchAndSave(server, hash, extension)) {
                                            file = fileStore.getFileByHash(hash)
                                            found = true
                                            break
                                        }
                                    }
                                    if (found) break
                                }
                            }
                        }

                        if (file == null || !file.exists()) {
                            MorganiteLog.d(Morganite.TAG, "File not found for hash: $hash")
                            call.respond(HttpStatusCode.NotFound)
                            return@head
                        }

                        val mimeType = fileStore.detectMimeType(file)
                        MorganiteLog.d(Morganite.TAG, "HEAD response for $hash: $mimeType, ${file.length()} bytes")

                        call.response.status(HttpStatusCode.OK)
                        call.response.headers.appendIfAbsent(HttpHeaders.ContentType, mimeType)
                        call.response.headers.appendIfAbsent(HttpHeaders.ContentLength, file.length().toString())
                        call.response.headers.appendIfAbsent(HttpHeaders.ETag, hash)
                    }
                }
            }
        }
    }
}
