package to.sava.peranta.platform

import kotlinx.coroutines.CoroutineDispatcher

/** ブロッキング I/O を逃がすためのディスパッチャ（各プラットフォームの IO 相当）。 */
expect val ioDispatcher: CoroutineDispatcher
