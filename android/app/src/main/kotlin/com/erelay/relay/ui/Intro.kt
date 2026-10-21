package com.erelay.relay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Mode
import com.erelay.relay.orb.OrbStage
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.restTone
import kotlinx.coroutines.launch

private class Slide(val icon: ImageVector?, val title: String, val say: String)

private val SLIDES = listOf(
    Slide(null, "Relay",
        "A private way out from a line that forges DNS answers and filters VPNs - one tap " +
            "to connect, and it tells you exactly where your traffic comes out."),
    Slide(Glyph.bolt, "The first exit that answers",
        "Relay asks dozens of your provider's exits at once and connects through whichever " +
            "answers first. Pick a country, or let it race them all."),
    Slide(Glyph.pin, "Pinned to real addresses",
        "This line's resolver answers with forged addresses. Relay looks every server up " +
            "over DNS-over-HTTPS and keeps the address that is really it."),
    Slide(Glyph.tower, "Or your own server",
        "Have a server of your own behind Cloudflare? Relay speaks its tunnel directly - " +
            "quicker and steadier, with uploads that do not stall."),
    Slide(Glyph.sparkle, "One tap, anywhere",
        "Tap the orb, the Connect button, or the Relay tile in quick settings. The " +
            "notification says where you are connected and can disconnect."),
)

/**
 * What Relay is, in five slides, the first time it opens. Swipe or press
 * Next; Skip goes straight to setting up.
 */
@Composable
fun Intro(paused: Boolean, onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex

    Backdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1f))
                if (!last) Box(Modifier.clip(RoundedCornerShape(12.dp)).tap(onClick = onDone)
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Skip", style = Type.button.copy(color = Tone.textMuted))
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val s = SLIDES[page]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (s.icon == null) {
                        // The orb lives on the first slide only: one GL view at a time.
                        OrbStage(Mode.OFF, 0f, paused = paused || pager.currentPage != 0,
                            size = 240.dp, toneOverride = restTone())
                    } else {
                        Box(
                            Modifier.size(132.dp).clip(CircleShape).background(Tone.ok.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(88.dp).clip(CircleShape).background(Tone.ok.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center) {
                                Icon(s.icon, null, Modifier.size(40.dp), tint = Tone.ok)
                            }
                        }
                        Spacer(Modifier.height(36.dp))
                    }
                    Text(s.title, style = Type.title.copy(fontSize = 28.sp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(s.say, style = Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                        textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                repeat(SLIDES.size) { i ->
                    val on = i == pager.currentPage
                    val w by animateDpAsState(if (on) 22.dp else 7.dp, label = "dot")
                    val c by animateColorAsState(if (on) Tone.ok else Tone.textOff.copy(alpha = 0.5f), label = "dotColor")
                    Box(Modifier.padding(horizontal = 4.dp).height(7.dp).width(w).clip(CircleShape).background(c))
                }
            }
            Btn(if (last) "Get started" else "Next", {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp).height(54.dp),
                strong = true)
        }
    }
}
