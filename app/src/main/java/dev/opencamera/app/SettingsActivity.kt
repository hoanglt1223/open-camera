/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.KeyCharacterMap
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.annotation.Px
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.opencamera.app.ui.skin.SkinRepository
import androidx.preference.SwitchPreference
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.opencamera.app.ext.setOffset
import dev.opencamera.app.models.HardwareKey
import dev.opencamera.app.models.Permission
import dev.opencamera.app.models.PermissionState
import dev.opencamera.app.repositories.PreferencesRepository.Companion.preferenceStringToGestureAction
import dev.opencamera.app.repositories.PreferencesRepository.Companion.sharedPreferencesKeyPrefix
import dev.opencamera.app.repositories.PreferencesRepository.Companion.toPreferenceString
import dev.opencamera.app.utils.CameraSoundsUtils
import dev.opencamera.app.utils.PermissionsManager
import dev.opencamera.app.repositories.DeviceProfileRepository
import dev.opencamera.profiles.ProfileResolver

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    // Views
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val coordinatorLayout by lazy { findViewById<CoordinatorLayout>(R.id.coordinatorLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    // Permissions manager
    private val permissionsManager = PermissionsManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()
        PerfTrace.endAfterNextFrame(window.decorView, PerfTrace.OPEN_SETTINGS)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, RootSettingsFragment())
                .commit()
        }

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    abstract class SettingsFragment(
        @XmlRes private val preferencesResId: Int,
    ) : PreferenceFragmentCompat() {
        private val settingsActivity
            get() = activity as SettingsActivity

        protected val permissionsManager
            get() = settingsActivity.permissionsManager

        @Px
        private var appBarOffset = -1

        private val offsetChangedListener = AppBarLayout.OnOffsetChangedListener { _, i ->
            appBarOffset = -i
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            settingsActivity.appBarLayout.apply {
                if (appBarOffset != -1) {
                    setOffset(appBarOffset, settingsActivity.coordinatorLayout)
                } else {
                    setExpanded(true, false)
                }

                setLiftOnScrollTargetView(listView)

                addOnOffsetChangedListener(offsetChangedListener)
            }
        }

        override fun onDestroyView() {
            settingsActivity.appBarLayout?.apply {
                removeOnOffsetChangedListener(offsetChangedListener)

                setLiftOnScrollTargetView(null)
            }

            super.onDestroyView()
        }

        @CallSuper
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(preferencesResId, rootKey)
        }

        @CallSuper
        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?
        ) = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false

            ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                updatePadding(
                    bottom = insets.bottom,
                    left = insets.left,
                    right = insets.right,
                )

                windowInsets
            }
        }
    }

    class RootSettingsFragment : SettingsFragment(R.xml.root_preferences) {
        private val enableZsl by lazy { findPreference<SwitchPreference>("enable_zsl")!! }
        private val photoCaptureMode by lazy {
            findPreference<ListPreference>("photo_capture_mode")!!
        }
        private val saveLocation by lazy { findPreference<SwitchPreference>("save_location") }
        private val shutterSound by lazy { findPreference<SwitchPreference>("shutter_sound") }

        private val photoCaptureModePreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference, newValue ->
                val currentPhotoCaptureMode = if (preference == photoCaptureMode) {
                    newValue as String
                } else {
                    photoCaptureMode.value
                }

                val enableZslCanBeEnabled = currentPhotoCaptureMode == "minimize_latency"
                enableZsl.isChecked = enableZsl.isChecked && enableZslCanBeEnabled
                enableZsl.isEnabled = enableZslCanBeEnabled

                true
            }

        @Suppress("UnsafeOptInUsageError")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            saveLocation?.let {
                // Reset location back to off if permissions aren't granted
                it.isChecked = it.isChecked && permissionsManager.permissionState(
                    Permission.LOCATION
                ) == PermissionState.GRANTED
                it.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        if (newValue as Boolean) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result = permissionsManager.requestPermission(
                                    Permission.LOCATION
                                )

                                if (result != PermissionState.GRANTED) {
                                    saveLocation?.isChecked = false
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.save_location_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        true
                    }
            }
            shutterSound?.isVisible = !CameraSoundsUtils.mustPlaySounds

            // Picking a skin by hand gives the skin layer another chance after a fallback.
            findPreference<ListPreference>(SkinRepository.KEY_SKIN)?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    SkinRepository.get(requireContext()).crashGuard.clear()
                    true
                }

            findPreference<ListPreference>(dev.opencamera.app.multiframe.ImageQuality.KEY)?.let { pref ->
                val iq = dev.opencamera.app.multiframe.ImageQuality
                val manager = requireContext().getSystemService(android.hardware.camera2.CameraManager::class.java)
                val mp = iq.firstBackCamera(manager)?.let { iq.megapixels(manager, it) }
                val choices = iq.choices(mp)
                pref.entries = choices.map { iq.label(requireContext(), it, mp) }.toTypedArray()
                pref.entryValues = choices.map { it.name }.toTypedArray()
                if (pref.value !in pref.entryValues) pref.value = choices.first().name
                pref.summaryProvider = Preference.SummaryProvider<ListPreference> {
                    "${it.entry}\n${getString(R.string.image_quality_summary)}"
                }
            }

            // Photo capture mode
            photoCaptureMode.onPreferenceChangeListener = photoCaptureModePreferenceChangeListener
            enableZsl.isEnabled = photoCaptureMode.value == "minimize_latency"

            findPreference<ListPreference>(DeviceProfileRepository.PINNED_PROFILE_KEY)?.let {
                setUpDeviceProfilePreference(it)
            }

            findPreference<Preference>("export_device_profile")?.setOnPreferenceClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = requireContext().applicationContext as ApertureApplication
                    val json = app.deviceProfileRepository.exportJson()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_device_profile_title))
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    startActivity(Intent.createChooser(send, null))
                }
                true
            }

            findPreference<Preference>("share_crash_report")?.setOnPreferenceClickListener {
                val text = CrashLog.latest(CrashLog.dir(requireContext()))
                if (text == null) {
                    Toast.makeText(requireContext(), R.string.share_crash_report_none, Toast.LENGTH_SHORT).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_crash_report_title))
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching { startActivity(Intent.createChooser(send, null)) }
                }
                true
            }
        }
    }

    class GesturesSettingsFragment : SettingsFragment(R.xml.gestures_preferences) {
        // Preferences
        private val singleButtonsPreferenceCategory by lazy {
            findPreference<PreferenceCategory>("single_buttons")
        }

        // Input device listener
        private val inputDeviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                recheckKeys()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                recheckKeys()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                recheckKeys()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            val context = requireContext()

            for (hardwareKey in HardwareKey.entries) {
                val actionPreference = ListPreference(context, null).apply {
                    key = hardwareKey.actionSharedPreferenceKey
                    setTitle(hardwareKey.actionPreferenceTitleStringResId)
                    setDialogTitle(hardwareKey.actionPreferenceTitleStringResId)
                    when {
                        hardwareKey.supportsDefault && hardwareKey.isTwoWayKey -> {
                            setEntries(R.array.gesture_actions_entries)
                            setEntryValues(R.array.gesture_actions_values)
                        }

                        hardwareKey.supportsDefault && !hardwareKey.isTwoWayKey -> {
                            setEntries(R.array.gesture_actions_no_two_way_entries)
                            setEntryValues(R.array.gesture_actions_no_two_way_values)
                        }

                        !hardwareKey.supportsDefault && hardwareKey.isTwoWayKey -> {
                            setEntries(R.array.gesture_actions_no_two_way_entries)
                            setEntryValues(R.array.gesture_actions_no_two_way_values)
                        }

                        else -> {
                            setEntries(R.array.gesture_actions_no_default_no_two_way_entries)
                            setEntryValues(R.array.gesture_actions_no_default_no_two_way_values)
                        }
                    }
                    setDefaultValue(hardwareKey.defaultAction.toPreferenceString())
                    isIconSpaceReserved = false
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }

                if (!hardwareKey.isTwoWayKey) {
                    singleButtonsPreferenceCategory?.addPreference(actionPreference)
                } else {
                    val invertPreference = SwitchPreference(context, null).apply {
                        key = hardwareKey.invertSharedPreferenceKey
                        setTitle(hardwareKey.invertPreferenceTitleStringResId!!)
                        setSummary(hardwareKey.invertPreferenceSummaryStringResId!!)
                        setDefaultValue(false)
                        isIconSpaceReserved = false
                    }

                    val keyCategory = PreferenceCategory(context, null).apply {
                        key = hardwareKey.sharedPreferencesKeyPrefix
                        setTitle(hardwareKey.preferenceCategoryTitleStringResId!!)
                        isIconSpaceReserved = false
                    }

                    preferenceScreen.addPreference(keyCategory)

                    keyCategory.addPreference(actionPreference)
                    keyCategory.addPreference(invertPreference)

                    actionPreference.setOnPreferenceChangeListener { _, newValue ->
                        val value = newValue as String
                        val gestureAction = preferenceStringToGestureAction(value) ?: run {
                            Log.wtf(LOG_TAG, "Got invalid gesture action $value")
                            null
                        }

                        val enableInvert = gestureAction?.isTwoWayAction ?: true

                        invertPreference.isEnabled = enableInvert
                        if (!enableInvert) {
                            invertPreference.isChecked = false
                        }

                        true
                    }
                    actionPreference.onPreferenceChangeListener!!.onPreferenceChange(
                        actionPreference, actionPreference.value
                    )
                }
            }

            recheckKeys()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val inputManager = requireContext().getSystemService(InputManager::class.java)

            recheckKeys()

            inputManager.registerInputDeviceListener(inputDeviceListener, null)
        }

        override fun onDestroyView() {
            val inputManager = requireContext().getSystemService(InputManager::class.java)

            inputManager.unregisterInputDeviceListener(inputDeviceListener)

            super.onDestroyView()
        }

        private fun recheckKeys() {
            var singleKeysPresent = false

            for (hardwareKey in HardwareKey.entries) {
                val present = KeyCharacterMap.deviceHasKeys(
                    mutableListOf(hardwareKey.firstKeycode).apply {
                        hardwareKey.secondKeycode?.let {
                            add(it)
                        }
                    }.toIntArray()
                ).all { it }

                if (hardwareKey.isTwoWayKey) {
                    val keyCategory = findPreference<PreferenceCategory>(
                        hardwareKey.sharedPreferencesKeyPrefix
                    )

                    keyCategory?.isVisible = present
                } else {
                    val actionPreference = findPreference<ListPreference>(
                        hardwareKey.actionSharedPreferenceKey
                    )

                    actionPreference?.isVisible = present

                    singleKeysPresent = singleKeysPresent || present
                }
            }

            singleButtonsPreferenceCategory?.isVisible = singleKeysPresent
        }

        private val HardwareKey.actionSharedPreferenceKey: String
            get() = "${sharedPreferencesKeyPrefix}_action"

        private val HardwareKey.invertSharedPreferenceKey: String
            get() = "${sharedPreferencesKeyPrefix}_invert"
    }

    class ProcessingSettingsFragment : SettingsFragment(R.xml.processing_preferences)

    companion object {
        private val LOG_TAG = SettingsActivity::class.simpleName!!
    }
}

/**
 * Lists the bundled profiles (matching ones first) so the user can pin one, keep the best
 * match, use only the scanned profile, or turn profiles off. The choice is stored locally.
 */
private fun PreferenceFragmentCompat.setUpDeviceProfilePreference(preference: ListPreference) {
    val repository = (requireContext().applicationContext as ApertureApplication)
        .deviceProfileRepository
    preference.isEnabled = false
    lifecycleScope.launch {
        val current = repository.profile()
        val profiles = repository.bundledProfiles()
            .sortedWith(compareBy({ it.id != current.id }, { it.name ?: it.id }))
        preference.entries = (listOf(
            getString(R.string.device_profile_best_match),
            getString(R.string.device_profile_auto),
            getString(R.string.device_profile_off),
        ) + profiles.map { it.name?.let { name -> "$name (${it.id})" } ?: it.id }).toTypedArray()
        preference.entryValues = (listOf(
            "", ProfileResolver.AUTO_ID, DeviceProfileRepository.PINNED_OFF,
        ) + profiles.map { it.id }).toTypedArray()
        preference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        preference.isEnabled = true
        preference.setOnPreferenceChangeListener { _, _ ->
            // Stored first by the preference; resolve again on the next main-thread turn.
            lifecycleScope.launch(Dispatchers.Main) { repository.refresh() }
            true
        }
    }
}
