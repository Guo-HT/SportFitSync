package com.sportfit.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sportfit.sync.ui.AppRoot
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.theme.SportFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.from(this)
        setContent {
            SportFitTheme {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModel.factory(container),
                )
                AppRoot(viewModel = viewModel)
            }
        }
    }
}
