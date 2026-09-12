package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val isSensitiveWebSessionRequest = request.url.host in SENSITIVE_WEB_SESSION_HOSTS
        val loggedUrl = if (isSensitiveWebSessionRequest) {
            request.url.newBuilder().query(null).build().toString()
        } else {
            request.url.toString()
        }
        val requestHeaders = request.headers.toMap()
        val requestBody = if (isSensitiveWebSessionRequest) {
            "[REDACTED: sensitive web session request]"
        } else {
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = loggedUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = loggedUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (SENSITIVE_HEADER_NAMES.any { it.equals(name, ignoreCase = true) }) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }

    private companion object {
        val SENSITIVE_HEADER_NAMES = setOf(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Api-Key",
            "Api-Key",
            "X-Goog-Api-Key",
        )
        val SENSITIVE_WEB_SESSION_HOSTS = setOf(
            "gemini.google.com",
            "accounts.google.com",
            "push.clients6.google.com",
        )
    }
}
