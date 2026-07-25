package to.sava.peranta.platform

import to.sava.peranta.android.CompanionAssociation

actual fun platformCapabilities(): PlatformCapabilities = PlatformCapabilities(
    canCaptureNotifications = true,
    canReceiveSms = true,
    usesUnifiedPush = true,
    requiresPostNotificationsPermission = true,
    supportsAutoStart = false,
    requiresCompanionAssociation = CompanionAssociation.isRequired(),
)
