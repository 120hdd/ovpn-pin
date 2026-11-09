package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.erelay.relay.data.HostRow
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay

private enum class By(val label: String) { DESTINATION("Destination"), PROGRAM("Program") }

/** One line of the sheet: a host or an app, everything it moved, and whether it is live. */
private data class Bucket(val name: String, val sub: String, val up: Long, val down: Long, val live: Int, val hits: Int)

private fun group(rows: List<HostRow>, by: By): List<Bucket> = when (by) {
    By.DESTINATION -> rows.groupBy { it.host.ifEmpty { it.addr } }.map { (host, rs) ->
        Bucket(host, rs.mapNotNull { it.app }.distinct().take(3).joinToString(", "),
            rs.sumOf { it.up }, rs.sumOf { it.down }, rs.sumOf { it.live }, rs.sumOf { it.hits })
    }
    By.PROGRAM -> rows.groupBy { it.app ?: "Unknown" }.map { (app, rs) ->
        Bucket(app, "${rs.map { it.host }.distinct().size} destinations",
            rs.sumOf { it.up }, rs.sumOf { it.down }, rs.sumOf { it.live }, rs.sumOf { it.hits })
    }
}.sortedByDescending { it.up + it.down }

/**
 * What went where through the tunnel, grouped by destination or by the app
 * that sent it. Pulled every two seconds while it is open, and not at all
 * otherwise.
 */
@Composable
fun LogSheet(open: Boolean, live: Boolean, hosts: suspend () -> List<HostRow>, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<HostRow>>(emptyList()) }
    var by by remember { mutableStateOf(By.DESTINATION) }
    LaunchedEffect(open) {
        while (open) {
            rows = hosts()
            delay(2000)
        }
    }

    Sheet(open, "What went where", onClose, scroll = false) {
        Column(Modifier.padding(horizontal = Space.x4)) {
            Segment(By.entries, by, { it.label }, { by = it }, Modifier.widthIn(max = 320.dp))
            Spacer(Modifier.height(Space.x3))
        }
        val groups = remember(rows, by) { group(rows, by) }
        if (groups.isEmpty()) {
            Text(if (live) "Nothing has gone through yet. This fills in as apps start talking."
                 else "Nothing is routed right now. Connect, and this fills in as your apps start talking.",
                style = Type.body, modifier = Modifier.fillMaxWidth().padding(Space.x4))
            return@Sheet
        }
        val total = groups.sumOf { it.up + it.down }.coerceAtLeast(1)
        Text("${groups.size} ${if (by == By.DESTINATION) "destinations" else "apps"} · ${bytes(total)}",
            style = Type.mono, modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(groups, key = { it.name }) { g ->
                BucketRow(g, (g.up + g.down).toFloat() / total)
                Sep()
            }
            if (rows.size >= 400) item {
                Text("Only the busiest 400 are kept.", style = Type.caption, modifier = Modifier.padding(Space.x4))
            }
        }
    }
}

@Composable
private fun BucketRow(g: Bucket, share: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.x4, vertical = Space.x3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Space.x2).clip(CircleShape).background(if (g.live > 0) Tone.ok else Tone.text3.copy(alpha = 0.4f)))
            Spacer(Modifier.width(Space.x3))
            Text(g.name, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.x2))
            Text(bytes(g.up + g.down), style = Type.mono.copy(color = Tone.text), maxLines = 1, softWrap = false)
        }
        Row(Modifier.padding(start = Space.x5, top = Space.x1), verticalAlignment = Alignment.CenterVertically) {
            Text(g.sub, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.x2))
            // The figures keep their line whole; the name beside them gives way.
            Text("↓ ${bytes(g.down)}  ↑ ${bytes(g.up)}", style = Type.mono, maxLines = 1, softWrap = false)
        }
        Box(Modifier.padding(start = Space.x5, top = Space.x2).fillMaxWidth().height(3.dp).clip(CircleShape).background(Tone.surface2)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.01f, 1f)).height(3.dp).background(Tone.text2))
        }
    }
}
