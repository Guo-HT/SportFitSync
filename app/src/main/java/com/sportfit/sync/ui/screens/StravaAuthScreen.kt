package com.sportfit.sync.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.sportfit.sync.platform.strava.StravaOAuth
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.SectionCard

/**
 * Drives Strava's OAuth2 authorization page in a WebView and intercepts the
 * redirect to [StravaOAuth.REDIRECT_URI].
 *
 * The callback URL (`http://localhost/exchange_token`) is intentionally never
 * loaded: the authorization code is read out of it and the navigation is
 * cancelled, so the user lands back in the app instead of on an error page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StravaAuthScreen(
    viewModel: AppViewModel,
    onDone: () -> Unit,
) {
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 留着 WebView 的引用，底部「打开授权页」按钮要靠它把授权页重新拉出来。
    // 用普通容器而不是 Compose state：WebView 是在 AndroidView 的 factory 里
    // 建的（组合阶段），往 state 里写会白白触发一次重组。
    val webViewHolder = remember { WebViewHolder() }

    LaunchedEffect(Unit) {
        authorizeUrl = viewModel.stravaAuthorizeUrl()
        loaded = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            ScreenHeader(
                title = "Strava 授权",
                subtitle = "授权后 App 才能读取你的活动",
                icon = Icons.Filled.Lock,
                modifier = Modifier.weight(1f),
            )
        }

        HairLine()

        val url = authorizeUrl
        when {
            !loaded -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            url == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(title = "尚未配置 Strava 应用", icon = Icons.Filled.Info) {
                    Text(
                        "请先回到账号管理，填入在 strava.com/settings/api 创建的 Client ID 与 " +
                            "Client Secret，并把 Authorization Callback Domain 设为 localhost。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                AuthHint("在下面的授权页面登录并同意授权，App 才能读取你在 Strava 上的活动。")

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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = handle(request.url.toString())

                                @Deprecated("Kept for API < 24 WebView behaviour")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String,
                                ): Boolean = handle(url)

                                private fun handle(target: String): Boolean {
                                    if (!StravaOAuth.isRedirect(target)) return false
                                    try {
                                        val code = StravaOAuth.extractCode(target)
                                        viewModel.completeStravaAuth(code)
                                        onDone()
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Strava 授权失败"
                                    }
                                    return true
                                }
                            }
                            loadUrl(url)
                            webViewHolder.view = this
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
                        onClick = { webViewHolder.view?.loadUrl(url) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                    ) {
                        Icon(
                            Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("  打开授权页")
                    }
                }
            }
        }
    }
}

/** WebView 的引用容器：只为了「打开授权页」按钮能把授权页重新加载出来。 */
private class WebViewHolder {
    var view: WebView? = null
}

/** 授权页顶部的提示条：说清楚「为什么要授权」。 */
@Composable
private fun AuthHint(text: String) {
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
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
