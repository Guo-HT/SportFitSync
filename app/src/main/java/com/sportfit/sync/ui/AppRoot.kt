package com.sportfit.sync.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.components.MfaDialog
import com.sportfit.sync.ui.screens.AccountEditScreen
import com.sportfit.sync.ui.screens.AccountsScreen
import com.sportfit.sync.ui.screens.ActivityPickerScreen
import com.sportfit.sync.ui.screens.CacheScreen
import com.sportfit.sync.ui.screens.HistoryScreen
import com.sportfit.sync.ui.screens.JobDetailScreen
import com.sportfit.sync.ui.screens.SettingsScreen
import com.sportfit.sync.ui.screens.StravaAuthScreen
import com.sportfit.sync.ui.screens.SyncScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Sync("sync", "同步", Icons.Filled.Sync),
    History("history", "记录", Icons.Filled.History),
    Settings("settings", "设置", Icons.Filled.Settings),
}

internal object Routes {
    const val SELECT = "select"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_EDIT = "account/{platform}"
    const val CACHE = "cache"
    const val STRAVA_AUTH = "strava-auth"
    const val JOB = "job/{jobId}"

    fun accountEdit(platform: Platform) = "account/${platform.key}"
    fun job(id: Long) = "job/$id"
}

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val pendingMfa by viewModel.pendingMfa.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 底部栏常驻：账号管理、账号编辑等非 Tab 页面一旦把底部栏藏掉，
        // 用户在配置完账号后就失去了「同步 / 记录 / 设置」的入口，
        // 只能靠系统返回键绕路。非 Tab 页面不属于任何 Tab，
        // selected 判定自然全为 false，表现为「都不选中」。
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // 已经在这个 Tab 的根页面：什么都不做，
                            // 免得重建页面、丢掉滚动位置。
                            if (currentDestination?.route == tab.route) return@NavigationBarItem
                            navController.navigate(tab.route) {
                                // 弹到起点（连起点一起弹掉）再重建目标 Tab：
                                // 这样点 Tab 一定落在「Tab 的根页面」上。
                                //
                                // 不能用常见的 saveState/restoreState 组合：它会把
                                // 「上次在这个 Tab 里打开的子页面」（账号管理、活动选择…）
                                // 一起恢复回来 —— 于是用户点「同步」又落回账号管理页，
                                // 正是「配置完账号回不到同步页」的真正原因。
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Sync.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Sync.route) {
                SyncScreen(
                    viewModel = viewModel,
                    onSelectActivities = { navController.navigate(Routes.SELECT) },
                    // 「去添加账号」直达账号管理页，省掉「先跳设置、再点账号管理」这一步。
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenJob = { id -> navController.navigate(Routes.job(id)) },
                )
            }
            composable(Tab.History.route) {
                HistoryScreen(
                    viewModel = viewModel,
                    onOpenJob = { id -> navController.navigate(Routes.job(id)) },
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenCache = { navController.navigate(Routes.CACHE) },
                )
            }
            composable(Routes.SELECT) {
                ActivityPickerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onEdit = { platform -> navController.navigate(Routes.accountEdit(platform)) },
                    onAuthorizeStrava = { navController.navigate(Routes.STRAVA_AUTH) },
                )
            }
            composable(
                route = Routes.ACCOUNT_EDIT,
                arguments = listOf(navArgument("platform") { type = NavType.StringType }),
            ) { entry ->
                val platform = Platform.fromKey(entry.arguments?.getString("platform"))
                    ?: Platform.GARMIN
                AccountEditScreen(
                    viewModel = viewModel,
                    platform = platform,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.STRAVA_AUTH) {
                StravaAuthScreen(
                    viewModel = viewModel,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.CACHE) {
                CacheScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.JOB,
                arguments = listOf(navArgument("jobId") { type = NavType.LongType }),
            ) { entry ->
                JobDetailScreen(
                    viewModel = viewModel,
                    jobId = entry.arguments?.getLong("jobId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    pendingMfa?.let { platform ->
        MfaDialog(
            platform = platform,
            onSubmit = { code -> viewModel.submitMfa(code) },
            onCancel = { viewModel.submitMfa(null) },
        )
    }
}
