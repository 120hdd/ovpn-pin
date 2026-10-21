package com.erelay.relay.ui

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
import androidx.compose.foundation.shape.CircleShape
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
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.Setup
import com.erelay.relay.orb.OrbStage
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.restTone

/**
 * The way in for a phone with nothing to connect with. There is no "not
 * now": an app with no account and no server of its own has nothing to do,
 * and a Connect button that cannot work is worse than a question. Each
 * answer goes straight to the page that sets it up.
 */
@Composable
fun Welcome(
    paused: Boolean,
    onProvider: (String) -> Unit,
    onServer: () -> Unit,
) {
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
            OrbStage(Mode.OFF, 0f, paused = paused, size = 200.dp, toneOverride = restTone())
            Text("Welcome to Relay", style = Type.title.copy(fontSize = 26.sp))
            Spacer(Modifier.height(8.dp))
            Text("How will you connect? Relay needs one of these before it can carry " +
                "anything - more can be added later under Settings.",
                style = Type.body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))

            Choice(Glyph.key, "A VPN account",
                "Sign in, and Relay gets the provider's servers and pins them to real " +
                    "addresses for you.") {
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Between signing in and being able to connect: the provider's servers are
 * fetched and pinned, and this says how far along that is. Nothing to press
 * unless it fails.
 */
@Composable
fun SetupScreen(
    setup: Setup,
    run: PinRun,
    paused: Boolean,
    onRetry: () -> Unit,
    onOtherAccount: () -> Unit,
) {
    val provider = providerName(setup.provider.ifEmpty { Accounts.SURFSHARK })
    Backdrop {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            OrbStage(if (setup.step == "failed") Mode.FAIL else Mode.BUSY, 0f, paused = paused,
                size = 220.dp, toneOverride = if (setup.step == "failed") null else restTone())
            Text(if (setup.step == "failed") "Setup did not finish" else "Getting your servers ready",
                style = Type.title.copy(fontSize = 24.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Once, on a new phone. After this Relay connects in a second.",
                style = Type.body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            val fetched = setup.step == "pinning" || setup.step == "done"
            Step("Signed in to $provider", done = true, active = false)
            Step("Getting $provider's server list", done = fetched,
                active = setup.step == "servers")
            Step("Pinning each server to its real address", done = setup.step == "done",
                active = setup.step == "pinning",
                detail = if (setup.step == "pinning" && run.total > 0)
                    "${run.done} of ${run.total} · ${run.reachable} answering" else null,
                progress = if (setup.step == "pinning" && run.total > 0) run.done.toFloat() / run.total else null)

            if (setup.step == "failed") {
                Spacer(Modifier.height(16.dp))
                SaidLine(setup.error, bad = true)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Try again", onRetry, strong = true, icon = Glyph.refresh)
                    Btn("Use another account", onOtherAccount)
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Text("This line's DNS forges answers, so each server is looked up over " +
                    "DNS-over-HTTPS and kept at the address that is really it.",
                    style = Type.small, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(title: String, done: Boolean, active: Boolean, detail: String? = null, progress: Float? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(when {
                    done -> Tone.ok
                    active -> Tone.ok.copy(alpha = 0.18f)
                    else -> Tone.control
                })
                .border(1.dp, if (active) Tone.ok else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Glyph.check, null, Modifier.size(13.dp), tint = Tone.ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong.copy(
                color = if (done || active) Tone.text else Tone.textOff,
                fontWeight = if (active) FontWeight.W700 else FontWeight.W600))
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = Type.mono)
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.control)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(Tone.ok))
                }
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
