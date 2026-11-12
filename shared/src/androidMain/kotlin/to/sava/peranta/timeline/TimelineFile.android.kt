package to.sava.peranta.timeline

import to.sava.peranta.platform.AndroidApp

actual fun defaultTimelineFile(): TimelineFile =
    FileTimelineFile(timelineFileIn(AndroidApp.context.filesDir))
