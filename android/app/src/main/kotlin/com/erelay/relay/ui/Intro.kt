package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs

private class Slide(val icon: ImageVector?, val tint: Color, val title: String, val say: String)

private val SLIDES = listOf(
    Slide(null, Color(0xFF8C82FF), "Relay",
        "A private way out from a line that forges DNS answers and filters VPNs - one tap " +
            "to connect, and it tells you exactly where your traffic comes out."),
    Slide(Glyph.bolt, Color(0xFFFFC761), "The first exit that answers",
        "Relay asks dozens of your provider's exits at once and connects through whichever " +
            "answers first. Pick a country, or let it race them all."),
    Slide(Glyph.pin, Color(0xFF61EFBF), "Pinned to real addresses",
        "This line's resolver answers with forged addresses. Relay looks every server up " +
            "over DNS-over-HTTPS and keeps the address that is really it."),
    Slide(Glyph.tower, Color(0xFF3DD4EE), "Or your own server",
        "Have a server of your own behind Cloudflare? Relay speaks its tunnel directly - " +
            "quicker and steadier, with uploads that do not stall."),
    Slide(Glyph.sparkle, Color(0xFFFF8FB8), "One tap, anywhere",
        "Tap the orb, the Connect button, or the Relay tile in quick settings. The " +
            "notification says where you are connected and can disconnect."),
)

/** How far [page] is from the middle of the screen: 0 there, ±1 a page away. */
private fun PagerState.away(page: Int) = (currentPage - page) + currentPageOffsetFraction

/**
 * What Relay is, in five slides, the first time it opens. Swipe or press
 * Next; Skip goes straight to setting up.
 *
 * Each slide has a colour of its own that the light behind the page follows
 * as you swipe, and the art moves at a different pace from the words - the
 * parallax that makes a swipe feel like moving through something rather than
 * sliding a picture.
 */
@Composable
fun Intro(paused: Boolean, onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex
    val tint by animateColorAsState(SLIDES[pager.currentPage].tint, tween(600), label = "tint")

    Backdrop {
        // The slide's own light, drifting with it.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.36f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width / 2, size.height * 0.36f))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
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
                    Box(Modifier.graphicsLayer {
                        val a = pager.away(page)
                        // The art lags the page and shrinks as it leaves.
                        translationX = a * size.width * 0.45f
                        alpha = (1f - abs(a) * 0.9f).coerceIn(0f, 1f)
                        val k = 1f - (abs(a) * 0.25f).coerceAtMost(0.25f)
                        scaleX = k
                        scaleY = k
                    }) {
                        if (s.icon == null) {
                            // The mark draws itself in: the first thing a new install sees.
                            RelayMark(Modifier.size(200.dp), animate = true)
                        } else {
                            Badge(s.icon, s.tint, active = pager.currentPage == page && !paused)
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    Column(
                        Modifier.graphicsLayer {
                            val a = pager.away(page)
                            // The words lead, and fade faster: the eye reads the next slide first.
                            translationX = -a * size.width * 0.12f
                            alpha = (1f - abs(a) * 1.4f).coerceIn(0f, 1f)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.title, style = Type.title.copy(fontSize = 28.sp), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Text(s.say, style = Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                            textAlign = TextAlign.Center)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                repeat(SLIDES.size) { i ->
                    val on = i == pager.currentPage
                    val w by animateDpAsState(if (on) 26.dp else 7.dp, tween(380, easing = com.erelay.relay.ui.theme.Shape.swing), label = "dot")
                    val c by animateColorAsState(if (on) tint else Tone.textOff.copy(alpha = 0.45f), label = "dotColor")
                    Box(Modifier.padding(horizontal = 4.dp).height(7.dp).width(w).clip(CircleShape).background(c))
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
                Btn("", {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }, Modifier.fillMaxWidth().height(56.dp), strong = true)
                AnimatedContent(last, Modifier.align(Alignment.Center), transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                }, label = "next") { end ->
                    Text(if (end) "Get started" else "Next", style = Type.button.copy(color = Tone.ink, fontSize = 16.sp))
                }
            }
        }
    }
}

/**
 * A slide's icon: in a disc of its colour, inside a ring that slowly turns
 * and breathes - something alive on a page that is otherwise words.
 */
@Composable
private fun Badge(icon: ImageVector, tint: Color, active: Boolean) {
    val loop = rememberInfiniteTransition(label = "badge")
    val turn = loop.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "turn")
    val breathe = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val b = if (active) breathe.value else 0.5f
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.28f + 0.12f * b), Color.Transparent),
                radius = r), radius = r)
            rotate(if (active) turn.value else 0f) {
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, tint.copy(alpha = 0.9f), Color.Transparent)),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(r * 0.26f, r * 0.26f),
                    size = androidx.compose.ui.geometry.Size(r * 1.48f, r * 1.48f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            drawCircle(tint.copy(alpha = 0.10f + 0.06f * b), radius = r * 0.62f)
        }
        Box(Modifier.size(92.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(42.dp), tint = tint)
        }
    }
}
