package tv.livingroom.watch

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV to a refresh rate that is a whole multiple of the film's frame rate, so 24 fps films
 * aren't shown on a 60 Hz screen with uneven frame timing (judder). Only the refresh rate changes; the
 * resolution stays as it is. The switch belongs to the player window, so the TV returns to its normal
 * mode when the player closes.
 */
object DisplayRate {
    private const val SWITCH_TIMEOUT_MS = 3_000L
    private const val SETTLE_MS = 800L

    /** Returns once the TV is at a matching rate, or right away when no switch is needed or possible. */
    suspend fun match(activity: Activity, fps: Float) {
        val display = currentDisplay(activity) ?: return
        val target = pick(display, fps) ?: return
        if (target.modeId == display.mode.modeId) return
        val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { done ->
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayRemoved(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId != display.displayId) return
                        if (currentDisplay(activity)?.mode?.modeId != target.modeId) return
                        manager.unregisterDisplayListener(this)
                        if (done.isActive) done.resumeWith(Result.success(true))
                    }
                }
                manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
                done.invokeOnCancellation { manager.unregisterDisplayListener(listener) }
                activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = target.modeId }
            }
        }
        // HDMI takes a moment to show a picture after a mode change; don't start the film into a black screen.
        if (switched == true) delay(SETTLE_MS)
    }

    /**
     * Among modes with the current resolution, finds one whose refresh rate is an exact multiple of the
     * frame rate (23.976 → 23.976 Hz, 25 → 50 Hz, 29.97 → 59.94 Hz). Prefers the fastest such rate so
     * menus stay smooth. Returns null when the TV has no exact match; a near miss is no better than now.
     */
    fun pick(display: Display, fps: Float): Display.Mode? {
        if (fps <= 0f || !fps.isFinite()) return null
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { mode ->
                val multiple = (mode.refreshRate / fps).roundToInt()
                multiple >= 1 && abs(mode.refreshRate - multiple * fps) / mode.refreshRate < 0.0005f
            }
            .maxByOrNull { it.refreshRate }
    }

    private fun currentDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
}
