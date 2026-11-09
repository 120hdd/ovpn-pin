package com.erelay.relay.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erelay.relay.data.City
import com.erelay.relay.data.Country
import com.erelay.relay.data.ExportPick
import com.erelay.relay.data.ExportRun
import com.erelay.relay.data.ExportSlotLinks
import com.erelay.relay.data.ExportStep
import com.erelay.relay.data.Mode
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Said
import com.erelay.relay.data.TunnelPlan
import com.erelay.relay.data.Way
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay

/*
 * Settings → Share with other devices: links for the phones and laptops that
 * have no Relay of their own.
 *
 * The user's own server keeps a few numbered places to leave from, set aside
 * by its installer for exactly this. The first is the server itself and is
 * always there. Each of the others is pinned, from here, to a provider exit
 * in a location chosen on this screen - the exit there that uploads best
 * from the server, found by the same search server + exit makes before it
 * connects. What comes out is one ordinary V2Ray link per place, VLESS or
 * VMess over the same nginx and the same Cloudflare the apps use, which an
 * iPhone's Shadowrocket or Streisand imports from a QR code.
 *
 * The run itself lives in the core, not on this screen: one location takes
 * the better part of a minute, and closing Settings to wait is not a reason
 * for it to stop. The screen only reads where it has got to.
 */

private const val SHARE_HELP = "Links an iPhone, a laptop or a friend's phone can import - " +
    "Shadowrocket, Streisand, V2Box, Hiddify, sing-box, or any app that reads VLESS or VMess " +
    "links. Every one goes through your own server. Your server's own link leaves from the " +
    "server; each location you pick leaves from the provider exit there that uploads best from " +
    "your server, found now and kept on the server. They keep working with this phone switched " +
    "off. Make them again when one slows down: a location that keeps its place on the list keeps " +
    "its link."

private const val NO_SERVER = "These links go through your own server, and there is none set up " +
    "yet. Set one up under Connection first."

private const val OLD_SERVER = "Your server needs the installer from this version. Run it again " +
    "on the server - Connection, then Copy the install command - and paste the new key it prints."

private const val SECRET = "Each link carries your server's access: whoever has it can use your " +
    "server. Treat it like a password and send it only to devices you mean to."

/*
 * Server + exit leaves by the server's exit-chain, and the search that finds
 * each location's best exit moves that very chain from exit to exit before
 * putting it back. Said beforehand rather than discovered as a connection
 * that briefly came out somewhere else.
 */
private const val BORROWED = "You are connected through server + exit. While this runs, your own " +
    "connection leaves by each exit being tried, and goes back to where it was at the end."

/** The QR code's side: large enough for another phone's camera from arm's length. */
private val QR_SIDE = 248.dp

@Composable
fun ShareScreen(core: RelayCore, tunnel: TunnelPlan, onPlaces: () -> Unit) {
    val run by core.export.collectAsStateWithLifecycle()
    val picks by core.exportPicks.collectAsStateWithLifecycle()
    val conn by core.conn.collectAsStateWithLifecycle()
    val way by core.way.collectAsStateWithLifecycle()

    if (!run.ready) {
        Section("Configs for other devices", help = SHARE_HELP) {
            Block { Text(if (tunnel.ready) OLD_SERVER else NO_SERVER, style = Type.body) }
        }
        return
    }

    // The names the chooser shows, so a city reads "Frankfurt, Germany" here
    // too and not as its key. Until they are in, a country still has its
    // name from the phone's own tables.
    val countries by produceState(emptyList<Country>(), run.ready) { value = core.shareCountries() }
    val names = remember(countries) { countries.associate { it.code to it.name } }
    val cities = remember(countries) { countries.associate { it.code to it.cities } }
    var said by remember { mutableStateOf<Said?>(null) }
    val over = picks.size > run.slots

    Section("Configs for other devices", help = SHARE_HELP,
        note = "Your server, and each location you pick, becomes a link another phone imports - " +
            "from a QR code or a copied link.") {
        Group {
            PlacesRow(picks.size, run.slots, enabled = !run.running, onClick = onPlaces)
            Sep()
            ServerSlotRow(tunnel.domain)
            for ((i, code) in picks.withIndex()) key(code) {
                Sep()
                val name = nameOfPick(code, names, cities)
                PickRow(code, name, beyond = i >= run.slots, enabled = !run.running) {
                    core.setExportPick(code, false)
                    said = null
                }
            }
        }
        if (over) {
            SaidLine("Your server keeps ${run.slots} locations. Take ${picks.size - run.slots} off " +
                "to make the configs.", bad = true)
        }
        ButtonRow {
            if (run.running) {
                Btn(if (run.stopping) "Stopping…" else "Stop", { core.stopExport() },
                    modifier = Modifier.weight(1f), danger = true, enabled = !run.stopping)
            } else {
                Btn(if (run.last == null) "Make configs" else "Make them again", {
                    said = core.startExport(picks.map { ExportPick(it, nameOfPick(it, names, cities)) })
                        .takeIf { !it.ok }
                }, modifier = Modifier.weight(1f), strong = true, enabled = !over, icon = Glyph.qr)
            }
        }
        if (!run.running && picks.isNotEmpty() && !over) {
            Text("Up to half a minute or so for each location: a few of its exits are tried " +
                "through your server, and the one that uploads best is kept.", style = Type.caption,
                modifier = Modifier.padding(top = Space.x2))
        }
        if (way == Way.SERVER_EXIT && conn.mode == Mode.ON) SaidLine(BORROWED)
        said?.let { SaidLine(it.text, bad = !it.ok) }
        if (!run.running && run.error.isNotEmpty()) SaidLine(run.error, bad = true)
    }

    if (run.running) RunPane(run)

    val last = run.last
    if (last != null) {
        val good = last.slots.filter { it.ok }
        var copied by remember { mutableStateOf("") }
        Section("Your configs", note = if (good.size == 1) "1 ready" else "${good.size} ready") {
            Block {
                Text(SECRET, style = Type.caption.copy(color = Tone.work))
                Text("Made ${madeAgo(last.at)} for ${last.host}.", style = Type.caption,
                    modifier = Modifier.padding(top = Space.x2))
                for (n in last.notes) SaidLine(n, bad = true)
                WrapRow {
                    // Every place's VLESS link, one to a line: what most of these
                    // apps import in one go from the clipboard.
                    Btn("Copy all", {
                        core.copy(good.joinToString("\n") { it.vless }, sensitive = true)
                        copied = if (good.size == 1) "Copied 1 link." else "Copied ${good.size} links, one per line."
                    }, enabled = good.isNotEmpty(), icon = Glyph.copy)
                    // The same lines as a subscription's body - base64, as the
                    // apps that take a subscription expect to find it.
                    Btn("Copy subscription", {
                        val body = good.joinToString("\n") { it.vless }
                        core.copy(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                            sensitive = true)
                        copied = "Copied as a subscription. Paste it into the app's subscription import."
                    }, enabled = good.isNotEmpty(), icon = Glyph.copy)
                }
                SaidLine(copied)
            }
        }
        for (s in last.slots) key(s.k) { SlotCard(core, s) }
    }
}

/** The way into the chooser, with how many are on the list and how many fit. */
@Composable
private fun PlacesRow(chosen: Int, max: Int, enabled: Boolean, onClick: () -> Unit) {
    SettingRow("Locations", desc = "$chosen of $max chosen", onClick = onClick, enabled = enabled) { Chevron() }
}

/** The server's own place: first, always there, and nothing to take off. */
@Composable
private fun ServerSlotRow(host: String) {
    SettingRow("Your server", desc = host, icon = Glyph.tower) {
        Text("Always", style = Type.caption)
    }
}

/** One location on the list, and the x that takes it off. */
@Composable
private fun PickRow(code: String, name: String, beyond: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    SettingRow(name, desc = when {
        beyond -> "More than your server keeps"
        '/' in code -> "A city"
        else -> "A country"
    }, leading = { Flag(countryOfPick(code), 24.dp) }) {
        IconButton(Glyph.x, "Take $name off", onRemove, size = 40.dp, tint = Tone.text3, enabled = enabled)
    }
}

// -- a run as it goes ---------------------------------------------------------------

/** What the run is doing now, a bar, and one line per location. */
@Composable
private fun RunPane(run: ExportRun) {
    val done = run.steps.count { it.phase == "pinned" || it.phase == "failed" || it.phase == "skipped" }
    Section("Making configs", note = if (run.steps.isEmpty()) null else "$done of ${run.steps.size}") {
        Block {
            Text(phaseSaid(run), style = Type.body)
            Spacer(Modifier.height(Space.x2))
            // The server's own slot needs nothing found, so a run of none is
            // over as soon as the links are made: the bar shows the last stretch.
            RunBar(if (run.steps.isEmpty()) 0.5f else done.toFloat() / run.steps.size)
            for (s in run.steps) StepRow(s)
        }
    }
}

private fun phaseSaid(run: ExportRun): String = when {
    run.stopping && run.phase == "locations" -> "Stopping after this location…"
    run.phase == "locations" -> "Location ${run.index + 1} of ${run.steps.size}: trying its exits " +
        "through your server and keeping the one that uploads best."
    run.phase == "restoring" -> "Putting your own server + exit back where it was…"
    run.phase == "saving" -> "Saving the locations on the server, so they outlive a restart…"
    run.phase == "links" -> "Making the links…"
    else -> "Asking the server…"
}

@Composable
private fun RunBar(fraction: Float) {
    val shown by animateFloatAsState(fraction.coerceIn(0.03f, 1f), tween(400), label = "share-bar")
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.surface2)) {
        Box(Modifier.fillMaxWidth(shown).height(4.dp).background(Tone.text))
    }
}

@Composable
private fun StepRow(s: ExportStep) {
    Row(Modifier.fillMaxWidth().padding(top = Space.x3), verticalAlignment = Alignment.Top) {
        StepMark(s.phase)
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = Type.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stepSaid(s), style = Type.caption.copy(color = if (s.phase == "failed") Tone.fail else Tone.text2),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun stepSaid(s: ExportStep): String = when (s.phase) {
    "testing" -> "Testing upload through its exits…"
    "pinned" -> "Pinned to ${s.exit} · ${upSaid(s.upKBs)}"
    "failed" -> s.error
    "skipped" -> "Not tried: the run stopped before it."
    else -> "Waiting"
}

/** The measured upload, or the word the connect screen uses when there was next to none. */
private fun upSaid(kbs: Int) = if (kbs > 0) "$kbs KB/s up" else "upload throttled"

/** A dot per location: hollow while it waits, pulsing while tested, a check or a cross after. */
@Composable
private fun StepMark(phase: String) {
    val box = Modifier.padding(top = 1.dp).size(18.dp).clip(CircleShape)
    when (phase) {
        "pinned" -> Box(box.background(Tone.ok), contentAlignment = Alignment.Center) {
            Icon(Glyph.check, null, Modifier.size(11.dp), tint = Tone.ink)
        }
        "failed" -> Box(box.background(Tone.fail.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(Glyph.x, null, Modifier.size(11.dp), tint = Tone.fail)
        }
        "testing" -> {
            // The pulse only exists while this one is tested, as the bolt's does in the picker.
            val loop = rememberInfiniteTransition(label = "step")
            val a = loop.animateFloat(1f, 0.3f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "a")
            Box(box.graphicsLayer { alpha = a.value }.background(Tone.work.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Tone.work))
            }
        }
        else -> Box(box.border(1.5.dp, Tone.strokeStrong, CircleShape))
    }
}

// -- the links ------------------------------------------------------------------------

private fun kindLabel(kind: String) = when (kind) {
    "vmess" -> "VMess"
    "ss" -> "Shadowsocks"
    else -> "VLESS"
}

/**
 * One place's links: where it leaves from, the format, the code another
 * phone's camera reads, and the link itself to copy or hand to an app.
 *
 * VLESS first, because it is what Shadowrocket, Streisand and V2Box all read;
 * VMess for an app that only knows that. The server's own place offers
 * Shadowsocks too when its key had it - straight to the server, no
 * Cloudflare, and the format the oldest apps know.
 */
@Composable
private fun SlotCard(core: RelayCore, s: ExportSlotLinks) {
    val context = LocalContext.current
    val kinds = remember(s) {
        listOf("vless" to s.vless, "vmess" to s.vmess, "ss" to s.ss).filter { it.second.isNotEmpty() }.map { it.first }
    }
    var kind by rememberSaveable(s.k) { mutableStateOf("vless") }
    val chosen = if (kind in kinds) kind else kinds.firstOrNull() ?: "vless"
    val link = when (chosen) {
        "vmess" -> s.vmess
        "ss" -> s.ss
        else -> s.vless
    }
    var said by remember(s.k) { mutableStateOf("") }

    Block(Modifier.padding(top = Space.x4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.k == 0) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Tone.surface2),
                    contentAlignment = Alignment.Center) {
                    Icon(Glyph.tower, null, Modifier.size(20.dp), tint = Tone.text2)
                }
            } else {
                Flag(countryOfPick(s.code), 36.dp)
            }
            Spacer(Modifier.width(Space.x3))
            Column(Modifier.weight(1f)) {
                Text(if (s.k == 0) "Your server" else s.place, style = Type.strong, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(slotSaid(s), style = Type.caption.copy(color = if (s.ok) Tone.text2 else Tone.fail),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.x1))
            }
        }
        if (s.ok) {
            Spacer(Modifier.height(Space.x3))
            if (kinds.size > 1) {
                Segment(kinds, chosen, ::kindLabel, { kind = it; said = "" })
                Spacer(Modifier.height(Space.x3))
            }
            QrCode(core, link)
            Text(link, style = Type.mono.copy(color = Tone.text3), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.x3))
            WrapRow {
                Btn("Copy", {
                    core.copy(link, sensitive = true)
                    said = "Copied the ${kindLabel(chosen)} link."
                }, icon = Glyph.copy)
                Btn("Share", { shareLink(context, link, s.name) }, icon = Glyph.share)
            }
            SaidLine(said)
        }
    }
}

private fun slotSaid(s: ExportSlotLinks): String = when {
    !s.ok -> s.error
    s.k == 0 -> "Leaves from ${s.exit} itself"
    else -> listOfNotNull(providerName(s.provider).takeIf { s.provider.isNotEmpty() }, s.exit.ifEmpty { null },
        upSaid(s.upKBs)).joinToString(" · ")
}

private fun madeAgo(at: Long): String {
    val m = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        m < 60 * 48 -> "yesterday"
        else -> "${m / (60 * 24)} days ago"
    }
}

/** Buttons that go onto a second line rather than off the edge of a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Space.x3),
        horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalArrangement = Arrangement.spacedBy(Space.x2),
    ) { content() }
}

/** Android's share sheet, with the link as text: a messenger, notes, AirDrop's cousins. */
private fun shareLink(context: Context, link: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, link)
    val chooser = Intent.createChooser(send, title)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app here takes a link to share.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * QR codes, drawn once per link and kept. The core draws them - the desktop's
 * page draws its own with a script; here a Go library already in the build
 * does it - and a few hundred KB of pixels each is worth keeping while the
 * screen is scrolled up and down.
 */
private object QrCodes {
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(text: String): ImageBitmap? = cache.get(text)

    fun put(text: String, image: ImageBitmap) {
        cache.put(text, image)
    }
}

/**
 * The link as a QR code, black on white whatever the theme - a camera reads
 * contrast, and a dark code on a dark page reads as nothing. The core leaves
 * the four-module quiet zone around it, and the white tile adds a little
 * more. Drawn pixel for pixel, never smoothed: a blurred edge between two
 * modules is where a scanner gives up.
 */
@Composable
private fun QrCode(core: RelayCore, text: String) {
    val px = with(LocalDensity.current) { QR_SIDE.roundToPx() }
    val image by produceState(QrCodes.peek(text), text, px) {
        // The last link's code is not left up while this one is drawn: a
        // second code on screen that opens something else is worse than none.
        val cached = QrCodes.peek(text)
        value = cached
        if (cached == null) value = core.qrBitmap(text, px)?.asImageBitmap()?.also { QrCodes.put(text, it) }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = QR_SIDE + 12.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Shape.control))
                .background(Color.White)
                .padding(Space.x2),
            contentAlignment = Alignment.Center,
        ) {
            val got = image
            if (got != null) {
                Image(bitmap = got, contentDescription = "QR code of this link", modifier = Modifier.fillMaxSize(),
                    filterQuality = FilterQuality.None)
            } else {
                Text("Drawing the code…", style = Type.caption.copy(color = Color(0xFF5A5D66)))
            }
        }
    }
}

// -- the chooser --------------------------------------------------------------------

private fun countryMatches(c: Country, q: String) =
    c.name.lowercase().contains(q) || c.code.lowercase() == q || c.alias.any { it.lowercase().contains(q) }

private fun cityMatches(city: City, q: String) =
    city.name.lowercase().contains(q) || city.nick.lowercase().contains(q)

/**
 * The locations to share, picked several at a time: countries, and inside
 * them their cities, laid out as the exit list lays them out. A sheet of its
 * own over Settings, as the split tunnel's apps are, because a list of every
 * country does not fit inside a pane.
 *
 * The server keeps a fixed number. Past it a tick is refused, with a buzz
 * that says no and a line that says why, rather than the oldest pick being
 * dropped somewhere out of sight.
 */
@Composable
fun SharePlacesSheet(open: Boolean, core: RelayCore, onClose: () -> Unit) {
    val picks by core.exportPicks.collectAsStateWithLifecycle()
    val run by core.export.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val countries by produceState<List<Country>?>(null, open) { if (open) value = core.shareCountries() }
    LaunchedEffect(open) { if (!open) query = "" }
    // Bumped by a tick refused for want of room; the line says so for a few
    // seconds and then goes back to the count.
    var full by remember { mutableStateOf(0) }
    LaunchedEffect(full) {
        if (full > 0) {
            delay(2600)
            full = 0
        }
    }
    val max = run.slots

    Sheet(open, "Locations", onClose, scroll = false) {
        val q = query.trim().lowercase()
        val all = countries
        // Laid out once - on opening, on a new search - and not again as
        // boxes are ticked, so a row never moves out from under a finger.
        // What holds a pick comes first, then the rest by name.
        val order = remember(all, q, open) {
            all.orEmpty()
                .filter { c -> q.isEmpty() || countryMatches(c, q) || c.cities.any { cityMatches(it, q) } }
                .sortedWith(compareBy<Country>({ c -> !(c.code in picks || c.cities.any { it.code in picks }) },
                    { it.name }))
        }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val haptics = rememberHaptics()
        val flip: (String) -> Unit = { code ->
            val on = code !in picks
            when {
                run.running -> Unit
                on && picks.size >= max -> {
                    haptics.refuse()
                    full++
                }
                else -> {
                    haptics.toggle(on)
                    core.setExportPick(code, on)
                }
            }
        }

        Column(Modifier.padding(horizontal = Space.x4)) {
            Search(query, "Search a country or city") { query = it }
            Text(when {
                run.running -> "Configs are being made with the list as it was. Changes here wait for the next run."
                full > 0 -> "Your server keeps $max locations. Take one off to add another."
                else -> "${picks.size} of $max chosen. Each becomes a config of its own, and your server " +
                    "itself is always one more."
            }, style = Type.caption.copy(color = if (full > 0) Tone.work else Tone.text2),
                modifier = Modifier.padding(top = Space.x3, start = Space.x1, end = Space.x1))
        }
        when {
            all == null -> Text("Reading the locations on this phone…", style = Type.body,
                modifier = Modifier.fillMaxWidth().padding(Space.x6))
            all.isEmpty() -> Text("There are no locations on this phone yet. Get servers under " +
                "Settings → Accounts.", style = Type.body, modifier = Modifier.fillMaxWidth().padding(Space.x6))
            order.isEmpty() -> Text("Nothing matches “$query”.", style = Type.body,
                modifier = Modifier.fillMaxWidth().padding(Space.x6))
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = Space.x2)) {
                items(order, key = { it.code }, contentType = { "country" }) { c ->
                    // Found by one of its cities rather than by its own name:
                    // opened, at just the cities that matched.
                    val byCity = q.isNotEmpty() && !countryMatches(c, q)
                    PlaceCountry(c, picks, open = byCity || expanded[c.code] == true,
                        cityQuery = if (byCity) q else "",
                        onExpand = { expanded[c.code] = expanded[c.code] != true }, onFlip = flip)
                }
                item { Spacer(Modifier.height(Space.x6)) }
            }
        }
    }
}

/**
 * One country: its flag, its name, how much is in it, the box, and - when it
 * has more than one city - the chevron that lays them out. The whole row is
 * the box's target, so a thumb on a small phone does not have to find it.
 * A country and a city inside it can both be ticked: they are two places to
 * leave from, the whole country's best and that one city's.
 */
@Composable
private fun PlaceCountry(
    c: Country, picks: List<String>, open: Boolean, cityQuery: String,
    onExpand: () -> Unit, onFlip: (String) -> Unit,
) {
    val on = c.code in picks
    val inside = c.cities.count { it.code in picks }
    val many = c.cities.size > 1
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.ListItem(
            // The caller answers the touch, with the switch's tick or the refusal.
            modifier = Modifier.toggleable(on, role = Role.Checkbox) { onFlip(c.code) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Flag(c.code, 24.dp) },
            headlineContent = { Text(c.name, style = Type.strong, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(countryLine(c, inside), style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(on, null, colors = checkColors())
                    if (many) PlaceChevron(open) {
                        haptics.tick()
                        onExpand()
                    }
                }
            },
        )
        AnimatedVisibility(open && many) {
            Column(Modifier.fillMaxWidth().padding(start = Space.x10)) {
                val shown = if (cityQuery.isEmpty()) c.cities else c.cities.filter { cityMatches(it, cityQuery) }
                shown.forEach { city ->
                    Sep()
                    key(city.code) { PlaceCity(city, city.code in picks) { onFlip(city.code) } }
                }
            }
        }
        Sep()
    }
}

private fun countryLine(c: Country, inside: Int): String = listOfNotNull(
    if (c.cities.size > 1) "${c.cities.size} cities" else c.cities.firstOrNull()?.name?.takeIf { it.isNotEmpty() },
    "${c.count} relay${if (c.count == 1) "" else "s"}",
    if (inside == 0) null else if (inside == 1) "1 city chosen" else "$inside cities chosen",
).joinToString(" · ")

@Composable
private fun PlaceCity(city: City, on: Boolean, onFlip: () -> Unit) {
    androidx.compose.material3.ListItem(
        modifier = Modifier.toggleable(on, role = Role.Checkbox) { onFlip() },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(city.name.ifEmpty { city.code }, style = Type.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(listOfNotNull(city.nick.ifEmpty { null },
                "${city.count} relay${if (city.count == 1) "" else "s"}").joinToString(" · "),
                style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { androidx.compose.material3.Checkbox(on, null, colors = checkColors()) },
    )
}

/** Open or close a country's cities: the chevron turns over on the picker's spring. */
@Composable
private fun PlaceChevron(open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "place-chev")
    Box(Modifier.graphicsLayer { rotationZ = turn }) {
        IconButton(Glyph.chevDown, if (open) "Hide cities" else "Show cities", onClick, size = 40.dp, feel = null)
    }
}
