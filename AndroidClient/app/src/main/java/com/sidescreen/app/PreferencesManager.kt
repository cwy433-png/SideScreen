package com.sidescreen.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var showStatsOverlay: Boolean
        get() = prefs.getBoolean("show_stats", true)
        set(value) = prefs.edit().putBoolean("show_stats", value).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlay_opacity", 0.8f)
        set(value) = prefs.edit().putFloat("overlay_opacity", value).apply()

    var overlayX: Float
        get() = prefs.getFloat("overlay_x", -1f)
        set(value) = prefs.edit().putFloat("overlay_x", value).apply()

    var overlayY: Float
        get() = prefs.getFloat("overlay_y", -1f)
        set(value) = prefs.edit().putFloat("overlay_y", value).apply()

    var settingsButtonX: Float
        get() = prefs.getFloat("settings_x", -1f)
        set(value) = prefs.edit().putFloat("settings_x", value).apply()

    var settingsButtonY: Float
        get() = prefs.getFloat("settings_y", -1f)
        set(value) = prefs.edit().putFloat("settings_y", value).apply()

    // Corner position: 0=bottom-right, 1=bottom-left, 2=top-right, 3=top-left
    var settingsButtonCorner: Int
        get() = prefs.getInt("settings_corner", 0)
        set(value) = prefs.edit().putInt("settings_corner", value).apply()

    var connectionMode: ConnectionMode
        get() = ConnectionMode.fromName(prefs.getString("connection_mode", null))
        set(value) = prefs.edit().putString("connection_mode", value.name).apply()

    /** When true, release FLAG_KEEP_SCREEN_ON while host display is sleeping. Default off. */
    var allowScreenOffOnHostSleep: Boolean
        get() = prefs.getBoolean("allow_screen_off_on_host_sleep", false)
        set(value) = prefs.edit().putBoolean("allow_screen_off_on_host_sleep", value).apply()

    /** Ambient content mode while host is sleeping. Default BLACK. */
    var ambientMode: AmbientMode
        get() = AmbientMode.fromName(prefs.getString("ambient_mode", null))
        set(value) = prefs.edit().putString("ambient_mode", value.name).apply()

    var photoTreeUri: String?
        get() = prefs.getString("photo_tree_uri", null)
        set(value) = prefs.edit().putString("photo_tree_uri", value).apply()

    var photoTreeDisplayName: String?
        get() = prefs.getString("photo_tree_display_name", null)
        set(value) = prefs.edit().putString("photo_tree_display_name", value).apply()

    var videoUri: String?
        get() = prefs.getString("video_uri", null)
        set(value) = prefs.edit().putString("video_uri", value).apply()

    var videoDisplayName: String?
        get() = prefs.getString("video_display_name", null)
        set(value) = prefs.edit().putString("video_display_name", value).apply()

    var nightWindowEnabled: Boolean
        get() = prefs.getBoolean("night_window_enabled", false)
        set(value) = prefs.edit().putBoolean("night_window_enabled", value).apply()

    /** Night window start as `"HH:mm"`. Default 22:00. */
    var nightStart: String
        get() = prefs.getString("night_start", "22:00") ?: "22:00"
        set(value) = prefs.edit().putString("night_start", value).apply()

    /** Night window end as `"HH:mm"`. Default 07:00. */
    var nightEnd: String
        get() = prefs.getString("night_end", "07:00") ?: "07:00"
        set(value) = prefs.edit().putString("night_end", value).apply()
}
