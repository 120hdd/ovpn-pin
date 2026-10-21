package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Conn
import com.erelay.relay.data.Mode
import com.erelay.relay.data.Place
import com.erelay.relay.data.Traffic
import com.erelay.relay.data.Way
import com.erelay.relay.orb.OrbStage
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.toneOf

/** Everything the main screen draws, gathered by [Root] from the core's flows. */
data class HomeState(
    val conn: Conn,
    val realIp: Place?,
    val realIpAsked: Boolean,
    val seen: Place?,
    val seenFailed: Boolean,
    val traffic: Traffic,
    val way: Way,
    val tunnelReady: Boolean,
    val picked: String,
    val pickLabel: String,
    val countries: Int,
    val serverCount: Int,
    val loaded: Boolean,
    val hasCredentials: Boolean,
    val deadCount: Int,
    val hint: String,
    val hintBad: Boolean,
    val feel: com.erelay.relay.data.Feel = com.erelay.relay.data.Feel(),
) {
    /** Whether the chosen way out has what it needs to start. */
    val canStart get() = if (way == Way.PROVIDER) hasCredentials && serverCount > 0 else tunnelReady
}

@Composable
fun Home(
    s: HomeState,
    orbPaused: Boolean,
    onLog: () -> Unit,
    onDrop: () -> Unit,
    onSettings: () -> Unit,
    onWay: (Way) -> Unit,
    onPick: () -> Unit,
    onAct: () -> Unit,
    onCopy: (String) -> Unit,
    onReconnect: () -> Unit = {},
) {
    Backdrop {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The desktop window is a fixed 400x740; a phone is taller. The
            // spare height goes to the hero, and the orb grows into it,
            // rather than to a band of empty background. Only a screen too
            // short for the smallest orb scrolls instead.
            val roomy = maxHeight >= 600.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 16.dp),
            ) {
                Header(s, onLog, onDrop, onSettings)
                Hero(s, orbPaused, onCopy, onAct, onReconnect, roomy, if (roomy) Modifier.weight(1f) else Modifier)
                Spacer(Modifier.height(12.dp))
                StatusCard(s, onWay, onPick, onAct)
                Text(
                    s.hint, textAlign = TextAlign.Center,
                    style = Type.small.copy(color = if (s.hintBad) Tone.fail else Tone.textOff),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(s: HomeState, onLog: () -> Unit, onDrop: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(6.dp))
        Text("RELAY", style = Type.strong.copy(fontWeight = FontWeight.W700, letterSpacing = 2.4.sp))
        Grow()
        Box {
            IconButton(Glyph.activity, "What is using the connection", onLog)
            if (s.traffic.live && s.traffic.downRate + s.traffic.upRate > 512) {
                Box(Modifier.align(Alignment.TopEnd).offset((-8).dp, 9.dp).size(6.dp)
                    .clip(CircleShape).background(Tone.ok))
            }
        }
        if (s.deadCount > 0) {
            Box {
                IconButton(Glyph.archive, "Exits that stopped answering", onDrop)
                Text(s.deadCount.toString(), style = Type.mono.copy(fontSize = 9.sp, color = Tone.ink),
                    modifier = Modifier.align(Alignment.TopEnd).offset((-2).dp, 4.dp)
                        .clip(CircleShape).background(Tone.fail).padding(horizontal = 4.dp))
            }
        }
        IconButton(Glyph.settings, "Settings", onSettings)
    }
}

private val ROUTE = mapOf(
    Mode.OFF to "READY TO RELAY",
    Mode.BUSY to "NEGOTIATING ROUTE",
    Mode.ON to "PRIVATE ROUTE ACTIVE",
    Mode.FAIL to "ROUTE UNAVAILABLE",
)

@Composable
private fun Hero(s: HomeState, orbPaused: Boolean, onCopy: (String) -> Unit, onAct: () -> Unit, onReconnect: () -> Unit, roomy: Boolean, modifier: Modifier) {
    val mode = s.conn.mode
    val accent by animateColorAsState(toneOf(mode).accent, tween(460), label = "routeDot")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().then(if (roomy) Modifier.weight(1f) else Modifier.height(236.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The label has a row of its own above the sphere rather than
            // sitting over the top of its stage, so however small the orb
            // gets - the meter takes its share of this height - the two never
            // touch. The orb is sized to what is left under it.
            //
            // Sized by scale, not by layout. When the meter slides in on
            // connect the space left changes every frame, and an orb laid out
            // at each new size snapped smaller - and made its GL surface
            // reallocate its buffers mid-animation, which was the stutter on
            // every connect and disconnect. The surface keeps the largest
            // size it has needed and the shrink is a smooth scale of it.
            val label = 34.dp
            val target = minOf(maxWidth * 0.86f, maxHeight - label).coerceIn(180.dp, 380.dp)
            var full by remember { mutableStateOf(target) }
            if (target > full) full = target
            // Slow and soft on purpose: the shrink is part of the moment the
            // tunnel comes up, not a layout catching up with itself.
            val shown by androidx.compose.animation.core.animateDpAsState(target,
                tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "orbSize")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.height(label), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(7.dp))
                    val tappable = s.feel.orbTap && (mode == Mode.BUSY || mode == Mode.ON || s.canStart)
                    Text(if (tappable && (mode == Mode.OFF || mode == Mode.FAIL)) "PRESS TO CONNECT"
                         else ROUTE.getValue(mode),
                        style = Type.eyebrow.copy(color = Tone.textMuted, fontWeight = FontWeight.W700))
                }
                val tappable = s.feel.orbTap && (mode == Mode.BUSY || mode == Mode.ON || s.canStart)
                Box(Modifier.size(shown), contentAlignment = Alignment.Center) {
                    OrbStage(mode, paceOf(s.traffic), orbPaused,
                        Modifier
                            .requiredSize(full)
                            .graphicsLayer {
                                val k = shown / full
                                scaleX = k
                                scaleY = k
                            },
                        size = full,
                        still = !s.feel.orbMotion, onTap = if (tappable) onAct else null,
                        onHold = if (tappable && mode == Mode.ON) onReconnect else null)
                }
            }
        }

        AnimatedVisibility(
            visible = mode == Mode.ON && s.traffic.live && s.feel.meter,
            // Paced with the orb, which gives up the room it takes.
            enter = fadeIn(tween(700, delayMillis = 150)) +
                expandVertically(tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
            exit = fadeOut(tween(250)) +
                shrinkVertically(tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        ) {
            Flux(s.traffic, Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
        }

        Swap(s, onCopy)
    }
}

@Composable
private fun Swap(s: HomeState, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val on = mode == Mode.ON
    val tone = toneOf(mode)

    val realIp = when {
        s.realIp != null -> s.realIp.ip
        s.realIpAsked -> "unknown"
        else -> "…"
    }
    val realPlace = when {
        s.realIp != null -> countryName(s.realIp.country).ifEmpty { "could not check" }
        s.realIpAsked -> "could not check"
        else -> "checking…"
    }

    val claimed = if (s.way == Way.PROVIDER) s.conn.exitName.take(2).lowercase() else ""
    val exitIp = when {
        !on -> "—"
        s.seen != null -> s.seen.ip
        s.seenFailed -> "unconfirmed"
        else -> "checking…"
    }
    val exitPlace = when {
        !on -> "not connected"
        s.way != Way.PROVIDER && s.seen == null -> "your server"
        s.seen != null -> countryName(s.seen.country)
        claimed.isNotEmpty() -> "${countryName(claimed)} (checking)"
        else -> "checking…"
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Side(
            cap = "YOUR ADDRESS", ip = realIp, place = realPlace, alignEnd = false,
            struck = on, dot = Tone.textOff, ipColor = if (on) Tone.textOff else Tone.text,
            modifier = Modifier.weight(1f), onCopy = { s.realIp?.let { onCopy(it.ip) } },
        )
        Icon(Glyph.wide, null, Modifier.padding(horizontal = 8.dp).width(30.dp).height(14.dp),
            tint = if (on) tone.accent else Tone.textOff)
        Side(
            cap = "SEEN AS", ip = exitIp, place = exitPlace, alignEnd = true,
            struck = false, dot = if (on) tone.accent else Tone.textOff,
            ipColor = if (on && s.seen != null) tone.accent else Tone.textMuted,
            modifier = Modifier.weight(1f), onCopy = { s.seen?.let { onCopy(it.ip) } },
            lit = on,
        )
    }
}

@Composable
private fun Side(
    cap: String, ip: String, place: String, alignEnd: Boolean, struck: Boolean,
    dot: Color, ipColor: Color, modifier: Modifier, onCopy: () -> Unit, lit: Boolean = false,
) {
    val border = if (lit) Tone.ok.copy(alpha = 0.35f) else Tone.border
    Column(
        modifier
            .press(shape = RoundedCornerShape(14.dp), sink = true, onClick = onCopy)
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.surface.copy(alpha = 0.6f))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!alignEnd) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
            }
            Text(cap, style = Type.eyebrow)
            if (alignEnd) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(4.dp).clip(CircleShape).background(dot))
            }
        }
        Spacer(Modifier.height(8.dp))
        // An address is no use with its end cut off, and a phone with a large
        // font setting did exactly that. It steps down in size until it fits.
        androidx.compose.foundation.text.BasicText(ip,
            style = Type.ip.copy(color = ipColor,
                textDecoration = if (struck) TextDecoration.LineThrough else null),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                minFontSize = 10.sp, maxFontSize = Type.ip.fontSize, stepSize = 0.5.sp))
        Spacer(Modifier.height(4.dp))
        Text(place, style = Type.small.copy(color = Tone.textMuted), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** What the status line says in each look, with the two smaller lines under it. */
private data class Status(val word: String, val where: String, val host: String)

private val STAGES = mapOf(
    "finding" to "Finding a way in",
    "racing" to "Looking for a server",
    "asked" to "Looking for a server",
    "checking" to "Checking the server answers",
    "opening" to "Opening the connection",
)

private fun statusOf(s: HomeState): Status {
    val c = s.conn
    return when (c.mode) {
        Mode.OFF -> Status("DISCONNECTED", "", "")
        Mode.BUSY -> Status(
            "CONNECTING",
            STAGES.entries.firstOrNull { c.note.startsWith(it.key) }?.value ?: "Looking for a server",
            if (c.total > 0) "${c.asked} of ${c.total} asked" else "")
        Mode.ON -> {
            val place = when {
                s.seen != null -> countryName(s.seen.country)
                s.way != Way.PROVIDER -> "Your server"
                else -> countryName(c.exitName.take(2))
            }
            Status("CONNECTED", place, c.exitAddress.let { if (it.isNotEmpty() && s.way == Way.PROVIDER) "$it:443" else it })
        }
        Mode.FAIL -> Status("NOT CONNECTED", "Could not connect", "")
    }
}

private val WAY_SAID = mapOf(
    Way.PROVIDER to "The provider's own proxy. Nothing to set up, and every country below is " +
        "available. Uploads are the catch — on this line they crawl or stall outright.",
    Way.SERVER to "Your own server, reached through a CDN. The quickest and steadiest of the " +
        "three. No country to choose: the server is where it is.",
    Way.SERVER_EXIT to "Your server, handing the last leg to a provider exit. Sites see that " +
        "country's address, and uploads keep your server's speed.",
)

private const val WAY_NEEDS_TUNNEL = "Set the domain and passwords under Settings → Your own tunnel first."

@Composable
private fun StatusCard(s: HomeState, onWay: (Way) -> Unit, onPick: () -> Unit, onAct: () -> Unit) {
    val mode = s.conn.mode
    val tone = toneOf(mode)
    val status = statusOf(s)
    var details by rememberSaveable { mutableStateOf(false) }
    val statusColor by animateColorAsState(when (mode) {
        Mode.ON -> Tone.ok
        Mode.BUSY -> Tone.work
        Mode.FAIL, Mode.OFF -> Tone.fail
    }, tween(300), label = "status")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Shape.card))
            .background(Tone.surface.copy(alpha = 0.78f))
            .border(1.dp, Tone.border, RoundedCornerShape(Shape.card))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("CONNECTION STATUS", style = Type.eyebrow)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(mode, statusColor)
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(status.word, transitionSpec = {
                        fadeIn(tween(Shape.ENTER_MS)) togetherWith fadeOut(tween(Shape.FADE_MS))
                    }, label = "word") { w ->
                        Text(w, style = Type.status.copy(color = statusColor))
                    }
                }
            }
            if (mode == Mode.ON) {
                IconButton(if (details) Glyph.chevDown else Glyph.chevRight, "Connection details",
                    { details = !details }, size = 34.dp)
            }
        }
        if (status.where.isNotEmpty() || status.host.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status.where, style = Type.small.copy(color = Tone.textMuted), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.host, style = Type.mono.copy(fontSize = 11.sp), maxLines = 1)
            }
        }
        AnimatedVisibility(mode == Mode.ON && details) {
            Column(Modifier.padding(top = 10.dp)) {
                DetailRow("In", if (s.way == Way.PROVIDER) "${s.conn.exitAddress}:443" else s.conn.exitAddress)
                DetailRow("Out", s.seen?.ip ?: "—")
                DetailRow("Via", s.conn.exitName.ifEmpty { "—" })
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segment(Way.entries, s.way, { it.label }, onWay, Modifier.weight(1f), enabled = mode != Mode.BUSY)
            InfoTip(WAY_SAID.getValue(s.way))
        }
        val ready = s.way == Way.PROVIDER || s.tunnelReady
        AnimatedVisibility(!ready) {
            Text(WAY_NEEDS_TUNNEL, style = Type.small.copy(color = Tone.fail),
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        }

        if (s.way != Way.SERVER) {
            Spacer(Modifier.height(12.dp))
            PickButton(s.picked, s.pickLabel, enabled = mode != Mode.BUSY, onPick)
        }
        Spacer(Modifier.height(12.dp))
        GoButton(mode, enabled = mode == Mode.BUSY || mode == Mode.ON || s.canStart, tone.accent, onAct)
    }
}

@Composable
private fun StatusDot(mode: Mode, color: Color) {
    val loop = rememberInfiniteTransition(label = "dot")
    val pulse = loop.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.size(8.dp).drawBehind {
            val a = if (mode == Mode.BUSY) pulse.value else 1f
            if (mode == Mode.ON) {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent)),
                    radius = size.minDimension * 1.4f)
            }
            drawCircle(color.copy(alpha = a))
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Type.small, modifier = Modifier.width(44.dp))
        Text(value, style = Type.mono.copy(color = Tone.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickButton(picked: String, label: String, enabled: Boolean, onPick: () -> Unit) {
    val flag = flagOf(countryOfPick(picked))
    Row(
        Modifier
            .fillMaxWidth()
            .press(enabled, RoundedCornerShape(14.dp), sink = true, onClick = onPick)
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.control.copy(alpha = 0.7f))
            .border(1.dp, Tone.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Tone.controlActive)
                .border(1.dp, Tone.border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (flag.isNotEmpty()) Text(flag, fontSize = 18.sp)
            else Icon(Glyph.globe, null, Modifier.size(16.dp), tint = Tone.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("EXIT LOCATION", style = Type.eyebrow)
            Spacer(Modifier.height(2.dp))
            Text(label, style = Type.strong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Glyph.chevRight, null, Modifier.size(16.dp), tint = Tone.textOff)
    }
}

/**
 * The big button. Connect is the jade gradient with dark text; Cancel
 * breathes amber while a route is being negotiated; Disconnect is the dark
 * jade with its two lights apart - the state it describes is the state it
 * looks like.
 */
@Composable
private fun GoButton(mode: Mode, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val loop = rememberInfiniteTransition(label = "go")
    val breathe = loop.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val shape = RoundedCornerShape(14.dp)
    val word = when (mode) {
        Mode.BUSY -> "Cancel"
        Mode.ON -> "Disconnect"
        else -> "Connect"
    }
    val dim = if (enabled) 1f else 0.45f
    val jade = listOf(Color(0xFF71F1BD), Tone.jade2, Color(0xFF3DD4EE)).map { it.copy(alpha = dim) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .press(enabled, RoundedCornerShape(14.dp), sink = true,
                feel = androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm, onClick = onClick)
            .drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
                when (mode) {
                    Mode.ON -> {
                        drawRoundRect(Tone.surface, cornerRadius = r)
                        drawRoundRect(Tone.ok.copy(alpha = 0.14f), cornerRadius = r)
                        drawRoundRect(Tone.ok.copy(alpha = 0.45f), cornerRadius = r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        val far = Offset(size.width - 44.dp.toPx(), size.height / 2)
                        val near = Offset(size.width - 76.dp.toPx(), size.height / 2)
                        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                            center = far, radius = 26.dp.toPx()), radius = 26.dp.toPx(), center = far)
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                            center = near, radius = 14.dp.toPx()), radius = 14.dp.toPx(), center = near)
                    }
                    Mode.BUSY -> {
                        drawRoundRect(Tone.surface, cornerRadius = r)
                        drawRoundRect(Tone.work.copy(alpha = 0.14f), cornerRadius = r)
                        drawRoundRect(Tone.work.copy(alpha = 0.5f), cornerRadius = r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        val at = Offset(size.width - 56.dp.toPx(), size.height / 2)
                        drawCircle(Brush.radialGradient(listOf(Tone.work.copy(alpha = breathe.value), Color.Transparent),
                            center = at, radius = 30.dp.toPx()), radius = 30.dp.toPx(), center = at)
                    }
                    else -> drawRoundRect(Brush.horizontalGradient(jade, startX = 0f, endX = size.width),
                        cornerRadius = r)
                }
            }
            .clip(shape)
            .padding(horizontal = 18.dp),
        contentAlignment = if (mode == Mode.OFF || mode == Mode.FAIL) Alignment.Center else Alignment.CenterStart,
    ) {
        AnimatedContent(word, transitionSpec = {
            fadeIn(tween(Shape.ENTER_MS)) togetherWith fadeOut(tween(Shape.FADE_MS))
        }, label = "goWord") { w ->
            Text(w, style = Type.button.copy(fontSize = 16.sp, color = when (mode) {
                Mode.ON -> Tone.ok
                Mode.BUSY -> Tone.work
                else -> Tone.ink
            }.copy(alpha = dim)))
        }
    }
}
