package com.snaix.pikpak

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment

internal const val CLEAR_PREVIEW_CACHE = "clear_preview_cache"
internal const val CACHE_ACTION_RESULT = "preview_cache_action"
internal const val CACHE_ACTION_KEY = "key"
internal const val CACHE_ACTION_VALUE = "value"

class PreviewCacheSettingsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val key = requireNotNull(requireArguments().getString(CACHE_ACTION_KEY))
        val builder = AlertDialog.Builder(requireContext()).setNegativeButton(R.string.preview_cache_cancel, null)
        if (key == CLEAR_PREVIEW_CACHE) {
            return builder.setTitle(R.string.preview_cache_clear_question)
                .setMessage(R.string.preview_cache_clear_message)
                .setPositiveButton(R.string.preview_cache_clear_confirm) { _, _ -> submit(key) }
                .create()
        }

        val limits = PreviewCacheLimits.read(requireContext().appearancePreferences())
        val isSize = key == PreviewCacheLimits.SIZE_KEY
        val title = if (isSize) R.string.preview_cache_size_dialog_title else R.string.preview_cache_count_dialog_title
        val input = AppCompatEditText(builder.context).apply {
            id = android.R.id.edit
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(title)
            setText((if (isSize) limits.maxMiB else limits.maxEntries).toString())
            setSelectAllOnFocus(true)
        }
        val body = FrameLayout(builder.context).apply {
            setPaddingRelative(context.dp(24), 0, context.dp(24), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = builder.setTitle(title)
            .setMessage(R.string.preview_cache_limit_description)
            .setView(body)
            .setPositiveButton(R.string.preview_cache_save) { _, _ ->
                input.text?.toString()?.toIntOrNull()?.takeIf { it > 0 }?.let { submit(key, it) }
            }.create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun validate() {
                val value = input.text?.toString().orEmpty()
                save.isEnabled = (value.toIntOrNull() ?: 0) > 0
                input.error = if (value.isNotEmpty() && !save.isEnabled) getString(R.string.preview_cache_positive_integer) else null
            }
            validate()
            input.doAfterTextChanged { validate() }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE && save.isEnabled) save.performClick() else false
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return dialog
    }

    private fun submit(key: String, value: Int = 0) {
        parentFragmentManager.setFragmentResult(CACHE_ACTION_RESULT, bundleOf(CACHE_ACTION_KEY to key, CACHE_ACTION_VALUE to value))
    }

    companion object {
        fun newInstance(key: String) = PreviewCacheSettingsDialog().apply {
            arguments = bundleOf(CACHE_ACTION_KEY to key)
        }
    }
}
