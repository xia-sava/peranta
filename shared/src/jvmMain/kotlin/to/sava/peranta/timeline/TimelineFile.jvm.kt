package to.sava.peranta.timeline

import to.sava.peranta.platform.JvmPaths

actual fun defaultTimelineFile(): TimelineFile = FileTimelineFile(JvmPaths.timelineFile)
