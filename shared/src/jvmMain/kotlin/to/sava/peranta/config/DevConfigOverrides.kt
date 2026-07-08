package to.sava.peranta.config

/**
 * 環境変数または `-D` システムプロパティで設定を上書きする、設定 UI 完成までの開発導線（§16）。
 * 参照キー: PERANTA_HOST / PERANTA_PORT / PERANTA_TLS / PERANTA_TOKEN /
 * PERANTA_KEY(base64) / PERANTA_TOPIC / PERANTA_DEVICE / PERANTA_KEY_ID。
 */
fun PerantaConfig.withDevOverrides(lookup: (String) -> String? = ::envOrProperty): PerantaConfig =
    copy(
        host = lookup("PERANTA_HOST") ?: host,
        port = lookup("PERANTA_PORT")?.toIntOrNull() ?: port,
        useTls = lookup("PERANTA_TLS")?.toBooleanStrictOrNull() ?: useTls,
        accessToken = lookup("PERANTA_TOKEN") ?: accessToken,
        sharedKeyBase64 = lookup("PERANTA_KEY") ?: sharedKeyBase64,
        keyId = lookup("PERANTA_KEY_ID") ?: keyId,
        receiveTopic = lookup("PERANTA_TOPIC") ?: receiveTopic,
        deviceName = lookup("PERANTA_DEVICE") ?: deviceName,
    )

/** 環境変数を優先し、無ければ同名のシステムプロパティを引く。 */
fun envOrProperty(name: String): String? =
    System.getenv(name) ?: System.getProperty(name)
