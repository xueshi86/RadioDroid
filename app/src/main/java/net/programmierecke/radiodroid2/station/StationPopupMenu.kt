package net.programmierecke.radiodroid2.station

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import net.programmierecke.radiodroid2.R
import net.programmierecke.radiodroid2.RadioDroidApp
import net.programmierecke.radiodroid2.Utils
import net.programmierecke.radiodroid2.players.PlayStationTask
import net.programmierecke.radiodroid2.players.selector.PlayerType

object StationPopupMenu {
    fun open(view: View, context: Context, activity: FragmentActivity, station: DataRadioStation, itemAdapterStation: ItemAdapterStation): PopupMenu {
        val rootView = view.rootView
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
        val play_external = sharedPref.getBoolean("play_external", false)
        
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.station_popup_menu)
        
        // Adjust menu items based on preferences
        val menu = popupMenu.menu
        menu.findItem(R.id.menu_play_in_radiodroid).isVisible = play_external
        menu.findItem(R.id.menu_play_in_external_player).isVisible = !play_external
        menu.findItem(R.id.menu_create_shortcut).isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        
        // Set up click listeners
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_play_in_radiodroid -> {
                    StationActions.playInRadioDroid(context, station)
                    true
                }
                R.id.menu_play_in_external_player -> {
                    Utils.playAndWarnIfMetered(context.applicationContext as RadioDroidApp, station,
                            PlayerType.EXTERNAL) { PlayStationTask.playExternal(station, context).execute() }
                    true
                }
                R.id.menu_visit_homepage -> {
                    StationActions.openStationHomeUrl(activity, station)
                    true
                }
                R.id.menu_share -> {
                    StationActions.share(context, station)
                    true
                }
                R.id.menu_add_alarm -> {
                    StationActions.setAsAlarm(activity, station)
                    true
                }
                R.id.menu_create_shortcut -> {
                    station.prepareShortcut(context, itemAdapterStation.CreatePinShortcutListener())
                    true
                }
                R.id.menu_delete -> {
                    StationActions.removeFromFavourites(context, rootView, station)
                    true
                }
                else -> false
            }
        }
        
        popupMenu.show()
        return popupMenu
    }
}