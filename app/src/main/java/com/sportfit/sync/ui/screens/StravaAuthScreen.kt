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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    LaunchedEffect(Unit) {
        authorizeUrl = viewModel.stravaAuthorizeUrl()
        loaded = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "授权 Strava",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        HorizontalDivider()

        val url = authorizeUrl
        when {
            !loaded -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            url == null -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("尚未配置 Strava 应用", style = MaterialTheme.typography.titleMedium)
                Text(
                    "请先回到账号管理，填入在 strava.com/settings/api 创建的 Client ID 与 " +
                        "Client Secret，并把 Authorization Callback Domain 设为 localhost。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
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
                        }
                    },
                )
            }
        }
    }
}
