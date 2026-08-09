package net.programmierecke.radiodroid2.station

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import net.programmierecke.radiodroid2.R
import net.programmierecke.radiodroid2.players.PlayStationTask
import net.programmierecke.radiodroid2.service.PlayerServiceUtil

object StationPopupMenu {
    fun open(view: View, context: Context, activity: FragmentActivity, station: DataRadioStation): PopupMenu {
        val rootView = view.rootView
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
        val play_external = sharedPref.getBoolean("play_external", false)
        
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.station_popup_menu)
        
        // Adjust menu items based on preferences
        val menu = popupMenu.menu
        menu.findItem(R.id.menu_play_in_radiodroid).isVisible = play_external
        menu.findItem(R.id.menu_play_in_external_player).isVisible = !play_external
        
        // Set up click listeners
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_play_in_radiodroid -> {
                    StationActions.playInRadioDroid(context, station)
                    true
                }
                R.id.menu_play_in_external_player -> {
                    // execute() 自身会在后台执行并在主线程回调，无需额外包裹协程
                    PlayStationTask.playExternal(station, context).execute()
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
                R.id.menu_refresh_icon -> {
                    // 刷新图标需要 ImageView，从长按的 view 中查找
                    val imageView = view.findViewById<android.widget.ImageView>(R.id.imageViewIcon)
                        ?: view.findViewById<android.widget.ImageView>(R.id.iconImageViewIcon)
                    if (imageView != null) {
                        PlayerServiceUtil.forceRefreshStationIcon(station, imageView)
                    } else {
                        // 没有 ImageView 时仅清除缓存，下次显示时自动重新加载
                        net.programmierecke.radiodroid2.service.StationIconCache
                            .getInstance(context).removeIcon(station.StationUuid)
                    }
                    true
                }
                R.id.menu_delete -> {
                    StationActions.removeFromFavourites(context, rootView, rootView, station)
                    true
                }
                else -> false
            }
        }
        
        popupMenu.show()
        return popupMenu
    }
}