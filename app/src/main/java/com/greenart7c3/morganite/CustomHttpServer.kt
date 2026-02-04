package com.greenart7c3.morganite

import android.util.Log
import com.greenart7c3.morganite.service.FileStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.tika.Tika
import java.io.ByteArrayInputStream
import java.net.URLConnection

class CustomHttpServer(
    val fileStore: FileStore,
) {
    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    val httpClient = OkHttpClient()

    fun start() {
        server = startKtorHttpServer()
        server.start()
    }

    fun stop() {
        server.stop()
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

    fun tryFetchBlob(url: String, expectedSize: Long? = null): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null
                val bytes = body.bytes()

                if (expectedSize != null && bytes.size.toLong() != expectedSize) {
                    return null
                }
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }


    fun fetchAuthorServers(pubkey: String): List<String> {
        // Fetch BUD-03 server list (kind:10063) for this author
        // Return a list of server domains/URLs
        return listOf() // TODO: implement lookup
    }

    fun detectMimeType(bytes: ByteArray): String {
        val tika = Tika()
        return tika.detect(bytes)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startKtorHttpServer(host: String = "0.0.0.0", port: Int = 24242): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(
            CIO,
            port = port,
            host = host,
        ) {
            monitor.subscribe(ApplicationStarted) {
                Log.d(Morganite.TAG, "Server started on $host:$port")
            }

            monitor.subscribe(ApplicationStopped) {
                Log.d(Morganite.TAG, "Server stopped")
            }

            routing {
                route("/") {
                    options {
                        call.response.header("Access-Control-Allow-Origin", "*")
                        call.response.header("Access-Control-Allow-Methods", "GET, HEAD, PUT, DELETE")
                        call.response.header("Access-Control-Allow-Headers", "*")
                        call.response.header("Access-Control-Max-Age", "86400")
                        call.respond(HttpStatusCode.OK)
                    }

                    head {
                        call.respondText("")
                    }
                }

                route("/{path...}") {
                    options {
                        call.response.header("Access-Control-Allow-Origin", "*")
                        call.response.header("Access-Control-Allow-Methods", "GET, HEAD, PUT, DELETE")
                        call.response.header("Access-Control-Allow-Headers", "*")
                        call.response.header("Access-Control-Max-Age", "86400")
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
                            val mimeType = fileStore.detectMimeType(file)
                            call.respondFile(file) {
                                mimeType?.let {
                                    call.response.header(HttpHeaders.ContentType, mimeType)
                                }
                                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                call.response.header(HttpHeaders.ETag, hash)
                            }
                            return@get
                        }

                        // Blob not found locally → attempt proxy retrieval using BUD-10 hints
                        val xsServers = call.request.queryParameters.getAll("xs") ?: emptyList()
                        val authorPubkeys = call.request.queryParameters.getAll("as") ?: emptyList()

                        // Attempt retrieval from xs hints
                        for (server in xsServers) {
                            val url = buildUrl(server, hash, extension)
                            val blob = tryFetchBlob(url) // implement HTTP GET + size verification if sz present
                            if (blob != null) {
                                fileStore.saveBlob(blob)
                                val mimeType = detectMimeType(blob)
                                call.respondBytes(blob, ContentType.parse(mimeType))
                                return@get
                            }
                        }

                        // Attempt retrieval from author server lists
                        for (pubkey in authorPubkeys) {
                            val servers = fetchAuthorServers(pubkey) // BUD-03 kind:10063
                            for (server in servers) {
                                val url = buildUrl(server, hash, extension)
                                val blob = tryFetchBlob(url)
                                if (blob != null) {
                                    fileStore.saveBlob(blob)
                                    val mimeType = detectMimeType(blob)
                                    call.respondBytes(blob, ContentType.parse(mimeType))
                                    return@get
                                }
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
                        mimeType?.let {
                            call.response.header(HttpHeaders.ContentType, mimeType)
                        }
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentLength, file.length())
                        call.response.header(HttpHeaders.ETag, hash)
                    }
                }
            }
        }
    }
}
