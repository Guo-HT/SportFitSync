package com.sportfit.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sportfit.sync.ui.AppRoot
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.theme.SportFitTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * The app-wide view model, owned by the activity.
     *
     * It used to be created inside `setContent`, but the window flags below
     * need to observe the very same instance the UI drives — a second one
     * would have its own run state and never leave the screen on.
     */
    private val container: AppContainer by lazy { AppContainer.from(this) }

    /** Exposed for the instrumented test that drives a rehearsal sync. */
    internal val appViewModel: AppViewModel by viewModels { AppViewModel.factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        keepScreenOnWhileSyncing()

        setContent {
            SportFitTheme {
                AppRoot(viewModel = appViewModel)
            }
        }
    }

    /**
     * Keeps the screen awake for as long as a sync is running.
     *
     * The flag is set on the **window**, not on a composable, so it survives
     * navigating between the app's screens while the run continues.
     */
    private fun keepScreenOnWhileSyncing() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.runState
                    .map { it.running }
                    .distinctUntilChanged()
                    .collect { KeepScreenOn.set(window, it) }
            }
        }
    }
}
