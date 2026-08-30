package to.sava.peranta.android

/** FileProvider の authority 接尾辞（androidApp の manifest の provider と一致させる）。 */
private const val FILE_PROVIDER_SUFFIX: String = ".fileprovider"

/**
 * [packageName] のアプリが宣言する FileProvider の authority。自己更新の配布物（§12）も
 * 受信添付（§4.3）も、この一つの provider を通して他アプリへ渡す。
 */
fun fileProviderAuthority(packageName: String): String = "$packageName$FILE_PROVIDER_SUFFIX"
