package to.sava.peranta.config

import com.russhwolf.settings.Settings

/**
 * 端末に保存する秘密（共有鍵・ntfy アクセストークン）の保管を抽象化する（§11）。
 * 名前で区別した複数の秘密を扱い、プラットフォームの保護機構（Windows の DPAPI 等）へ
 * expect/actual で差し替えられるよう、生成を [createSecretStore] に集約する。
 *
 * 値は文字列で受け渡す。共有鍵は base64、アクセストークンはトークンそのもので、
 * いずれも [PerantaConfig] が持つ形と同じ。
 */
interface SecretStore {
    fun loadSecret(name: String): String?
    fun storeSecret(name: String, value: String)
    fun clearSecret(name: String)
}

/** 共有鍵（base64）の秘密名。 */
const val SECRET_SHARED_KEY: String = "sharedKey"

/** ntfy アクセストークンの秘密名。 */
const val SECRET_ACCESS_TOKEN: String = "accessToken"

/** 保管する秘密の全て。「すべての情報の消去」（§11）は取りこぼしを避けてこの一覧を回る。 */
val SECRET_NAMES: List<String> = listOf(SECRET_SHARED_KEY, SECRET_ACCESS_TOKEN)

/**
 * settings へ値をそのまま保存する [SecretStore]。
 * アプリ専用の保存先が他プロセスから読めないプラットフォーム、および
 * OS の保護機構を使えない環境での退避先に使う。
 */
class SettingsSecretStore(private val settings: Settings) : SecretStore {

    override fun loadSecret(name: String): String? = settings.getStringOrNull(name)

    override fun storeSecret(name: String, value: String) {
        settings.putString(name, value)
    }

    override fun clearSecret(name: String) {
        settings.remove(name)
    }
}

/** プラットフォーム毎の秘密の保管庫を返す。 */
expect fun createSecretStore(settings: Settings): SecretStore
