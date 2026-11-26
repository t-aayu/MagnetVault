package com.example.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResponseData(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

class RateLimitedException(
    val host: String,
    val retryAfterMs: Long?,
    message: String = "HTTP 429 rate limited by $host"
) : IllegalStateException(message)

class HttpStatusException(
    val statusCode: Int,
    val host: String,
    message: String = "HTTP $statusCode from $host"
) : IllegalStateException(message)

class ResponseTooLargeException(
    val maxBytes: Long,
    message: String = "Response exceeded $maxBytes byte safety limit"
) : IllegalStateException(message)

class MirrorBlockedException(
    message: String
) : IllegalStateException(message)

interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES
    ): String

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES
    ): String

    suspend fun probe(
        url: String,
        postJsonBody: String? = null,
        maxResponseBytes: Long = 16L * 1024L
    ): HttpResponseData

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}

class OkHttpTransport(
    private val client: OkHttpClient = HttpClientFactory.defaultClient,
    private val pingClient: OkHttpClient = HttpClientFactory.pingClient
) : HttpTransport {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxResponseBytes: Long
    ): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val data = executeCancellable(client, request, maxResponseBytes, throwOnHttpError = true)
        return data.body
    }

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        maxResponseBytes: Long
    ): String {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val data = executeCancellable(client, request, maxResponseBytes, throwOnHttpError = true)
        return data.body
    }

    override suspend fun probe(
        url: String,
        postJsonBody: String?,
        maxResponseBytes: Long
    ): HttpResponseData {
        val builder = Request.Builder().url(url)
        if (postJsonBody != null) {
            builder.post(postJsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
        } else {
            builder.get()
        }
        return executeCancellable(pingClient, builder.build(), maxResponseBytes, throwOnHttpError = false)
    }

    private suspend fun executeCancellable(
        targetClient: OkHttpClient,
        request: Request,
        maxResponseBytes: Long,
        throwOnHttpError: Boolean
    ): HttpResponseData = suspendCancellableCoroutine { cont ->
        val call = targetClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val headersMap = buildMap<String, String> {
                            for (i in 0 until resp.headers.size) {
                                put(
                                    resp.headers.name(i).lowercase(Locale.US),
                                    resp.headers.value(i)
                                )
                            }
                        }

                        if (throwOnHttpError && resp.code == 429) {
                            val retryAfterSeconds = headersMap["retry-after"]?.trim()?.toLongOrNull()
                            val retryAfterMs = retryAfterSeconds?.coerceAtLeast(0L)?.times(1000L)
                            throw RateLimitedException(
                                host = request.url.host,
                                retryAfterMs = retryAfterMs,
                                message = "HTTP 429 rate limited on ${request.url.host}"
                            )
                        }
                        if (throwOnHttpError && !resp.isSuccessful) {
                            throw HttpStatusException(
                                statusCode = resp.code,
                                host = request.url.host,
                                message = "HTTP ${resp.code} from ${request.url.host}"
                            )
                        }

                        val bodyText = readBoundedBody(resp, maxResponseBytes, throwOnOverflow = throwOnHttpError)
                        if (cont.isActive) {
                            cont.resume(
                                HttpResponseData(
                                    code = resp.code,
                                    body = bodyText,
                                    headers = headersMap
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) {
                            cont.resumeWithException(t)
                        }
                    }
                }
            }
        })
    }

    private fun readBoundedBody(
        response: Response,
        maxResponseBytes: Long,
        throwOnOverflow: Boolean
    ): String {
        val body = response.body ?: return ""
        val declaredLength = body.contentLength()
        if (throwOnOverflow && declaredLength > maxResponseBytes) {
            throw ResponseTooLargeException(maxResponseBytes)
        }
        val source = body.source()
        val buffer = Buffer()
        val scratch = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(scratch, 8192L)
            if (read == -1L) break
            val nextTotal = total + read
            if (nextTotal > maxResponseBytes) {
                if (throwOnOverflow) {
                    throw ResponseTooLargeException(maxResponseBytes)
                }
                val remaining = (maxResponseBytes - total).coerceAtLeast(0L)
                if (remaining > 0L) {
                    buffer.write(scratch, remaining)
                }
                break
            }
            buffer.write(scratch, read)
            total = nextTotal
        }
        val charset: Charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(charset)
    }
}
