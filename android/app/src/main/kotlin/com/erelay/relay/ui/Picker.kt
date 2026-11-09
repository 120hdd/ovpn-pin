package com.erelay.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.erelay.relay.data.City
import com.erelay.relay.data.Country
import com.erelay.relay.data.Exit
import com.erelay.relay.data.ReachRun
import com.erelay.relay.data.Sort
import com.erelay.relay.data.Source
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.flow.drop
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
    /** Kept for the caller; the list no longer shows a Test all button. */
    val onTestAll: () -> Unit = {},
    /** The picker opened: the moment a stale list gets a fresh quick test. */
    val onOpen: () -> Unit = {},
    /**
     * Whether choosing a row connects now, or only remembers the pick for
     * later - while a connect is already under way. The touch says which.
     */
    val connects: Boolean = true,
)

/** A row chosen: the firmer confirm when it connects, a tick when it only remembers. */
private fun Haptics.chose(connects: Boolean) = if (connects) confirm() else select()

// -- continents -------------------------------------------------------------------------

/** The order the groups are listed in; the core's CountriesJSON carries the same names. */
private val CONTINENTS = listOf("Europe", "Middle East", "Asia", "North America", "South America",
    "Africa", "Oceania", "Antarctica", "Elsewhere")

private const val STARRED = "Starred"

// -- search and order -------------------------------------------------------------------

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

/** Starred itself, or one of its cities is: either way it goes to the top. */
private fun Country.starredIn(favourites: Set<String>) =
    code in favourites || cities.any { it.code in favourites }

/** Within a group: places known dead sink, then by the chosen measure; untimed after timed. */
private fun inGroup(sort: Sort): Comparator<Country> = compareBy<Country> { it.blocked }
    .then(when (sort) {
        Sort.PING -> compareBy<Country>({ it.tested == 0 }, { it.ping == null }, { it.ping ?: 0 }, { it.name })
        Sort.LOAD -> compareBy<Country>({ it.lowestLoad == null }, { it.lowestLoad ?: 0 }, { it.name })
        Sort.NAME -> compareBy { it.name }
    })

/**
 * The groups of the list, as codes: Starred first when there is any, then
 * the continents in their order, each sorted within itself; a search keeps
 * the groups and orders each by how well it matched. Empty groups are left out.
 */
private fun laidOut(countries: List<Country>, q: String, sort: Sort, favourites: Set<String>): List<Pair<String, List<String>>> {
    val hits = countries.mapNotNull { c -> score(c, q)?.let { c to it } }.toMap()
    fun order(list: List<Country>) =
        if (q.isEmpty()) list.sortedWith(inGroup(sort))
        else list.sortedWith(compareBy<Country>({ hits[it] ?: 0 }, { it.name }))
    val starred = hits.keys.filter { it.starredIn(favourites) }
    val rest = hits.keys.filterNot { it.starredIn(favourites) }.groupBy { it.continent }
    return buildList {
        if (starred.isNotEmpty()) add(STARRED to order(starred).map { it.code })
        for (name in CONTINENTS) rest[name]?.let { add(name to order(it).map { c -> c.code }) }
    }
}

/**
 * A narrow row: a small screen, or a large font on an ordinary one - what
 * counts is how much text fits across. The ping then goes under the name
 * instead of beside the star.
 */
private val LocalNarrow = staticCompositionLocalOf { false }

/** The closest two scroll ticks may come: a fling past ten rows is a run of ticks, not a hum. */
private const val TICK_GAP_MS = 45L

@Composable
private fun narrowScreen(): Boolean {
    val density = LocalDensity.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    return wide.value / density.fontScale < 380f
}

// -- the sheet --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Picker(open: Boolean, m: PickerModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    // Bumped by tapping the sort that is already chosen: the way to put
    // times that came in since the list was laid out into their places.
    var resort by remember { mutableStateOf(0) }
    LaunchedEffect(open) {
        if (!open) query = "" else m.onOpen()
    }

    Sheet(open, "Choose a location", onClose, scroll = false) {
        val q = query.trim().lowercase()
        // The order is laid out once - on opening, on a new sort or search -
        // and not again when results arrive, so a row never moves out from
        // under a finger. The rows themselves still show the newest numbers.
        val groups = remember(open, m.sort, m.favourites, q, resort, m.countries.size) {
            laidOut(m.countries, q, m.sort, m.favourites)
        }
        val byCode = remember(m.countries) { m.countries.associateBy { it.code } }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val list = rememberLazyListState()
        // A light detent as each country passes the top of the list, the
        // way a picker wheel clicks under the thumb. Only while a finger has
        // the list - dragging it, or the fling it let go into - and never for
        // a scroll the app made itself; only for a country row, not a
        // heading; and never closer together than a fast fling's rows would
        // make a buzz of it. Look and feel's switch, and the phone's own
        // touch-feedback setting, are both honoured by [Haptics].
        val haptics = rememberHaptics()
        LaunchedEffect(list, haptics) {
            // From a drag's start until the scroll it began - the fling
            // included - comes to rest. A programmatic scroll never drags.
            var byFinger = false
            launch {
                list.interactionSource.interactions.collect {
                    if (it is DragInteraction.Start) byFinger = true
                }
            }
            launch {
                snapshotFlow { list.isScrollInProgress }.collect { if (!it) byFinger = false }
            }
            var last = 0L
            snapshotFlow { list.firstVisibleItemIndex }
                .drop(1)
                .collect {
                    if (!byFinger || !list.isScrollInProgress) return@collect
                    val top = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (top?.contentType != "country") return@collect
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - last >= TICK_GAP_MS) {
                        last = now
                        haptics.detent()
                    }
                }
        }
        val quick = m.reach.code == "quick"
        val timing = quick && m.reach.running
        val stale = m.sort == Sort.PING && q.isEmpty() && m.countries.any { it.tested == 0 } &&
            m.countries.any { it.ping != null }

        Column(Modifier.padding(horizontal = Space.x4)) {
            Search(query) { query = it }
            Spacer(Modifier.height(Space.x3))
            // One scrollable line: the sorts, then where the list comes from.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
                verticalAlignment = Alignment.CenterVertically) {
                SortChip(Glyph.gauge, "Ping", m.sort == Sort.PING) {
                    if (m.sort == Sort.PING) resort++ else m.onSort(Sort.PING)
                }
                SortChip(Glyph.layers, "Load", m.sort == Sort.LOAD) { m.onSort(Sort.LOAD) }
                SortChip(Glyph.az, "A–Z", m.sort == Sort.NAME) { m.onSort(Sort.NAME) }
                SourceChip(m.sources.firstOrNull { it.key == m.source }?.name ?: "Everything", m.onSources)
            }
            val note = when {
                timing -> "Timing ${m.reach.done} of ${m.reach.total} countries…"
                stale -> "New times are in. Tap Ping to put them in order."
                else -> sortNote(m)
            }
            if (note.isNotEmpty() || timing) {
                Row(Modifier.padding(top = Space.x3), verticalAlignment = Alignment.CenterVertically) {
                    // Three lines before it is cut, and an ellipsis when it is:
                    // a narrow phone with a large font needs the third.
                    Text(note, style = Type.caption, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (timing) {
                        Text("Stop", style = Type.caption.copy(color = Tone.text),
                            modifier = Modifier.heightIn(min = 32.dp).clickable { m.onCancelTest() }
                                .padding(start = Space.x3), maxLines = 1)
                    }
                }
                if (timing) {
                    Spacer(Modifier.height(Space.x2))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Tone.surface2)) {
                        Box(Modifier.fillMaxWidth(if (m.reach.total > 0)
                            (m.reach.done.toFloat() / m.reach.total).coerceIn(0.03f, 1f) else 0.03f)
                            .height(2.dp).background(Tone.text2))
                    }
                }
            }
            Spacer(Modifier.height(Space.x1))
        }

        val narrow = narrowScreen()
        CompositionLocalProvider(LocalNarrow provides narrow) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
                if (groups.isEmpty()) {
                    item(key = "none") {
                        Text("No country matches “$query”.", style = Type.body,
                            modifier = Modifier.fillMaxWidth().padding(Space.x6))
                    }
                }
                for ((name, codes) in groups) {
                    stickyHeader(key = "group-$name", contentType = "header") { GroupHeader(name, codes.size) }
                    items(codes, key = { it }, contentType = { "country" }) { code ->
                        byCode[code]?.let { CountryItem(it, m, expanded) }
                    }
                }
                item { Spacer(Modifier.height(Space.x6)) }
            }
        }
    }
}

/** A continent's name in subtitle size, text-2, with how many are under it. Opaque: rows slide beneath it. */
@Composable
private fun GroupHeader(name: String, n: Int) {
    Row(
        Modifier.fillMaxWidth().background(Tone.bg)
            .padding(start = Space.x4, end = Space.x4, top = Space.x4, bottom = Space.x2),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(name, style = Type.subtitle.copy(color = Tone.text2), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Space.x2))
        Text(n.toString(), style = Type.caption.copy(color = Tone.text3), maxLines = 1)
    }
}

// -- rows -------------------------------------------------------------------------------

/** The selected row's mark: a 2dp rule on its leading edge in the state colour. */
private fun Modifier.rule(on: Boolean): Modifier = if (!on) this else drawBehind {
    val w = 2.dp.toPx()
    drawRect(Tone.state, topLeft = androidx.compose.ui.geometry.Offset(
        if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
}

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun CountryItem(c: Country, m: PickerModel, expanded: MutableMap<String, Boolean>) {
    val open = expanded[c.code] == true
    val haptics = rememberHaptics()
    val starred = c.code in m.favourites
    val narrow = LocalNarrow.current
    val reach = m.reach.takeIf { it.code == c.code }
    val testing = reach?.running == true
    // Lit when the pick is anywhere inside it - a city or one server in
    // it as much as the country itself - so the list says where you are.
    val picked = m.picked != "auto" && countryOfPick(m.picked).lowercase() == c.code.lowercase()
    val expandable = c.count > 1 || c.cities.size > 1
    val tenG = c.cities.any { (it.gbps ?: 0) >= 10 }
    val p2p = c.cities.any { it.p2p == true }

    Column(Modifier.fillMaxWidth().alpha(if (c.blocked) 0.6f else 1f)) {
        ListItem(
            modifier = Modifier
                .rule(picked)
                // No haptic of its own: the caller's says whether this connects.
                .clickable { haptics.chose(m.connects); m.onChoose(c.code) },
            colors = rowColors(),
            leadingContent = { Flag(c.code, 24.dp) },
            headlineContent = {
                Text(c.name, style = Type.strong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val places = if (c.cities.size > 1) "${c.cities.size} cities"
                    else c.cities.firstOrNull()?.name?.takeIf { it.isNotEmpty() }
                val line = listOfNotNull(places, "${c.count} relay${if (c.count == 1) "" else "s"}")
                    .joinToString("  ·  ")
                Column {
                    Text(line, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ProviderLine(c.by, c.byTested, c.byOk, tenG, p2p,
                        extra = if (narrow) { { PingText(c.ping, c.blocked, reach.takeIf { testing }) } } else null)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!narrow) PingText(c.ping, c.blocked, reach.takeIf { testing })
                    Star(starred) { m.onStar(c.code) }
                    if (expandable) Chevron(open) { expanded[c.code] = !open }
                }
            },
        )
        Expand(open) {
            Column(Modifier.padding(start = Space.x10)) {
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(c.code) }
                if (c.cities.size > 1) {
                    // Starred cities first; the rest keep the order the core gave.
                    // Keyed by city, so a city's own open/closed state goes with it
                    // when starring one moves it up, rather than staying at the slot.
                    for (city in c.cities.sortedBy { it.code !in m.favourites }) key(city.code) { CityBlock(city, m) }
                } else {
                    ExitList(c.code, m)
                }
            }
        }
        Divider()
    }
}

/** Opens in place, the way the list moves: height, with a fade. */
@Composable
private fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    val ms = if (rememberReducedMotion()) 0 else Shape.ENTER_MS
    AnimatedVisibility(visible,
        enter = expandVertically(tween(ms, easing = Shape.ease)) + fadeIn(tween(ms, easing = Shape.ease)),
        exit = shrinkVertically(tween(ms, easing = Shape.ease)) + fadeOut(tween(if (ms == 0) 0 else 167))) {
        content()
    }
}

/** Providers' bare marks, 10G and P2P as words; [extra] is a place to put the ping on a narrow row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderLine(
    by: Map<String, Int>, byTested: Map<String, Int>, byOk: Map<String, Int>,
    tenG: Boolean, p2p: Boolean, extra: (@Composable () -> Unit)? = null,
) {
    val marks = by.toSortedMap().filter { it.value > 0 }
    if (marks.isEmpty() && !tenG && !p2p && extra == null) return
    FlowRow(Modifier.padding(top = Space.x1), itemVerticalAlignment = Alignment.CenterVertically) {
        for ((provider, n) in marks) {
            ProviderTag(provider, count = n, tested = byTested[provider] ?: 0, ok = byOk[provider] ?: 0)
        }
        if (tenG) Text("10G", style = Type.caption, modifier = Modifier.padding(end = Space.x2))
        if (p2p) Text("P2P", style = Type.caption, modifier = Modifier.padding(end = Space.x2))
        extra?.invoke()
    }
}

/** The round trip, in mono and tabular; "blocked" in the fail colour; the test's progress while it runs. */
@Composable
private fun PingText(ping: Int?, blocked: Boolean, testing: ReachRun?) {
    val (say, c) = when {
        testing != null -> "${testing.done}/${testing.total}" to Tone.work
        blocked -> "blocked" to Tone.fail
        ping != null -> "$ping ms" to Tone.text2
        else -> return
    }
    Text(say, style = Type.mono.copy(color = c), maxLines = 1, softWrap = false,
        modifier = Modifier.padding(end = Space.x1))
}

/** A star, felt as a switch: on with one tick, off with a softer one. */
@Composable
private fun Star(starred: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    IconToggleButton(checked = starred, onCheckedChange = { haptics.toggle(it); onClick() }) {
        Icon(if (starred) Glyph.starFilled else Glyph.star, if (starred) "Unstar" else "Star",
            Modifier.size(20.dp), tint = if (starred) Tone.text else Tone.text3)
    }
}

/** Open or close what is inside a row; the light tick under the thumb, since this lays a list out. */
@Composable
private fun Chevron(open: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    val ms = if (rememberReducedMotion()) 0 else Shape.ENTER_MS
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(ms, easing = Shape.ease), label = "chev")
    IconToggleButton(checked = open, onCheckedChange = { haptics.tick(); onToggle() }) {
        Icon(Glyph.chevDown, if (open) "Hide" else "Show", Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
            tint = Tone.text2)
    }
}

/** Time just these exits: the per-row test, now inside the row that opens. */
@Composable
private fun TestRow(testing: Boolean, reach: ReachRun?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
            .padding(horizontal = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.bolt, null, Modifier.size(16.dp), tint = if (testing) Tone.work else Tone.text2)
        Spacer(Modifier.width(Space.x3))
        Text(if (testing) "Stop timing  ·  ${reach?.done ?: 0} of ${reach?.total ?: 0}" else "Time these exits",
            style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CityBlock(city: City, m: PickerModel) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val narrow = LocalNarrow.current
    val testing = m.reach.running && m.reach.code == city.code
    val reach = m.reach.takeIf { testing }
    val starred = city.code in m.favourites
    val blocked = city.tested > 0 && city.ok == 0
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .rule(m.picked == city.code)
                .clickable { haptics.chose(m.connects); m.onChoose(city.code) },
            colors = rowColors(),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(city.name.ifEmpty { city.code }, style = Type.strong, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (city.nick.isNotEmpty()) {
                        Spacer(Modifier.width(Space.x2))
                        Text(city.nick, style = Type.caption.copy(color = Tone.text3), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            },
            supportingContent = {
                Column {
                    Text(buildString {
                        append("${city.count} relay${if (city.count == 1) "" else "s"}")
                        if (city.load != null) append("  ·  ${city.load}% load")
                    }, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ProviderLine(city.by, emptyMap(), emptyMap(), (city.gbps ?: 0) >= 10, city.p2p == true,
                        extra = if (narrow) { { PingText(city.ping, blocked, reach) } } else null)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!narrow) PingText(city.ping, blocked, reach)
                    Star(starred) { m.onStar(city.code) }
                    Chevron(open) { open = !open }
                }
            },
        )
        Expand(open) {
            Column(Modifier.padding(start = Space.x6)) {
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(city.code) }
                ExitList(city.code, m)
            }
        }
        Divider()
    }
}

/** The individual exits behind a row, fetched when it opens. */
@Composable
private fun ExitList(code: String, m: PickerModel) {
    var exits by remember(code) { mutableStateOf<List<Exit>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(code, m.countries) { scope.launch { exits = m.exitsIn(code) } }
    val got = exits
    val haptics = rememberHaptics()
    when {
        got == null -> Text("Reading…", style = Type.caption, modifier = Modifier.padding(Space.x4))
        got.isEmpty() -> Text("No exits here.", style = Type.caption, modifier = Modifier.padding(Space.x4))
        else -> Column {
            for (e in got) key(e.file) {
                ExitRow(e, m.picked == "file:${e.file}") {
                    haptics.chose(m.connects)
                    m.onChoose("file:${e.file}")
                }
            }
        }
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

@Composable
private fun ExitRow(e: Exit, picked: Boolean, onClick: () -> Unit) {
    // The city is the name; the server's number and address are what
    // tell two exits in it apart.
    val number = serverNumber(e.host.ifEmpty { e.file })
    ListItem(
        modifier = Modifier.rule(picked).clickable(onClick = onClick),
        colors = rowColors(),
        leadingContent = {
            ProviderTag(e.provider, count = 1, tested = if (e.ok != null) 1 else 0,
                ok = if (e.ok == true) 1 else 0, slot = 16.dp)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.cityName.ifEmpty { e.host.ifEmpty { e.file } }, style = Type.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.nick.isNotEmpty()) {
                    Spacer(Modifier.width(Space.x2))
                    Text(e.nick, style = Type.caption.copy(color = Tone.text3), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        },
        supportingContent = {
            Text(listOfNotNull(number ?: e.host.ifEmpty { null }, e.ip.ifEmpty { null }).joinToString(" · "),
                style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            when {
                e.ok == true && e.ms != null -> Text("${e.ms} ms", style = Type.mono, maxLines = 1, softWrap = false)
                // A red mark where the time would be; the reason is behind it.
                e.ok == false -> InfoTip(failReason(e.why), icon = Glyph.alert, tint = Tone.fail,
                    label = "Why it failed")
            }
        },
    )
}

// -- the controls above the list ----------------------------------------------------------

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

/** The search box over a long list: this sheet's countries, the split tunnel's apps. */
@Composable
fun Search(value: String, hint: String = "Search a country or city", onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // At least the height it was drawn at; taller when a large font
            // needs it, rather than the text being cut through the middle.
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Shape.control))
            .background(Tone.surface2)
            .border(1.dp, if (value.isEmpty()) Tone.stroke else Tone.strokeStrong, RoundedCornerShape(Shape.control))
            .padding(start = Space.x4, end = Space.x1, top = Space.x1, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.search, null, Modifier.size(20.dp), tint = if (value.isEmpty()) Tone.text3 else Tone.text)
        Spacer(Modifier.width(Space.x3))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.body.copy(fontSize = 16.sp, color = Tone.text3),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = Tone.text),
                cursorBrush = SolidColor(Tone.text), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Glyph.x, "Clear", { onChange("") }, size = 40.dp)
    }
}

/** A sort, as a filter chip that always says what it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean,
                     onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FilterChip(
        selected = on,
        onClick = { haptics.tick(); onClick() },
        label = { Text(label, style = Type.body.copy(color = if (on) Tone.text else Tone.text2), maxLines = 1,
            softWrap = false) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = if (on) Tone.text else Tone.text3) },
        shape = RoundedCornerShape(Shape.control),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, selectedContainerColor = Tone.surface2),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = on,
            borderColor = Tone.stroke, selectedBorderColor = Tone.strokeStrong),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceChip(name: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(name, style = Type.body.copy(color = Tone.text2), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        trailingIcon = { Icon(Glyph.chevDown, null, Modifier.size(16.dp), tint = Tone.text3) },
        shape = RoundedCornerShape(Shape.control),
        colors = AssistChipDefaults.assistChipColors(containerColor = Color.Transparent),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Tone.stroke),
    )
}

/**
 * Who runs these exits: the provider's own mark and nothing round it - no
 * tile, no capsule, no border - drawn as the desktop's row__tag draws it. A
 * 17dp slot (16 on a single exit) holding a 14dp mark, so a row one provider
 * reaches keeps the same column as a row both do.
 *
 * The colour is the verdict, state for state: dim while untested, green
 * when every exit asked answered, amber when some did or not all were
 * asked, red when every one was asked and none did.
 */
@Composable
fun ProviderTag(provider: String, count: Int = 1, tested: Int = 0, ok: Int = 0, slot: Dp = 17.dp) {
    val c = when {
        tested <= 0 -> Tone.text3
        ok > 0 -> if (ok < tested) Tone.work else Tone.ok
        tested >= count -> Tone.fail
        else -> Tone.work
    }
    Box(
        Modifier.padding(end = Space.x2).size(slot),
        contentAlignment = Alignment.Center,
    ) {
        when (provider) {
            "windscribe" -> Icon(Glyph.windscribe, "Windscribe", Modifier.size(14.dp), tint = c)
            "surfshark" -> Icon(Glyph.surfshark, "Surfshark", Modifier.size(14.dp), tint = c)
            // Anything not taught yet keeps its initial, which still holds the column.
            else -> Text(provider.take(1).uppercase(), style = Type.mono.copy(color = c))
        }
    }
}

/** Which pool to connect out of: Everything, or only the starred places. */
@Composable
fun SourceSheet(
    open: Boolean, sources: List<Source>, source: String,
    onPick: (String) -> Unit, onImport: () -> Unit, onClose: () -> Unit,
) {
    Sheet(open, "Servers", onClose) {
        for (s in sources) {
            ListItem(
                modifier = Modifier.rule(s.key == source).clickable { onPick(s.key) },
                colors = rowColors(),
                headlineContent = { Text(s.name, style = Type.strong) },
                supportingContent = { Text(s.note, style = Type.caption) },
                trailingContent = { Text(s.count.toString(), style = Type.mono) },
            )
            Divider()
        }
        if (sources.isEmpty()) Text("Nothing has been read yet.", style = Type.body,
            modifier = Modifier.padding(vertical = Space.x4))
        ButtonRow {
            Btn("Import a folder…", onImport, icon = Glyph.folder)
            InfoTip("A picked folder is copied into the app's own; the copy is what is read.")
        }
    }
}
