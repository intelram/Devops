package dev.netpilot.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap to turn protection on or off from the notification shade. */
class ShieldTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = ShieldVpnService.state.value != ShieldState.OFF
        if (on) {
            ShieldVpnService.send(this, ShieldVpnService.ACTION_DISCONNECT)
        } else if (VpnService.prepare(this) == null && tryStart()) {
            // started directly from the tile
        } else {
            // VPN permission not granted yet: open the app, which asks for it.
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_CONNECT, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        }
        refresh()
    }

    /** Android may refuse a service start from the background; then the app is opened instead. */
    private fun tryStart(): Boolean = try {
        ShieldVpnService.send(this, ShieldVpnService.ACTION_CONNECT); true
    } catch (e: IllegalStateException) { false }

    private fun refresh() {
        val t = qsTile ?: return
        val s = ShieldVpnService.state.value
        t.state = if (s == ShieldState.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.label = getString(R.string.tile_label)
        t.subtitle = getString(when (s) {
            ShieldState.OFF -> R.string.state_off_short
            ShieldState.CONNECTING -> R.string.state_connecting_short
            ShieldState.ON -> R.string.state_on_short
            ShieldState.PAUSED -> R.string.state_paused_short
        })
        t.updateTile()
    }
}
