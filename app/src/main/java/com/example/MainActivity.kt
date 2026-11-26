package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.domain.magnet.MagnetIntake
import com.example.ui.MagnetVaultApp
import java.util.UUID

data class IncomingShareRequest(
    val eventId: String,
    val rawText: String
)

object ShareIntentParser {
    private const val EXTRA_SHARE_CONSUMED = "com.example.extra.SHARE_CONSUMED"

    fun extract(intent: Intent?, isRestoredInstance: Boolean): IncomingShareRequest? {
        if (intent == null || isRestoredInstance) return null
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return null

        val mime = intent.type
        if (mime != null && !mime.startsWith("text/", ignoreCase = true)) {
            return null
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            ?: return null

        // Mark intent consumed so configuration changes / history-relaunch won't replay it
        intent.putExtra(EXTRA_SHARE_CONSUMED, true)
        val bounded = if (sharedText.length > MagnetIntake.MAX_SHARE_TEXT_LENGTH + 1) {
            sharedText.take(MagnetIntake.MAX_SHARE_TEXT_LENGTH + 1)
        } else {
            sharedText
        }
        return IncomingShareRequest(
            eventId = UUID.randomUUID().toString(),
            rawText = bounded
        )
    }
}

class MainActivity : ComponentActivity() {
    private var incomingShareState by mutableStateOf<IncomingShareRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        incomingShareState = ShareIntentParser.extract(
            intent = intent,
            isRestoredInstance = savedInstanceState != null
        )
        setContent {
            MagnetVaultApp(incomingShare = incomingShareState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = ShareIntentParser.extract(intent = intent, isRestoredInstance = false)
        if (next != null) {
            incomingShareState = next
        }
    }
}
