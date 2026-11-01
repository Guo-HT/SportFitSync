package com.sportfit.sync.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sportfit.sync.platform.outbase.OutbaseAuth
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SubPageHeader

/**
 * Logs into Outbase in a WebView and keeps the `sessionId` cookie.
 *
 * Outbase has no programmatic login: the web app posts the credentials to
 * its own gateway and the answer sets a `sessionId` cookie. The app therefore
 * shows the real login page, and once that cookie exists it is read back out
 * of the WebView's cookie jar.
 *
 * The screen polls the cookie jar while the page is open, because the cookie
 * only appears once the login request has actually been answered.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OutbaseAuthScreen(
    viewModel: AppViewModel,
    onDone: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    // WebView 是在 AndroidView 的 factory 里建的（组合阶段），往 state 里写会
    // 白白触发一次重组，所以引用用一个普通容器拿着。
    val webViewHolder = remember { OutbaseWebViewHolder() }

    LaunchedEffect(Unit) {
        // 登录页可能已经留在 WebView 的缓存里，先把旧 cookie 清掉，
        // 否则上一次登录的 sessionId 会让这一屏直接「看起来已登录」。
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        checking = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SubPageHeader(
            title = "登录 Outbase",
            onBack = onDone,
            subtitle = "登录后 App 才能把活动上传到 Outbase",
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )

        HairLine()

        Column(modifier = Modifier.fillMaxSize()) {
            OutbaseAuthHint(
                "在下面的页面登录 Outbase。登录成功后 App 会自动保存登录态，" +
                    "之后同步时就不需要再登录了。",
            )

            error?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                // 登录成功后页面会跳走；每次跳转都看一眼 cookie，
                                // 拿到 sessionId 就收工。
                                harvestSession(view) { sessionId ->
                                    if (sessionId != null) {
                                        viewModel.completeOutbaseAuth(sessionId)
                                        onDone()
                                    }
                                }
                            }
                        }
                        loadUrl(OutbaseAuth.LOGIN_URL)
                        webViewHolder.view = this
                    }
                },
                update = { view ->
                    // 页面加载完不一定带 cookie（比如用户只是打开首页），
                    // 所以每次重组也顺手查一次。
                    harvestSession(view) { sessionId ->
                        if (sessionId != null) {
                            viewModel.completeOutbaseAuth(sessionId)
                            onDone()
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone) { Text("取消") }
                Button(
                    onClick = { webViewHolder.view?.loadUrl(OutbaseAuth.LOGIN_URL) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) {
                    Icon(
                        Icons.Filled.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("打开登录页")
                }
            }
        }
    }
}

/**
 * Reads the `sessionId` cookie out of the WebView's jar.
 *
 * The cookie is set on the `outbase.cn` domain, so the jar is asked for that
 * host; a `null` cookie just means the user has not logged in yet.
 */
private fun harvestSession(view: WebView, onFound: (String?) -> Unit) {
    val cookies = CookieManager.getInstance().getCookie(OutbaseAuth.COOKIE_HOST)
    val sessionId = OutbaseAuth.extractSessionId(cookies)
    onFound(sessionId)
}

/** WebView 的引用容器：只为了「打开登录页」按钮能把登录页重新加载出来。 */
private class OutbaseWebViewHolder {
    var view: WebView? = null
}

/** 登录页顶部的提示条：说清楚「为什么要登录」。 */
@Composable
private fun OutbaseAuthHint(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
