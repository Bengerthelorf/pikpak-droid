package com.snaix.pikpak

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SeslProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.text.BreakIterator
import kotlin.math.roundToInt

internal class DrawerAccountPanel(
    private val activity: AppCompatActivity,
    private val root: NavDrawerLayout,
    private val model: CloudModel,
) {
    private val header = root.findViewById<ViewGroup>(dev.oneuiproject.oneui.design.R.id.header_layout)
    private val profile = activity.layoutInflater.inflate(R.layout.drawer_profile, header, false)
    private val name = profile.findViewById<TextView>(R.id.drawer_profile_name)
    private val initial = profile.findViewById<TextView>(R.id.drawer_initial)
    private val photo = profile.findViewById<ImageView>(R.id.drawer_avatar_image)
    private val menuList = root.findViewById<DrawerNavigationView>(R.id.workspace_drawer).getChildAt(0) as RecyclerView
    private val footer = activity.layoutInflater.inflate(R.layout.drawer_quota_footer, menuList, false)
    private val quotas = footer.findViewById<LinearLayout>(R.id.drawer_quota_panel)
    private val avatarInk = activity.themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer, name.currentTextColor)
    private var avatar: Uri? = null
    private var avatarLoaded = false
    private var avatarJob: Job? = null
    private var drawerOpen = false
    private var expanded = model.drawerQuota.density == DrawerQuotaDensity.Detailed
    private var density = model.drawerQuota.density
    private val observe: Job

    // DrawerNavigationView owns the library's single drawer callback in rail mode.
    private val onDraw = ViewTreeObserver.OnPreDrawListener {
        val open = root.drawerOffset > 0f
        if (open != drawerOpen) {
            drawerOpen = open
            if (open) {
                if (!avatarLoaded && avatarJob?.isCompleted == true) avatarJob = null
                bindProfile(model.drawerAccount)
                model.loadDrawerAccount()
                model.loadDrawerFavorites()
            }
            updateVisibility()
        }
        true
    }

    init {
        // One scrollable RecyclerView keeps only visible favorite rows alive, even in large accounts.
        val footerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                    id = R.id.drawer_quota_host
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val host = holder.itemView as FrameLayout
                if (footer.parent === host) return
                (footer.parent as? ViewGroup)?.removeView(footer)
                host.addView(footer)
            }
        }
        menuList.adapter = ConcatAdapter(checkNotNull(menuList.adapter), footerAdapter)
        header.addView(profile)
        val avatarFrame = profile.findViewById<FrameLayout>(R.id.drawer_avatar)
        initial.setTextColor(avatarInk)
        avatarFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(activity.themeColor(com.google.android.material.R.attr.colorSecondaryContainer, activity.themeColor(androidx.appcompat.R.attr.colorControlHighlight)))
        }
        avatarFrame.clipToOutline = true
        profile.setOnClickListener { openAccount() }
        root.viewTreeObserver.addOnPreDrawListener(onDraw)
        observe = activity.lifecycleScope.launch {
            model.observe { Triple(model.drawerAccount, model.drawerQuota, model.location.drawerDestination == Page.Account) }
                .collect { (account, preferences, selected) ->
                    bindProfile(account)
                    profile.isSelected = selected
                    if (density != preferences.density) {
                        density = preferences.density
                        expanded = density == DrawerQuotaDensity.Detailed
                    }
                    bindQuotas(account, preferences)
                }
        }
    }

    private fun openAccount() {
        root.setDrawerOpen(false)
        model.open(Page.Account)
    }

    private fun bindProfile(account: DrawerAccountData?) {
        val displayName = account?.displayName?.trim()?.takeIf { it.isNotBlank() }
        name.text = displayName ?: activity.getString(R.string.my_account)
        profile.contentDescription = if (displayName == null) name.text
            else activity.getString(R.string.drawer_named_profile, displayName)
        val letter = displayName?.let {
            val boundary = BreakIterator.getCharacterInstance(AppLanguages.locale).apply { setText(it) }.next()
            it.substring(0, boundary).uppercase(AppLanguages.locale)
        }
        initial.text = letter
        val requested = account?.avatar
        if (requested != avatar) {
            avatarJob?.cancel()
            avatarJob = null
            avatarLoaded = false
            avatar = requested
        }
        if (!avatarLoaded) {
            photo.scaleType = if (letter == null) ImageView.ScaleType.CENTER else ImageView.ScaleType.CENTER_CROP
            photo.setImageDrawable(if (letter == null) activity.icon(Symbol.Person, avatarInk) else null)
        }
        if (requested == null || !drawerOpen || avatarJob != null) return
        avatarJob = activity.lifecycleScope.launch {
            val bitmap = try {
                runInterruptible(Dispatchers.IO) { loadPreviewImage(requested, 2 * 1024 * 1024, activity.dp(48)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (bitmap != null && requested == avatar) {
                avatarLoaded = true
                photo.scaleType = ImageView.ScaleType.CENTER_CROP
                photo.setImageBitmap(bitmap)
            }
        }
    }

    private fun bindQuotas(account: DrawerAccountData?, preferences: DrawerQuotaPreferences) {
        quotas.removeAllViews()
        if (account != null) {
            val visible = DrawerQuotaKind.entries.mapNotNull { kind ->
                account.quota(kind)?.takeIf { kind in preferences.visible }?.let { kind to it }
            }
            val details = visible.associate { (kind, quota) ->
                val remaining = quota.remaining
                val extra = when {
                    remaining != null -> activity.getString(R.string.ui_quota_remaining, readableBytes(remaining))
                    quota.unlimited && quota.used != null -> activity.getString(R.string.ui_usage, readableBytes(quota.used))
                    else -> null
                }
                kind to extra
            }
            visible.forEach { (kind, quota) ->
                val row = activity.column().apply {
                    setPaddingRelative(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                    minimumHeight = activity.dp(48)
                    selectableBackground()
                    isFocusable = true
                    setOnClickListener { openAccount() }
                }
                val value = quota.detail()
                val label = if (kind == DrawerQuotaKind.Storage) activity.getString(R.string.drawer_storage_label) else kind.label
                row.addFullWidth(activity.label(label, size = 15f).apply { setTypeface(null, Typeface.BOLD) })
                row.addFullWidth(activity.label(value, size = 14f, secondary = true))
                val limit = quota.limit
                val used = quota.used
                if (used != null && limit != null && limit > 0L && (kind == DrawerQuotaKind.Storage || expanded)) {
                    val track = activity.layoutInflater.inflate(R.layout.drawer_quota_progress, row, false) as SeslProgressBar
                    if (kind != DrawerQuotaKind.Storage) track.id = View.NO_ID
                    track.progress = (used.toDouble() / limit * 100).coerceIn(0.0, 100.0).roundToInt()
                    row.addView(track)
                }
                val extra = details[kind].takeIf { expanded }
                if (extra != null) row.addFullWidth(activity.label(extra, size = 13f, secondary = true))
                val spoken = listOfNotNull(value, extra).joinToString(". ")
                row.contentDescription = activity.getString(R.string.drawer_open_quota, label, spoken)
                for (index in 0 until row.childCount) row.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                quotas.addFullWidth(row)
            }
            if (details.values.any { it != null }) {
                val more = AppCompatButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    isAllCaps = false
                    text = activity.getString(if (expanded) R.string.drawer_collapse_usage else R.string.drawer_expand_usage)
                    setOnClickListener {
                        val restoreFocus = isFocused
                        expanded = !expanded
                        bindQuotas(account, preferences)
                        if (restoreFocus) quotas.getChildAt(quotas.childCount - 1)?.requestFocus()
                    }
                }
                quotas.addFullWidth(more)
            }
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val fullDrawer = !root.isLargeScreenMode || drawerOpen
        profile.showWhen(fullDrawer)
        footer.showWhen(fullDrawer && quotas.childCount > 0)
    }

    fun dispose() {
        root.viewTreeObserver.removeOnPreDrawListener(onDraw)
        avatarJob?.cancel()
        observe.cancel()
    }
}
