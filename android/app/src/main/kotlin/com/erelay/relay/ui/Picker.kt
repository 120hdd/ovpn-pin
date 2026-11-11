package com.erelay.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
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
import com.erelay.relay.ui.theme.Data
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        // Continents are drawers, all shut each time the sheet opens: a
        // continent, then a country, then a server - three short choices
        // instead of one long scroll. One open at a time: the open drawer is
        // the screen's one active item, and only one thing glows.
        var drawer by remember(open) { mutableStateOf<String?>(null) }
        // Rows that have made their entrance this time the sheet is open, so
        // one scrolled back into view does not make it again.
        val entered = remember(open) { mutableSetOf<String>() }
        val reduced = rememberReducedMotion()
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
            // Drawer motion (DESIGN-NIGHT 6): rows arrive over 0.3s expo.out
            // and leave over 0.18s power2.in; nothing moves with animations off.
            val rowIn = if (reduced) null else tween<Float>(Shape.DRAWER_OPEN_MS, easing = Shape.expoOut)
            val rowOut = if (reduced) null else tween<Float>(Shape.DRAWER_CLOSE_MS, easing = Shape.power2In)
            val rowMove = if (reduced) null
                else tween<androidx.compose.ui.unit.IntOffset>(Shape.DRAWER_OPEN_MS, easing = Shape.expoOut)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.x4)) {
                if (groups.isEmpty()) {
                    item(key = "none") {
                        Text("No country matches “$query”. Try a city or a two-letter code, or clear the search.",
                            style = Type.body, modifier = Modifier.fillMaxWidth().padding(vertical = Space.x6))
                    }
                }
                for ((name, codes) in groups) {
                    // Starred is the short list on purpose and stays open, and so
                    // does everything while a search is typed: a result inside a
                    // shut drawer is a result not found.
                    val fixed = q.isNotEmpty() || name == STARRED
                    val shown = fixed || drawer == name
                    item(key = "gap-$name", contentType = "gap") { Spacer(Modifier.height(Space.x2)) }
                    stickyHeader(key = "group-$name", contentType = "header") {
                        DrawerHead(name, codes.size, if (fixed) null else shown, shown) {
                            drawer = if (shown) null else name
                        }
                    }
                    if (shown) {
                        itemsIndexed(codes, key = { _, c -> c }, contentType = { _, _ -> "country" }) { i, code ->
                            Box(Modifier.animateItem(fadeInSpec = rowIn, placementSpec = rowMove, fadeOutSpec = rowOut)
                                .slateSeg(Seg.BODY)) {
                                byCode[code]?.let { c ->
                                    // The first twelve rows of a drawer come in one after another;
                                    // decided once, so a later recomposition does not cut it short.
                                    val stagger = remember { if (!reduced && i < 12 && code !in entered) i else -1 }
                                    SideEffect { entered += code }
                                    Entrance(stagger) { CountryItem(c, m, expanded, first = i == 0) }
                                }
                            }
                        }
                        item(key = "foot-$name", contentType = "foot") {
                            Box(Modifier.animateItem(fadeInSpec = rowIn, placementSpec = rowMove, fadeOutSpec = rowOut)
                                .fillMaxWidth().height(Shape.card).slateSeg(Seg.FOOT))
                        }
                    }
                }
                item { Spacer(Modifier.height(Space.x6)) }
            }
        }
    }
}

/**
 * A continent drawer's head (DESIGN-NIGHT 9): a Slate card holding the
 * continent's mark in a Raised Slate well, the name, an outlined count and the
 * chevron. While the drawer is open its rows hang below in the same card, and
 * the head is the screen's one active item: the Pulse edge and fill, the
 * halo, the edge bar, the mark in Pulse.
 *
 * [open] is null for a heading that is not a drawer (Starred, or any group
 * while a search is typed): no chevron, and no glow, though its rows still
 * hang below it ([shown]). Otherwise the whole card opens and shuts it.
 * Sticky: rows slide beneath it.
 */
@Composable
private fun DrawerHead(name: String, n: Int, open: Boolean?, shown: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val active = open == true
    val lit by animateFloatAsState(if (active) 1f else 0f,
        tween(if (reduced) 0 else 450, easing = Shape.power3Out), label = "lit")
    val seg = if (shown) Seg.HEAD else Seg.WHOLE
    Box(
        Modifier.fillMaxWidth()
            // Behind the head's round top corners, the page: a row passing
            // under the stuck head does not show at its corners.
            .then(if (shown) Modifier.drawBehind { drawRect(Tone.bg) } else Modifier)
            .slateSeg(seg, lit = lit)
            .then(if (open == null) Modifier
                else Modifier.press(shape = RoundedCornerShape(Shape.card), feel = null) { haptics.tick(); onToggle() })
            .semantics { if (open != null) stateDescription = if (open) "Open" else "Shut" },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(start = Space.x3, end = Space.x2, top = Space.x3, bottom = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val markTint = androidx.compose.ui.graphics.lerp(Tone.text2, Tone.pulse, lit)
            IconWell(ContinentMark.of(name), tint = markTint)
            Spacer(Modifier.width(Space.x3))
            Text(name, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.x2))
            CountBadge(n.toString(), active = active)
            Spacer(Modifier.weight(1f))
            if (open != null) {
                val turn by animateFloatAsState(if (open) 180f else 0f,
                    tween(if (reduced) 0 else Shape.DRAWER_OPEN_MS, easing = Shape.expoOut), label = "chev")
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Glyph.chevDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
                        tint = if (open) Tone.text else Tone.text3)
                }
            }
        }
    }
}

/**
 * A row's entrance (DESIGN-NIGHT 6): up 8dp from transparent, 30ms after the
 * row above it. [order] below zero: no entrance, it is simply there.
 */
@Composable
private fun Entrance(order: Int, content: @Composable () -> Unit) {
    if (order < 0) {
        content()
        return
    }
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(Shape.DRAWER_OPEN_MS, delayMillis = order * 30, easing = Shape.expoOut))
    }
    Box(Modifier.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 8.dp.toPx()
    }) { content() }
}

// -- rows -------------------------------------------------------------------------------

/** A hairline between two rows inside a drawer, from [inset] in to the card's inner edge. */
@Composable
private fun Hair(inset: Dp = Space.x4) {
    Box(Modifier.fillMaxWidth().padding(start = inset, end = Space.x4).height(1.dp).background(Tone.hairline))
}

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun CountryItem(c: Country, m: PickerModel, expanded: MutableMap<String, Boolean>, first: Boolean = false) {
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
        if (!first) Hair()
        ListItem(
            modifier = Modifier
                .pickedBar(picked)
                // No haptic of its own: the caller's says whether this connects.
                .press(feel = null) { haptics.chose(m.connects); m.onChoose(c.code) },
            colors = rowColors(),
            leadingContent = { Flag(c.code, 28.dp) },
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
        // The country's cities and servers hang under it, indented past the
        // flag, each set off by a hairline.
        Expand(open) {
            Column(Modifier.padding(start = Space.x10)) {
                Hair(Space.x4)
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(c.code) }
                if (c.cities.size > 1) {
                    // Starred cities first; the rest keep the order the core gave.
                    // Keyed by city, so a city's own open/closed state goes with it
                    // when starring one moves it up, rather than staying at the slot.
                    for (city in c.cities.sortedBy { it.code !in m.favourites }) key(city.code) {
                        Hair(Space.x4)
                        CityBlock(city, m)
                    }
                } else {
                    ExitList(c.code, m)
                }
            }
        }
    }
}

/** Opens in place (DESIGN-NIGHT 6): height and opacity, 0.3s expo.out; shuts in 0.18s power2.in. */
@Composable
private fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val inMs = if (reduced) 0 else Shape.DRAWER_OPEN_MS
    val outMs = if (reduced) 0 else Shape.DRAWER_CLOSE_MS
    AnimatedVisibility(visible,
        enter = expandVertically(tween(inMs, easing = Shape.expoOut)) + fadeIn(tween(inMs, easing = Shape.expoOut)),
        exit = shrinkVertically(tween(outMs, easing = Shape.power2In)) + fadeOut(tween(outMs, easing = Shape.power2In))) {
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

/**
 * A star, felt as a switch: on with one tick, off with a softer one. A line
 * star either way (no filled icons beside line ones); on, it is in Pulse.
 */
@Composable
private fun Star(starred: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    IconToggleButton(checked = starred, onCheckedChange = { haptics.toggle(it); onClick() }) {
        Icon(Glyph.star, if (starred) "Unstar" else "Star",
            Modifier.size(20.dp), tint = if (starred) Tone.pulse else Tone.text3)
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
        Modifier.fillMaxWidth().heightIn(min = 48.dp).press(onClick = onClick)
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
                .pickedBar(m.picked == city.code)
                .press(feel = null) { haptics.chose(m.connects); m.onChoose(city.code) },
            colors = rowColors(),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(city.name.ifEmpty { city.code }, style = Type.strong, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (city.nick.isNotEmpty()) {
                        Spacer(Modifier.width(Space.x2))
                        Text(city.nick, style = Type.caption.copy(color = Tone.text3).script(city.nick), maxLines = 1,
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
                Hair(Space.x4)
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(city.code) }
                ExitList(city.code, m)
            }
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
    val haptics = rememberHaptics()
    when {
        got == null -> Column(Modifier.padding(Space.x4)) {
            // A skeleton of the rows that are coming, not a spinner.
            Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
            Spacer(Modifier.height(Space.x2))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(10.dp))
        }
        got.isEmpty() -> Text("No exits here. Time them again, or pick the country itself.", style = Type.caption,
            modifier = Modifier.padding(Space.x4))
        else -> Column {
            for (e in got) key(e.file) {
                Hair(Space.x4)
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
        modifier = Modifier.pickedBar(picked).press(feel = null, onClick = onClick),
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
                    Text(e.nick, style = Type.caption.copy(color = Tone.text3).script(e.nick), maxLines = 1,
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

/** The search box over a long list: this sheet's countries, the split tunnel's apps. A pill; Pulse ring while typing. */
@Composable
fun Search(value: String, hint: String = "Search a country or city", onChange: (String) -> Unit) {
    val focus = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Shape.pill)
    Row(
        Modifier
            .fillMaxWidth()
            // At least the height it was drawn at; taller when a large font
            // needs it, rather than the text being cut through the middle.
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Tone.surface2)
            .border(1.dp, when {
                focused -> Tone.focus
                value.isEmpty() -> Tone.stroke
                else -> Tone.strokeStrong
            }, shape)
            .padding(start = Space.x4, end = Space.x1, top = Space.x1, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.search, null, Modifier.size(20.dp), tint = if (value.isEmpty()) Tone.text3 else Tone.text)
        Spacer(Modifier.width(Space.x3))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.body.copy(fontSize = 16.sp, color = Tone.text3),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = Tone.text).script(value),
                cursorBrush = SolidColor(Tone.pulse), interactionSource = focus, modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Glyph.x, "Clear", { onChange("") }, size = 40.dp)
    }
}

/**
 * A chip (DESIGN-NIGHT 4): a pill, Raised Slate with a stronger Edge when on,
 * a bare Edge when off. 36dp to look at, 44dp to touch.
 */
@Composable
fun Chip(
    label: String, on: Boolean, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    maxWidth: Dp = Dp.Unspecified,
) {
    val shape = RoundedCornerShape(Shape.pill)
    Box(Modifier.heightIn(min = 44.dp).press(shape = shape, feel = null, onClick = onClick)
        .semantics { selected = on }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(shape)
                .background(if (on) Tone.surface2 else Color.Transparent)
                .border(1.dp, if (on) Tone.strokeStrong else Tone.stroke, shape)
                .padding(horizontal = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (on) Tone.text else Tone.text3)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = Type.body.copy(color = if (on) Tone.text else Tone.text2), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailing, null, Modifier.size(16.dp), tint = Tone.text3)
            }
        }
    }
}

/** A sort, as a chip that always says what it is. */
@Composable
private fun SortChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean,
                     onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Chip(label, on, { haptics.tick(); onClick() }, icon = icon)
}

@Composable
private fun SourceChip(name: String, onClick: () -> Unit) {
    Chip(name, false, onClick, trailing = Glyph.chevDown, maxWidth = 180.dp)
}

/**
 * Loading, as the shape of what is coming (DESIGN-NIGHT 4): a Raised Slate
 * bar with a soft sheen passing over it. Still with animations off.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(-1f, 2f, androidx.compose.animation.core.infiniteRepeatable(
        tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "sheen")
    Box(modifier.clip(RoundedCornerShape(Shape.pill)).background(Tone.surface2).drawBehind {
        if (!reduced) {
            val w = size.width
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color.Transparent, 0.5f to Tone.wash.copy(alpha = 0.06f), 1f to Color.Transparent,
                startX = w * (x - 0.5f), endX = w * (x + 0.5f)))
        }
    })
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
    // Untested, the provider's own data colour; once asked, the status colours.
    val c = when {
        tested <= 0 -> Data.provider(provider)
        ok > 0 -> if (ok < tested) Tone.work else Tone.good
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
        if (sources.isNotEmpty()) Group {
            sources.forEachIndexed { i, s ->
                if (i > 0) Sep()
                ListItem(
                    modifier = Modifier.pickedBar(s.key == source).press { onPick(s.key) }.padding(horizontal = Space.x1),
                    colors = rowColors(),
                    headlineContent = { Text(s.name, style = Type.strong.script(s.name)) },
                    supportingContent = { Text(s.note, style = Type.caption) },
                    trailingContent = { CountBadge(s.count.toString(), active = s.key == source) },
                )
            }
        }
        if (sources.isEmpty()) Text("Nothing has been read yet. Import a folder of configs to begin.", style = Type.body,
            modifier = Modifier.padding(vertical = Space.x4))
        ButtonRow {
            Btn("Import a folder…", onImport, icon = Glyph.folder)
            InfoTip("A picked folder is copied into the app's own; the copy is what is read.")
        }
    }
}
