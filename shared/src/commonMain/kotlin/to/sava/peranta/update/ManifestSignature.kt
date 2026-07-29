package to.sava.peranta.update

/**
 * 配布元がマニフェストへ付ける署名の検証鍵（§12）。
 * X.509 SubjectPublicKeyInfo の DER を base64 にした ECDSA P-256 の公開鍵で、アプリへ埋め込む。
 * 配布経路（TLS）ともマニフェストの中身とも独立した信頼の起点になる。
 */
const val MANIFEST_PUBLIC_KEY: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEZEKvNteC6DDKA7tYX85vzGFlhjQbhwc/99nC6q8ED3YphUC9mvbZYWVgCUb+sizUFzZMQQdAiLN1d11Nmnbhw=="

/**
 * マニフェストの生バイト [manifest] に対する [signature]（DER 署名を base64 にしたもの）を
 * [publicKey] で検証する（§12）。署名が読めない・鍵が合わない・バイト列が食い違うときは false を返す。
 */
expect fun verifyManifestSignature(manifest: ByteArray, signature: String, publicKey: String): Boolean
