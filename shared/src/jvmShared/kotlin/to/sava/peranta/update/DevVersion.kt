package to.sava.peranta.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 開発ビルドに添えるビルド時刻の形式。 */
private val BUILD_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/**
 * 開発ビルドの版数表示（§12）。開発ビルドの版数は既定値のまま動かないため、そのままでは手元の
 * ビルドが入れ替わったのかを版数から判断できない。成果物の更新時刻（[buildEpochMillis]）を添えて
 * 見分けられるようにする。時刻を解決できなければ印だけを付ける。
 *
 * 時刻をビルド時の生成リソースへ書くとビルドのたびに内容が変わって Gradle の最新性判定が
 * 効かなくなるため、値は実行時にファイルの更新時刻から採る。
 */
fun devVersionName(versionName: String, buildEpochMillis: Long?): String {
    val stamp = buildEpochMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(BUILD_STAMP_FORMATTER) }
        ?: return "$versionName (dev)"
    return "$versionName (dev-$stamp)"
}
