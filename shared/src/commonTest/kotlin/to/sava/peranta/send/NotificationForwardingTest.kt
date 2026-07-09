package to.sava.peranta.send

import to.sava.peranta.filter.FilterMode
import to.sava.peranta.filter.FilterRule
import to.sava.peranta.filter.RuleAction
import to.sava.peranta.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationForwardingTest {

    private fun input(
        packageName: String = "com.example.bank",
        title: String = "Verification",
        text: String = "your code is 123456",
    ) = NotificationInput(
        packageName = packageName,
        appName = "Bank",
        title = title,
        text = text,
        notificationKey = "0|$packageName|1|null|10",
        actions = listOf("アーカイブ"),
        postedAtEpochMillis = 1000,
    )

    /** 転送対象の通知は宛先を全端末にした NotificationPayload になる。 */
    @Test
    fun buildsPayloadForForwardedNotification() {
        val payload = buildNotificationPayload(
            input(),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
            idGen = { "id-1" },
        )!!
        assertEquals("phone", payload.from)
        assertEquals("*", payload.to)
        assertEquals("id-1", payload.id)
        assertEquals(listOf("アーカイブ"), payload.actions)
    }

    /** システム暗黙除外パッケージは null（転送しない）を返す。 */
    @Test
    fun systemPackageIsDropped() {
        val payload = buildNotificationPayload(
            input(packageName = "com.android.systemui", text = "90% まで充電します"),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
        )
        assertNull(payload)
    }

    /** 注入した暗黙システム判定でランチャーを持つプリインアプリを転送対象に含められる。 */
    @Test
    fun injectedSystemPredicateForwardsLauncherApp() {
        val payload = buildNotificationPayload(
            input(packageName = "com.android.systemui", title = "お知らせ", text = "こんにちは"),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
            isImplicitlySystemPackage = { false },
        )
        assertTrue(payload != null)
    }

    /** 注入した暗黙システム判定でランチャーを持たない通常アプリを暗黙除外できる。 */
    @Test
    fun injectedSystemPredicateDropsLauncherlessApp() {
        val payload = buildNotificationPayload(
            input(packageName = "com.example.background", title = "お知らせ", text = "こんにちは"),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
            isImplicitlySystemPackage = { true },
        )
        assertNull(payload)
    }

    /** OTP 通知は priority HIGH へ昇格し、失効時刻が付く。 */
    @Test
    fun otpNotificationGetsHighPriorityAndExpiry() {
        val payload = buildNotificationPayload(
            input(),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
        )!!
        assertEquals(Priority.HIGH, payload.priority)
        assertEquals(2000 + OTP_TTL_MILLIS, payload.expiresAtEpochMillis)
    }

    /** 非 OTP 通知は失効しない（expiresAt は null）。 */
    @Test
    fun nonOtpNotificationHasNoExpiry() {
        val payload = buildNotificationPayload(
            input(title = "お知らせ", text = "こんにちは"),
            mode = FilterMode.DENYLIST,
            rules = emptyList(),
            deviceId = "phone",
            now = 2000,
        )!!
        assertNull(payload.expiresAtEpochMillis)
        assertEquals(Priority.NORMAL, payload.priority)
    }

    /** redaction ルールでタイトル・本文が伏せ字になる（appName は残す）。 */
    @Test
    fun redactionMasksTitleAndText() {
        val rules = listOf(FilterRule("com.example.bank", RuleAction.INCLUDE, redact = true))
        val payload = buildNotificationPayload(
            input(text = "秘密のコード 123456", title = "code"),
            mode = FilterMode.ALLOWLIST,
            rules = rules,
            deviceId = "phone",
            now = 2000,
        )!!
        assertEquals(REDACTED_PLACEHOLDER, payload.title)
        assertEquals(REDACTED_PLACEHOLDER, payload.text)
        assertEquals("Bank", payload.appName)
    }

    /** SMS ペイロードは既定 HIGH・受信 +10 分の失効を持つ。 */
    @Test
    fun buildsSmsPayloadWithHighPriorityAndExpiry() {
        val payload = buildSmsPayload(
            senderNumber = "09011112222",
            text = "確認コード 987654",
            deviceId = "phone",
            now = 3000,
            idGen = { "sms-1" },
        )
        assertEquals(Priority.HIGH, payload.priority)
        assertEquals(3000 + SMS_TTL_MILLIS, payload.expiresAtEpochMillis)
        assertEquals("09011112222", payload.senderNumber)
        assertTrue(payload.text.contains("987654"))
    }
}
