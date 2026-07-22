package to.sava.peranta.platform

actual fun platformCapabilities(): PlatformCapabilities = PlatformCapabilities(
    canCaptureNotifications = true,
    canReceiveSms = true,
    usesUnifiedPush = true,
    requiresPostNotificationsPermission = true,
    supportsAutoStart = false,
)
