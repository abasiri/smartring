package dev.ring.companion.firmware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Xml
import dev.ring.companion.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks whether the ring's maker has published newer firmware. Installing is left to the
 * maker's app; this only tells you an update exists.
 *
 * The maker publishes one public file per model: `firmware/<model>.plist`, holding
 *  - a general release: `bNo`.`sNo` + `url`, limited by `allUpdateVersion` (which installed
 *    versions may update; empty = any) and `autoUpdateVersion` (which must update), and
 *  - a targeted release: `mac_bNo`.`mac_sNo` + `mac_url`, only for addresses listed in `mac`.
 * Nothing about the ring or user is sent; the model comes from the ring's advertised name.
 */
class FirmwareCheck(private val context: Context, private val prefs: Prefs) {

    enum class State { UpToDate, Available, Required, NoInfo, Failed }

    data class Result(val checkedMs: Long, val state: State, val latest: String?, val current: String?)

    private val _result = MutableStateFlow(load())
    val result: StateFlow<Result?> = _result

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** Model code, worked out once from the advertised name ("R11M 801B" → "R11M") and saved. */
    @SuppressLint("MissingPermission")
    fun model(address: String?): String? {
        prefs.ringModel?.let { return it }
        val advertised = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.name
        }.getOrNull() ?: prefs.ringName.value
        val model = advertised?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.length >= 2 }
        if (model != null) prefs.ringModel = model
        return model
    }

    /** Runs the check if the last one is older than [maxAgeMs]. */
    suspend fun checkIfStale(address: String?, firmware: String?, maxAgeMs: Long = 24 * 3600_000L) {
        val last = _result.value
        if (last == null || System.currentTimeMillis() - last.checkedMs > maxAgeMs || last.current != firmware) {
            check(address, firmware)
        }
    }

    suspend fun check(address: String?, firmware: String?) {
        if (_checking.value) return
        _checking.value = true
        try {
            val model = model(address)
            val current = firmware?.let(::parseVersion)
            val result = if (model == null || current == null) {
                Result(System.currentTimeMillis(), State.NoInfo, null, firmware)
            } else {
                val dict = withContext(Dispatchers.IO) { fetch("$BASE$model.plist") }
                if (dict == null) Result(System.currentTimeMillis(), State.Failed, null, firmware)
                else evaluate(dict, current, address).copy(current = firmware)
            }
            _result.value = result
            prefs.firmwareCheck = "${result.checkedMs}|${result.latest.orEmpty()}|${result.state.name}|${result.current.orEmpty()}"
        } finally {
            _checking.value = false
        }
    }

    private fun load(): Result? = prefs.firmwareCheck?.split('|')?.takeIf { it.size == 4 }?.let { f ->
        runCatching { Result(f[0].toLong(), State.valueOf(f[2]), f[1].ifEmpty { null }, f[3].ifEmpty { null }) }.getOrNull()
    }

    companion object {
        private const val BASE = "https://staticpage.ycaviation.com/firmware/"

        /** "2.12" → (2, 12). Our display pads the minor number, the maker's file doesn't; compare as numbers. */
        fun parseVersion(v: String): Pair<Int, Int>? {
            val p = v.trim().split('.')
            if (p.size < 2) return null
            val major = p[0].toIntOrNull() ?: return null
            val minor = p[1].toIntOrNull() ?: return null
            return major to minor
        }

        private fun newer(a: Pair<Int, Int>, b: Pair<Int, Int>) =
            a.first > b.first || (a.first == b.first && a.second > b.second)

        /** Same rules as the maker's app. */
        fun evaluate(d: Map<String, String>, current: Pair<Int, Int>, address: String?): Result {
            val now = System.currentTimeMillis()
            val cur = "${current.first}.${current.second}"

            // Targeted release for listed addresses
            val listed = address != null && d["mac"].orEmpty().split(',').any { it.trim().equals(address, ignoreCase = true) }
            val macVer = version(d["mac_bNo"], d["mac_sNo"])
            if (listed && macVer != null && d["mac_url"].orEmpty().isNotBlank() && newer(macVer, current)) {
                return Result(now, State.Available, "${macVer.first}.${macVer.second}", null)
            }

            // General release
            val ver = version(d["bNo"], d["sNo"])
            if (ver == null || d["url"].orEmpty().isBlank() || !newer(ver, current)) {
                return Result(now, State.UpToDate, ver?.let { "${it.first}.${it.second}" }, null)
            }
            val latest = "${ver.first}.${ver.second}"
            val required = d["autoUpdateVersion"].orEmpty().split(',').any { it.trim() == cur }
            if (required) return Result(now, State.Required, latest, null)
            val allowed = d["allUpdateVersion"].orEmpty().let { list -> list.isBlank() || list.split(',').any { it.trim() == cur } }
            return Result(now, if (allowed) State.Available else State.UpToDate, latest, null)
        }

        private fun version(b: String?, s: String?): Pair<Int, Int>? {
            val major = b?.trim()?.toIntOrNull() ?: return null
            val minor = s?.trim()?.toIntOrNull() ?: return null
            return major to minor
        }

        private fun fetch(url: String): Map<String, String>? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                if (c.responseCode != 200) null else parsePlist(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
        }.getOrNull()

        /** Flat `<dict>` of `<key>`/`<string>` pairs, which is all these files use. */
        fun parsePlist(xml: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            p.setInput(StringReader(xml))
            var key: String? = null
            var tag: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "string" && key != null) out[key] = ""
                    }
                    XmlPullParser.TEXT -> when (tag) {
                        "key" -> key = p.text.trim()
                        "string", "integer" -> if (key != null) out[key] = p.text.trim()
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "string" || p.name == "integer") key = null
                        tag = null
                    }
                }
            }
            return out
        }
    }
}
