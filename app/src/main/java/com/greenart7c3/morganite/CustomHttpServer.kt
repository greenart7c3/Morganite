package com.greenart7c3.morganite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.greenart7c3.morganite.models.SettingsManager
import com.greenart7c3.morganite.service.FileStore
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.downloadFirstEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.torproject.jni.TorService
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest

class NostrClientLoggerListener() : IRelayClientListener {
    override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
        Log.d(Morganite.TAG, "onCannotConnect")
        super.onCannotConnect(relay, errorMessage)
    }

    override fun onSent(relay: IRelayClient, cmdStr: String, cmd: Command, success: Boolean) {
        Log.d(Morganite.TAG, "onSent $cmdStr $success")

        super.onSent(relay, cmdStr, cmd, success)
    }

    override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
        Log.d(Morganite.TAG, "onIncomingMessage $msgStr")

        super.onIncomingMessage(relay, msgStr, msg)
    }

    override fun onDisconnected(relay: IRelayClient) {
        Log.d(Morganite.TAG, "onDisconnected")
        super.onDisconnected(relay)
    }

    override fun onConnecting(relay: IRelayClient) {
        Log.d(Morganite.TAG, "onConnecting")
        super.onConnecting(relay)
    }

    override fun onConnected(relay: IRelayClient, pingMillis: Int, compressed: Boolean) {
        Log.d(Morganite.TAG, "onConnected")
        super.onConnected(relay, pingMillis, compressed)
    }
}

class CustomHttpServer(
    val fileStore: FileStore,
    val settingsManager: SettingsManager,
) {
    val isRunning = MutableStateFlow(false)
    val torStatus = MutableStateFlow(TorService.STATUS_OFF)

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private var rootClient = OkHttpClient.Builder().build()
    private var torClient = OkHttpClient.Builder().build()
    val socketBuilder = BasicOkHttpWebSocket.Builder { _ -> if (settingsManager.settings.value.useTor) torClient else rootClient }
    val nostrClient = NostrClient(socketBuilder)

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(Morganite.TAG, "Received Broadcast: ${intent?.action}")
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS) ?: TorService.STATUS_OFF
                    Log.d(Morganite.TAG, "Tor connection status: $status")
                    torStatus.value = status
                    updateClients()
                }
                TorService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(Intent.EXTRA_TEXT)
                    Log.e(Morganite.TAG, "Tor connection error: $error")
                }
            }
        }
    }

    val listener = NostrClientLoggerListener().also {
        nostrClient.subscribe(it)
    }

    init {
        updateClients()
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        Morganite.instance.registerReceiver(torStatusReceiver, filter, Context.RECEIVER_EXPORTED)

        Morganite.instance.scope.launch {
            settingsManager.settings.collect {
                if (it.useTor && torStatus.value == TorService.STATUS_OFF) {
                    Log.d(Morganite.TAG, "Tor enabled in settings, starting service...")
                    startTor()
                } else if (!it.useTor && torStatus.value != TorService.STATUS_OFF) {
                    Log.d(Morganite.TAG, "Tor disabled in settings, stopping service...")
                    stopTor()
                }
                updateClients()
            }
        }
    }

    private fun updateClients() {
        val settings = settingsManager.settings.value
        Log.d(Morganite.TAG, "Updating clients. useTor: ${settings.useTor}, status: ${torStatus.value}")

        // Use default port 9050 if not yet reported by TorService
        val port = if (TorService.socksPort > 0) TorService.socksPort else 9050
        val torProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

        if (settings.useTor) {
            // Always configure torClient with proxy if useTor is enabled
            // This ensures no leaks for .onion even during bootstrap
            torClient = OkHttpClient.Builder()
                .proxy(torProxy)
                .build()

            if (settings.useTorForAllUrls) {
                Log.d(Morganite.TAG, "Routing all traffic through Tor proxy")
                rootClient = torClient
            } else {
                rootClient = OkHttpClient.Builder().build()
            }
        } else {
            rootClient = OkHttpClient.Builder().build()
            torClient = rootClient
        }
    }

    suspend fun start() {
        if (::server.isInitialized) {
            Log.d(Morganite.TAG, "Server already initialized. Starting")
            server.startSuspend()
            return
        }
        Log.d(Morganite.TAG, "Starting CustomHttpServer")
        if (settingsManager.settings.value.useTor) {
            startTor()
        }
        updateClients()
        server = startKtorHttpServer()
        startMonitoring()
        server.startSuspend()
    }

    private fun startTor() {
        Log.d(Morganite.TAG, "Starting Tor Service via Intent")
        val intent = Intent(Morganite.instance, TorService::class.java)
        intent.action = TorService.ACTION_START
        Morganite.instance.startService(intent)
    }

    private fun stopTor() {
        Log.d(Morganite.TAG, "Stopping Tor Service via Intent")
        val intent = Intent(Morganite.instance, TorService::class.java)
        intent.action = TorService.ACTION_STOP
        Morganite.instance.startService(intent)
    }

    suspend fun stop() {
        Log.d(Morganite.TAG, "Stopping CustomHttpServer")
        server.stopSuspend()
        if (settingsManager.settings.value.useTor) {
            stopTor()
        }
    }

    fun extractHash(path: String): String? {
        val regex = Regex("""/([0-9a-f]{64})(?:\.[^/]+)?$""")
        return regex.find(path)?.groupValues?.get(1)
    }

    fun buildUrl(server: String, hash: String, extension: String): String {
        val s = if (server.startsWith("http://") || server.startsWith("https://")) server
        else "https://$server"
        return "$s/$hash$extension"
    }

    suspend fun fetchAuthorServers(pubkey: String): List<String> {
        Log.d(Morganite.TAG, "Fetching author servers for $pubkey")
        val event = nostrClient.downloadFirstEvent(
            filters = mapOf(
                NormalizedRelayUrl("wss://nostr.land") to listOf(
                    Filter(
                        kinds = listOf(BlossomServersEvent.KIND),
                        authors = listOf(pubkey),
                        limit = 1,
                    )
                )
            )
        )

        val servers = (event as? BlossomServersEvent)?.servers() ?: emptyList()
        Log.d(Morganite.TAG, "Found ${servers.size} servers for $pubkey")
        return servers
    }

    private suspend fun tryFetchAndStream(
        server: String,
        hash: String,
        extension: String,
        call: ApplicationCall,
    ): Boolean {
        val url = buildUrl(server, hash, extension)
        val useTor = url.contains(".onion") || settingsManager.settings.value.useTorForAllUrls
        Log.d(Morganite.TAG, "Attempting to fetch and stream from $url (Use Tor: $useTor)")

        val client = if (useTor) {
            torClient
        } else {
            rootClient
        }

        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(Morganite.TAG, "Fetch failed from $url: ${response.code}")
                    return false // Try next server
                }

                val body = response.body ?: run {
                    Log.d(Morganite.TAG, "Fetch failed from $url: Empty body")
                    return false
                }
                val contentType = response.header("Content-Type")?.let { ContentType.parse(it) }
                    ?: ContentType.Application.OctetStream

                val tempFile = File.createTempFile("download-", ".tmp")
                val digest = MessageDigest.getInstance("SHA-256")

                response.headers.forEach { (name, value) ->
                    call.response.headers.appendIfAbsent(name, value)
                }

                try {
                    // Ktor streaming
                    call.respondOutputStream(contentType, HttpStatusCode.fromValue(response.code)) {
                        body.byteStream().use { inputStream ->
                            tempFile.outputStream().use { fileOut ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    digest.update(buffer, 0, bytesRead)
                                    fileOut.write(buffer, 0, bytesRead)
                                    this.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }

                    // Finalize
                    fileStore.moveFile(tempFile, hash)
                    Log.d(Morganite.TAG, "Successfully streamed and saved $hash from $url")
                    true // Signal SUCCESS to the loop
                } catch (e: Exception) {
                    Log.e(Morganite.TAG, "Error while streaming from $url", e)
                    if (tempFile.exists()) tempFile.delete()

                    // If the user (the client) disconnected, throw to stop everything
                    if (e is java.io.IOException && e.message?.contains("Broken pipe") == true) {
                        throw e
                    }
                    false // Server error mid-stream, return false to try next server
                }
            }
        } catch (e: Exception) {
            Log.e(Morganite.TAG, "Network error fetching from $url", e)
            false // Connection error, return false to try next server
        }
    }

    fun startMonitoring() {
        server.application.monitor.subscribe(ApplicationStarted) {
            isRunning.value = true
            Log.d(Morganite.TAG, "Server started")
        }

        server.application.monitor.subscribe(ApplicationStopped) {
            isRunning.value = false
            Log.d(Morganite.TAG, "Server stopped")
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
                        Log.d(Morganite.TAG, "GET request: $path")
                        val regex = Regex("([0-9a-f]{64})(\\.[a-z0-9]+)?")
                        val match = regex.find(path) ?: run {
                            Log.d(Morganite.TAG, "Invalid SHA-256 hash in path: $path")
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
                                Log.d(Morganite.TAG, "Serving $hash (Not Modified)")
                                call.respond(HttpStatusCode.NotModified)
                                return@get
                            }

                            val mimeType = fileStore.detectMimeType(file)
                            Log.d(Morganite.TAG, "Serving $hash from local storage ($mimeType)")
                            call.respondFile(file) {
                                call.response.headers.appendIfAbsent(HttpHeaders.ContentType, mimeType)
                                call.response.headers.appendIfAbsent(HttpHeaders.ETag, hash)
                            }
                            return@get
                        }

                        // Blob not found locally → attempt proxy retrieval using BUD-10 hints
                        val xsServers = call.request.queryParameters.getAll("xs") ?: emptyList()
                        val authorPubkeys = call.request.queryParameters.getAll("as") ?: emptyList()

                        Log.d(Morganite.TAG, "$hash not found locally. Attempting proxy (xs: ${xsServers.size}, as: ${authorPubkeys.size})")

                        // Attempt retrieval from xs hints
                        for (server in xsServers) {
                            Log.d(Morganite.TAG, "Trying xs hint server: $server")
                            val success = tryFetchAndStream(server, hash, extension, call)
                            if (success) return@get // Exit the route on first success
                        }

                        // Attempt retrieval from author server lists
                        for (pubkey in authorPubkeys) {
                            val servers = fetchAuthorServers(pubkey) // BUD-03 kind:10063
                            for (server in servers) {
                                Log.d(Morganite.TAG, "Trying author server: $server for $pubkey")
                                val success = tryFetchAndStream(server, hash, extension, call)
                                if (success) return@get // Exit the route on first success
                            }
                        }

                        Log.d(Morganite.TAG, "Resource $hash not found on any server")
                        call.respond(HttpStatusCode.NotFound)
                    }

                    head {
                        val path = call.request.path()
                        Log.d(Morganite.TAG, "HEAD request: $path")
                        val hash = extractHash(path) ?: run {
                            Log.d(Morganite.TAG, "Invalid hash in path: $path")
                            call.respond(HttpStatusCode.BadRequest)
                            return@head
                        }

                        val file = fileStore.getFileByHash(hash) ?: run {
                            Log.d(Morganite.TAG, "File not found for hash: $hash")
                            call.respond(HttpStatusCode.NotFound)
                            return@head
                        }

                        val mimeType = fileStore.detectMimeType(file)
                        Log.d(Morganite.TAG, "HEAD response for $hash: $mimeType, ${file.length()} bytes")

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
