package com.erelay.relay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.erelay.relay.ui.countryName

/**
 * Relay in the quick-settings panel: one press to connect, one to stop.
 *
 * It connects to whatever the window last picked, through whichever way out
 * the window has chosen - the same call Connect makes, so the tile and the
 * button can never disagree about what connecting means. What the tile
 * cannot do on its own it hands to the window: Android's VPN consent and a
 * missing account are both questions only an Activity can ask.
 */
class RelayTile : TileService() {

    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = paint()
    }
    private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        ContextCompat.registerReceiver(this, status, IntentFilter(RelayVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        listening = true
        paint()
    }

    override fun onStopListening() {
        if (listening) unregisterReceiver(status)
        listening = false
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val core = (application as RelayApp).core
        val up = RelayVpnService.exitAddress.isNotEmpty()
        val busy = !up && RelayVpnService.total > 0
        if (up || busy) {
            core.disconnect()
            return
        }
        if (VpnService.prepare(this) != null || !core.ready().ok) {
            openWindow()
            return
        }
        val said = core.connect(core.picked.value)
        if (!said.ok) openWindow()
        paint()
    }

    private fun openWindow() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** The tile from the service's companion: lit while up, a word under the name. */
    private fun paint() {
        val tile = qsTile ?: return
        val up = RelayVpnService.exitAddress.isNotEmpty()
        val note = RelayVpnService.status
        val working = !up && !note.startsWith("failed") && RelayVpnService.total > 0
        tile.state = when {
            up -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Relay"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_relay)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                up -> RelayVpnService.exitName.take(2).let { cc ->
                    countryName(cc).ifEmpty { "Connected" }.takeIf { RelayVpnService.exitProvider.isNotEmpty() }
                        ?: "Your server"
                }
                working -> "Connecting…"
                note.startsWith("failed") -> "Could not connect"
                else -> "Off"
            }
        }
        tile.updateTile()
    }
}
