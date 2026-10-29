package com.sportfit.sync.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse progress of the run currently in flight, for the status notification. */
data class SyncProgress(
    val directionLabel: String,
    val index: Int,
    val total: Int,
    val currentName: String?,
    val running: Boolean,
) {
    val percent: Int
        get() = if (total <= 0) 0 else (index * 100 / total).coerceIn(0, 100)
}

/**
 * Process-wide channel between whoever is running a sync and the foreground
 * service that keeps it alive.
 *
 * Deliberately a singleton rather than something passed around: the runner can
 * be either the UI (via the view model) or the background worker, and both must
 * drive the same notification. A `null` value means "nothing is running", which
 * is the service's signal to stop.
 */
object SyncProgressBus {

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    fun publish(value: SyncProgress?) {
        _progress.value = value
    }

    fun clear() {
        _progress.value = null
    }
}
