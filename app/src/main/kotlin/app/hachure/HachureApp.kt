package app.hachure

import android.app.Application
import android.content.Context
import app.hachure.ink.InkDiagnostics
import org.lsposed.hiddenapibypass.HiddenApiBypass

class HachureApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Must run before any Onyx class is loaded, including Class.forName probes, or the SDK's
        // ReflectUtil fails for the lifetime of the process (verified: unofficial docs,
        // Troubleshooting). Harmless on non-Onyx devices, so it runs everywhere.
        InkDiagnostics.hiddenApiBypass = try {
            if (HiddenApiBypass.addHiddenApiExemptions("")) "ok" else "returned false"
        } catch (t: Throwable) {
            "failed: ${t.javaClass.simpleName}"
        }
    }
}
