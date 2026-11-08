package app.hachure

import android.app.Application
import android.content.Context

class HachureApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Milestone 2: HiddenApiBypass.addHiddenApiExemptions("") goes here, before any Onyx
        // class is loaded (including Class.forName probes), or the SDK's ReflectUtil can break
        // for the lifetime of the process.
    }
}
