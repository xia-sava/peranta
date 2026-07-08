package to.sava.peranta.platform

import android.content.Context

/**
 * Android の Application Context を共有コードへ橋渡しする保持箱。
 * Application.onCreate で [init] を呼ぶこと。
 */
object AndroidApp {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val context: Context
        get() = appContext
            ?: error("AndroidApp.init(context) が未呼び出しです")
}
