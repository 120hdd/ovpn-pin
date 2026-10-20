package com.erelay.relay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.Accounts
import com.erelay.relay.data.Mode
import com.erelay.relay.orb.OrbStage
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type

/**
 * The first thing a phone with nothing on it sees: no account, no servers,
 * no server of its own. Instead of a Connect button that cannot work and a
 * Settings sheet to find the way out of, it asks the one question that
 * matters - how will you connect - and each answer goes straight to the page
 * that sets it up.
 */
@Composable
fun Welcome(
    open: Boolean,
    onProvider: (String) -> Unit,
    onServer: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    AnimatedVisibility(open, enter = fadeIn(tween(Shape.ENTER_MS)), exit = fadeOut(tween(Shape.ENTER_MS))) {
        BackHandler(onBack = onSkip)
        Backdrop {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                OrbStage(Mode.OFF, 0f, paused = false, size = 168.dp, still = true)
                Text("Welcome to Relay", style = Type.title.copy(fontSize = 26.sp))
                Spacer(Modifier.height(8.dp))
                Text("How will you connect? Pick one to start - the others can be added later " +
                    "under Settings.", style = Type.body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))

                Choice(Glyph.key, "A VPN account",
                    "Sign in, and Relay fetches the provider's servers and pins them to real " +
                        "addresses.") {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Surfshark", { onProvider(Accounts.SURFSHARK) }, Modifier.weight(1f),
                            strong = true)
                        Btn("Windscribe", { onProvider(Accounts.WINDSCRIBE) }, Modifier.weight(1f),
                            strong = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Choice(Glyph.tower, "My own server",
                    "A server of yours behind Cloudflare. Set its domain and password.",
                    onClick = onServer)
                Spacer(Modifier.height(12.dp))
                Choice(Glyph.folder, "I have .ovpn files",
                    "Import a folder of configs from this phone.", onClick = onImport)

                Spacer(Modifier.height(20.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).tap(onClick = onSkip)
                    .padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("Not now", style = Type.button.copy(color = Tone.textMuted))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One answer: a card with an icon, what it is, and how it works. */
@Composable
private fun Choice(
    icon: ImageVector,
    title: String,
    say: String,
    onClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.press(shape = shape, sink = true, onClick = onClick)
                  else Modifier)
            .clip(shape)
            .background(Tone.surface)
            .border(1.dp, Tone.border, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Tone.ok.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = Tone.ok)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.strong.copy(fontSize = 17.sp, fontWeight = FontWeight.W700))
                Spacer(Modifier.height(2.dp))
                Text(say, style = Type.small.copy(color = Tone.textMuted))
            }
            if (onClick != null) Icon(Glyph.chevRight, null, Modifier.size(18.dp), tint = Tone.textOff)
        }
        extra()
    }
}
