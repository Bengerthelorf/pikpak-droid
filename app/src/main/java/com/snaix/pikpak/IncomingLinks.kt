package com.snaix.pikpak

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.content.IntentCompat

internal object IncomingLinks {
    fun fromIntent(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf { link ->
            if (intent.scheme.equals("magnet", true)) isOfflineLink(link) else isValidShareUrl(link)
        }
        Intent.ACTION_SEND -> fromParts(buildList {
            addText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            addClip(intent.clipData)
            addUri(intent.data)
            addUri(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { addText(Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT)) }
        })
        else -> null
    }

    fun fromClip(clip: ClipData?): String? = fromParts(buildList { addClip(clip) })

    private fun MutableList<String>.addClip(clip: ClipData?) {
        if (clip == null) return
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            addText(item.text)
            addUri(item.uri)
            item.htmlText?.let { addText(Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT)) }
        }
    }

    private fun MutableList<String>.addText(value: CharSequence?) {
        if (value.isNullOrBlank()) return
        add(value.toString())
        if (value is Spanned) {
            value.getSpans(0, value.length, URLSpan::class.java).forEach { addUri(Uri.parse(it.url)) }
        }
    }

    private fun MutableList<String>.addUri(uri: Uri?) {
        if (uri?.scheme?.lowercase() in setOf("magnet", "http", "https")) add(uri.toString())
    }

    private fun fromParts(parts: List<String>): String? {
        val text = parts.distinct().joinToString("\n")
        val links = embeddedLinks(text)
        links.firstOrNull { it.startsWith("magnet:", true) && isOfflineLink(it) }?.let { return it }
        if (containsPikPakShare(text)) {
            val share = parseShareInput(text) ?: return links.firstOrNull(::isPikPakShare)
            return if (share.accessCode == null) share.link else "${share.link}\n提取码：${share.accessCode}"
        }
        return links.firstOrNull(::isOfflineLink)
    }
}
