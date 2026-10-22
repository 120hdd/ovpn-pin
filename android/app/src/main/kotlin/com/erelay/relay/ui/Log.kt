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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.erelay.relay.data.HostRow
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay

private enum class By(val label: String) { DESTINATION("Destination"), PROGRAM("Program") }

/** One line of the sheet: a host or an app, everything it moved, and whether it is live. */
private data class Group(val name: String, val sub: String, val up: Long, val down: Long, val live: Int, val hits: Int)

private fun group(rows: List<HostRow>, by: By): List<Group> = when (by) {
    By.DESTINATION -> rows.groupBy { it.host.ifEmpty { it.addr } }.map { (host, rs) ->
        Group(host, rs.mapNotNull { it.app }.distinct().take(3).joinToString(", "),
            rs.sumOf { it.up }, rs.sumOf { it.down }, rs.sumOf { it.live }, rs.sumOf { it.hits })
    }
    By.PROGRAM -> rows.groupBy { it.app ?: "Unknown" }.map { (app, rs) ->
        Group(app, "${rs.map { it.host }.distinct().size} destinations",
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

    Sheet(open, "Through the relay", "What went where", onClose, scroll = false) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Segment(By.entries, by, { it.label }, { by = it }, Modifier.width(220.dp))
            Spacer(Modifier.height(12.dp))
            Divider()
        }
        val groups = remember(rows, by) { group(rows, by) }
        if (groups.isEmpty()) {
            Text(if (live) "Nothing has gone through yet. This fills in as apps start talking."
                 else "Nothing is routed right now. Connect, and this fills in as your apps start talking.",
                style = Type.body, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
            return@Sheet
        }
        val total = groups.sumOf { it.up + it.down }.coerceAtLeast(1)
        Text("${groups.size} ${if (by == By.DESTINATION) "destinations" else "apps"} · ${bytes(total)}",
            style = Type.mono, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(groups, key = { it.name }) { g -> GroupRow(g, (g.up + g.down).toFloat() / total) }
            if (rows.size >= 400) item {
                Text("Only the busiest 400 are kept.", style = Type.small, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun GroupRow(g: Group, share: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (g.live > 0) Tone.ok else Tone.textOff.copy(alpha = 0.4f)))
            Spacer(Modifier.width(10.dp))
            Text(g.name, style = Type.strong.copy(fontSize = androidx.compose.ui.unit.TextUnit(14f,
                androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(bytes(g.up + g.down), style = Type.mono.copy(color = Tone.text))
        }
        Row(Modifier.padding(start = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.sub, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("↓ ${bytes(g.down)}  ↑ ${bytes(g.up)}", style = Type.mono)
        }
        Box(Modifier.padding(start = 16.dp, top = 6.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Tone.control)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.01f, 1f)).height(3.dp).background(Tone.ok.copy(alpha = 0.7f)))
        }
    }
}
