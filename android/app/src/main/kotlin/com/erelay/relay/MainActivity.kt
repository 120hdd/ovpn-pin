package com.erelay.relay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.erelay.relay.ui.Root
import com.erelay.relay.ui.theme.RelayTheme
import com.erelay.relay.ui.theme.Tone

/**
 * The window. Everything it shows is Compose; everything it knows comes from
 * [RelayApp.core]. The tunnel is not this Activity's - closing the window is
 * not disconnecting.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Tone.bg.toArgb()),
        )
        val core = (application as RelayApp).core
        setContent {
            RelayTheme {
                Root(core)
            }
        }
    }
}
