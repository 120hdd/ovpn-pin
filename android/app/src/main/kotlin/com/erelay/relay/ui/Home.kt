package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Conn
import com.erelay.relay.data.Mode
import com.erelay.relay.data.Place
import com.erelay.relay.data.Traffic
import com.erelay.relay.data.Way
import com.erelay.relay.orb.GlobeBackdrop
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
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
    Backdrop {
        // While connected, the country's flag washes the whole page, deep and dim.
        FlagWash(connectedCode(s))
        // The hologram globe, half of it, on the right edge, behind everything.
        GlobeBackdrop(s.conn.mode, orbPaused, connectedCode(s), still = !s.feel.orbMotion)
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The spare height of a tall phone goes to the gap between the
            // status block and the controls, over the globe; what the rest
            // needs is measured, not guessed, so a large font only costs the
            // gap, and when even that is gone the screen scrolls.
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
                    Readout(s, onPick, onCopy)
                    Spacer(Modifier)
                    Footer(s, onWay, onAct)
                }
            }
        }
    }
}

// -- the connected country's backdrop ----------------------------------------------

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
 * The flag, cover-cropped, at a tenth of its strength, under a gradient that
 * dissolves it into the page (more toward the bottom and the text side) and
 * a dark vignette at the edges. In on Connected, out on disconnect or fail;
 * with animations off it just swaps.
 */
@Composable
private fun FlagWash(code: String) {
    val reduced = rememberReducedMotion()
    Crossfade(code, Modifier.fillMaxSize(),
        animationSpec = tween(if (reduced) 0 else Shape.LARGE_MS, easing = Shape.ease),
        label = "flagWash") { c ->
        val bitmap = if (c.isEmpty()) null else rememberFlagBitmap(c)
        if (bitmap != null) {
            Box(Modifier.fillMaxSize()) {
                Image(bitmap, null, Modifier.fillMaxSize().alpha(if (Tone.light) 0.20f else 0.30f),
                    contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Tone.bg.copy(alpha = 0.10f), 0.45f to Tone.bg.copy(alpha = 0.55f),
                    1f to Tone.bg.copy(alpha = 0.96f))))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    0f to Tone.bg.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().drawWithContent {
                    val r = kotlin.math.hypot(size.width, size.height) / 2f
                    drawRect(Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (Tone.light) 0.10f else 0.55f),
                        center = Offset(size.width / 2f, size.height / 2f), radius = r))
                })
            }
        }
    }
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
            if (s.traffic.live && s.traffic.downRate + s.traffic.upRate > 512) {
                Box(Modifier.align(Alignment.TopEnd).offset((-Space.x2), Space.x3).size(Space.x2)
                    .clip(CircleShape).background(Tone.ok))
            }
        }
        if (s.deadCount > 0) {
            Box {
                IconButton(Glyph.archive, "Exits that stopped answering", onDrop)
                Text(s.deadCount.toString(), style = Type.mono.copy(color = Tone.ink),
                    modifier = Modifier.align(Alignment.TopEnd).offset((-Space.x1), Space.x1)
                        .clip(CircleShape).background(Tone.fail).padding(horizontal = Space.x1))
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
 * Top-left: the status word in the state's colour, the place under it, the
 * address in mono, the timer, and under them the throughput line. Left-aligned,
 * every line a fixed height so nothing under it moves when the words change.
 */
@Composable
private fun Readout(s: HomeState, onPick: () -> Unit, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else Shape.LARGE_MS
    val word = wordOf(mode)
    val stateColor by animateColorAsState(if (mode == Mode.OFF) Tone.text2 else Tone.stateOf(mode),
        tween(ms, easing = Shape.ease), label = "word")

    Column(Modifier.fillMaxWidth().padding(top = Space.x6)) {
        AnimatedContent(word, transitionSpec = {
            fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS, easing = Shape.ease)) togetherWith
                fadeOut(tween(if (reduced) 0 else Shape.FADE_MS))
        }, label = "word") { w ->
            // One word: smaller rather than broken over two lines.
            androidx.compose.foundation.text.BasicText(w, style = Type.status.copy(color = stateColor),
                maxLines = 1, softWrap = false, autoSize = FitText(Type.status.fontSize, floor = 24.dp))
        }
        Spacer(Modifier.height(Space.x2))
        LocationLine(s, onPick)
        Spacer(Modifier.height(Space.x1))
        AddressLine(s, onCopy)
        Spacer(Modifier.height(Space.x1))
        Timer(s)
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
private fun LocationLine(s: HomeState, onPick: () -> Unit) {
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

    val shape = RoundedCornerShape(Shape.control)
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
        Text(place, style = Type.title.copy(color = if (dim) Tone.text2 else Tone.text),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (pickable) {
            Spacer(Modifier.width(Space.x2))
            Icon(Glyph.chevRight, "Change location", Modifier.size(16.dp), tint = Tone.text3)
        }
    }
}

/** What the world sees (or, before a connection, what it sees now), in mono. */
@Composable
private fun AddressLine(s: HomeState, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val line: String
    val copy: String?
    when (mode) {
        Mode.ON -> {
            val ip = when {
                s.seen != null -> s.seen.ip
                s.seenFailed -> "unconfirmed"
                else -> "checking…"
            }
            val via = s.conn.exitAddress.takeIf { it.isNotEmpty() }
                ?.let { if (s.way == Way.PROVIDER) "$it:443" else it }
            line = if (via != null) "$ip  ·  via $via" else ip
            copy = s.seen?.ip
        }
        Mode.BUSY -> {
            val stage = STAGES.entries.firstOrNull { s.conn.note.startsWith(it.key) }?.value
                ?: "Looking for a server"
            line = if (s.conn.total > 0) "$stage  ·  ${s.conn.asked} of ${s.conn.total}" else stage
            copy = null
        }
        else -> {
            val real = when {
                s.realIp != null -> s.realIp.ip
                s.realIpAsked -> "unknown"
                else -> "checking…"
            }
            line = "Your address  $real"
            copy = s.realIp?.ip
        }
    }
    Text(line, style = Type.mono, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .heightIn(min = 16.dp)
            .then(if (copy != null) Modifier.press(shape = RoundedCornerShape(4.dp)) { onCopy(copy) } else Modifier))
}

/** How long the tunnel has been up: h:mm:ss, ticking once a second. */
@Composable
private fun Timer(s: HomeState) {
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
        style = Type.monoBody.copy(color = Tone.text2), maxLines = 1,
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
private fun Footer(s: HomeState, onWay: (Way) -> Unit, onAct: () -> Unit) {
    val mode = s.conn.mode
    Column(Modifier.fillMaxWidth().padding(top = Space.x2, bottom = Space.x4)) {
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
 * The one primary action, full width, in the thumb zone. Filled with the
 * state colour while connecting or connected; outlined when off.
 */
@Composable
private fun GoButton(mode: Mode, enabled: Boolean, onClick: () -> Unit) {
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else Shape.LARGE_MS
    val shape = RoundedCornerShape(Shape.control)
    val filled = mode == Mode.BUSY || mode == Mode.ON
    val state = Tone.stateOf(mode)
    val fill by animateColorAsState(if (filled) state else state.copy(alpha = 0f),
        tween(ms, easing = Shape.ease), label = "goFill")
    val edge by animateColorAsState(if (filled) Color.Transparent else Tone.strokeStrong,
        tween(ms, easing = Shape.ease), label = "goEdge")
    val ink by animateColorAsState(if (filled) Tone.ink else Tone.text,
        tween(ms, easing = Shape.ease), label = "goInk")
    val word = when (mode) {
        Mode.BUSY -> "Cancel"
        Mode.ON -> "Disconnect"
        else -> "Connect"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .press(enabled, shape, sink = true,
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
