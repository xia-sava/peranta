package to.sava.peranta

import android.app.Application
import android.content.pm.ApplicationInfo
import to.sava.peranta.android.PerantaSend
import to.sava.peranta.platform.AndroidApp

/** Application Context を共有コードへ渡すためのエントリポイント。 */
class PerantaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidApp.init(this)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        PerantaSend.configureLogging(debuggable)
        PerantaSend.pruneTimelineInBackground()
    }
}
