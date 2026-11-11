package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch

/** One page: a statement, and one plain sentence under it. */
private class Slide(val title: String, val say: String)

private val SLIDES = listOf(
    Slide("A way out when the line is filtered.",
        "Relay sends your traffic through your VPN provider's servers, or through a server of your own, " +
            "and shows where it comes out."),
    Slide("It connects through the first exit that answers.",
        "Relay asks many of your provider's exits at once and uses the first to reply. " +
            "Pick a country, or let it race them all."),
    Slide("Servers are pinned to real addresses.",
        "This line's resolver gives out forged addresses, so Relay looks each server up over " +
            "DNS-over-HTTPS and keeps the one that is really it."),
    Slide("Your own server works too.",
        "If you run a server behind Cloudflare, Relay talks to it directly. That is steadier, " +
            "and uploads do not stall."),
    Slide("Connect from three places.",
        "Tap the orb, press Connect, or use the Relay tile in quick settings. " +
            "The notification says where you are connected and can disconnect."),
)

/**
 * What Relay is, in five type-led pages, the first time it opens. Swipe or
 * press Next; Skip goes straight to setting up.
 */
@Composable
fun Intro(paused: Boolean, onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex
    val reduced = rememberReducedMotion()

    Backdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.x3),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (!last) Box(Modifier.heightIn(min = 48.dp)
                    .press(shape = RoundedCornerShape(Shape.pill), nudge = true, onClick = onDone)
                    .padding(horizontal = Space.x4, vertical = Space.x3),
                    contentAlignment = Alignment.Center) {
                    Text("Skip", style = Type.strong.copy(color = Tone.text2))
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val s = SLIDES[page]
                // What does not fit scrolls; the words sit low and to the leading
                // edge, with the mark above them on the first page only.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val pageHeight = maxHeight
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .heightIn(min = pageHeight).padding(horizontal = Space.x6),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        if (page == 0) {
                            // The mark draws itself in: the first thing a new install sees.
                            RelayMark(Modifier.size(72.dp), animate = !reduced && !paused)
                            Spacer(Modifier.height(Space.x8))
                        }
                        Text(s.title, style = Type.display)
                        Spacer(Modifier.height(Space.x4))
                        Text(s.say, style = Type.body.copy(color = Tone.text2))
                        Spacer(Modifier.height(Space.x6))
                    }
                }
            }
            // Where you are in the five: one bar each, the current one in the text colour.
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.x6, vertical = Space.x4),
                horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                repeat(SLIDES.size) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(Shape.pill))
                        .background(if (i == pager.currentPage) Tone.text else Tone.strokeStrong))
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.x6).padding(bottom = Space.x5)) {
                Btn("", {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }, Modifier.fillMaxWidth().heightIn(min = 56.dp), strong = true)
                AnimatedContent(last, Modifier.align(Alignment.Center), transitionSpec = {
                    fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS)) togetherWith fadeOut(tween(if (reduced) 0 else Shape.SINK_MS))
                }, label = "next") { end ->
                    Text(if (end) "Get started" else "Next", style = Type.button.copy(color = Tone.ink))
                }
            }
        }
    }
}
