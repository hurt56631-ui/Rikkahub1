package me.rerere.ai.provider.providers.geminiweb

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.text.StringEscapeUtils

/**
 * Bridges Android WebView's Google login session to OkHttp without persisting cookies in
 * ProviderSetting. This deliberately keeps Google session credentials out of RikkaHub backups.
 */
class GeminiWebSessionManager(
    private val client: OkHttpClient,
) {
    data class RequestParams(
        val atValue: String,
        val blValue: String,
        val fSid: String,
        val locale: String,
        val authUser: String,
        val uploadPushId: String?,
        val uploadClientPctx: String?,
    )

    @Volatile
    private var lastRotationAttemptMs: Long = 0L

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    fun hasWebSession(): Boolean {
        val cookies = cookieManager.getCookie(GEMINI_HOME).orEmpty()
        return cookies.isNotBlank() && (
            cookies.contains("__Secure-1PSID=") ||
                cookies.contains("__Secure-3PSID=") ||
                cookies.contains("SID=")
            )
    }

    fun cookieHeader(url: String = GEMINI_HOME): String = cookieManager.getCookie(url).orEmpty()

    fun syncResponseCookies(url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        setCookies.forEach { cookieManager.setCookie(url, it) }
        cookieManager.flush()
    }

    suspend fun fetchRequestParams(requestedAuthUser: String = "0"): RequestParams = withContext(Dispatchers.IO) {
        maybeRotateCookies()
        val authUser = requestedAuthUser.trim().takeIf { it.matches(Regex("\\d+")) } ?: "0"
        val appUrl = if (authUser == "0") GEMINI_HOME else "https://gemini.google.com/u/$authUser/app"
        val cookies = cookieHeader(appUrl)
        if (cookies.isBlank()) {
            error("Gemini Web 未登录。请先在 Provider 设置中登录 Google。")
        }

        val request = Request.Builder()
            .url(appUrl)
            .header("Cookie", cookies)
            .header("User-Agent", ANDROID_CHROME_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            syncResponseCookies(appUrl, response.headers("Set-Cookie"))
            if (!response.isSuccessful) {
                error("Gemini Web 登录状态读取失败: HTTP ${response.code}")
            }
            val html = response.body.string()
            if (html.contains("Sign in", ignoreCase = true) || html.contains("accounts.google.com/ServiceLogin")) {
                error("Gemini Web 登录已失效，请重新登录 Google。")
            }

            val atValue = extract(html, "SNlM0e")
            val blValue = extract(html, "cfb2h")
            val fSid = extract(html, "FdrFJe")
            if (atValue.isNullOrBlank() || blValue.isNullOrBlank() || fSid.isNullOrBlank()) {
                error("Gemini Web 会话参数读取失败，请刷新登录状态后重试。")
            }

            val detectedIndex = Regex("data-index=[\\\"'](\\d+)[\\\"']")
                .find(html)?.groupValues?.getOrNull(1)
                ?.takeIf { it.toIntOrNull() in 0..19 }
                ?: authUser

            RequestParams(
                atValue = atValue,
                blValue = blValue,
                fSid = fSid,
                locale = Regex("<html[^>]*\\slang=\\\"([^\\\"]+)\\\"")
                    .find(html)?.groupValues?.getOrNull(1) ?: "en-US",
                authUser = detectedIndex,
                uploadPushId = extract(html, "qKIAYe"),
                uploadClientPctx = extract(html, "Ylro7b"),
            )
        }
    }

    /**
     * Best-effort equivalent of Gemini Nexus' RotateCookies keep-alive.
     * Android does this opportunistically before Web requests instead of running a permanent
     * background alarm. Failures never block chat; loading gemini.google.com below can still
     * refresh normal request tokens.
     */
    private fun maybeRotateCookies() {
        val now = System.currentTimeMillis()
        if (now - lastRotationAttemptMs < ROTATION_INTERVAL_MS) return
        synchronized(this) {
            val insideNow = System.currentTimeMillis()
            if (insideNow - lastRotationAttemptMs < ROTATION_INTERVAL_MS) return
            lastRotationAttemptMs = insideNow
        }

        runCatching {
            val url = ROTATE_COOKIES_URL
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader(url))
                .header("User-Agent", ANDROID_CHROME_UA)
                .post(
                    "[000,\"-0000000000000000000\"]"
                        .toRequestBody("application/json".toMediaType())
                )
                .build()
            client.newCall(request).execute().use { response ->
                syncResponseCookies(url, response.headers("Set-Cookie"))
            }
        }
    }

    private fun extract(html: String, key: String): String? {
        val raw = Regex("\\\"${Regex.escape(key)}\\\":\\\"([^\\\"]+)\\\"")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { StringEscapeUtils.unescapeJson(raw) }.getOrDefault(raw)
    }

    companion object {
        private const val ROTATE_COOKIES_URL = "https://accounts.google.com/RotateCookies"
        private const val ROTATION_INTERVAL_MS = 9 * 60 * 1000L
        const val GEMINI_HOME = "https://gemini.google.com/app"
        const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
    }
}
