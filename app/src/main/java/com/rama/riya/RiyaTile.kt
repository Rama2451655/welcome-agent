package com.rama.riya

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the notification shade and tap to talk. */
class RiyaTile : TileService() {

    override fun onStartListening() {
        val name = getSharedPreferences("riya", Context.MODE_PRIVATE).getString("name", "Riya") ?: "Riya"
        qsTile?.apply {
            label = "Talk to $name"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        startForegroundService(Intent(this, RiyaService::class.java).setAction("WAKE"))
    }
}
