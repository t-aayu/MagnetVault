package com.example.ui.handoff

import android.content.ClipboardManager
import android.content.Context
import com.example.domain.magnet.MagnetParseResult
import com.example.domain.magnet.MagnetParser
import com.example.model.WebsiteOpenMode
import com.example.ui.browser.BrowserUrlPolicy
import com.example.ui.browser.InterceptedDownload
import com.example.util.IntentActions

data class HandoffFeedback(
    val success: Boolean,
    val message: String,
    val openInAppUrl: String? = null
)

interface MagnetHandoffGateway {
    fun readClipboardText(): String?
    fun copyToClipboard(label: String, text: String): Boolean
    fun openMagnetInLocalClient(magnetUri: String): Boolean
    fun openUrlInBrowser(url: String): Boolean
    fun shareText(title: String, content: String): Boolean
    fun openDownloadManagerChooser(url: String, fileName: String?, mimeType: String?): Boolean = false
}

class AndroidMagnetHandoffGateway(
    private val context: Context
) : MagnetHandoffGateway {
    override fun readClipboardText(): String? {
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
        }.getOrNull()
    }

    override fun copyToClipboard(label: String, text: String): Boolean {
        return runCatching {
            IntentActions.copyToClipboard(context, label, text)
            true
        }.getOrDefault(false)
    }

    override fun openMagnetInLocalClient(magnetUri: String): Boolean =
        IntentActions.openMagnetInLocalApp(context, magnetUri)

    override fun openUrlInBrowser(url: String): Boolean =
        IntentActions.openUrlInBrowser(context, url)

    override fun shareText(title: String, content: String): Boolean {
        return runCatching {
            IntentActions.shareText(context, title, content)
            true
        }.getOrDefault(false)
    }

    override fun openDownloadManagerChooser(url: String, fileName: String?, mimeType: String?): Boolean =
        IntentActions.openDownloadManagerChooser(context, url, fileName, mimeType)
}

/**
 * Single shared handoff controller keeping Android Intent and ClipboardManager operations outside
 * presentational Jetpack Compose UI components. Validates outgoing magnet semantics and URLs.
 */
class MagnetHandoffController(
    private val gateway: MagnetHandoffGateway
) {
    constructor(context: Context) : this(AndroidMagnetHandoffGateway(context))

    fun readClipboard(): String = gateway.readClipboardText().orEmpty()

    private fun validateOutgoingMagnet(magnetUri: String?): MagnetParseResult.Valid? {
        val raw = magnetUri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return MagnetParser.parseMagnetInput(raw) as? MagnetParseResult.Valid
    }

    fun copyMagnet(title: String, magnetUri: String?): HandoffFeedback {
        if (magnetUri.isNullOrBlank()) {
            return HandoffFeedback(false, "Magnet link is not available yet.")
        }
        val valid = validateOutgoingMagnet(magnetUri)
            ?: return HandoffFeedback(false, "Cannot copy malformed or oversized magnet link.")
        val copied = gateway.copyToClipboard(title.ifBlank { valid.displayName }, valid.magnetUri)
        return if (copied) {
            HandoffFeedback(true, "Magnet link copied to clipboard")
        } else {
            HandoffFeedback(false, "Could not access clipboard on this device")
        }
    }

    fun openLocalClient(title: String, magnetUri: String?): HandoffFeedback {
        if (magnetUri.isNullOrBlank()) {
            return HandoffFeedback(false, "Magnet link is not ready yet. Resolve or retry first.")
        }
        val valid = validateOutgoingMagnet(magnetUri)
            ?: return HandoffFeedback(false, "Invalid magnet link blocked before opening external client.")
        val launched = gateway.openMagnetInLocalClient(valid.magnetUri)
        if (launched) {
            return HandoffFeedback(true, "Opening in local torrent client")
        }
        val copied = gateway.copyToClipboard(title.ifBlank { valid.displayName }, valid.magnetUri)
        val guidance = if (copied) {
            "No torrent client installed — magnet copied. Install LibreTorrent/Flud or use Seedr/TorBox."
        } else {
            "No compatible torrent client installed. Install LibreTorrent/Flud or use Seedr/TorBox."
        }
        return HandoffFeedback(false, guidance)
    }

    fun openCloudBox(
        title: String,
        magnetUri: String?,
        targetUrl: String,
        targetLabel: String,
        mode: WebsiteOpenMode
    ): HandoffFeedback {
        val validUrl = BrowserUrlPolicy.validateStrictHttpsUrl(targetUrl)
            ?: return HandoffFeedback(false, "Invalid $targetLabel URL")
        var copied = false
        if (!magnetUri.isNullOrBlank()) {
            val validMagnet = validateOutgoingMagnet(magnetUri)
                ?: return HandoffFeedback(false, "Invalid magnet link cannot be copied to $targetLabel.")
            copied = gateway.copyToClipboard(title.ifBlank { validMagnet.displayName }, validMagnet.magnetUri)
        }
        val copyNotice = if (copied) "Magnet copied for manual paste — " else ""
        return when (mode) {
            WebsiteOpenMode.IN_APP -> HandoffFeedback(
                success = true,
                message = "${copyNotice}opening $targetLabel in app",
                openInAppUrl = validUrl
            )
            WebsiteOpenMode.DEFAULT_BROWSER -> {
                val opened = gateway.openUrlInBrowser(validUrl)
                if (opened) {
                    HandoffFeedback(true, "${copyNotice}opening $targetLabel in browser")
                } else if (copied) {
                    HandoffFeedback(
                        false,
                        "No browser app found to open $targetLabel — magnet copied to clipboard."
                    )
                } else {
                    HandoffFeedback(false, "No browser app found to open $targetLabel.")
                }
            }
        }
    }

    fun openSourcePage(rawUrl: String, mode: WebsiteOpenMode): HandoffFeedback {
        val validUrl = BrowserUrlPolicy.validateAndNormalizeWebUrl(rawUrl)
            ?: return HandoffFeedback(false, "Invalid website URL")
        return when (mode) {
            WebsiteOpenMode.IN_APP -> HandoffFeedback(
                success = true,
                message = "Opening site in app",
                openInAppUrl = validUrl
            )
            WebsiteOpenMode.DEFAULT_BROWSER -> {
                val opened = gateway.openUrlInBrowser(validUrl)
                if (opened) {
                    HandoffFeedback(true, "Opening website in browser")
                } else {
                    HandoffFeedback(false, "No browser app found to open website.")
                }
            }
        }
    }

    fun shareMagnet(title: String, magnetUri: String?): HandoffFeedback {
        if (magnetUri.isNullOrBlank()) {
            return HandoffFeedback(false, "Magnet link is not available yet.")
        }
        val valid = validateOutgoingMagnet(magnetUri)
            ?: return HandoffFeedback(false, "Cannot share malformed or oversized magnet link.")
        val safeTitle = title.ifBlank { valid.displayName }
        val shared = gateway.shareText(safeTitle, "$safeTitle\n${valid.magnetUri}")
        return if (shared) {
            HandoffFeedback(true, "Sharing magnet link")
        } else {
            HandoffFeedback(false, "No compatible sharing app found on this device.")
        }
    }

    fun launchDownloadManager(download: InterceptedDownload): HandoffFeedback {
        val safeUrl = BrowserUrlPolicy.validateAndNormalizeWebUrl(download.url)
            ?: return HandoffFeedback(false, "Unsupported download link scheme")
        val launched = gateway.openDownloadManagerChooser(
            url = safeUrl,
            fileName = download.fileName,
            mimeType = download.mimeType
        )
        return if (launched) {
            HandoffFeedback(true, "Opening download chooser for ${download.hostLabel.ifBlank { "file" }}")
        } else {
            HandoffFeedback(false, "No external download manager or browser found")
        }
    }

    fun copyDownloadLink(download: InterceptedDownload): HandoffFeedback {
        val safeUrl = BrowserUrlPolicy.validateAndNormalizeWebUrl(download.url)
            ?: return HandoffFeedback(false, "Unsupported download link scheme")
        val copied = gateway.copyToClipboard(download.fileName, safeUrl)
        return if (copied) {
            HandoffFeedback(
                true,
                if (download.isLikelySessionBound) {
                    "Download link copied (note: session cookies remain in browser)"
                } else {
                    "Download link copied to clipboard"
                }
            )
        } else {
            HandoffFeedback(false, "Could not access clipboard")
        }
    }

    fun shareDownloadLink(download: InterceptedDownload): HandoffFeedback {
        val safeUrl = BrowserUrlPolicy.validateAndNormalizeWebUrl(download.url)
            ?: return HandoffFeedback(false, "Unsupported download link scheme")
        val shared = gateway.shareText(download.fileName, safeUrl)
        return if (shared) {
            HandoffFeedback(true, "Sharing download link")
        } else {
            HandoffFeedback(false, "No compatible sharing app found")
        }
    }
}
