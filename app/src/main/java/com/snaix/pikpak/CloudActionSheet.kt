package com.snaix.pikpak

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.R as AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import dev.oneuiproject.oneui.app.SemBottomSheetDialogFragment

internal class CloudActionSheet : SemBottomSheetDialogFragment(R.layout.cloud_action_sheet) {
    var heading: String = ""
    var actions: List<Choice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The commands are scoped to the current screen and are not restored after recreation.
        if (actions.isEmpty()) showsDialog = false
    }

    @SuppressLint("PrivateResource") // Reuse the list row supplied by the app's SESL AppCompat dependency.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (actions.isEmpty()) return
        super.onViewCreated(view, savedInstanceState)

        val edgeInset = resources.getDimensionPixelSize(R.dimen.cloud_sheet_edge_inset)
        val decor = view.rootView
        decor.updatePadding(left = maxOf(decor.paddingLeft, edgeInset), right = maxOf(decor.paddingRight, edgeInset))

        view.findViewById<TextView>(R.id.cloud_sheet_title).text = heading
        val rowInset = resources.getDimensionPixelSize(R.dimen.cloud_sheet_content_inset) -
            resources.getDimensionPixelSize(R.dimen.cloud_sheet_list_inset)
        view.findViewById<ListView>(R.id.cloud_sheet_actions).apply {
            adapter = object : ArrayAdapter<Choice>(context, AppCompatResources.layout.sesl_select_dialog_item, actions) {
                override fun getViewTypeCount() = 2

                override fun getItemViewType(position: Int) = if (checkNotNull(getItem(position)).separated) 1 else 0

                override fun getView(position: Int, old: View?, parent: ViewGroup): View {
                    val choice = checkNotNull(getItem(position))
                    val recycled = old?.findViewById<TextView>(android.R.id.text1)
                    val row = super.getView(position, recycled, parent) as TextView
                    row.bindChoice(choice, rowInset)
                    if (old != null || !choice.separated) return old ?: row
                    return context.column().apply {
                        val divider = View(context).apply {
                            setBackgroundColor(context.themeColor(AppCompatResources.attr.seslDialogDivderColor))
                        }
                        addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                            marginStart = rowInset
                            marginEnd = rowInset
                        })
                        addFullWidth(row)
                    }
                }
            }
            setOnItemClickListener { _, _, index, _ ->
                val perform = actions[index].action
                dismissNow()
                perform()
            }
        }
    }

    private fun TextView.bindChoice(choice: Choice, rowInset: Int) {
        val color = if (choice.dangerous) context.themeColor(com.google.android.material.R.attr.colorError)
            else context.themeColor(android.R.attr.textColorPrimary, currentTextColor)
        val hintColor = context.contentTextColors(secondary = true)?.defaultColor
            ?: context.themeColor(android.R.attr.textColorSecondary, color)
        text = choice.detail?.let { detail ->
            SpannableString("${choice.title}\n$detail").apply {
                val detailStart = choice.title.length + 1
                setSpan(RelativeSizeSpan(0.82f), detailStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(hintColor), detailStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } ?: choice.title
        setTextColor(color)
        updatePaddingRelative(start = rowInset, end = rowInset)
        compoundDrawablePadding = context.dp(12)
        val icon = context.icon(choice.icon, color)?.apply { setBounds(0, 0, context.dp(22), context.dp(22)) }
        val indicator = when {
            choice.opensSubmenu -> context.icon(Symbol.ChevronEnd, hintColor)?.apply { isAutoMirrored = true }
            choice.selected == true -> context.icon(Symbol.Check, color)
            else -> null
        }?.apply { setBounds(0, 0, context.dp(20), context.dp(20)) }
        setCompoundDrawablesRelative(icon, null, indicator, null)
        ViewCompat.setStateDescription(this, choice.selected?.let { if (it) appString(R.string.ui_selected) else appString(R.string.ui_unselected) })
    }

    override fun onStart() {
        super.onStart()
        if (actions.isEmpty()) dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "cloud-actions"
    }
}
