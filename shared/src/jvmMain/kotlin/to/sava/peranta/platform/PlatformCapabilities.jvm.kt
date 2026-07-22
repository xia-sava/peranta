package to.sava.peranta.platform

actual fun platformCapabilities(): PlatformCapabilities = PlatformCapabilities(
    canCaptureNotifications = false,
    canReceiveSms = false,
    usesUnifiedPush = false,
    requiresPostNotificationsPermission = false,
    supportsAutoStart = true,
)
