package org.jianyu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jianyu.app.ui.JianyuApp
import org.jianyu.app.ui.theme.JianyuTheme

class MainActivity : ComponentActivity() {
    private var incomingShareText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingShareText = intent.toIncomingShareDraft()
        setContent {
            JianyuTheme {
                JianyuApp(
                    incomingShareText = incomingShareText,
                    onIncomingShareConsumed = { incomingShareText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShareText = intent.toIncomingShareDraft()
    }

    private fun Intent?.toIncomingShareDraft(): String? {
        if (this?.action != Intent.ACTION_SEND || type != "text/plain") return null
        return buildIncomingShareDraft(
            subject = getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            text = getCharSequenceExtra(Intent.EXTRA_TEXT),
        )
    }
}
