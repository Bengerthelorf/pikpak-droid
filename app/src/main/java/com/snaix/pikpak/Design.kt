package com.snaix.pikpak

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils

internal enum class Symbol(vararg val names: String) {
    Folder("ic_oui_folder_outline", "ic_oui_folder"),
    Download("ic_oui_download", "ic_oui_save"),
    Share("ic_oui_share", "ic_oui_share_outline"),
    Person("ic_oui_contact_outline", "ic_oui_contact"),
    Star("ic_oui_star_outline", "ic_oui_favorite_off"),
    Trash("ic_oui_delete", "ic_oui_delete_outline"),
    Clock("ic_oui_recent", "ic_oui_clock_outline"),
    Back("ic_oui_back", "ic_oui_arrow_back"),
    More("ic_oui_more_vertical", "ic_oui_more"),
    Plus("ic_oui_add", "ic_oui_plus"),
    Search("ic_oui_search"),
    Refresh("ic_oui_refresh", "ic_oui_sync"),
    Sort("ic_oui_list_sort"),
    ViewOptions("ic_oui_list_view_by"),
    ListView("ic_oui_list"),
    GridView("ic_oui_list_grid"),
    GroupBy("ic_oui_list_extended"),
    FileName("ic_oui_text"),
    FileKind("ic_oui_file_type_etc"),
    FileSize("ic_oui_analyze_storage"),
    DateCreated("ic_oui_calendar"),
    DateModified("ic_oui_calendar_period_time"),
    SortUp("ic_oui_arrow_up"),
    SortDown("ic_oui_arrow_down"),
    ChevronEnd("ic_oui_keyboard_arrow_right"),
    FolderFilled("ic_oui_folder"),
    Check("sesl_spinner_icon_check", "ic_oui_text_check_on"),
    Video("ic_oui_file_type_video", "ic_oui_control_play"),
    Image("ic_oui_gallery_outline", "ic_oui_image"),
    Audio("ic_oui_file_type_audio"),
    Text("ic_oui_text_outline", "ic_oui_document_outline"),
    Document("ic_oui_document_outline", "ic_oui_details"),
    External("ic_oui_open_in_new", "ic_oui_open"),
    Upload("ic_oui_upload"),
    Move("ic_oui_move"),
    Copy("ic_oui_copy"),
    Rename("ic_oui_edit"),
    Info("ic_oui_info_outline", "ic_oui_info"),
    Link("ic_oui_link", "ic_oui_internet_website"),
    Play("ic_oui_control_play"),
    Pause("ic_oui_control_pause", "ic_oui_control_pause_circle_filled"),
    Sun("ic_oui_brightness_outline", "ic_oui_settings_outline"),
    Settings("ic_oui_settings_outline", "ic_oui_settings"),
    Lock("ic_oui_lock_outline", "ic_oui_lock"),
    Exit("ic_oui_sign_out", "ic_oui_close"),
    Hash("ic_oui_keyboard_outline", "ic_oui_info_outline"),
    Empty("ic_oui_folder_outline"),
}

internal val FileCategory.symbol: Symbol get() = when (this) {
    FileCategory.Folder -> Symbol.Folder
    FileCategory.Video -> Symbol.Video
    FileCategory.Image -> Symbol.Image
    FileCategory.Audio -> Symbol.Audio
    FileCategory.Text -> Symbol.Text
    FileCategory.Document -> Symbol.Document
}

internal fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

internal fun Context.themeColor(attr: Int, fallback: Int = Color.BLACK): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return fallback
    return if (value.resourceId != 0) runCatching { getColor(value.resourceId) }.getOrDefault(fallback) else value.data
}

internal fun Context.icon(symbol: Symbol, color: Int? = null): Drawable? {
    @Suppress("DiscouragedApi")
    val id = symbol.names.firstNotNullOfOrNull { name ->
        resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
    } ?: return null
    return AppCompatResources.getDrawable(this, id)?.mutate()?.apply { if (color != null) setTint(color) }
}

internal fun Context.contentTextColors(secondary: Boolean = false): ColorStateList? {
    val colors = obtainStyledAttributes(R.styleable.WorkspaceText)
    return try {
        val primary = colors.getColorStateList(R.styleable.WorkspaceText_android_textColorPrimary)
        val muted = colors.getColorStateList(R.styleable.WorkspaceText_android_textColorSecondary)
        if (!secondary || primary == null || muted == null) return if (secondary) muted ?: primary else primary
        // Small supporting text keeps the theme's neutral hue with more contrast.
        val enabled = ColorUtils.blendARGB(muted.defaultColor, primary.defaultColor, 0.24f)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(muted.getColorForState(disabled, enabled), enabled))
    } finally {
        colors.recycle()
    }
}

internal fun Context.label(value: CharSequence = "", size: Float = 16f, secondary: Boolean = false): AppCompatTextView =
    AppCompatTextView(this).apply {
        text = value
        textSize = size
        contentTextColors(secondary)?.let(::setTextColor)
    }

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

internal fun Context.button(title: String, action: () -> Unit): AppCompatButton =
    AppCompatButton(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }

internal fun View.selectableBackground(borderless: Boolean = false) {
    val value = TypedValue()
    val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
    if (context.theme.resolveAttribute(attr, value, true) && value.resourceId != 0) setBackgroundResource(value.resourceId)
}

internal fun ViewGroup.addFullWidth(child: View) = addView(
    child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
)

internal fun View.showWhen(show: Boolean) { visibility = if (show) View.VISIBLE else View.GONE }

internal fun String.displayTime(): String = take(16).replace('T', ' ')
