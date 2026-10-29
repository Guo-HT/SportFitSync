package com.sportfit.sync.platform

import com.sportfit.sync.domain.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges a synchronous-looking platform login into an asynchronous UI prompt.
 *
 * Garmin only reveals that it wants a verification code part-way through the
 * SSO handshake, so the auth flow suspends here while a dialog collects the
 * code. Cancelling the dialog (or the sync) resolves the wait with `null`,
 * which the caller turns into a clean authentication failure.
 */
class MfaCoordinator : MfaProvider {

    private val _pending = MutableStateFlow<Platform?>(null)

    /** Non-null while the UI should be showing the code prompt. */
    val pending: StateFlow<Platform?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<String?>? = null

    override suspend fun requestCode(platform: Platform): String? {
        val waiter = CompletableDeferred<String?>()
        deferred = waiter
        _pending.value = platform
        return try {
            waiter.await()
        } finally {
            _pending.value = null
            deferred = null
        }
    }

    /** Called by the dialog; `null` means "the user cancelled". */
    fun submit(code: String?) {
        deferred?.complete(code)
    }
}
