package to.sava.peranta.toast

import co.touchlab.kermit.Logger
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/** Windows のサウンド設定で通知イベントに割り当てられた音を保持するレジストリキー。 */
private const val NOTIFICATION_SOUND_KEY =
    "HKCU\\AppEvents\\Schemes\\Apps\\.Default\\Notification.Default\\.Current"

/** `reg query` 出力の値行。型名に続く残りが値そのもの。 */
private val REGISTRY_VALUE_PATTERN = Regex("""REG_(?:EXPAND_)?SZ\s+(.+)""")

/** `%VAR%` 形式の環境変数参照。 */
private val ENVIRONMENT_REFERENCE_PATTERN = Regex("""%([^%]+)%""")

/** `reg query /ve` の出力から既定値を取り出す。値が空・見つからないときは null。 */
internal fun parseRegistryDefaultValue(output: String): String? =
    output.lineSequence()
        .mapNotNull { line -> REGISTRY_VALUE_PATTERN.find(line)?.groupValues?.get(1)?.trim() }
        .firstOrNull()
        ?.takeIf { it.isNotEmpty() }

/** `%VAR%` を環境変数の値へ展開する。[lookup] が解決できない参照はそのまま残す。 */
internal fun expandEnvironmentReferences(value: String, lookup: (String) -> String?): String =
    ENVIRONMENT_REFERENCE_PATTERN.replace(value) { match ->
        lookup(match.groupValues[1]) ?: match.value
    }

/**
 * Windows のサウンド設定に登録された通知音を鳴らす [ToastSound]。
 * ユーザーが音を割り当てていない場合と Windows 以外では何も鳴らさない。
 */
class WindowsNotificationSound(
    private val log: Logger = Logger.withTag("Toaster"),
) : ToastSound {

    private val soundFile: File? by lazy { resolveSoundFile() }

    override fun play() {
        val file = soundFile ?: return
        try {
            val clip = AudioSystem.getClip()
            clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) clip.close() }
            AudioSystem.getAudioInputStream(file).use { clip.open(it) }
            clip.start()
        } catch (error: Exception) {
            log.w(error) { "failed to play the notification sound: $file" }
        }
    }

    private fun resolveSoundFile(): File? {
        if (!isWindows()) return null
        val configured = readNotificationSoundSetting() ?: run {
            log.i { "no notification sound is assigned; toasts stay silent" }
            return null
        }
        val file = File(expandEnvironmentReferences(configured) { name -> System.getenv(name) })
        if (!file.isFile) {
            log.w { "the assigned notification sound is missing: $file" }
            return null
        }
        log.i { "notification sound resolved to $file" }
        return file
    }

    private fun readNotificationSoundSetting(): String? =
        try {
            val process = ProcessBuilder("reg", "query", NOTIFICATION_SOUND_KEY, "/ve")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            parseRegistryDefaultValue(output)
        } catch (error: java.io.IOException) {
            log.w(error) { "failed to read the notification sound setting" }
            null
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log.w(error) { "interrupted while reading the notification sound setting" }
            null
        }
}

/** 現在の OS が Windows なら true。 */
private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
