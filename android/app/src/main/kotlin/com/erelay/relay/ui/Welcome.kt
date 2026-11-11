package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.erelay.relay.Accounts
import com.erelay.relay.data.Mode
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.Setup
import com.erelay.relay.orb.OrbStage
import com.erelay.relay.ui.theme.Space
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
                // A tablet gets the phone's column, in the middle.
                .wrapContentWidth()
                .widthIn(max = CONTENT_MAX)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.x6),
        ) {
            // The orb is the one object: small, at the trailing edge, above the words.
            Row(Modifier.fillMaxWidth().padding(top = Space.x4), horizontalArrangement = Arrangement.End) {
                OrbStage(Mode.OFF, 0f, paused = paused, size = heroOrb(120.dp), toneOverride = restTone())
            }
            Text("Connect with an account or a server of your own.", style = Type.display)
            Spacer(Modifier.height(Space.x3))
            Text("Relay needs one of these before it can carry anything. More can be added later in Settings.",
                style = Type.body)

            Section("A VPN account",
                note = "Sign in, and Relay gets the provider's servers and pins them to real addresses.") {
                Group {
                    NavRow("Surfshark", "Sign in with the service login", { onProvider(Accounts.SURFSHARK) },
                        Glyph.surfshark)
                    Sep()
                    NavRow("Windscribe", "Sign in with your Windscribe login", { onProvider(Accounts.WINDSCRIBE) },
                        Glyph.windscribe)
                }
            }
            Section("Your own server") {
                Group {
                    NavRow("My own server", "A server of yours behind Cloudflare. Set its domain and password.",
                        onServer, Glyph.tower)
                }
            }
            Spacer(Modifier.height(Space.x6))
        }
    }
}

/**
 * Between signing in and being able to connect: the provider's servers are
 * fetched and pinned, and this says how far along that is. Nothing to press
 * unless it fails.
 */
@OptIn(ExperimentalLayoutApi::class)
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
                // A tablet gets the phone's column, in the middle.
                .wrapContentWidth()
                .widthIn(max = CONTENT_MAX)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.x6),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Space.x4), horizontalArrangement = Arrangement.End) {
                OrbStage(if (setup.step == "failed") Mode.FAIL else Mode.BUSY, 0f, paused = paused,
                    size = heroOrb(140.dp), toneOverride = if (setup.step == "failed") null else restTone())
            }
            Text(if (setup.step == "failed") "Setup did not finish." else "Getting your servers ready.",
                style = Type.display)
            Spacer(Modifier.height(Space.x3))
            Text("Once, on a new phone. After this Relay connects in a second.", style = Type.body)
            Spacer(Modifier.height(Space.x6))

            val fetched = setup.step == "pinning" || setup.step == "done"
            Group {
                Step("Signed in to $provider", done = true, active = false)
                Sep()
                Step("Getting $provider's server list", done = fetched, active = setup.step == "servers")
                Sep()
                Step("Pinning each server to its real address", done = setup.step == "done",
                    active = setup.step == "pinning",
                    detail = if (setup.step == "pinning" && run.total > 0)
                        "${run.done} of ${run.total} · ${run.reachable} answering" else null,
                    progress = if (setup.step == "pinning" && run.total > 0) run.done.toFloat() / run.total else null)
            }

            if (setup.step == "failed") {
                SaidLine(setup.error, bad = true)
                Spacer(Modifier.height(Space.x4))
                // The second goes under the first when they do not fit side by side.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2),
                    verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                    Btn("Try again", onRetry, strong = true, icon = Glyph.refresh)
                    Btn("Use another account", onOtherAccount)
                }
            } else {
                Text("This line's DNS forges answers, so each server is looked up over " +
                    "DNS-over-HTTPS and kept at the address that is really it.",
                    style = Type.caption, modifier = Modifier.padding(top = Space.x4, start = Space.x1))
            }
            Spacer(Modifier.height(Space.x6))
        }
    }
}

/**
 * The orb over a page of words: [full] on a phone with the height for it,
 * down to 96 on a short one - a little less again when a large font makes
 * the words under it taller - so the statement and its first answer still
 * show without a scroll.
 */
@Composable
private fun heroOrb(full: Dp): Dp {
    val tall = windowDp().height
    val font = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    return (tall * (0.18f / font)).coerceIn(96.dp, full)
}

/** One step of the setup: its name, where it stands in words, and a bar while it runs. */
@Composable
private fun Step(title: String, done: Boolean, active: Boolean, detail: String? = null, progress: Float? = null) {
    // A status is a mark and a word: a small check in Good when done, never a glow.
    SettingRow(title, desc = detail) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (done) {
                Icon(Glyph.check, null, Modifier.size(14.dp), tint = Tone.good)
                Spacer(Modifier.width(Space.x1))
            }
            Text(when {
                done -> "Done"
                active -> "Working"
                else -> "Waiting"
            }, style = Type.caption.copy(color = when {
                done -> Tone.good
                active -> Tone.work
                else -> Tone.text3
            }))
        }
    }
    if (progress != null) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.x5).padding(bottom = Space.x3).height(4.dp)
            .clip(CircleShape).background(Tone.surface2)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Tone.text2))
        }
    }
}
