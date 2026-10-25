package org.sight.app.navigation

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun interface JsonTransport { suspend fun get(url: String): String }

class BoundedHttpsTransport(
    private val userAgent: String = "SIGHT/0.1 (Android accessibility research)",
    private val maximumBytes: Int = 2_000_000,
) : JsonTransport {
    init { require(userAgent.isNotBlank() && maximumBytes > 0) }

    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val endpoint = URL(url)
        require(endpoint.protocol == "https") { "HTTPS required" }
        require(endpoint.userInfo == null) { "Credentials in URLs are forbidden" }
        throttle.withLock {
            val now = System.nanoTime()
            val waitMs = ((nextAllowedNs - now) / 1_000_000).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs + 1)
            nextAllowedNs = System.nanoTime() + 1_100_000_000
        }
        val connection = endpoint.openConnection() as HttpsURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            coroutineScope {
                // Disconnect blocking network work when the owning request is cancelled.
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { connection.disconnect() }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    if (connection.responseCode !in 200..299) {
                        throw RouteProviderException(RouteFailure.HTTP, "Route service returned HTTP ${connection.responseCode}.")
                    }
                    if (connection.contentLengthLong > maximumBytes) {
                        throw RouteProviderException(RouteFailure.RESPONSE_TOO_LARGE, "Route service response exceeds its limit.")
                    }
                    val output = ByteArrayOutputStream()
                    val deadlineNs = System.nanoTime() + 20_000_000_000
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(8_192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (System.nanoTime() > deadlineNs) throw java.net.SocketTimeoutException("Response deadline exceeded")
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maximumBytes) {
                                throw RouteProviderException(RouteFailure.RESPONSE_TOO_LARGE, "Route service response exceeds its limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString(Charsets.UTF_8.name())
                } finally { cancellation.cancel(); connection.disconnect() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: RouteProviderException) {
            throw known
        } catch (failure: Exception) {
            throw RouteProviderException(RouteFailure.NETWORK, "The route service could not be reached.", failure)
        } finally { connection.disconnect() }
    }

    private companion object {
        val throttle = Mutex()
        var nextAllowedNs = 0L
    }
}
