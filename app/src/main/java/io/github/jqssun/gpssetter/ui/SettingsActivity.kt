package io.github.jqssun.gpssetter.ui


import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivitySettingsBinding
import io.github.jqssun.gpssetter.utils.FloatingMapService
import io.github.jqssun.gpssetter.utils.JoystickService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.control.ControlReceiver
import io.github.jqssun.gpssetter.room.FavoriteDao
import io.github.jqssun.gpssetter.utils.FavoritesTransfer
import kotlinx.coroutines.launch
import timber.log.Timber
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.xposed.XposedPrefs
import io.github.jqssun.gpssetter.utils.ext.showToast

@AndroidEntryPoint
class ActivitySettings : AppCompatActivity() {

    private var settingsFragment: SettingsPreferenceFragment? = null

    // Drives the manual "Check now" preference: the fragment calls checkNow(), and the result
    // (an available update, or "up to date") is surfaced from here.
    val mainViewModel: MainViewModel by viewModels()

    // Search and reset live on the toolbar; the fragment owns the preference tree, so both
    // actions are forwarded to it. The toolbar is the support action bar, so these must go
    // through the options-menu callbacks rather than toolbar.inflateMenu().
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        (searchItem?.actionView as? androidx.appcompat.widget.SearchView)?.apply {
            queryHint = getString(R.string.settings_search_hint)
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    settingsFragment?.filter(newText.orEmpty())
                    return true
                }
            })
        }
        return true
    }



    private val binding by lazy {
        ActivitySettingsBinding.inflate(layoutInflater)
    }

    /**
     * Routes every preference through [PrefManager] so writes are mirrored to the framework's
     * remote preferences, which is what the hook reads.
     *
     * Unknown keys must never throw here: an unhandled key would crash the settings screen rather
     * than merely misbehave.
     */
    class SettingPreferenceDataStore : PreferenceDataStore() {

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = when (key) {
            "system_hooked" -> PrefManager.isSystemHooked
            "random_position" -> PrefManager.isRandomPosition
            // The switch reads positively ("Check for updates"); the stored flag is the
            // opposite ("disabled"), so invert. ON = checking enabled, and it is on by default.
            "update_disabled" -> !PrefManager.isUpdateDisabled
            "joystick_enabled" -> PrefManager.isJoystickEnabled
            "use_altitude" -> PrefManager.useAltitude
            "use_msl" -> PrefManager.useMsl
            "use_msl_accuracy" -> PrefManager.useMslAccuracy
            "use_vertical_accuracy" -> PrefManager.useVerticalAccuracy
            "use_speed" -> PrefManager.useSpeed
            "use_speed_accuracy" -> PrefManager.useSpeedAccuracy
            "enable_wifi_identity" -> PrefManager.wifiIdentityEnabled
            "fake_gnss" -> PrefManager.fakeGnss
            "block_geofence" -> PrefManager.blockGeofence
            "hide_active_toast" -> PrefManager.hideActiveToast
            "enable_broadcast_control" -> PrefManager.enableBroadcastControl
            "verbose_log" -> PrefManager.verboseLog
            else -> defValue
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "system_hooked" -> PrefManager.isSystemHooked = value
                "random_position" -> PrefManager.isRandomPosition = value
                "update_disabled" -> PrefManager.isUpdateDisabled = !value
                "joystick_enabled" -> PrefManager.isJoystickEnabled = value
                "use_altitude" -> PrefManager.useAltitude = value
                "use_msl" -> PrefManager.useMsl = value
                "use_msl_accuracy" -> PrefManager.useMslAccuracy = value
                "use_vertical_accuracy" -> PrefManager.useVerticalAccuracy = value
                "use_speed" -> PrefManager.useSpeed = value
                "use_speed_accuracy" -> PrefManager.useSpeedAccuracy = value
                "enable_wifi_identity" -> PrefManager.wifiIdentityEnabled = value
                "fake_gnss" -> PrefManager.fakeGnss = value
                "block_geofence" -> PrefManager.blockGeofence = value
                "hide_active_toast" -> PrefManager.hideActiveToast = value
                "enable_broadcast_control" -> PrefManager.enableBroadcastControl = value
                "verbose_log" -> PrefManager.verboseLog = value
            }
        }

        override fun getString(key: String?, defValue: String?): String? = when (key) {
            "mode_accuracy" -> PrefManager.modeAccuracy.toString()
            "mode_altitude" -> PrefManager.modeAltitude.toString()
            "mode_msl" -> PrefManager.modeMsl.toString()
            "mode_msl_accuracy" -> PrefManager.modeMslAccuracy.toString()
            "mode_vertical_accuracy" -> PrefManager.modeVerticalAccuracy.toString()
            "mode_speed" -> PrefManager.modeSpeed.toString()
            "mode_speed_accuracy" -> PrefManager.modeSpeedAccuracy.toString()
            "mode_bearing" -> PrefManager.modeBearing.toString()
            "mode_bearing_accuracy" -> PrefManager.modeBearingAccuracy.toString()
            "mode_randomize" -> PrefManager.modeRandomize.toString()
            "accuracy_level" -> PrefManager.accuracy
            "map_type" -> PrefManager.mapType.toString()
            "dark_theme" -> PrefManager.darkTheme.toString()
            "wifi_ssid" -> PrefManager.wifiSsid
            "wifi_bssid" -> PrefManager.wifiBssid
            "language_tag" -> PrefManager.languageTag
            else -> defValue
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "mode_accuracy" -> PrefManager.modeAccuracy = value!!.toInt()
                "mode_altitude" -> PrefManager.modeAltitude = value!!.toInt()
                "mode_msl" -> PrefManager.modeMsl = value!!.toInt()
                "mode_msl_accuracy" -> PrefManager.modeMslAccuracy = value!!.toInt()
                "mode_vertical_accuracy" -> PrefManager.modeVerticalAccuracy = value!!.toInt()
                "mode_speed" -> PrefManager.modeSpeed = value!!.toInt()
                "mode_speed_accuracy" -> PrefManager.modeSpeedAccuracy = value!!.toInt()
                "mode_bearing" -> PrefManager.modeBearing = value!!.toInt()
                "mode_bearing_accuracy" -> PrefManager.modeBearingAccuracy = value!!.toInt()
                "mode_randomize" -> PrefManager.modeRandomize = value!!.toInt()
                "accuracy_level" -> PrefManager.accuracy = value
                "map_type" -> PrefManager.mapType = value!!.toInt()
                "dark_theme" -> PrefManager.darkTheme = value!!.toInt()
                "wifi_ssid" -> PrefManager.wifiSsid = normalizeSsid(value)
                "wifi_bssid" -> PrefManager.wifiBssid = normalizeBssid(value)
                "language_tag" -> PrefManager.languageTag = value.orEmpty()
            }
        }

        // SeekBarPreference stores ints; the hook side wants floats for the location fields.
        override fun getInt(key: String?, defValue: Int): Int = when (key) {
            "randomize_radius" -> PrefManager.randomizeRadius.toInt()
            "altitude" -> PrefManager.altitude.toInt()
            "msl" -> PrefManager.msl.toInt()
            "msl_accuracy" -> PrefManager.mslAccuracy.toInt()
            "vertical_accuracy" -> PrefManager.verticalAccuracy.toInt()
            // Stored in m/s (the location API's unit); shown in km/h.
            "speed" -> Math.round(PrefManager.speed * 3.6f)
            "speed_accuracy" -> Math.round(PrefManager.speedAccuracy * 3.6f)
            "wifi_rssi" -> PrefManager.wifiRssi
            "accuracy_value" -> (PrefManager.accuracy?.toFloatOrNull() ?: 10f).toInt()
            "bearing" -> PrefManager.bearing.toInt()
            "bearing_accuracy" -> PrefManager.bearingAccuracy.toInt()
            else -> defValue
        }

        override fun putInt(key: String?, value: Int) {
            when (key) {
                "randomize_radius" -> PrefManager.randomizeRadius = value.toFloat()
                "altitude" -> PrefManager.altitude = value.toFloat()
                "msl" -> PrefManager.msl = value.toFloat()
                "msl_accuracy" -> PrefManager.mslAccuracy = value.toFloat()
                "vertical_accuracy" -> PrefManager.verticalAccuracy = value.toFloat()
                // Slider is km/h; store as m/s.
                "speed" -> PrefManager.speed = value / 3.6f
                "speed_accuracy" -> PrefManager.speedAccuracy = value / 3.6f
                "wifi_rssi" -> PrefManager.wifiRssi = value
                "accuracy_value" -> PrefManager.accuracy = value.toString()
                "bearing" -> PrefManager.bearing = value.toFloat()
                "bearing_accuracy" -> PrefManager.bearingAccuracy = value.toFloat()
            }
        }

        private companion object {
            val MAC_REGEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

            /** SSIDs are capped at 32 bytes; anything longer or empty falls back to the default. */
            fun normalizeSsid(raw: String?): String {
                val trimmed = raw?.trim().orEmpty()
                return if (trimmed.isNotEmpty() &&
                    trimmed.toByteArray(Charsets.UTF_8).size <= 32
                ) trimmed else XposedPrefs.Defaults.WIFI_SSID
            }

            /** A malformed BSSID would make the fake access point obviously bogus. */
            fun normalizeBssid(raw: String?): String {
                val trimmed = raw?.trim().orEmpty()
                return if (MAC_REGEX.matches(trimmed)) trimmed.lowercase()
                else XposedPrefs.Defaults.WIFI_BSSID
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContentView(binding.root)
        theme.applyStyle(com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar, true)
        setSupportActionBar(binding.toolbar)
        settingsFragment = if (savedInstanceState == null) {
            SettingsPreferenceFragment().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, it)
                    .commit()
            }
        } else {
            supportFragmentManager.findFragmentById(R.id.settings_container)
                    as? SettingsPreferenceFragment
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                mainViewModel.manualResult.collect { upd ->
                    if (upd != null) showUpdatePrompt(mainViewModel, upd)
                    else showToast(getString(R.string.up_to_date))
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            R.id.action_reset -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_reset)
                    .setMessage(R.string.settings_reset_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.settings_reset) { _, _ ->
                        PrefManager.resetToDefaults()
                        recreate()
                    }
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {

        // Favourites live in Room behind Hilt, which this fragment is not part of, so the DAO is
        // pulled from the application-scoped component directly.
        private val favoriteDao: FavoriteDao by lazy {
            EntryPointAccessors
                .fromApplication(requireContext().applicationContext, FavoritesEntryPoint::class.java)
                .favoriteDao()
        }

        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface FavoritesEntryPoint {
            fun favoriteDao(): FavoriteDao
        }

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { runTransfer(exporting = true, uri = it) } }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { runTransfer(exporting = false, uri = it) } }

        private fun runTransfer(exporting: Boolean, uri: android.net.Uri) {
            viewLifecycleOwner.lifecycleScope.launch {
                val context = requireContext()
                runCatching {
                    if (exporting) FavoritesTransfer.export(context, favoriteDao, uri)
                    else FavoritesTransfer.import(context, favoriteDao, uri)
                }.onSuccess { count ->
                    context.showToast(
                        getString(
                            if (exporting) R.string.favorites_exported
                            else R.string.favorites_imported,
                            count
                        )
                    )
                }.onFailure {
                    Timber.e(it, "favourites transfer failed")
                    context.showToast(getString(R.string.favorites_transfer_failed))
                }
            }
        }


        private companion object {
            /** Mode dropdown -> the slider it governs. */
            val MODE_TO_VALUE = mapOf(
                XposedPrefs.MODE_ACCURACY to "accuracy_value",
                XposedPrefs.MODE_VERTICAL_ACCURACY to "vertical_accuracy",
                XposedPrefs.MODE_ALTITUDE to "altitude",
                XposedPrefs.MODE_MSL to "msl",
                XposedPrefs.MODE_MSL_ACCURACY to "msl_accuracy",
                XposedPrefs.MODE_SPEED to "speed",
                XposedPrefs.MODE_SPEED_ACCURACY to "speed_accuracy",
                XposedPrefs.MODE_BEARING to "bearing",
                XposedPrefs.MODE_BEARING_ACCURACY to "bearing_accuracy",
                XposedPrefs.MODE_RANDOMIZE to "randomize_radius",
            )
        }


        /**
         * Wraps the standard adapter so the "what does this do?" button on each row is wired up.
         * Preference has no hook for extra views in its layout, so binding happens here.
         */
        override fun onCreateAdapter(
            preferenceScreen: androidx.preference.PreferenceScreen
        ): RecyclerView.Adapter<androidx.preference.PreferenceViewHolder> {
            return object : androidx.preference.PreferenceGroupAdapter(preferenceScreen) {
                override fun onBindViewHolder(
                    holder: androidx.preference.PreferenceViewHolder,
                    position: Int
                ) {
                    super.onBindViewHolder(holder, position)
                    val info = holder.itemView.findViewById<android.widget.ImageButton>(R.id.info_button)
                        ?: return
                    val preference = getItem(position) ?: return
                    val explanation = preference.summary
                    // Categories and rows with nothing to explain simply do not show the button.
                    if (explanation.isNullOrBlank()) {
                        info.visibility = View.GONE
                        return
                    }
                    info.visibility = View.VISIBLE
                    info.setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(preference.title)
                            .setMessage(explanation)
                            .setPositiveButton(R.string.action_ok, null)
                            .show()
                    }
                }
            } as RecyclerView.Adapter<androidx.preference.PreferenceViewHolder>
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>("accuracy_level")?.let {
                it.summary = "${PrefManager.accuracy} m"
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER;
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,");
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText));
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    try {
                        newValue as String?
                        preference.summary = "$newValue m"
                    } catch (n: NumberFormatException) {
                        n.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.enter_valid_input),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            findPreference<DropDownPreference>("dark_theme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }

            // android:summary="%s" only expands via a summary provider, which preferences backed
            // by a custom PreferenceDataStore do not get for free -- without this the rows show a
            // literal "%s".
            listOf("wifi_ssid", "wifi_bssid").forEach { key ->
                findPreference<EditTextPreference>(key)?.summaryProvider =
                    EditTextPreference.SimpleSummaryProvider.getInstance()
            }

            // The receiver is declared disabled in the manifest; enabling the setting is what
            // actually makes it able to receive anything.
            findPreference<SwitchPreferenceCompat>("enable_broadcast_control")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    ControlReceiver.setEnabled(requireContext(), newValue == true)
                    true
                }

            // Show the chosen mode on the row itself. Without this the summary carries only the
            // explanation, so the user cannot tell whether a field is Off, Fixed or Auto without
            // opening the dropdown.
            MODE_TO_VALUE.keys.forEach { modeKey ->
                val pref = findPreference<DropDownPreference>(modeKey) ?: return@forEach
                val explanation = pref.summary?.toString().orEmpty()
                pref.summaryProvider = Preference.SummaryProvider<DropDownPreference> { p ->
                    val label = p.entry ?: getString(R.string.mode_off)
                    if (explanation.isEmpty()) label else "$label\n$explanation"
                }
            }

            // A slider is only meaningful in Fixed mode: in Off the field is untouched, and in
            // Auto the value is derived, so showing a stale number would be misleading.
            MODE_TO_VALUE.forEach { (modeKey, valueKey) ->
                val slider = findPreference<Preference>(valueKey)
                val modePref = findPreference<DropDownPreference>(modeKey)
                fun sync(mode: Int) {
                    slider?.isVisible = mode == XposedPrefs.Mode.FIXED
                }
                sync(modePref?.value?.toIntOrNull() ?: XposedPrefs.Defaults.MODE)
                modePref?.setOnPreferenceChangeListener { _, newValue ->
                    sync((newValue as String).toIntOrNull() ?: XposedPrefs.Mode.OFF)
                    true
                }
            }

            // Applying a locale recreates the activity, so the change is visible immediately.
            findPreference<DropDownPreference>("language_tag")?.setOnPreferenceChangeListener { _, newValue ->
                val tag = newValue as String
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(tag)
                )
                true
            }

            // Backup and restore live here rather than in the favourites dialog, so that screen
            // stays as it was.
            findPreference<Preference>("favorites_export")?.setOnPreferenceClickListener {
                exportLauncher.launch("gps-setter-favourites.json")
                true
            }
            findPreference<Preference>("favorites_import")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                true
            }

            // Opens the target apps picker. Lives in settings so the map screen is untouched.
            findPreference<Preference>("target_apps")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), TargetAppsActivity::class.java))
                true
            }

            // System-level hooks are on by default now (stable and the useful path). The warning is
            // shown once -- the first time it is enabled from off -- as a heads-up, then never again.
            findPreference<SwitchPreferenceCompat>("system_hooked")?.setOnPreferenceChangeListener { pref, newValue ->
                if (newValue == true && !PrefManager.systemHooksWarned) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.system_hooks_warning_title)
                        .setMessage(R.string.system_hooks_warning_message)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.system_hooks_warning_confirm) { _, _ ->
                            PrefManager.systemHooksWarned = true
                            PrefManager.isSystemHooked = true
                            (pref as SwitchPreferenceCompat).isChecked = true
                        }
                        .show()
                    false // only commit once the user confirms in the dialog
                } else {
                    true
                }
            }

            // Floating map window: toggles the overlay service, needs the overlay permission.
            findPreference<Preference>("floating_map")?.setOnPreferenceClickListener {
                if (askOverlayPermission()) {
                    val intent = Intent(context, FloatingMapService::class.java)
                    if (FloatingMapService.isRunning) {
                        requireContext().stopService(intent)
                        requireContext().showToast(getString(R.string.floating_map_closed))
                    } else {
                        requireContext().startService(intent)
                        requireContext().showToast(getString(R.string.floating_map_opened))
                    }
                }
                true
            }

            findPreference<Preference>("check_now")?.setOnPreferenceClickListener {
                (activity as? ActivitySettings)?.let { host ->
                    host.showToast(getString(R.string.check_update))
                    host.mainViewModel.checkNow()
                }
                true
            }

            findPreference<Preference>("joystick_enabled")?.let {
                it.setOnPreferenceClickListener {
                    if (askOverlayPermission()){
                        if (isJoystickRunning()) {
                            requireContext().stopService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick disabled"
                        } else if (PrefManager.isStarted) {
                            requireContext().startService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick enabled"
                        } else {
                            requireContext().showToast(requireContext().getString(R.string.location_not_select))
                        }
                    }
                    true
                }
            }
        }

        /**
         * Shows only the preferences whose title or summary match [query]. Category headers hide
         * when nothing inside them survives, so the screen never shows an empty section.
         */
        fun filter(query: String) {
            val needle = query.trim().lowercase()
            val screen = preferenceScreen ?: return
            for (i in 0 until screen.preferenceCount) {
                val group = screen.getPreference(i)
                if (group is PreferenceCategory) {
                    var anyVisible = false
                    for (j in 0 until group.preferenceCount) {
                        val child = group.getPreference(j)
                        // A slider hidden because its mode is not Fixed must stay hidden.
                        if (child.key in MODE_TO_VALUE.values && !isFixedMode(child.key)) {
                            child.isVisible = false
                            continue
                        }
                        val match = needle.isEmpty() || child.matches(needle)
                        child.isVisible = match
                        anyVisible = anyVisible || match
                    }
                    group.isVisible = anyVisible
                } else {
                    group.isVisible = needle.isEmpty() || group.matches(needle)
                }
            }
        }

        private fun Preference.matches(needle: String): Boolean =
            title?.toString()?.lowercase()?.contains(needle) == true ||
                summary?.toString()?.lowercase()?.contains(needle) == true

        private fun isFixedMode(valueKey: String?): Boolean {
            val modeKey = MODE_TO_VALUE.entries.firstOrNull { it.value == valueKey }?.key
                ?: return true
            return findPreference<DropDownPreference>(modeKey)?.value?.toIntOrNull() ==
                XposedPrefs.Mode.FIXED
        }

        private fun isJoystickRunning(): Boolean = JoystickService.isRunning


        private fun askOverlayPermission() : Boolean {
            if (Settings.canDrawOverlays(context)){
                return true
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context?.applicationContext?.packageName}" ))
            requireContext().startActivity(intent)
            return false
        }


        private fun getCommaReplacerTextWatcher(editText: EditText): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun onTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun afterTextChanged(editable: Editable) {
                    val text = editable.toString()
                    if (text.contains(",")) {
                        editText.setText(text.replace(",", "."))
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }
    }
}