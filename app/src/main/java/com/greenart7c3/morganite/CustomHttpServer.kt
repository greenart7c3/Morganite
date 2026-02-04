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
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File

class CustomHttpServer(
    val fileStore: FileStore,
) {
    lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

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
                head("/") {
                    call.respondText("")
                }

                get("/{path...}") {
                    val path = call.request.path()

                    // Match 64-char hex SHA-256, optional extension
                    val regex = Regex("""/([0-9a-f]{64})(?:\.[^/]+)?$""")
                    val match = regex.find(path) ?: run {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val hash = match.groupValues[1]

                    // Map hash → File (your implementation)
                    val file: File = fileStore.getFileByHash(hash) ?: run {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    // Determine MIME type
                    val mimeType = run {
                        // 1) If you already know it, use that
                        fileStore.detectMimeType(file)
                        // 2) Fallback required by spec
                            ?: "application/octet-stream"
                    }

                    call.respondFile(
                        file = file,
                        configure = {
                            call.response.status(HttpStatusCode.OK)
                            call.response.headers.append(HttpHeaders.ContentType, mimeType)
                            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                            call.response.headers.append(HttpHeaders.ETag, hash)
                        },
                    )
                }

                head("/{path...}") {
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
