package me.rerere.ai.provider.providers.geminiweb

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiWebSessionManager(
    baseClient: OkHttpClient,
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

    private val pageClient = baseClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val rotateClient = baseClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val cookieManager: CookieManager get() = CookieManager.getInstance()
    @Volatile private var lastRotationAttemptMs: Long = 0L

    fun hasWebSession(): Boolean {
        val cookies = cookieManager.getCookie(GEMINI_HOME).orEmpty()
        return cookies.contains("__Secure-1PSID=") || cookies.contains("__Secure-3PSID=") || cookies.contains("SID=")
    }

    fun cookieHeader(url: String): String = cookieManager.getCookie(url).orEmpty()

    fun syncResponseCookies(url: String, setCookies: List<String>) {
        setCookies.forEach { cookieManager.setCookie(url, it) }
        if (setCookies.isNotEmpty()) cookieManager.flush()
    }

    suspend fun maybeRotateCookies() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastRotationAttemptMs < ROTATION_INTERVAL_MS) return@withContext
        lastRotationAttemptMs = now
        val cookies = cookieHeader(ROTATE_URL)
        if (cookies.isBlank()) return@withContext
        runCatching {
            val request = Request.Builder()
                .url(ROTATE_URL)
                .header("Cookie", cookies)
                .header("User-Agent", ANDROID_CHROME_UA)
                .post("[000,\"-0000000000000000000\"]".toRequestBody("application/json".toMediaType()))
                .build()
            rotateClient.newCall(request).execute().use { response ->
                syncResponseCookies(ROTATE_URL, response.headers("Set-Cookie"))
            }
        }
    }

    suspend fun fetchRequestParams(requestedAuthUser: String = "0"): RequestParams = withContext(Dispatchers.IO) {
        maybeRotateCookies()
        val authUser = requestedAuthUser.trim().takeIf { it.matches(Regex("\\d+")) } ?: "0"
        val appUrl = if (authUser == "0") GEMINI_HOME else "https://gemini.google.com/u/$authUser/app"
        val cookies = cookieHeader(appUrl)
        if (cookies.isBlank()) error("Gemini Web 未登录，请先登录 Google。")

        val request = Request.Builder()
            .url(appUrl)
            .header("Cookie", cookies)
            .header("User-Agent", ANDROID_CHROME_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        pageClient.newCall(request).execute().use { response ->
            syncResponseCookies(appUrl, response.headers("Set-Cookie"))
            if (!response.isSuccessful) error("Gemini Web 登录状态读取失败: HTTP ${response.code}")
            val html = response.body.string()
            if (html.contains("accounts.google.com/ServiceLogin") || html.contains("Sign in", ignoreCase = true)) {
                error("Gemini Web 登录已失效，请重新登录 Google。")
            }
            val atValue = extract(html, "SNlM0e") ?: error("Gemini Web 缺少 atValue，请重新登录。")
            val blValue = extract(html, "cfb2h") ?: error("Gemini Web 缺少 blValue，请重新登录。")
            val fSid = extract(html, "FdrFJe") ?: error("Gemini Web 缺少 f.sid，请重新登录。")
            RequestParams(
                atValue = atValue,
                blValue = blValue,
                fSid = fSid,
                locale = Regex("<html[^>]*\\slang=\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.getOrNull(1) ?: "en-US",
                authUser = Regex("data-index=[\\\"'](\\d+)[\\\"']").find(html)?.groupValues?.getOrNull(1) ?: authUser,
                uploadPushId = extract(html, "qKIAYe"),
                uploadClientPctx = extract(html, "Ylro7b"),
            )
        }
    }

    private fun extract(html: String, key: String): String? {
        val raw = Regex("\\\"${Regex.escape(key)}\\\":\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        return runCatching { json.decodeFromString<String>("\"$raw\"") }.getOrDefault(raw)
    }

    companion object {
        const val GEMINI_HOME = "https://gemini.google.com/app"
        const val ANDROID_CHROME_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"
        private const val ROTATE_URL = "https://accounts.google.com/RotateCookies"
        private const val ROTATION_INTERVAL_MS = 9 * 60 * 1000L
    }
}
