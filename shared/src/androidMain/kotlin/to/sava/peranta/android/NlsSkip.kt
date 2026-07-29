package to.sava.peranta.android

/** アプリ自身のパッケージ名（自分の通知を転送してループするのを防ぐ）。 */
const val SELF_PACKAGE = "to.sava.peranta"

/** 捕捉した通知を NLS 側で落とす理由（§3.1）。 */
enum class NotificationSkipReason {
    /** アプリ自身の通知。転送すると自分の通知を無限に転送してしまう。 */
    SELF,

    /** 進行中通知（音楽・ダウンロード等の常駐表示）。 */
    ONGOING,

    /** グループ要約。個別通知と二重になる。 */
    GROUP_SUMMARY,

    /** 既定 SMS アプリの通知で、直接受信済みの SMS と重複するもの。 */
    SMS_DUPLICATE,

    /** 仕事用プロファイルの通知で、その転送を有効にしていないもの。 */
    WORK_PROFILE,
}

/**
 * 捕捉した通知を NLS 側で落とすべきかを純粋に判定し、落とす理由を返す。落とさないなら null。
 * [NotificationSkipReason.SMS_DUPLICATE] は落とすと同時に、その通知が直接受信した SMS の
 * 元通知であることも意味する（§3.1 の対応づけ）。
 *
 * [isCrossProfile] は通知の発生元が自ユーザーとは別のプロファイル（仕事用プロファイル等）であることを示す。
 * これらは組織の管理境界の内側にあるため、[forwardWorkProfile] で明示的に有効化しない限り転送しない（§3.1）。
 * パッケージ名によるフィルタ（§7）はプロファイルを跨いで一致するとは限らないため、この判断はそこに委ねない。
 */
fun notificationSkipReason(
    packageName: String,
    defaultSmsPackage: String?,
    isSmsDuplicate: Boolean,
    isOngoing: Boolean,
    isGroupSummary: Boolean,
    isCrossProfile: Boolean = false,
    forwardWorkProfile: Boolean = false,
): NotificationSkipReason? = when {
    packageName == SELF_PACKAGE -> NotificationSkipReason.SELF
    isCrossProfile && !forwardWorkProfile -> NotificationSkipReason.WORK_PROFILE
    isOngoing -> NotificationSkipReason.ONGOING
    isGroupSummary -> NotificationSkipReason.GROUP_SUMMARY
    packageName == defaultSmsPackage && isSmsDuplicate -> NotificationSkipReason.SMS_DUPLICATE
    else -> null
}
