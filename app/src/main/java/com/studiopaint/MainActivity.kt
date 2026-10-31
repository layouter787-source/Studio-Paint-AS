package com.studiopaint

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.studiopaint.storage.ProjectStore
import com.studiopaint.ui.EditorScreen
import com.studiopaint.ui.HomeScreen

/** Ganchos chamados em onPause para gravar o projeto aberto. */
object SaveHooks { val hooks = mutableListOf<() -> Unit>() }

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7AA2FF), background = Color(0xFF15181E), surface = Color(0xFF1E222A))) {
                AppRoot(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        SaveHooks.hooks.toList().forEach { it() }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        incoming.value = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
    }
}

@Composable
fun AppRoot(incoming: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val store = remember { ProjectStore(ctx.applicationContext) }
    var openId by remember { mutableStateOf<String?>(null) }
    val id = openId
    if (id == null) {
        HomeScreen(store, incoming) { openId = it }
    } else {
        EditorScreen(store, id) { openId = null }
    }
}
