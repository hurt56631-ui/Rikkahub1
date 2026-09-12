package me.rerere.rikkahub.ui.pages.setting.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.ai.provider.providers.geminiweb.GeminiWebSessionManager

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GeminiWebLoginDialog(
    authUser: String,
    onDismiss: () -> Unit,
    onSessionChanged: () -> Unit,
) {
    val account = authUser.trim().takeIf { it.matches(Regex("\\d+")) } ?: "0"
    val startUrl = if (account == "0") {
        GeminiWebSessionManager.GEMINI_HOME
    } else {
        "https://gemini.google.com/u/$account/app"
    }

    AlertDialog(
        onDismissRequest = {
            CookieManager.getInstance().flush()
            onSessionChanged()
            onDismiss()
        },
        title = { Text("登录 Gemini Web") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "请在下面完成 Google 登录并打开 Gemini。登录 Cookie 只保存在本机 WebView Cookie 存储，不写入 Provider 配置或云备份。",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(560.dp),
                    factory = { context ->
                        WebView(context).apply webView@ {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = GeminiWebSessionManager.ANDROID_CHROME_UA
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(this@webView, true)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    CookieManager.getInstance().flush()
                                    if (url?.contains("gemini.google.com") == true) {
                                        onSessionChanged()
                                    }
                                }
                            }
                            loadUrl(startUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url.isNullOrBlank()) webView.loadUrl(startUrl)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CookieManager.getInstance().flush()
                onSessionChanged()
                onDismiss()
            }) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
