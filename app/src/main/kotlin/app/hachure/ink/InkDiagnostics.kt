package app.hachure.ink

import android.os.Build

/** Facts for the debug overlay, since logcat is not easy to reach on the device. */
object InkDiagnostics {
    @Volatile var hiddenApiBypass: String = "not run"
    @Volatile var backendFallbackReason: String? = null

    val device: String get() = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
}

/** Which backend to use. Detection never touches Onyx classes. */
object InkBackends {
    /**
     * Onyx devices report manufacturer "ONYX" (inferred from Boox apps in the wild; the overlay
     * shows the actual value so this can be confirmed on the Note Air 2).
     */
    fun isOnyxDevice(): Boolean =
        Build.MANUFACTURER.equals("onyx", ignoreCase = true) ||
            Build.BRAND.equals("onyx", ignoreCase = true)

    fun create(): InkBackend = if (isOnyxDevice()) OnyxInkBackend() else MotionEventInkBackend()
}
