package app.hachure.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Listens for the Boox system UI broadcasts that say a panel is open over the app, so the pen SDK
 * can let go of the screen while it is. Receives only; nothing is sent and no permission is used.
 *
 * [V] Action and extra names are read from onyxsdk-base 1.7.7 (DeviceReceiver bytecode).
 * [G] The Note Air 2 firmware sends them to ordinary apps; the debug overlay shows the result.
 *
 * [onChange] gets a short reason while something is open, or null when nothing is.
 */
class SystemUiWatcher(private val context: Context, private val onChange: (String?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var menuOpen = false
    private var ballTouched = false
    private var dialogOpen = false
    private var registered = false

    /** A "touch ended" broadcast may never come; do not stay blocked on the touch alone. */
    private val touchTimeout = Runnable {
        ballTouched = false
        publish()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_MENU_STATE -> menuOpen = intent.getBooleanExtra(EXTRA_MENU_STATE, false)
                ACTION_BALL_TOUCH -> {
                    ballTouched = intent.getBooleanExtra(EXTRA_BALL_STATUS, false)
                    main.removeCallbacks(touchTimeout)
                    if (ballTouched) main.postDelayed(touchTimeout, TOUCH_TIMEOUT_MS)
                }
                ACTION_DIALOG_OPEN -> dialogOpen = true
                ACTION_DIALOG_CLOSE -> dialogOpen = false
            }
            publish()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_MENU_STATE)
            addAction(ACTION_BALL_TOUCH)
            addAction(ACTION_DIALOG_OPEN)
            addAction(ACTION_DIALOG_CLOSE)
        }
        // The senders are system apps, so the receiver has to be exported on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        reset()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        main.removeCallbacks(touchTimeout)
    }

    /** Forget everything, for when the app is known to be in front with nothing over it. */
    fun reset() {
        menuOpen = false
        ballTouched = false
        dialogOpen = false
        main.removeCallbacks(touchTimeout)
        publish()
    }

    private fun publish() = onChange(
        when {
            menuOpen -> "naviball menu"
            ballTouched -> "naviball touch"
            dialogOpen -> "system dialog"
            else -> null
        },
    )

    private companion object {
        const val ACTION_MENU_STATE = "com.onyx.floatingbutton.menu_state_changed"
        const val EXTRA_MENU_STATE = "floatbutton_menu_state"
        const val ACTION_BALL_TOUCH = "com.onyx.floatingbutton.touch"
        const val EXTRA_BALL_STATUS = "floatbutton_status"
        const val ACTION_DIALOG_OPEN = "com.android.systemui.SYSTEM_UI_DIALOG_OPEN_ACTION"
        const val ACTION_DIALOG_CLOSE = "com.android.systemui.SYSTEM_UI_DIALOG_CLOSE_ACTION"
        const val TOUCH_TIMEOUT_MS = 3000L
    }
}
