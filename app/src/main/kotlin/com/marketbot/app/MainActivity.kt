package com.marketbot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.marketbot.app.ui.AppRoot
import com.marketbot.app.ui.MarketbotTheme

class MainActivity : FragmentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Shortcuts.from(intent)
        setContent { MarketbotTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Shortcuts.from(intent)
    }
}

/** The launcher shortcut that opened the app (res/xml/shortcuts.xml), handed to the UI once. */
object Shortcuts {
    val requested = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val known = setOf("kill", "trades", "stop")
    fun from(intent: android.content.Intent?) { intent?.getStringExtra("shortcut")?.takeIf { it in known }?.let { requested.value = it } }
}
