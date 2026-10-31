package com.sportfit.sync

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sportfit.sync.domain.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the screen stays awake while a sync is running.
 *
 * A full sync takes minutes and its progress is only useful if the user can
 * watch it, so the window carries `FLAG_KEEP_SCREEN_ON` for the whole run —
 * and the flag has to come back off afterwards, or the phone would never
 * sleep again.
 *
 * The run is driven through the **rehearsal platform** (`Platform.DEMO`):
 * it needs no account, no network and no credentials, and it is deterministic.
 */
@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {

    /**
     * The whole feature, end to end: the window flag follows the run state of
     * a real sync driven through the app's own view model.
     */
    @Test
    fun theWindowStaysAwakeWhileTheSyncRunsAndSleepsAgainAfterwards() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 1. Idle: the phone is allowed to sleep.
            scenario.onActivity { activity ->
                assertFalse(
                    "空闲时不应保持常亮",
                    KeepScreenOn.isActive(activity.window),
                )
            }

            // 2. Start a rehearsal sync.
            scenario.onActivity { activity ->
                activity.appViewModel.setSource(Platform.DEMO)
                activity.appViewModel.setTarget(Platform.DEMO)
                activity.appViewModel.startSync(selectedIds = null)
            }
            awaitIdle(scenario)

            // 3. While the sync runs, the window asks to stay awake.
            scenario.onActivity { activity ->
                assertTrue(
                    "同步期间必须保持常亮",
                    KeepScreenOn.isActive(activity.window),
                )
            }

            // 4. When it is over, the normal screen-off behaviour is back.
            awaitRunFinished(scenario)
            awaitIdle(scenario)
            scenario.onActivity { activity ->
                assertFalse(
                    "同步结束后必须恢复正常熄屏",
                    KeepScreenOn.isActive(activity.window),
                )
            }
        }
    }

    /** The flag itself is a plain window operation, so it is checked directly too. */
    @Test
    fun theFlagCanBeTurnedOnAndOffAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                KeepScreenOn.set(activity.window, true)
                assertTrue(KeepScreenOn.isActive(activity.window))

                KeepScreenOn.set(activity.window, false)
                assertFalse(KeepScreenOn.isActive(activity.window))
            }
        }
    }

    /**
     * Waits for the rehearsal sync to finish.
     *
     * The run is driven by the view model on the main thread, so the test
     * thread only polls its state and never blocks it.
     */
    private fun awaitRunFinished(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var running = true
            scenario.onActivity { activity ->
                running = activity.appViewModel.runState.value.running
            }
            if (!running) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("同步未在 ${RUN_TIMEOUT_MS}ms 内结束")
    }

    /** Lets the main looper deliver whatever the collectors have pending. */
    private fun awaitIdle(scenario: ActivityScenario<MainActivity>) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        /** The rehearsal platform has 12 activities; a minute is plenty. */
        const val RUN_TIMEOUT_MS = 60_000L

        const val POLL_INTERVAL_MS = 200L
    }
}
