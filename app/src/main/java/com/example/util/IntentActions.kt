package com.example.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.domain.magnet.MagnetParseResult
import com.example.domain.magnet.MagnetParser
import java.util.Locale

object IntentActions {
    const val SEEDR_WEB_URL = "https://www.seedr.cc"
    const val TORBOX_WEB_URL = "https://torbox.app/dashboard"

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Validates magnet semantics via [MagnetParser] and opens a local torrent client
     * (e.g. Flud, LibreTorrent). Never auto-launches or accepts malformed magnets.
     */
    fun openMagnetInLocalApp(context: Context, magnetUri: String): Boolean {
        val valid = MagnetParser.parseMagnetInput(magnetUri) as? MagnetParseResult.Valid ?: return false
        val uri = runCatching { Uri.parse(valid.magnetUri) }.getOrNull() ?: return false
        if (!uri.scheme.equals("magnet", ignoreCase = true)) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openUrlInBrowser(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Opens Android's native bottom intent chooser so the user can pick an external download manager
     * (e.g., 1DM, IDM+, ADM, FDM) or another installed browser to handle a direct download URL.
     */
    fun openDownloadManagerChooser(
        context: Context,
        url: String,
        fileName: String? = null,
        mimeType: String? = null
    ): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrEmpty()) return false

        val cleanTitle = fileName?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(80)
        val primaryIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (!cleanTitle.isNullOrBlank()) {
                putExtra("title", cleanTitle)
            }
        }

        val chooserTitle = if (!cleanTitle.isNullOrBlank()) {
            "Download $cleanTitle with…"
        } else {
            "Choose Download Manager or Browser"
        }

        val chooser = Intent.createChooser(primaryIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val safeMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf {
                it.isNotBlank() &&
                    it != "*/*" &&
                    it != "text/html" &&
                    it != "application/vnd.android.package-archive"
            } ?: "application/octet-stream"

        val typedIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, safeMime)
            if (!cleanTitle.isNullOrBlank()) {
                putExtra("title", cleanTitle)
            }
        }
        chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, arrayOf(typedIntent))

        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Outbound text sharing via system chooser. Explicitly excludes MagnetVault's own
     * ACTION_SEND intake activity so outbound sharing stays distinct from incoming capture.
     */
    fun shareText(context: Context, title: String, content: String, chooserLabel: String = "Share Magnet Link") {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        val chooser = Intent.createChooser(sendIntent, chooserLabel).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context.packageName, "com.example.MainActivity"))
                )
            }
        }
        context.startActivity(chooser)
    }
}
