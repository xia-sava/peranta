package to.sava.peranta.toast

import kotlin.test.Test
import kotlin.test.assertEquals

class SnoreToastCommandTest {

    private val exe = "C:\\Peranta\\snoretoast.exe"

    /** 表示引数は -persistent と AppUserModelID・タイトル・本文・id・「消す」ボタンを順に含む。 */
    @Test
    fun showArgsContainsPersistentAndAllFields() {
        val item = ReceivedNotificationToast(id = "abc-123", title = "コード", body = "123456")
        assertEquals(
            listOf(
                exe,
                "-persistent",
                "-appID", "Peranta",
                "-t", "コード",
                "-m", "123456",
                "-id", "abc-123",
                "-b", "消す",
            ),
            SnoreToastCommand.showArgs(exe, item),
        )
    }

    /** openUrl があるときは「開く」「消す」の 2 ボタン構成になる。 */
    @Test
    fun showArgsUsesTwoButtonsWhenOpenUrlPresent() {
        val item = ReceivedNotificationToast(
            id = "abc-123",
            title = "お知らせ",
            body = "https://example.com/info を見てください",
            openUrl = "https://example.com/info",
        )
        assertEquals(
            listOf(
                exe,
                "-persistent",
                "-appID", "Peranta",
                "-t", "お知らせ",
                "-m", "https://example.com/info を見てください",
                "-id", "abc-123",
                "-b", "開く;消す",
            ),
            SnoreToastCommand.showArgs(exe, item),
        )
    }

    /** 取り下げ引数は appID と -close・正規化済み id を渡す。 */
    @Test
    fun closeArgsPassesAppIdAndSanitizedId() {
        assertEquals(
            listOf(exe, "-appID", "Peranta", "-close", "id-with-slash"),
            SnoreToastCommand.closeArgs(exe, "id/with/slash"),
        )
    }

    /** インストール引数は shortcut・exe・AppUserModelID の 3 引数を -install に渡す。 */
    @Test
    fun installArgsUsesShortcutExeAndAppId() {
        assertEquals(
            listOf(exe, "-install", "Peranta", exe, "Peranta"),
            SnoreToastCommand.installArgs(exe, "Peranta"),
        )
    }

    /** UUID など英数・ハイフンだけの id はそのまま保たれる。 */
    @Test
    fun sanitizeIdKeepsUuidCharacters() {
        val uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        assertEquals(uuid, SnoreToastCommand.sanitizeId(uuid))
    }

    /** 記号や空白を含む id は英数・ハイフン・アンダースコア以外がハイフンに置換される。 */
    @Test
    fun sanitizeIdReplacesUnsafeCharacters() {
        assertEquals("a-b_c-d-e", SnoreToastCommand.sanitizeId("a b_c/d\\e"))
    }

    /** 空になる id は既定ラベルへフォールバックする。 */
    @Test
    fun sanitizeIdFallsBackWhenEmpty() {
        assertEquals("toast", SnoreToastCommand.sanitizeId(""))
    }

    /** exit code は §3.3 の割り切りどおり Clicked/Dismissed/TimedOut/ButtonDismiss にマップされる。 */
    @Test
    fun resultFromExitCodeMapsKnownActions() {
        assertEquals(ToastResult.Clicked, SnoreToastCommand.resultFromExitCode(0))
        assertEquals(ToastResult.Dismissed, SnoreToastCommand.resultFromExitCode(2))
        assertEquals(ToastResult.TimedOut, SnoreToastCommand.resultFromExitCode(3))
        assertEquals(ToastResult.ButtonDismiss, SnoreToastCommand.resultFromExitCode(4))
    }

    /** 未知・エラーの exit code（Hidden=1 / TextEntered=5 / エラー / 番兵）は Failed に落ちる。 */
    @Test
    fun resultFromExitCodeMapsUnknownToFailed() {
        listOf(1, 5, -1, 255, Int.MIN_VALUE).forEach { code ->
            assertEquals(ToastResult.Failed, SnoreToastCommand.resultFromExitCode(code), "code=$code")
        }
    }

    /** 2 ボタン構成では exit 4 + stdout「開く」で ButtonOpen と判別する。 */
    @Test
    fun resultFromMapsOpenLabelToButtonOpen() {
        assertEquals(ToastResult.ButtonOpen, SnoreToastCommand.resultFrom(4, "開く\r\n"))
    }

    /** 2 ボタン構成では exit 4 + stdout「消す」で ButtonDismiss と判別する。 */
    @Test
    fun resultFromMapsDismissLabelToButtonDismiss() {
        assertEquals(ToastResult.ButtonDismiss, SnoreToastCommand.resultFrom(4, "消す\r\n"))
    }

    /** stdout の前後の空白・改行はトリムしてから比較する。 */
    @Test
    fun resultFromTrimsStdoutBeforeComparing() {
        assertEquals(ToastResult.ButtonOpen, SnoreToastCommand.resultFrom(4, "  開く  \n"))
    }

    /** exit 4 で stdout が未知のラベル・空のときは、誤って消す扱いにせず Failed とする（安全側）。 */
    @Test
    fun resultFromMapsUnknownOrEmptyStdoutToFailed() {
        assertEquals(ToastResult.Failed, SnoreToastCommand.resultFrom(4, "不明なボタン\r\n"))
        assertEquals(ToastResult.Failed, SnoreToastCommand.resultFrom(4, ""))
    }

    /** exit 4 以外は stdout を見ず [SnoreToastCommand.resultFromExitCode] と同じ判別になる。 */
    @Test
    fun resultFromDelegatesToExitCodeMappingWhenNotButtonPressed() {
        assertEquals(ToastResult.Clicked, SnoreToastCommand.resultFrom(0, ""))
        assertEquals(ToastResult.Dismissed, SnoreToastCommand.resultFrom(2, ""))
        assertEquals(ToastResult.TimedOut, SnoreToastCommand.resultFrom(3, ""))
        assertEquals(ToastResult.Failed, SnoreToastCommand.resultFrom(1, "開く"))
    }
}
