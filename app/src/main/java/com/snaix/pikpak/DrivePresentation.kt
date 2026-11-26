package com.snaix.pikpak

import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

internal enum class DriveView(@StringRes private val labelId: Int) {
    List(R.string.ui_list_view), Icons(R.string.ui_icon_view);

    val label: String get() = appString(labelId)
}

internal enum class FileSort(@StringRes private val labelId: Int, val defaultDescending: Boolean = false) {
    Name(R.string.ui_name), Created(R.string.ui_created_time, true), Recent(R.string.ui_modified_time, true),
    ContentModified(R.string.ui_content_modified_time, true), Kind(R.string.ui_file_type), Size(R.string.ui_size, true);

    val label: String get() = appString(labelId)
}

internal enum class DriveGrouping(@StringRes private val labelId: Int) {
    None(R.string.ui_no_group), Kind(R.string.ui_file_type), Created(R.string.ui_created_time),
    Modified(R.string.ui_modified_time), ContentModified(R.string.ui_content_modified_time), Size(R.string.ui_size);

    val label: String get() = appString(labelId)
}

internal data class DrivePresentation(
    val view: DriveView = DriveView.List,
    val grouping: DriveGrouping = DriveGrouping.None,
    val sort: FileSort = FileSort.Name,
    val descending: Boolean = sort.defaultDescending,
) {
    fun available(folderStats: Boolean): DrivePresentation {
        if (folderStats || (sort != FileSort.ContentModified && grouping != DriveGrouping.ContentModified)) return this
        return copy(
            sort = if (sort == FileSort.ContentModified) FileSort.Recent else sort,
            grouping = if (grouping == DriveGrouping.ContentModified) DriveGrouping.Modified else grouping,
        )
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("drive_view", view.name).putString("drive_group", grouping.name)
            .putString("drive_sort", sort.name).putBoolean("drive_descending", descending).apply()
    }

    fun sorted(entries: List<CloudFile>, query: String): List<CloudFile> =
        entries.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedWith(fileOrder())

    fun fileOrder(): Comparator<CloudFile> {
        val order = when (sort) {
            FileSort.Name -> compareBy<CloudFile> { it.name.lowercase(Locale.ROOT) }.inOrder()
            FileSort.Created -> valuesInOrder(CloudFile::createdAt)
            FileSort.Recent -> valuesInOrder(CloudFile::modifiedAt)
            FileSort.ContentModified -> valuesInOrder(CloudFile::contentModifiedAt)
                .thenBy { it.folder && it.folderStats !is FolderStats.Complete }
            FileSort.Kind -> compareBy<CloudFile> { it.category }.inOrder()
            FileSort.Size -> valuesInOrder(CloudFile::displayBytes)
        }
        val primary = if (sort == FileSort.Name) compareByDescending(CloudFile::folder).then(order) else order
        return primary.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy(CloudFile::id)
    }

    fun sections(
        entries: List<CloudFile>,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = AppLanguages.locale,
    ): List<DriveSection> {
        if (grouping == DriveGrouping.None) return listOf(DriveSection(null, entries))
        val today = LocalDate.now(zone)
        val monthFormat by lazy(LazyThreadSafetyMode.NONE) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
        }
        val groups = entries.groupBy { file ->
            when (grouping) {
                DriveGrouping.Kind -> Heading(file.category.label, file.category.ordinal.toLong())
                DriveGrouping.Created -> dateHeading(file.createdAt?.atZone(zone)?.toLocalDate(), today, monthFormat)
                DriveGrouping.Modified -> dateHeading(file.modifiedAt?.atZone(zone)?.toLocalDate(), today, monthFormat)
                DriveGrouping.ContentModified -> when (val stats = file.folderStats) {
                    FolderStats.Pending -> Heading(appString(R.string.ui_calculating_folder_stats), Long.MIN_VALUE + 3)
                    FolderStats.Failed -> Heading(appString(R.string.ui_folder_stats_failed), Long.MIN_VALUE + 1)
                    is FolderStats.Complete -> if (stats.isEmpty) Heading(appString(R.string.ui_empty_folder), Long.MIN_VALUE + 2)
                        else dateHeading(file.contentModifiedAt?.atZone(zone)?.toLocalDate(), today, monthFormat)
                    null -> dateHeading(file.contentModifiedAt?.atZone(zone)?.toLocalDate(), today, monthFormat)
                }
                DriveGrouping.Size -> sizeHeading(file)
                DriveGrouping.None -> error("Ungrouped entries use one section")
            }
        }
        val order = if (grouping == DriveGrouping.Kind) compareBy<Heading> { it.order }
            else compareByDescending { it.order }
        return groups.keys.sortedWith(order).map { DriveSection(it.label, groups.getValue(it)) }
    }

    private fun <T : Comparable<T>> valuesInOrder(read: (CloudFile) -> T?): Comparator<CloudFile> =
        compareByDescending<CloudFile> { read(it) != null }.then(compareBy(read).inOrder())

    private fun Comparator<CloudFile>.inOrder() = if (descending) reversed() else this

    private data class Heading(val label: String, val order: Long)

    private fun dateHeading(date: LocalDate?, today: LocalDate, monthFormat: DateTimeFormatter): Heading {
        if (date == null) return Heading(appString(R.string.ui_unknown_date), Long.MIN_VALUE)
        val daysAgo = today.toEpochDay() - date.toEpochDay()
        return when (daysAgo) {
            0L -> Heading(appString(R.string.ui_today), today.toEpochDay())
            1L -> Heading(appString(R.string.ui_yesterday), today.toEpochDay() - 1)
            in 2L..6L -> Heading(appString(R.string.ui_past_week), today.toEpochDay() - 2)
            else -> Heading(monthFormat.format(date), date.withDayOfMonth(1).toEpochDay())
        }
    }

    private fun sizeHeading(file: CloudFile): Heading {
        val bytes = file.displayBytes ?: return when (file.folderStats) {
            FolderStats.Pending -> Heading(appString(R.string.ui_calculating_folder_stats), -1)
            FolderStats.Failed -> Heading(appString(R.string.ui_folder_stats_failed), -2)
            else -> Heading(if (file.folder) appString(R.string.ui_folder) else appString(R.string.ui_unknown_size), -3)
        }
        return when {
            bytes >= (1uL shl 30) -> Heading(appString(R.string.ui_size_group_gib), 3)
            bytes >= (100uL shl 20) -> Heading(appString(R.string.ui_size_group_hundreds_mib), 2)
            bytes >= (1uL shl 20) -> Heading(appString(R.string.ui_size_group_mib), 1)
            else -> Heading(appString(R.string.ui_size_group_small), 0)
        }
    }

    fun rowDetail(file: CloudFile, zone: ZoneId = ZoneId.systemDefault()): String {
        val timestamp = when {
            sort == FileSort.Created -> file.createdAt
            sort == FileSort.Recent -> file.modifiedAt
            sort == FileSort.ContentModified -> file.contentModifiedAt
            grouping == DriveGrouping.Created -> file.createdAt
            grouping == DriveGrouping.Modified -> file.modifiedAt
            file.folderStats != null || grouping == DriveGrouping.ContentModified -> file.contentModifiedAt
            else -> file.modifiedAt
        }
        return listOfNotNull(
            file.sizeDescription(),
            timestamp?.atZone(zone)?.toLocalDate()?.toString() ?: if (sort == FileSort.ContentModified &&
                file.folderStats is FolderStats.Complete && !file.folderStats.isEmpty
            ) appString(R.string.ui_unavailable_timestamp) else null,
            if (file.starred) appString(R.string.ui_favorited) else null,
        ).joinToString(" · ")
    }

    companion object {
        fun load(prefs: SharedPreferences): DrivePresentation {
            val sort = prefs.option("drive_sort", FileSort.Name)
            return DrivePresentation(
                prefs.option("drive_view", DriveView.List), prefs.option("drive_group", DriveGrouping.None),
                sort, prefs.getBoolean("drive_descending", sort.defaultDescending),
            )
        }

        private inline fun <reified T : Enum<T>> SharedPreferences.option(key: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == getString(key, null) } ?: fallback
    }
}

internal data class DriveSection(val label: String?, val entries: List<CloudFile>)

private fun CloudFile.sizeDescription(): String = when (val stats = folderStats) {
    null -> if (folder) appString(R.string.ui_folder) else displayBytes?.let(::readableBytes) ?: appString(R.string.ui_unknown_size)
    FolderStats.Pending -> appString(R.string.ui_calculating_folder_stats)
    FolderStats.Failed -> appString(R.string.ui_folder_stats_failed)
    is FolderStats.Complete -> if (stats.isEmpty) appString(R.string.ui_empty_folder_size) else readableBytes(stats.bytes)
}

private val detailDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

internal fun CloudFile.detailText(metadata: JSONObject, zone: ZoneId = ZoneId.systemDefault()): String = buildList {
    fun Instant.display() = detailDate.format(atZone(zone))
    val kind = if (folder) appString(R.string.ui_folder)
        else metadata.stringOrNull("mime_type") ?: appString(R.string.ui_single_file)
    add(appString(R.string.ui_file_kind_detail, kind))
    if (!folder) {
        val size = metadata.stringOrNull("size")?.toULongOrNull() ?: displayBytes
        add(appString(R.string.ui_file_size_detail, size?.let(::readableBytes) ?: appString(R.string.ui_unknown)))
    }
    (metadata.stringOrNull("created_time")?.cloudInstant() ?: createdAt)?.let { add(appString(R.string.ui_created_detail, it.display())) }
    (metadata.stringOrNull("modified_time")?.cloudInstant() ?: modifiedAt)?.let {
        val dateLabel = if (folder) R.string.ui_folder_modified_detail else R.string.ui_modified_detail
        add(appString(dateLabel, it.display()))
    }
    when (val stats = folderStats) {
        null -> Unit
        is FolderStats.Complete -> {
            val exactBytes = if (stats.bytes >= 1024uL) appString(R.string.ui_exact_size_detail, stats.bytes) else ""
            add(appString(R.string.ui_total_size_detail, sizeDescription(), exactBytes))
            val modified = stats.modifiedAt?.display()
                ?: appString(if (stats.isEmpty) R.string.ui_empty_folder else R.string.ui_unavailable_timestamp)
            add(appString(R.string.ui_content_modified_detail, modified))
        }
        else -> add(appString(R.string.ui_folder_stats_detail, sizeDescription()))
    }
    metadata.stringOrNull("hash")?.let { add(appString(R.string.ui_gcid_detail, it)) }
    add(appString(R.string.ui_id_detail, id))
}.joinToString("\n\n")
