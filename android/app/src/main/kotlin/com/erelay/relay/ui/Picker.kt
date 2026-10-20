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
    LaunchedEffect(open) { if (!open) query = "" }

    Sheet(open, "Relay network", "Choose an exit", onClose, scroll = false) {
        val q = query.trim().lowercase()
        val shown = remember(m.countries, m.sort, m.favourites, q) {
            val hits = m.countries.mapNotNull { c -> score(c, q)?.let { c to it } }
            if (q.isEmpty()) ordered(hits.map { it.first }, m.sort, m.favourites)
            else hits.sortedBy { it.second }.map { it.first }
        }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val list = rememberLazyListState()

        Column(Modifier.padding(horizontal = 16.dp)) {
            Search(query) { query = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SortChip(Glyph.gauge, "Ping", m.sort == Sort.PING) { m.onSort(Sort.PING) }
                SortChip(Glyph.layers, "Load", m.sort == Sort.LOAD) { m.onSort(Sort.LOAD) }
                SortChip(Glyph.az, "Name", m.sort == Sort.NAME) { m.onSort(Sort.NAME) }
                Grow()
                SourceChip(m.sources.firstOrNull { it.key == m.source }?.name ?: "Everything", m.onSources)
            }
            Text(sortNote(m), style = Type.small, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (q.isEmpty()) "ALL LOCATIONS" else "MATCHES", style = Type.eyebrow)
                Spacer(Modifier.width(8.dp))
                Text(shown.size.toString(), style = Type.eyebrow.copy(color = Tone.text))
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
            if (q.isEmpty()) {
                item(key = "auto") { AutoRow(m.picked == "auto") { m.onChoose("auto") } }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text("No country matches “$query”.", style = Type.body,
                        modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            }
            items(shown, key = { it.code }) { c ->
                val open = expanded[c.code] == true
                Column {
                    CountryRow(
                        c, starred = c.code in m.favourites, picked = m.picked == c.code,
                        reach = m.reach.takeIf { it.code == c.code },
                        expandable = c.count > 1 || c.cities.size > 1, expanded = open,
                        onChoose = { m.onChoose(c.code) },
                        onStar = { m.onStar(c.code) },
                        onTest = { if (m.reach.running && m.reach.code == c.code) m.onCancelTest() else m.onTest(c.code) },
                        onExpand = { expanded[c.code] = !open },
                    )
                    AnimatedVisibility(open) {
                        Column(Modifier.padding(start = 28.dp)) {
                            if (c.cities.size > 1) {
                                for (city in c.cities) CityBlock(city, m)
                            } else {
                                ExitList(c.code, m)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun sortNote(m: PickerModel): String {
    val tested = m.countries.any { it.tested > 0 }
    return when {
        m.reach.running -> "Testing ${m.reach.done} of ${m.reach.total}…"
        m.reach.said.isNotEmpty() -> m.reach.said
        m.sort == Sort.PING && !tested -> "Nothing tested yet. Tap a row's bolt to time its exits."
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
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0E1A17), Color(0xFF101318))))
            .border(1.dp, Tone.ok.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.search, null, Modifier.size(18.dp), tint = Tone.ok)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search countries", style = Type.body.copy(fontSize = 16.sp, color = Tone.textOff))
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = Tone.text),
                cursorBrush = SolidColor(Tone.ok), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Glyph.x, "Clear", { onChange("") }, size = 34.dp)
    }
}

@Composable
private fun SortChip(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 6.dp)
            .press(shape = CircleShape, sink = true,
                feel = androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick, onClick = onClick)
            .clip(CircleShape)
            .background(if (on) Tone.controlActive else Tone.control.copy(alpha = 0.5f))
            .border(1.dp, if (on) Tone.border else Tone.divider, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (on) Tone.text else Tone.textOff)
        if (on) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = Type.small.copy(color = Tone.text, fontWeight = FontWeight.W600))
        }
    }
}

@Composable
private fun SourceChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier.press(shape = CircleShape, sink = true, onClick = onClick).clip(CircleShape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Type.small.copy(color = Tone.textMuted, fontWeight = FontWeight.W600))
        Icon(Glyph.chevDown, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Tone.textOff)
    }
}

@Composable
private fun AutoRow(picked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (picked) Tone.ok.copy(alpha = 0.06f) else Color.Transparent)
            .tap(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Tone.controlActive),
            contentAlignment = Alignment.Center) {
            Icon(Glyph.sparkle, null, Modifier.size(18.dp), tint = Tone.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Fastest available", style = Type.strong)
            Text("Races every location and takes the first to answer", style = Type.mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Divider()
}

@Composable
private fun CountryRow(
    c: Country, starred: Boolean, picked: Boolean, reach: ReachRun?,
    expandable: Boolean, expanded: Boolean,
    onChoose: () -> Unit, onStar: () -> Unit, onTest: () -> Unit, onExpand: () -> Unit,
) {
    val testing = reach?.running == true
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (picked) Tone.ok.copy(alpha = 0.06f) else Color.Transparent)
            .tap(onClick = onChoose)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .alpha(if (c.blocked) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Tone.control), contentAlignment = Alignment.Center) {
            Text(flagOf(c.code), fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append(c.code.uppercase())
                append(" · ${c.count} relay${if (c.count == 1) "" else "s"}")
                when {
                    testing -> append(" · ${reach.done}/${reach.total}")
                    c.blocked -> append(" · blocked")
                    c.ping != null -> append(" · ${c.ping} ms")
                }
            }
            Text(meta, style = Type.mono.copy(color = if (c.blocked) Tone.fail else Tone.textOff),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for ((provider, n) in c.by.toSortedMap()) {
            if (n > 0) ProviderTag(provider, tested = (c.byTested[provider] ?: 0) > 0,
                ok = (c.byOk[provider] ?: 0) > 0)
        }
        Spacer(Modifier.width(4.dp))
        Bolt(testing, onTest)
        IconButton(if (starred) Glyph.starFilled else Glyph.star, if (starred) "Unstar" else "Star", onStar,
            tint = if (starred) Tone.work else Tone.textOff, size = 36.dp)
        if (expandable) {
            IconButton(if (expanded) Glyph.minus else Glyph.plus, "Show exits", onExpand, size = 36.dp)
        } else {
            Spacer(Modifier.width(36.dp))
        }
    }
    Divider()
}

@Composable
private fun Bolt(testing: Boolean, onClick: () -> Unit) {
    val loop = rememberInfiniteTransition(label = "bolt")
    val a = loop.animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
    Box(Modifier.alpha(if (testing) a.value else 1f)) {
        IconButton(Glyph.bolt, if (testing) "Stop testing" else "Test these exits", onClick,
            tint = if (testing) Tone.work else Tone.textOff, size = 36.dp)
    }
}

/** A provider's letter, coloured by what the last test said of its exits here. */
@Composable
fun ProviderTag(provider: String, tested: Boolean = false, ok: Boolean = false) {
    val base = if (provider == "windscribe") Tone.windscribe else Tone.fail
    val c = when {
        !tested -> base.copy(alpha = 0.75f)
        ok -> Tone.ok
        else -> Tone.work
    }
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.take(1), style = Type.mono.copy(fontSize = 10.sp, color = c, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun CityBlock(city: City, m: PickerModel) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (m.picked == city.code) Tone.ok.copy(alpha = 0.06f) else Color.Transparent)
            .tap { m.onChoose(city.code) }
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(
            if (city.tested > 0 && city.ok == 0) Tone.fail else if (city.ok > 0) Tone.ok else Tone.textOff))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(city.name + if (city.nick.isNotEmpty()) "  ${city.nick}" else "",
                style = Type.body.copy(color = Tone.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append("${city.count} relay${if (city.count == 1) "" else "s"}")
                if (city.ping != null) append(" · ${city.ping} ms")
                if (city.tested > 0 && city.ok == 0) append(" · blocked")
                if (city.gbps != null && city.gbps >= 10) append(" · 10G")
                if (city.p2p == true) append(" · P2P")
            }
            Text(meta, style = Type.mono, maxLines = 1)
        }
        city.load?.let { LoadBar(it) }
        for ((provider, n) in city.by.toSortedMap()) if (n > 0) ProviderTag(provider)
        IconButton(if (open) Glyph.minus else Glyph.plus, "Show exits", { open = !open }, size = 34.dp)
    }
    AnimatedVisibility(open) { Column(Modifier.padding(start = 18.dp)) { ExitList(city.code, m) } }
}

@Composable
private fun LoadBar(load: Int) {
    val c = when {
        load < 25 -> Tone.ok
        load < 60 -> Tone.work
        else -> Tone.fail
    }
    Box(Modifier.padding(end = 6.dp).size(width = 28.dp, height = 4.dp).clip(CircleShape).background(Tone.control)) {
        Box(Modifier.fillMaxWidth(load.coerceIn(2, 100) / 100f).height(4.dp).background(c))
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
            .background(if (picked) Tone.ok.copy(alpha = 0.06f) else Color.Transparent)
            .tap(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderTag(e.provider, tested = e.ok != null, ok = e.ok == true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.host.ifEmpty { e.file }, style = Type.mono.copy(color = Tone.text, fontFamily = Mono),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(e.ip.ifEmpty { null }, e.cityName.ifEmpty { null }).joinToString(" · "),
                style = Type.mono, maxLines = 1)
        }
        Text(when {
            e.ok == true && e.ms != null -> "${e.ms} ms"
            e.ok == false -> e.why.ifEmpty { "no answer" }
            else -> ""
        }, style = Type.mono.copy(color = if (e.ok == false) Tone.fail else Tone.textMuted),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(90.dp))
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
