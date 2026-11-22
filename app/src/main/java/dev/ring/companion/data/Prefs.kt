package dev.ring.companion.data

import android.content.Context
import dev.ring.companion.protocol.JieliCipher
import dev.ring.companion.protocol.hex
import dev.ring.companion.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small user settings. Each one is also exposed as a StateFlow so the UI updates live. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ring", Context.MODE_PRIVATE)

    private val _ringAddress = MutableStateFlow(sp.getString("address", null))
    val ringAddress: StateFlow<String?> = _ringAddress
    private val _ringName = MutableStateFlow(sp.getString("name", null))
    val ringName: StateFlow<String?> = _ringName

    val autoReconnect = bool("autoReconnect", true)
    /** true: stay connected all the time. false: connect only while the app is open (and during workouts). */
    val keepInBackground = bool("keepInBackground", true)
    /** The user tapped Disconnect (e.g. to use another app with the ring). No reconnecting until they tap Connect. */
    val connectionPaused = bool("connectionPaused", false)
    val screenOnInWorkout = bool("screenOn", true)
    val moveReminders = bool("moveReminders", false)

    /** How often the ring takes its background reading (heart rate, blood oxygen, HRV…), in minutes. */
    private val _measureInterval = MutableStateFlow(sp.getInt("measureInterval", 30))
    val measureInterval: StateFlow<Int> = _measureInterval
    fun setMeasureInterval(min: Int) {
        sp.edit().putInt("measureInterval", min).apply()
        _measureInterval.value = min
    }
    /** Move reminders run from this hour (inclusive) to [moveEndHour] (exclusive). */
    val moveStartHour: Int get() = sp.getInt("moveStart", 9)
    val moveEndHour: Int get() = sp.getInt("moveEnd", 21)

    private val _maxHr = MutableStateFlow(sp.getInt("maxHr", 186))
    val maxHr: StateFlow<Int> = _maxHr

    val stepGoal: Int get() = sp.getInt("stepGoal", 8000)
    val moveGoalKcal: Int get() = sp.getInt("moveGoal", 400)
    val exerciseGoalMin: Int get() = sp.getInt("exerciseGoal", 30)
    val sleepGoalMin: Int get() = sp.getInt("sleepGoal", 480)

    val linkKey: ByteArray
        get() = sp.getString("linkKey", null)?.let { runCatching { hex(it) }.getOrNull() }
            ?.takeIf { it.size == 16 } ?: JieliCipher.DEFAULT_LINK_KEY

    fun saveRing(address: String, name: String?) {
        sp.edit().putString("address", address).putString("name", name).apply()
        _ringAddress.value = address
        _ringName.value = name
    }

    fun updateRingName(name: String) {
        sp.edit().putString("name", name).apply()
        _ringName.value = name
    }

    /** Model code used for firmware files, e.g. "R11M" (first word of the advertised name). */
    var ringModel: String?
        get() = sp.getString("model", null)
        set(v) { sp.edit().putString("model", v).apply() }

    /** Last firmware version the ring reported, e.g. "2.12". */
    var lastFirmware: String?
        get() = sp.getString("lastFirmware", null)
        set(v) { sp.edit().putString("lastFirmware", v).apply() }

    /** Last firmware check result, as "checkedMs|latest|state" (see FirmwareCheck). */
    var firmwareCheck: String?
        get() = sp.getString("firmwareCheck", null)
        set(v) { sp.edit().putString("firmwareCheck", v).apply() }

    fun forgetRing() {
        sp.edit().remove("address").remove("name").remove("model").remove("firmwareCheck").remove("lastFirmware").apply()
        _ringAddress.value = null
        _ringName.value = null
    }

    fun setMaxHr(v: Int) {
        val c = v.coerceIn(120, 230)
        sp.edit().putInt("maxHr", c).apply()
        _maxHr.value = c
    }

    /** Last step/distance/calorie totals from the ring, so they show before it reconnects. */
    var lastActivity: String?
        get() = sp.getString("lastActivity", null)
        set(v) { sp.edit().putString("lastActivity", v).apply() }

    fun setLinkKey(key: ByteArray) = sp.edit().putString("linkKey", key.toHex("")).apply()

    inner class BoolPref(private val key: String, default: Boolean) {
        private val _state = MutableStateFlow(sp.getBoolean(key, default))
        val state: StateFlow<Boolean> = _state
        val value: Boolean get() = _state.value
        fun set(v: Boolean) {
            sp.edit().putBoolean(key, v).apply()
            _state.value = v
        }
    }

    private fun bool(key: String, default: Boolean) = BoolPref(key, default)
}
