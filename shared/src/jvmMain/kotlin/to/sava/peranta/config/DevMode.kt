package to.sava.peranta.config

/** 開発ビルドかどうかを判定するシステムプロパティのキー。 */
const val DEV_MODE_PROPERTY: String = "peranta.devMode"

/**
 * 開発ビルドとして動作しているか。desktopApp の `run` タスクが注入する
 * [DEV_MODE_PROPERTY] システムプロパティが `"true"` のときだけ真になる。
 * 配布物（jpackage 成果物）にはこのプロパティが載らないため常に偽で、
 * 開発用オーバーライド（[withDevOverrides]）や TLS ダウングレードは効かない。
 */
fun isDevMode(): Boolean = System.getProperty(DEV_MODE_PROPERTY) == "true"
