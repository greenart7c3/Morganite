package com.greenart7c3.morganite

import android.util.Log
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
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
) {
    val isRunning = MutableStateFlow(false)

    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    val rootClient = OkHttpClient.Builder().build()
    val socketBuilder = BasicOkHttpWebSocket.Builder { _ -> rootClient }
    val nostrClient = NostrClient(socketBuilder)

    val listener = NostrClientLoggerListener().also {
        nostrClient.subscribe(it)
    }

    suspend fun start() {
        if (::server.isInitialized) {
            Log.d(Morganite.TAG, "Server already initialized. Starting")
            server.startSuspend()
            return
        }
        server = startKtorHttpServer()
        startMonitoring()
        server.startSuspend()
    }

    suspend fun stop() {
        server.stopSuspend()
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

        return (event as? BlossomServersEvent)?.servers() ?: emptyList()
    }

    private suspend fun tryFetchAndStream(
        server: String,
        hash: String,
        extension: String,
        call: ApplicationCall,
    ): Boolean {
        val url = buildUrl(server, hash, extension)

        return try {
            rootClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return false // Try next server

                val body = response.body ?: return false
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
                    true // Signal SUCCESS to the loop
                } catch (e: Exception) {
                    if (tempFile.exists()) tempFile.delete()

                    // If the user (the client) disconnected, throw to stop everything
                    if (e is java.io.IOException && e.message?.contains("Broken pipe") == true) {
                        throw e
                    }
                    false // Server error mid-stream, return false to try next server
                }
            }
        } catch (e: Exception) {
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
                        val regex = Regex("([0-9a-f]{64})(\\.[a-z0-9]+)?")
                        val match = regex.find(path) ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid SHA-256 hash")

                        val hash = match.groupValues[1]
                        val extension = match.groupValues.getOrNull(2) ?: ""

                        // Check if blob exists locally
                        val file = fileStore.getFileByHash(hash)
                        if (file != null && file.exists()) {
                            val clientETag = call.request.header(HttpHeaders.IfNoneMatch)
                            val hashETag = "\"$hash\""

                            if (clientETag == hashETag) {
                                call.respond(HttpStatusCode.NotModified)
                                return@get
                            }

                            val mimeType = fileStore.detectMimeType(file)
                            call.respondFile(file) {
                                call.response.headers.appendIfAbsent(HttpHeaders.ContentType, mimeType)
                                call.response.headers.appendIfAbsent(HttpHeaders.ETag, hash)
                            }
                            return@get
                        }

                        // Blob not found locally → attempt proxy retrieval using BUD-10 hints
                        val xsServers = call.request.queryParameters.getAll("xs") ?: emptyList()
                        val authorPubkeys = call.request.queryParameters.getAll("as") ?: emptyList()

                        // Attempt retrieval from xs hints
                        for (server in xsServers) {
                            val success = tryFetchAndStream(server, hash, extension, call)
                            if (success) return@get // Exit the route on first success
                        }

                        // Attempt retrieval from author server lists
                        for (pubkey in authorPubkeys) {
                            val servers = fetchAuthorServers(pubkey) // BUD-03 kind:10063
                            // Label the loop so we can jump to the next iteration from deep inside
                            for (server in servers) {
                                val success = tryFetchAndStream(server, hash, extension, call)
                                if (success) return@get // Exit the route on first success
                            }
                        }

                        call.respond(HttpStatusCode.NotFound)
                    }

                    head {
                        val hash = extractHash(call.request.path()) ?: run {
                            call.respond(HttpStatusCode.BadRequest)
                            return@head
                        }

                        val file = fileStore.getFileByHash(hash) ?: run {
                            call.respond(HttpStatusCode.NotFound)
                            return@head
                        }

                        val mimeType = fileStore.detectMimeType(file)

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
