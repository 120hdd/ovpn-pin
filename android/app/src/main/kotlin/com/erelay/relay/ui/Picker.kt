package com.erelay.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.City
import com.erelay.relay.data.Country
import com.erelay.relay.data.Exit
import com.erelay.relay.data.ReachRun
import com.erelay.relay.data.Sort
import com.erelay.relay.data.Source
import com.erelay.relay.ui.theme.Mono
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.launch

/** What the picker needs from the core, as plain values and callbacks. */
class PickerModel(
    val countries: List<Country>,
    val favourites: Set<String>,
    val sort: Sort,
    val picked: String,
    val reach: ReachRun,
    val sources: List<Source>,
    val source: String,
    val exitsIn: suspend (String) -> List<Exit>,
    val onSort: (Sort) -> Unit,
    val onStar: (String) -> Unit,
    val onTest: (String) -> Unit,
    val onCancelTest: () -> Unit,
    val onChoose: (String) -> Unit,
    val onSources: () -> Unit,
    /** Time one exit per country - the button at the top. */
    val onTestAll: () -> Unit = {},
    /** The picker opened: the moment a stale list gets a fresh quick test. */
    val onOpen: () -> Unit = {},
)

/**
 * A match score for the search box, standing in for fuse.js: the desktop
 * searches name, code and alias with a forgiving threshold. A prefix beats
 * a substring beats letters in order; nothing else matches.
 */
private fun score(c: Country, q: String): Int? {
    if (q.isEmpty()) return 0
    val hay = listOf(c.name.lowercase(), c.code.lowercase()) + c.alias.map { it.lowercase() }
    var best: Int? = null
    for (h in hay) {
        val s = when {
            h == q -> 0
            h.startsWith(q) -> 1
            h.split(' ', '-').any { it.startsWith(q) } -> 2
            h.contains(q) -> 3
            q.length >= 3 && inOrder(h, q) -> 5
            else -> null
        }
        if (s != null && (best == null || s < best)) best = s
    }
    return best
}

private fun inOrder(h: String, q: String): Boolean {
    var i = 0
    for (ch in h) if (i < q.length && ch == q[i]) i++
    return i == q.length
}

/**
 * The desktop's order: starred first, then the ones that are not known dead,
 * then by the chosen measure.
 */
private fun ordered(list: List<Country>, sort: Sort, favourites: Set<String>): List<Country> =
    list.sortedWith(
        compareBy<Country>({ it.code !in favourites }, { it.blocked })
            .then(when (sort) {
                Sort.PING -> compareBy<Country>({ it.ping == null }, { it.ping ?: 0 }, { it.name })
                Sort.LOAD -> compareBy<Country>({ it.lowestLoad == null }, { it.lowestLoad ?: 0 }, { it.name })
                Sort.NAME -> compareBy { it.name }
            }))

@Composable
fun Picker(open: Boolean, m: PickerModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    // Bumped by tapping the sort that is already chosen: the way to put
    // times that came in since the list was laid out into their places.
    var resort by remember { mutableStateOf(0) }
    LaunchedEffect(open) {
        if (!open) query = "" else m.onOpen()
    }

    Sheet(open, "Relay network", "Choose an exit", onClose, scroll = false) {
        val q = query.trim().lowercase()
        // The order is laid out once - on opening, on a new sort or search -
        // and not again when results arrive, so a row never moves out from
        // under a finger. The rows themselves still show the newest numbers.
        val order = remember(open, m.sort, m.favourites, q, resort, m.countries.size) {
            val hits = m.countries.mapNotNull { c -> score(c, q)?.let { c to it } }
            val laid = if (q.isEmpty()) ordered(hits.map { it.first }, m.sort, m.favourites)
                else hits.sortedBy { it.second }.map { it.first }
            laid.map { it.code to (it.tested > 0) }
        }
        val byCode = remember(m.countries) { m.countries.associateBy { it.code } }
        val shown = order.mapNotNull { (code, _) -> byCode[code] }
        // Ping order is only honest about the countries that were timed; the
        // rest go below, under their own heading, in alphabetical order.
        val split = m.sort == Sort.PING && q.isEmpty()
        val timed = if (split) order.filter { it.second }.mapNotNull { byCode[it.first] } else shown
        val untimed = if (split) order.filterNot { it.second }.mapNotNull { byCode[it.first] }
            .sortedWith(compareBy<Country>({ it.code !in m.favourites }, { it.name })) else emptyList()
        val stale = split && untimed.any { it.ping != null }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val list = rememberLazyListState()
        val quick = m.reach.code == "quick"

        Column(Modifier.padding(horizontal = 16.dp)) {
            Search(query) { query = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SortChip(Glyph.gauge, "Ping", m.sort == Sort.PING) {
                    if (m.sort == Sort.PING) resort++ else m.onSort(Sort.PING)
                }
                SortChip(Glyph.layers, "Load", m.sort == Sort.LOAD) { m.onSort(Sort.LOAD) }
                SortChip(Glyph.az, "A–Z", m.sort == Sort.NAME) { m.onSort(Sort.NAME) }
                Grow()
                SourceChip(m.sources.firstOrNull { it.key == m.source }?.name ?: "Everything", m.onSources)
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when {
                        quick && m.reach.running -> "Timing ${m.reach.done} of ${m.reach.total} countries…"
                        stale -> "New times are in. Tap Ping to put them in order."
                        else -> sortNote(m)
                    }, style = Type.small, maxLines = 2, modifier = Modifier.padding(start = 2.dp))
                    if (quick && m.reach.running) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Tone.control)) {
                            Box(Modifier.fillMaxWidth(if (m.reach.total > 0)
                                (m.reach.done.toFloat() / m.reach.total).coerceIn(0.03f, 1f) else 0.03f)
                                .height(3.dp).background(Tone.ok))
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                TestAllChip(running = quick && m.reach.running, busy = m.reach.running && !quick,
                    onStart = m.onTestAll, onStop = m.onCancelTest)
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            if (q.isEmpty()) {
                item(key = "auto") { AutoRow(m.picked == "auto") { m.onChoose("auto") } }
            }
            item(key = "head") {
                Heading(when {
                    q.isNotEmpty() -> "MATCHES"
                    split -> "FASTEST FIRST"
                    else -> "ALL LOCATIONS"
                }, timed.size)
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text("No country matches “$query”.", style = Type.body,
                        modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            }
            if (split && timed.isEmpty() && untimed.isNotEmpty()) {
                item(key = "none-timed") {
                    Text("Nothing timed yet. Test all puts a time on every country.", style = Type.small,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp))
                }
            }
            items(timed, key = { it.code }, contentType = { "country" }) { c -> CountryItem(c, m, expanded) }
            if (untimed.isNotEmpty()) {
                item(key = "head-untimed") { Heading("NOT TESTED YET", untimed.size) }
                items(untimed, key = { it.code }, contentType = { "country" }) { c -> CountryItem(c, m, expanded) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CountryItem(c: Country, m: PickerModel, expanded: MutableMap<String, Boolean>) {
    val open = expanded[c.code] == true
    CountryCard(
        c, starred = c.code in m.favourites, picked = m.picked == c.code,
        reach = m.reach.takeIf { it.code == c.code },
        expandable = c.count > 1 || c.cities.size > 1, expanded = open,
        onChoose = { m.onChoose(c.code) },
        onStar = { m.onStar(c.code) },
        onTest = { if (m.reach.running && m.reach.code == c.code) m.onCancelTest() else m.onTest(c.code) },
        onExpand = { expanded[c.code] = !open },
    ) {
        if (c.cities.size > 1) {
            for (city in c.cities) CityBlock(city, m)
        } else {
            ExitList(c.code, m)
        }
    }
}

@Composable
private fun Heading(text: String, n: Int) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.eyebrow)
        Spacer(Modifier.width(8.dp))
        Text(n.toString(), style = Type.eyebrow.copy(color = Tone.text))
    }
}

/** Test all, or Stop while it runs. Waits while another row's test runs. */
@Composable
private fun TestAllChip(running: Boolean, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val c = if (running) Tone.fail else Tone.ok
    Row(
        Modifier
            .press(enabled = !busy, shape = CircleShape, sink = true, onClick = if (running) onStop else onStart)
            .clip(CircleShape)
            .background(c.copy(alpha = if (busy) 0.05f else 0.12f))
            .border(1.dp, c.copy(alpha = if (busy) 0.15f else 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (running) Glyph.x else Glyph.bolt, null, Modifier.size(13.dp),
            tint = if (busy) Tone.textOff else c)
        Spacer(Modifier.width(5.dp))
        Text(if (running) "Stop" else "Test all", style = Type.small.copy(
            color = if (busy) Tone.textOff else Tone.text, fontWeight = FontWeight.W600))
    }
}

private fun sortNote(m: PickerModel): String {
    val tested = m.countries.any { it.tested > 0 }
    return when {
        m.reach.running -> "Testing ${m.reach.done} of ${m.reach.total}…"
        m.reach.said.isNotEmpty() -> m.reach.said
        m.sort == Sort.PING && !tested -> "Nothing tested yet."
        m.sort == Sort.LOAD && m.countries.all { it.lowestLoad == null } ->
            "No load figures in these servers. Get Surfshark's list to have them."
        m.sort == Sort.PING -> "Times are from the last test."
        else -> ""
    }
}

@Composable
private fun Search(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tone.control.copy(alpha = 0.7f))
            .border(1.dp, if (value.isEmpty()) Tone.divider else Tone.ok.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.search, null, Modifier.size(18.dp), tint = if (value.isEmpty()) Tone.textOff else Tone.ok)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search a country or city", style = Type.body.copy(fontSize = 16.sp, color = Tone.textOff))
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = Tone.text),
                cursorBrush = SolidColor(Tone.ok), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Glyph.x, "Clear", { onChange("") }, size = 34.dp)
    }
}

/** A sort, as a pill that always says what it is. */
@Composable
private fun SortChip(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 6.dp)
            .press(shape = CircleShape, sink = true,
                feel = androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick, onClick = onClick)
            .clip(CircleShape)
            .background(if (on) Tone.ok.copy(alpha = 0.14f) else Tone.control.copy(alpha = 0.5f))
            .border(1.dp, if (on) Tone.ok.copy(alpha = 0.45f) else Tone.divider, CircleShape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (on) Tone.ok else Tone.textOff)
        Spacer(Modifier.width(5.dp))
        Text(label, style = Type.small.copy(color = if (on) Tone.text else Tone.textMuted,
            fontWeight = if (on) FontWeight.W600 else FontWeight.W500))
    }
}

@Composable
private fun SourceChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier.press(shape = CircleShape, sink = true, onClick = onClick).clip(CircleShape)
            .border(1.dp, Tone.divider, CircleShape).padding(start = 11.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Type.small.copy(color = Tone.textMuted, fontWeight = FontWeight.W600))
        Icon(Glyph.chevDown, null, Modifier.padding(start = 4.dp).size(13.dp), tint = Tone.textOff)
    }
}

/** Fastest available: the default, drawn as the one featured card. */
@Composable
private fun AutoRow(picked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .press(shape = shape, sink = true, onClick = onClick)
            .clip(shape)
            .background(Tone.surface.copy(alpha = 0.72f))
            .background(Brush.linearGradient(listOf(Tone.accent.copy(alpha = 0.16f), Tone.ok.copy(alpha = 0.06f))))
            .border(if (picked) 1.5.dp else 1.dp, if (picked) Tone.ok.copy(alpha = 0.7f) else Tone.accent.copy(alpha = 0.25f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tone.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Icon(Glyph.sparkle, null, Modifier.size(20.dp), tint = Tone.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Fastest available", style = Type.strong.copy(fontSize = 16.sp))
            Text("Races every location, takes the first to answer", style = Type.small,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (picked) Picked()
    }
}

/** The tick on whatever is chosen. */
@Composable
private fun Picked() {
    Box(Modifier.size(22.dp).clip(CircleShape).background(Tone.ok), contentAlignment = Alignment.Center) {
        Icon(Glyph.check, null, Modifier.size(13.dp), tint = Tone.ink)
    }
}

/** A round time, coloured by how good it is. */
private fun pingTone(ms: Int) = when {
    ms < 150 -> Tone.ok
    ms < 300 -> Tone.work
    else -> Tone.fail
}

/**
 * One country as a card: its flag, its name, what is in it (cities, relays,
 * the last time measured), and badges for who runs it and what it offers -
 * the provider's own mark, 10G, P2P. Opening it lays its cities out inside.
 */
@Composable
private fun CountryCard(
    c: Country, starred: Boolean, picked: Boolean, reach: ReachRun?,
    expandable: Boolean, expanded: Boolean,
    onChoose: () -> Unit, onStar: () -> Unit, onTest: () -> Unit, onExpand: () -> Unit,
    inside: @Composable () -> Unit,
) {
    val testing = reach?.running == true
    val shape = RoundedCornerShape(18.dp)
    val tenG = c.cities.any { (it.gbps ?: 0) >= 10 }
    val p2p = c.cities.any { it.p2p == true }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (picked) Tone.ok.copy(alpha = 0.07f) else Tone.surface.copy(alpha = 0.6f))
            .border(if (picked) 1.5.dp else 1.dp, if (picked) Tone.ok.copy(alpha = 0.6f) else Tone.divider, shape)
            .alpha(if (c.blocked) 0.6f else 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().tap(onClick = onChoose).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Tone.control), contentAlignment = Alignment.Center) {
                Text(flagOf(c.code), fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = Type.strong.copy(fontSize = 15.5.sp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (picked) { Spacer(Modifier.width(8.dp)); Picked() }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        testing -> Text("Testing ${reach.done}/${reach.total}", style = Type.small.copy(color = Tone.work))
                        c.blocked -> Text("Blocked here", style = Type.small.copy(color = Tone.fail))
                        c.ping != null -> {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(pingTone(c.ping)))
                            Spacer(Modifier.width(5.dp))
                            Text("${c.ping} ms", style = Type.small.copy(color = Tone.text, fontWeight = FontWeight.W600))
                        }
                    }
                    val places = if (c.cities.size > 1) "${c.cities.size} cities"
                        else c.cities.firstOrNull()?.name?.takeIf { it.isNotEmpty() }
                    val rest = listOfNotNull(places, "${c.count} relay${if (c.count == 1) "" else "s"}")
                    Text((if (testing || c.blocked || c.ping != null) "  ·  " else "") + rest.joinToString("  ·  "),
                        style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for ((provider, n) in c.by.toSortedMap()) {
                        if (n > 0) ProviderTag(provider, tested = (c.byTested[provider] ?: 0) > 0,
                            ok = (c.byOk[provider] ?: 0) > 0)
                    }
                    if (tenG) Badge("10G", Tone.accent)
                    if (p2p) Badge("P2P", Tone.ok)
                }
            }
            Bolt(testing, onTest)
            IconButton(if (starred) Glyph.starFilled else Glyph.star, if (starred) "Unstar" else "Star", onStar,
                tint = if (starred) Tone.work else Tone.textOff, size = 36.dp)
            if (expandable) {
                val turn by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f,
                    tween(220), label = "chev")
                Box(Modifier.graphicsLayer { rotationZ = turn }) {
                    IconButton(Glyph.chevDown, if (expanded) "Hide exits" else "Show exits", onExpand, size = 36.dp)
                }
            } else {
                Spacer(Modifier.width(36.dp))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Tone.bg.copy(alpha = 0.5f))) {
                inside()
            }
        }
    }
}

/** A small word in a tinted capsule: 10G, P2P. */
@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.padding(end = 5.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = Type.eyebrow.copy(color = color, fontSize = 9.5.sp))
    }
}

@Composable
private fun Bolt(testing: Boolean, onClick: () -> Unit) {
    // The pulse only exists while a test runs. One per row, always running,
    // redrew every visible row on every frame and made the list stutter.
    if (!testing) {
        IconButton(Glyph.bolt, "Test these exits", onClick, tint = Tone.textOff, size = 36.dp)
        return
    }
    val loop = rememberInfiniteTransition(label = "bolt")
    val a = loop.animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
    Box(Modifier.graphicsLayer { alpha = a.value }) {
        IconButton(Glyph.bolt, "Stop testing", onClick, tint = Tone.work, size = 36.dp)
    }
}

/** The core's word for why an exit failed, said for a person. */
private fun failReason(why: String): String = when {
    why.isEmpty() -> "It did not answer."
    why.contains("timeout", true) || why.contains("deadline", true) ->
        "It did not answer in time. It may be down, or blocked on this line."
    why.contains("refused", true) -> "It refused the connection."
    why.contains("407") || why.contains("auth", true) -> "It turned the account away."
    else -> why.replaceFirstChar { it.uppercase() }
}

/**
 * Who runs these exits, as the provider's own mark in a tinted capsule. The
 * tint says what the last test found here: the provider's colour untested,
 * green when one answered, amber when none did.
 */
@Composable
fun ProviderTag(provider: String, tested: Boolean = false, ok: Boolean = false) {
    val base = if (provider == "windscribe") Tone.windscribe else Tone.surfshark
    val c = when {
        !tested -> base
        ok -> Tone.ok
        else -> Tone.work
    }
    Box(
        Modifier
            .padding(end = 5.dp)
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(c.copy(alpha = 0.13f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (provider) {
            "windscribe" -> Icon(Glyph.windscribe, "Windscribe", Modifier.size(13.dp), tint = c)
            "surfshark" -> Icon(Glyph.surfshark, "Surfshark", Modifier.size(13.dp), tint = c)
            else -> Text(provider.take(1).uppercase(),
                style = Type.mono.copy(fontSize = 10.sp, color = c, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun CityBlock(city: City, m: PickerModel) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (m.picked == city.code) Tone.ok.copy(alpha = 0.07f) else Color.Transparent)
            .tap { m.onChoose(city.code) }
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(when {
            city.tested > 0 && city.ok == 0 -> Tone.fail
            city.ping != null -> pingTone(city.ping)
            city.ok > 0 -> Tone.ok
            else -> Tone.textOff
        }))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(city.name.ifEmpty { city.code }, style = Type.strong.copy(fontSize = 14.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (city.nick.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(city.nick, style = Type.small.copy(color = Tone.textOff), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (m.picked == city.code) { Spacer(Modifier.width(6.dp)); Picked() }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for ((provider, n) in city.by.toSortedMap()) if (n > 0) ProviderTag(provider)
                if ((city.gbps ?: 0) >= 10) Badge("10G", Tone.accent)
                if (city.p2p == true) Badge("P2P", Tone.ok)
                Text(buildString {
                    append("${city.count} relay${if (city.count == 1) "" else "s"}")
                    if (city.tested > 0 && city.ok == 0) append(" · blocked")
                    else if (city.ping != null) append(" · ${city.ping} ms")
                }, style = Type.small, maxLines = 1)
            }
        }
        city.load?.let { LoadBar(it) }
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, tween(220), label = "chev")
        Box(Modifier.graphicsLayer { rotationZ = turn }) {
            IconButton(Glyph.chevDown, if (open) "Hide exits" else "Show exits", { open = !open }, size = 34.dp)
        }
    }
    AnimatedVisibility(open) { Column(Modifier.padding(start = 14.dp)) { ExitList(city.code, m) } }
    Divider()
}

/** How busy a city's servers are, as a short bar with the number on it. */
@Composable
private fun LoadBar(load: Int) {
    val c = when {
        load < 25 -> Tone.ok
        load < 60 -> Tone.work
        else -> Tone.fail
    }
    Column(Modifier.padding(end = 6.dp), horizontalAlignment = Alignment.End) {
        Text("$load%", style = Type.small.copy(fontSize = 10.5.sp, color = c))
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(width = 30.dp, height = 4.dp).clip(CircleShape).background(Tone.control)) {
            Box(Modifier.fillMaxWidth(load.coerceIn(2, 100) / 100f).height(4.dp).background(c))
        }
    }
}

/** The individual exits behind a row, fetched when it opens. */
@Composable
private fun ExitList(code: String, m: PickerModel) {
    var exits by remember(code) { mutableStateOf<List<Exit>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(code, m.countries) { scope.launch { exits = m.exitsIn(code) } }
    val got = exits
    when {
        got == null -> Text("Reading…", style = Type.small, modifier = Modifier.padding(16.dp))
        got.isEmpty() -> Text("No exits here.", style = Type.small, modifier = Modifier.padding(16.dp))
        else -> Column {
            for (e in got) ExitRow(e, m.picked == "file:${e.file}") { m.onChoose("file:${e.file}") }
        }
    }
}

@Composable
private fun ExitRow(e: Exit, picked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (picked) Tone.ok.copy(alpha = 0.07f) else Color.Transparent)
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderTag(e.provider, tested = e.ok != null, ok = e.ok == true)
        Spacer(Modifier.width(6.dp))
        // The city is the name; the server's number and address are what
        // tell two exits in it apart.
        val number = serverNumber(e.host.ifEmpty { e.file })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.cityName.ifEmpty { e.host.ifEmpty { e.file } }, style = Type.strong.copy(fontSize = 14.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.nick.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(e.nick, style = Type.small.copy(color = Tone.textOff), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Text(listOfNotNull(number ?: e.host.ifEmpty { null }, e.ip.ifEmpty { null }).joinToString(" · "),
                style = Type.mono.copy(color = Tone.textOff), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            picked -> Picked()
            e.ok == true && e.ms != null -> Pill("${e.ms} ms", color = pingTone(e.ms))
            // A red mark where the time would be; the reason is behind it.
            e.ok == false -> InfoTip(failReason(e.why), icon = Glyph.alert, tint = Tone.fail,
                label = "Why it failed")
        }
    }
}

/** Which pool to connect out of: Everything, or only the starred places. */
@Composable
fun SourceSheet(
    open: Boolean, sources: List<Source>, source: String,
    onPick: (String) -> Unit, onImport: () -> Unit, onClose: () -> Unit,
) {
    Sheet(open, "Where the list comes from", "Servers", onClose) {
        Spacer(Modifier.height(4.dp))
        for (s in sources) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (s.key == source) Tone.ok.copy(alpha = 0.08f) else Tone.surface)
                    .border(1.dp, if (s.key == source) Tone.ok.copy(alpha = 0.4f) else Tone.divider,
                        RoundedCornerShape(14.dp))
                    .tap { onPick(s.key) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = Type.strong)
                    Text(s.note, style = Type.small)
                }
                Text(s.count.toString(), style = Type.mono.copy(color = Tone.textMuted))
            }
        }
        if (sources.isEmpty()) Text("Nothing has been read yet.", style = Type.body)
        ButtonRow {
            Btn("Import a folder…", onImport, icon = Glyph.folder)
            InfoTip("A picked folder is copied into the app's own; the copy is what is read.")
        }
    }
}
