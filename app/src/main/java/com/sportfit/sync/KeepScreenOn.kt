package com.sportfit.sync

import android.view.Window
import android.view.WindowManager

/**
 * Keeps the screen awake while a long-running operation is in flight.
 *
 * A sync that walks a few thousand activities takes minutes, and its progress
 * is only useful if the user can watch it — a screen that dimmed after the
 * usual 30 seconds would make the whole run feel broken.
 *
 * The flag lives on the **window**, not on a composable, so it survives
 * navigating between the app's screens while the run continues.
 *
 * Android only honours `FLAG_KEEP_SCREEN_ON` while the window has focus, so
 * leaving the app or locking the screen still lets it sleep — which is what
 * the user asked for by doing that. The sync itself keeps running either way,
 * in the foreground service.
 */
internal object KeepScreenOn {

    /** Turns the keep-awake flag on or off for [window]. */
    fun set(window: Window, enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Whether [window] currently asks to stay awake. */
    fun isActive(window: Window): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
}
