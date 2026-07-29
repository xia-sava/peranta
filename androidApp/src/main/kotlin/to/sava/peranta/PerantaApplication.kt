package to.sava.peranta

import android.app.Application
import to.sava.peranta.android.PerantaSend
import to.sava.peranta.platform.AndroidApp

/** Application Context を共有コードへ渡すためのエントリポイント。 */
class PerantaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidApp.init(this)
        PerantaSend.configureLogging()
        PerantaSend.primeTimelineInBackground()
    }
}
