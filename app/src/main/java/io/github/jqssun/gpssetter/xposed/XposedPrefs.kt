package io.github.jqssun.gpssetter.xposed

/**
 * Keys and defaults shared between the app process (which writes through
 * [io.github.libxposed.service.XposedService]) and the module code running inside hooked processes
 * (which reads through [io.github.libxposed.api.XposedInterface.getRemotePreferences]).
 *
 * Both sides must agree on [GROUP]; it names the remote preference group owned by the framework
 * and replaces the world-readable SharedPreferences file the legacy API used.
 */
object XposedPrefs {
    const val GROUP = "gpssetter"

    // --- position ---------------------------------------------------------
    const val START = "start"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"

    // --- horizontal accuracy / randomisation ------------------------------
    const val ACCURACY_SETTING = "accuracy_level"
    const val RANDOM_POSITION = "random_position"
    const val RANDOMIZE_RADIUS = "randomize_radius"
    const val USE_VERTICAL_ACCURACY = "use_vertical_accuracy"
    const val VERTICAL_ACCURACY = "vertical_accuracy"

    // --- altitude ---------------------------------------------------------
    const val USE_ALTITUDE = "use_altitude"
    const val ALTITUDE = "altitude"
    const val USE_MSL = "use_msl"
    const val MSL = "msl"
    const val USE_MSL_ACCURACY = "use_msl_accuracy"
    const val MSL_ACCURACY = "msl_accuracy"

    // --- movement ---------------------------------------------------------
    /** Static speed, used when the position is not being driven by the joystick or a route. */
    const val USE_SPEED = "use_speed"
    const val SPEED = "speed"
    const val USE_SPEED_ACCURACY = "use_speed_accuracy"
    const val SPEED_ACCURACY = "speed_accuracy"

    /** Live values written by [io.github.jqssun.gpssetter.utils.PrefManager.update] while moving. */
    const val LIVE_SPEED = "live_speed"
    const val LIVE_BEARING = "live_bearing"
    const val LIVE_MOVING_UNTIL = "live_moving_until"

    /** True while an auto-route is driving the position; makes the hook use route speed. */
    const val ROUTE_ACTIVE = "route_active"

    // --- targeting --------------------------------------------------------
    /** Comma-separated package allowlist. Empty means "every scoped app", see [SPOOF_ALL_SCOPED]. */
    const val TARGET_APPS = "target_apps"
    const val SPOOF_ALL_SCOPED = "spoof_all_scoped"

    // --- hooks ------------------------------------------------------------
    const val HOOKED_SYSTEM = "system_hooked"
    const val BLOCK_GEOFENCE = "block_geofence"
    const val FAKE_GNSS = "fake_gnss"

    // --- wifi identity ----------------------------------------------------
    const val ENABLE_WIFI_IDENTITY = "enable_wifi_identity"
    const val WIFI_SSID = "wifi_ssid"
    const val WIFI_BSSID = "wifi_bssid"
    const val WIFI_RSSI = "wifi_rssi"

    // --- behaviour --------------------------------------------------------
    const val HIDE_ACTIVE_TOAST = "hide_active_toast"
    const val ENABLE_BROADCAST_CONTROL = "enable_broadcast_control"
    const val VERBOSE_LOG = "verbose_log"

    // --- app --------------------------------------------------------------
    const val MAP_TYPE = "map_type"
    const val DARK_THEME = "dark_theme"
    const val DISABLE_UPDATE = "update_disabled"

    /** The release tag the user chose to ignore; the update prompt stays hidden only while the
     *  latest release still equals this. A newer release supersedes it and prompts again. */
    const val IGNORED_UPDATE_VERSION = "ignored_update_version"
    const val ENABLE_JOYSTICK = "joystick_enabled"
    const val LANGUAGE_TAG = "language_tag"

    /** True once the user has placed a pin; keeps the red marker on the map across restarts even
     *  when spoofing is stopped, so the last chosen location is not lost on reopen. */
    const val PIN_PLACED = "pin_placed"

    /**
     * How a numeric field is produced.
     *
     * [OFF] leaves whatever the original fix carried. [FIXED] uses the value from the slider.
     * [AUTO] derives a plausible value via [DynamicValues] -- realistic range, slow drift, and
     * consistent with the other fields.
     */
    object Mode {
        const val OFF = 0
        const val FIXED = 1
        const val AUTO = 2
    }

    /** Mode keys, one per numeric field. */
    const val MODE_ACCURACY = "mode_accuracy"
    const val MODE_ALTITUDE = "mode_altitude"
    const val MODE_MSL = "mode_msl"
    const val MODE_MSL_ACCURACY = "mode_msl_accuracy"
    const val MODE_VERTICAL_ACCURACY = "mode_vertical_accuracy"
    const val MODE_SPEED = "mode_speed"
    const val MODE_SPEED_ACCURACY = "mode_speed_accuracy"
    const val MODE_BEARING = "mode_bearing"
    const val MODE_BEARING_ACCURACY = "mode_bearing_accuracy"
    const val MODE_RANDOMIZE = "mode_randomize"

    /** Fixed bearing value, in degrees. */
    const val BEARING = "bearing"
    const val BEARING_ACCURACY = "bearing_accuracy"

    /**
     * Terrain elevation and geoid offset looked up for the pinned point, used by AUTO altitude.
     *
     * Both live in the settings store rather than a cache directory: Android clears caches for
     * apps that have not been opened in a while, and losing these would silently degrade altitude
     * back to a generic value.
     */
    const val TERRAIN_ELEVATION = "terrain_elevation"
    const val GEOID_OFFSET = "geoid_offset"

    /** Defaults, kept here so the hook side and the settings UI can never disagree. */
    object Defaults {
        const val ACCURACY = "10"
        const val RANDOMIZE_RADIUS = 35f

        const val ALTITUDE = 0f
        const val MSL = 0f
        const val MSL_ACCURACY = 0f
        const val VERTICAL_ACCURACY = 0f

        const val SPEED = 0f
        const val SPEED_ACCURACY = 0f

        /**
         * Every value field defaults to AUTO: a fix where all fields are realistic and mutually
         * consistent is much harder to detect than one with a couple of hand-set numbers and the
         * rest left describing the real device.
         */
        const val MODE = Mode.AUTO

        /** System-level hooks are opt-in: they are the only ones that can restart the UI. */
        const val HOOKED_SYSTEM = true

        /** The in-app confirmation toast is hidden by default. */
        const val HIDE_ACTIVE_TOAST = true

        const val BEARING = 0f
        const val BEARING_ACCURACY = 0f
        const val SPOOF_ALL_SCOPED = true
        const val BLOCK_GEOFENCE = true
        const val FAKE_GNSS = true

        const val WIFI_SSID = "AndroidAP"
        const val WIFI_BSSID = "02:00:00:00:00:00"
        const val WIFI_RSSI = -60

        /** How long a position update keeps counting as "moving", in milliseconds. */
        const val MOVING_WINDOW_MS = 2000L
    }
}
