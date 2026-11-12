package to.sava.peranta.config

import to.sava.peranta.blob.BlobCipher
import to.sava.peranta.crypto.MessageCipher
import kotlin.io.encoding.Base64

/** 共有鍵のバイト列を返す。共有鍵が未設定なら [IllegalStateException]。 */
fun PerantaConfig.sharedKeyBytes(): ByteArray =
    Base64.decode(checkNotNull(sharedKeyBase64) { "shared key not configured" })

/** 設定の共有鍵と keyId から [MessageCipher] を作る。いずれかが未設定なら [IllegalStateException]。 */
fun PerantaConfig.messageCipher(): MessageCipher = MessageCipher(sharedKeyBytes(), requireKeyId())

/** 設定の共有鍵と keyId から [BlobCipher] を作る。いずれかが未設定なら [IllegalStateException]。 */
fun PerantaConfig.blobCipher(): BlobCipher = BlobCipher(sharedKeyBytes(), requireKeyId())

/** keyId を返す。未設定なら [IllegalStateException]。 */
fun PerantaConfig.requireKeyId(): String = checkNotNull(keyId) { "keyId not configured" }
