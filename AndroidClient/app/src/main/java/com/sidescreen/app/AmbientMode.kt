package com.sidescreen.app

/**
 * Ambient content shown while host-display privacy mode is active.
 * Default [BLACK] preserves the original full blackout behavior.
 */
enum class AmbientMode {
    BLACK,
    CLOCK,
    PHOTOS,
    VIDEO,
    ;

    companion object {
        fun fromName(name: String?): AmbientMode =
            values().firstOrNull { it.name == name } ?: BLACK
    }
}
