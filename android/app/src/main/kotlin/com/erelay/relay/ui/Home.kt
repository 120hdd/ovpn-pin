package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Conn
import com.erelay.relay.data.Mode
import com.erelay.relay.data.Place
import com.erelay.relay.data.Traffic
import com.erelay.relay.data.Way
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import com.erelay.relay.world.WorldInput
import com.erelay.relay.world.WorldLayer
import kotlinx.coroutines.delay

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
    // Where the scene's own box and the free band between the status block and
    // the panel are on screen: the map puts its country in the middle of the
    // band, not in the middle of the screen.
    var scene by remember { mutableStateOf(Rect.Zero) }
    var band by remember { mutableStateOf(Rect.Zero) }
    val shift = if (scene.height > 0f && band.height > 0f)
        (Math.round(((band.top + band.bottom) / 2f - (scene.top + scene.bottom) / 2f) / scene.height * 500f) / 500f)
    else 0f

    val reduced = rememberReducedMotion()
    val light = Tone.light
    val bg = Tone.bg
    val live = connectedCode(s)
    val picked = if (s.way == Way.SERVER) "" else countryOfPick(s.picked).lowercase()
    val home = s.realIp?.country.orEmpty()
    val calm = reduced || !s.feel.orbMotion
    val world = remember(s.conn.mode, live, picked, home, light, calm, bg, shift) {
        WorldInput(s.conn.mode, live, picked, home, light, calm, floatArrayOf(bg.red, bg.green, bg.blue), shift)
    }

    Backdrop {
        // The map fills the screen, behind everything: it is where the country is now.
        WorldLayer(world, orbPaused, Modifier.fillMaxSize().onGloballyPositioned { scene = it.boundsInRoot() })
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The spare height of a tall phone goes to the gap between the
            // status block and the panel, over the map; what the rest needs is
            // measured, not guessed, so a large font only costs the gap, and
            // when even that is gone the screen scrolls.
            val screen = maxHeight
            val scroll = rememberScrollState()
            val gutter = if (maxWidth < 360.dp) Space.x4 else Space.x5
            // A tablet gets the phone's column, in the middle of the screen.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = CONTENT_MAX)
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                    .padding(horizontal = gutter),
            ) {
                FillColumn(screen, flex = 2, least = 0.dp) {
                    Header(s, onLog, onDrop, onSettings)
                    Readout(s, onPick)
                    Spacer(Modifier.onGloballyPositioned { band = it.boundsInRoot() })
                    Panel(s, onWay, onAct, onCopy)
                }
            }
        }
    }
}

// -- the connected country ----------------------------------------------------------

/** The country the tunnel comes out in, once there is one; empty when there is nothing to show. */
private fun connectedCode(s: HomeState): String {
    if (s.conn.mode != Mode.ON) return ""
    val measured = s.seen?.country.orEmpty()
    return when {
        measured.isNotEmpty() -> measured.lowercase()
        s.way == Way.PROVIDER -> s.conn.exitName.take(2).lowercase()
        s.way == Way.SERVER_EXIT -> countryOfPick(s.picked).lowercase()
        else -> ""
    }.takeIf { it.length == 2 } ?: ""
}

/**
 * The soft dark shadow type needs where it lies over the map (a light one on
 * the light theme): the map is busy, and a word with nothing under it would
 * lose to a coastline.
 */
@Composable
private fun typeShadow(): Shadow {
    val px = with(LocalDensity.current) { 12.dp.toPx() }
    return Shadow(if (Tone.light) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.75f),
        Offset(0f, px / 6f), px)
}

// -- header -----------------------------------------------------------------------------

@Composable
private fun Header(s: HomeState, onLog: () -> Unit, onDrop: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.x2), verticalAlignment = Alignment.CenterVertically) {
        RelayMark(Modifier.size(24.dp))
        Spacer(Modifier.width(Space.x2))
        Text("Relay", style = Type.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Box {
            IconButton(Glyph.activity, "What is using the connection", onLog)
            // Traffic is moving: a still dot. The pulsing one is the status word's alone.
            if (s.traffic.live && s.traffic.downRate + s.traffic.upRate > 512) {
                LiveDot(Modifier.align(Alignment.TopEnd).offset((-Space.x2), Space.x3), size = 6.dp, ring = false)
            }
        }
        if (s.deadCount > 0) {
            Box {
                IconButton(Glyph.archive, "Exits that stopped answering", onDrop)
                Text(s.deadCount.toString(), style = Type.mono.copy(color = Tone.ink, fontSize = 11.sp),
                    modifier = Modifier.align(Alignment.TopEnd).offset((-Space.x1), Space.x1)
                        .clip(RoundedCornerShape(Shape.pill)).background(Tone.fail).padding(horizontal = 5.dp))
            }
        }
        IconButton(Glyph.settings, "Settings", onSettings)
    }
}

// -- the readout ------------------------------------------------------------------------

private val STAGES = mapOf(
    "finding" to "Finding a way in",
    "racing" to "Looking for a server",
    "asked" to "Looking for a server",
    "checking" to "Checking the server answers",
    "opening" to "Opening the connection",
)

private fun wordOf(mode: Mode) = when (mode) {
    Mode.OFF -> "Disconnected"
    Mode.BUSY -> "Connecting"
    Mode.ON -> "Connected"
    Mode.FAIL -> "Not connected"
}

/**
 * Top-left: the status word in the state's colour, the place under it, what
 * the connection is doing in mono, the timer, and under them the throughput
 * line. Left-aligned, every line a fixed height so nothing under it moves when
 * the words change. It lies over the map, so its type carries a soft shadow.
 */
@Composable
private fun Readout(s: HomeState, onPick: () -> Unit) {
    val mode = s.conn.mode
    val shadow = typeShadow()
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else Shape.LARGE_MS
    val word = wordOf(mode)
    val stateColor by animateColorAsState(if (mode == Mode.OFF) Tone.text2 else Tone.stateOf(mode),
        tween(ms, easing = Shape.ease), label = "word")

    Column(Modifier.fillMaxWidth().padding(top = Space.x6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Connected: the live dot, Pulse with its halo ring breathing out
            // (DESIGN-NIGHT 4) - the one perpetual loop on the screen.
            AnimatedVisibility(mode == Mode.ON,
                enter = fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS)),
                exit = fadeOut(tween(if (reduced) 0 else Shape.FADE_MS))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(Modifier.padding(start = 4.dp))
                    Spacer(Modifier.width(14.dp))
                }
            }
            AnimatedContent(word, transitionSpec = {
                fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS, easing = Shape.ease)) togetherWith
                    fadeOut(tween(if (reduced) 0 else Shape.FADE_MS))
            }, label = "word") { w ->
                // One word: smaller rather than broken over two lines.
                androidx.compose.foundation.text.BasicText(w,
                    style = Type.status.copy(color = stateColor, fontSize = 30.sp, lineHeight = 34.sp, shadow = shadow),
                    maxLines = 1, softWrap = false, autoSize = FitText(30.sp, floor = 20.dp))
            }
        }
        Spacer(Modifier.height(Space.x2))
        LocationLine(s, onPick, shadow)
        Spacer(Modifier.height(Space.x1))
        AddressLine(s, shadow)
        Spacer(Modifier.height(Space.x1))
        Timer(s, shadow)
        // The last minute of traffic, under the status block (the slot is
        // always reserved, so the Connect button never moves when it appears).
        Box(Modifier.padding(top = Space.x2).height(if (shortScreen()) SPARK_SLOT_SHORT else SPARK_SLOT)
            .fillMaxWidth(0.72f), contentAlignment = Alignment.CenterStart) {
            if (s.feel.meter) Sparkline(s.traffic, shown = mode == Mode.ON, reduced = reduced)
        }
    }
}

/** The place, as one line: flag, name. It is also the way into the list of places. */
@Composable
private fun LocationLine(s: HomeState, onPick: () -> Unit, shadow: Shadow) {
    val mode = s.conn.mode
    val pickable = s.way != Way.SERVER && mode != Mode.BUSY
    val any = s.picked == "auto" && s.way != Way.SERVER
    val pickCode = countryOfPick(s.picked).lowercase()

    val connectedCode = connectedCode(s)
    val place: String
    val code: String
    when {
        s.way == Way.SERVER && mode != Mode.ON -> { place = "Your server"; code = "" }
        mode == Mode.ON && s.seen == null && s.way != Way.PROVIDER -> { place = "Your server"; code = "" }
        mode == Mode.ON && connectedCode.isNotEmpty() -> {
            // The exact place the pick named, when that is where traffic comes out.
            place = if (!any && pickCode == connectedCode) s.pickLabel else countryName(connectedCode)
            code = connectedCode
        }
        any -> { place = "Any country"; code = "" }
        else -> { place = s.pickLabel.ifEmpty { "Choose a location" }; code = pickCode }
    }
    val dim = any && mode != Mode.ON

    val shape = RoundedCornerShape(Shape.tip)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .press(pickable, shape, onClick = onPick)
            .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (code.isNotEmpty()) {
            Flag(code, 20.dp)
        } else {
            Icon(Glyph.globe, null, Modifier.size(20.dp), tint = Tone.text2)
        }
        Spacer(Modifier.width(Space.x3))
        Text(place, style = Type.title.copy(color = if (dim) Tone.text2 else Tone.text, shadow = shadow).script(place),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (pickable) {
            Spacer(Modifier.width(Space.x2))
            Icon(Glyph.chevRight, "Change location", Modifier.size(16.dp), tint = Tone.text3)
        }
    }
}

/**
 * What the connection is doing, in mono: while it works, how far the search
 * has got; once it is up, the exit it came out of. The two addresses
 * themselves are in the panel below.
 */
@Composable
private fun AddressLine(s: HomeState, shadow: Shadow) {
    val line = when (s.conn.mode) {
        Mode.ON -> s.conn.exitAddress.takeIf { it.isNotEmpty() }
            ?.let { "via " + if (s.way == Way.PROVIDER) "$it:443" else it }.orEmpty()
        Mode.BUSY -> {
            val stage = STAGES.entries.firstOrNull { s.conn.note.startsWith(it.key) }?.value
                ?: "Looking for a server"
            if (s.conn.total > 0) "$stage  ·  ${s.conn.asked} of ${s.conn.total}" else stage
        }
        else -> ""
    }
    Text(line.ifEmpty { " " }, style = Type.mono.copy(shadow = shadow), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.heightIn(min = 16.dp))
}

/** How long the tunnel has been up: h:mm:ss, ticking once a second. */
@Composable
private fun Timer(s: HomeState, shadow: Shadow) {
    val on = s.conn.mode == Mode.ON
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on, s.conn.since) {
        while (on) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val secs = if (on && s.conn.since > 0) (now / 1000 - s.conn.since).coerceAtLeast(0) else 0
    Text(
        if (on) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60) else " ",
        style = Type.monoBody.copy(color = Tone.text2, shadow = shadow), maxLines = 1,
        modifier = Modifier.heightIn(min = 20.dp),
    )
}

// -- layout ---------------------------------------------------------------------------------

/** The strip under the status block for the throughput line; reserved so nothing jumps when it appears. */
private val SPARK_SLOT = 52.dp
private val SPARK_SLOT_SHORT = 44.dp

/**
 * A column [height] tall, its [flex]th child given whatever the others leave
 * it, but never less than [least]. When the others leave less, the column is
 * taller than [height] and whatever holds it scrolls.
 */
@Composable
private fun FillColumn(height: Dp, flex: Int, least: Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placed = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != flex) placed[i] = m.measure(loose) }
        val rest = placed.sumOf { it?.height ?: 0 }
        val h = maxOf(least.roundToPx(), height.roundToPx() - rest)
        placed[flex] = measurables[flex].measure(loose.copy(minHeight = h, maxHeight = h))
        val width = constraints.maxWidth
        layout(width, rest + h) {
            var y = 0
            for (p in placed) {
                p!!.place((width - p.width) / 2, y)
                y += p.height
            }
        }
    }
}

// -- the panel ------------------------------------------------------------------------------

/**
 * The foot of the screen, one frosted panel (a floating layer, so glass): the
 * two addresses side by side - yours on the left, the one the world sees on the
 * right, each under its own flag - then the way out and the one action. The
 * fill is translucent over the map; the blur the desktop puts behind it is not
 * drawn, because Compose cannot blur what is behind a view, only what is in it.
 */
@Composable
private fun Panel(s: HomeState, onWay: (Way) -> Unit, onAct: () -> Unit, onCopy: (String) -> Unit) {
    val shape = RoundedCornerShape(Shape.sheet)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2, bottom = Space.x3)
            .floatShadow(shape)
            .glass(shape, solid = false)
            .padding(start = Space.x5, end = Space.x5, top = 18.dp, bottom = Space.x5),
    ) {
        Addresses(s, onCopy)
        Spacer(Modifier.height(Space.x4))
        Controls(s, onWay, onAct)
    }
}

/** Who you are, and who the world thinks you are: the whole product is the difference between them. */
@Composable
private fun Addresses(s: HomeState, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val homeCode = s.realIp?.country.orEmpty()
    val exitCode = connectedCode(s)
    val ownServer = mode == Mode.ON && exitCode.isEmpty() && s.way != Way.PROVIDER

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AddressColumn(
            label = "Your address", end = false,
            code = homeCode,
            place = when {
                homeCode.isNotEmpty() -> countryName(homeCode)
                s.realIpAsked -> "Unknown"
                else -> "Checking…"
            },
            ip = s.realIp?.ip ?: if (s.realIpAsked) "unknown" else "…",
            copy = s.realIp?.ip, onCopy = onCopy, modifier = Modifier.weight(1f))
        AddressColumn(
            label = "Seen as", end = true,
            code = exitCode,
            place = when {
                mode != Mode.ON -> "Not connected"
                exitCode.isNotEmpty() -> countryName(exitCode)
                ownServer -> "Your server"
                else -> "Checking…"
            },
            ip = when {
                mode != Mode.ON -> "—"
                s.seen != null -> s.seen.ip
                s.seenFailed -> "unconfirmed"
                else -> "checking…"
            },
            copy = if (mode == Mode.ON) s.seen?.ip else null, onCopy = onCopy, modifier = Modifier.weight(1f))
    }
}

/** A label in Dust, the flag and the country beside it, the address under in mono: right-aligned when [end]. */
@Composable
private fun AddressColumn(
    label: String, end: Boolean, code: String, place: String, ip: String,
    copy: String?, onCopy: (String) -> Unit, modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = Type.caption, maxLines = 1)
        Spacer(Modifier.height(Space.x1))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The flag sits on the outer side of the name: left for yours, right for the exit.
            if (!end && code.isNotEmpty()) FlagTile(code)
            Text(place, style = Type.strong.copy(fontSize = 17.sp, lineHeight = 22.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (end && code.isNotEmpty()) FlagTile(code)
        }
        Spacer(Modifier.height(2.dp))
        Text(ip, style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .heightIn(min = 16.dp)
                .then(if (copy != null) Modifier.press(shape = RoundedCornerShape(Shape.pill)) { onCopy(copy) } else Modifier))
    }
}

// -- the thumb zone -------------------------------------------------------------------------

private val WAY_SAID = mapOf(
    Way.PROVIDER to "The provider's own proxy. Nothing to set up, and every country below is " +
        "available. Uploads are the catch — on this line they crawl or stall outright.",
    Way.SERVER to "Your own server, reached through a CDN. The quickest and steadiest of the " +
        "three. No country to choose: the server is where it is.",
    Way.SERVER_EXIT to "Your server, handing the last leg to a provider exit. Sites see that " +
        "country's address, and uploads keep your server's speed.",
)

/** What each way is, in the few words its line in the menu has room for. */
private val WAY_NOTE = mapOf(
    Way.PROVIDER to "The provider's proxy, any country",
    Way.SERVER to "Your server, through a CDN",
    Way.SERVER_EXIT to "Your server, out by a provider exit",
)

private val WAY_ICON = mapOf(
    Way.PROVIDER to Glyph.globe,
    Way.SERVER to Glyph.tower,
    Way.SERVER_EXIT to Glyph.route,
)

private const val WAY_NEEDS_TUNNEL = "Set the domain and passwords under Settings → Your own tunnel first."

@Composable
private fun Controls(s: HomeState, onWay: (Way) -> Unit, onAct: () -> Unit) {
    val mode = s.conn.mode
    Column(Modifier.fillMaxWidth()) {
        if (s.hint.isNotEmpty()) {
            Text(s.hint, style = Type.caption.copy(color = if (s.hintBad) Tone.fail else Tone.text2),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.x3))
        }

        // Only the ways that can work: a provider needs an account and its
        // servers, your server needs the tunnel set up, and server + exit
        // needs both. With one way left there is nothing to choose, so the
        // strip is not drawn at all.
        val provider = s.hasCredentials && s.serverCount > 0
        val ways = Way.entries.filter {
            when (it) {
                Way.PROVIDER -> provider
                Way.SERVER -> s.tunnelReady
                Way.SERVER_EXIT -> s.tunnelReady && provider
            }
        }
        LaunchedEffect(ways) {
            if (ways.isNotEmpty() && s.way !in ways) onWay(ways.first())
        }
        if (ways.size >= 2) {
            Row(Modifier.padding(bottom = Space.x3), verticalAlignment = Alignment.CenterVertically) {
                Dropdown(ways, s.way, { it.label }, onWay, caption = "Way out",
                    modifier = Modifier.weight(1f), icon = { WAY_ICON.getValue(it) },
                    note = { WAY_NOTE.getValue(it) }, enabled = mode != Mode.BUSY)
                InfoTip(WAY_SAID.getValue(s.way))
            }
        }
        val ready = s.way == Way.PROVIDER || s.tunnelReady
        AnimatedVisibility(!ready) {
            Text(WAY_NEEDS_TUNNEL, style = Type.caption.copy(color = Tone.fail),
                modifier = Modifier.padding(bottom = Space.x3))
        }

        GoButton(mode, enabled = mode == Mode.BUSY || mode == Mode.ON || s.canStart, onAct)
    }
}

/**
 * The one primary action, full width, a pill: Mist White fill and Void type
 * when there is something to start; once there is something to stop, a ghost
 * pill with a 1dp Edge. A press moves it down 1dp; it never glows.
 */
@Composable
private fun GoButton(mode: Mode, enabled: Boolean, onClick: () -> Unit) {
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else Shape.LARGE_MS
    val shape = RoundedCornerShape(50)
    val outlined = mode == Mode.BUSY || mode == Mode.ON
    val fill by animateColorAsState(if (outlined) Tone.text.copy(alpha = 0f) else Tone.text,
        tween(ms, easing = Shape.ease), label = "goFill")
    val edge by animateColorAsState(if (outlined) Tone.strokeStrong else Color.Transparent,
        tween(ms, easing = Shape.ease), label = "goEdge")
    val ink by animateColorAsState(if (outlined) Tone.text else Tone.ink,
        tween(ms, easing = Shape.ease), label = "goInk")
    val word = when (mode) {
        Mode.BUSY -> "Cancel"
        Mode.ON -> "Disconnect"
        else -> "Connect"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .press(enabled, shape, sink = true, sinkTo = 0.985f, nudge = true,
                feel = androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm, onClick = onClick)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = Space.x4),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(word, transitionSpec = {
            fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS, easing = Shape.ease)) togetherWith
                fadeOut(tween(if (reduced) 0 else Shape.FADE_MS))
        }, label = "goWord") { w ->
            Text(w, style = Type.button.copy(color = ink), maxLines = 1)
        }
    }
}
