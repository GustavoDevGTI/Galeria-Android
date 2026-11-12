package com.galeria.android

import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Touch vibrations by meaning, so each kind of interaction feels different.
 * Everything goes through [View.performHapticFeedback], which follows the phone's
 * "touch feedback" setting: with it turned off, nothing vibrates.
 */
internal object Haptics {
    enum class Effect {
        /** Light tap on a bar action or a menu option. */
        TAP,
        /** One step: item added to or removed from a selection, speed step, drag crossing an item. */
        TICK,
        /** Press and hold recognized. */
        LONG_PRESS,
        /** Something was turned on (favorite, sound, cinema mode, a settings switch). */
        TOGGLE_ON,
        /** Something was turned off. */
        TOGGLE_OFF,
        /** An action was accepted (apply, confirm, save). */
        CONFIRM,
        /** An action failed or was refused. */
        REJECT,
        /** A gesture crossed the point where it takes effect (video shrinking to the thumbnail). */
        THRESHOLD
    }

    /** Android constant for [effect] on a given API level, with fallbacks for older versions. */
    fun constantFor(effect: Effect, sdk: Int = Build.VERSION.SDK_INT): Int = when (effect) {
        Effect.TAP -> HapticFeedbackConstants.CONTEXT_CLICK
        Effect.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Effect.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        Effect.TOGGLE_ON -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
        Effect.TOGGLE_OFF -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        Effect.CONFIRM -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Effect.REJECT -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Effect.THRESHOLD ->
            if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    }

    fun toggle(on: Boolean): Effect = if (on) Effect.TOGGLE_ON else Effect.TOGGLE_OFF

    @JvmStatic
    fun perform(view: View?, effect: Effect) {
        if (view == null) return
        // Grid items keep their automatic feedback off (see Ui.longPressFeedback); enable it only for this call.
        val wasEnabled = view.isHapticFeedbackEnabled
        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(constantFor(effect))
        view.isHapticFeedbackEnabled = wasEnabled
    }

    /**
     * For options inside a menu or dialog that closes on the same tap: Android drops a vibration
     * requested by a window that is going away, so ask through the screen underneath.
     */
    @JvmStatic
    fun performFromOverlay(view: View, effect: Effect) {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is Activity) context = context.baseContext
        val host = (context as? Activity)?.window?.decorView
        if (host == null || host.hasWindowFocus()) {
            perform(host ?: view, effect)
            return
        }
        // The menu still holds the focus at this instant; vibrate as soon as the screen gets it back.
        val observer = host.viewTreeObserver
        var listener: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null
        val remove = Runnable {
            listener?.let { if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
            listener = null
        }
        listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && listener != null) {
                remove.run()
                perform(host, effect)
            }
        }
        if (observer.isAlive) observer.addOnWindowFocusChangeListener(listener)
        host.postDelayed(remove, OVERLAY_FOCUS_WAIT_MS)
    }

    @JvmStatic
    fun perform(activity: Activity, effect: Effect) = perform(activity.window?.decorView, effect)

    private const val OVERLAY_FOCUS_WAIT_MS = 600L
}
