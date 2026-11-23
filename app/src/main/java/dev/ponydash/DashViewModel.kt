package dev.ponydash

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ponydash.obd.BleTransport
import dev.ponydash.obd.Elm327
import dev.ponydash.obd.FordPids
import dev.ponydash.obd.K
import dev.ponydash.obd.ObdSession
import dev.ponydash.obd.Pids
import dev.ponydash.obd.Rate
import dev.ponydash.sensors.GMeter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

enum class Link { IDLE, SIM, CONNECTING, LIVE, RETRYING, REPLAY }

data class ConnState(val link: Link, val detail: String = "", val device: String? = null)

data class ScanItem(val name: String, val address: String, val rssi: Int) {
    val looksLikeObd: Boolean
        get() = OBD_NAME.containsMatchIn(name)

    companion object {
        private val OBD_NAME = Regex("obd|vlink|v-link|vgate|icar|elm|vlinker|obdii", RegexOption.IGNORE_CASE)
    }
}

/** Distance and fuel used since the trip was reset. */
data class Trip(val miles: Double = 0.0, val gallons: Double = 0.0, val seconds: Double = 0.0) {
    val mpg: Double? get() = if (gallons > 0.02) miles / gallons else null
}

@SuppressLint("MissingPermission")
class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ponydash", Context.MODE_PRIVATE)
    private val bt = app.getSystemService(BluetoothManager::class.java).adapter

    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val conn = MutableStateFlow(ConnState(Link.IDLE))
    val supported = MutableStateFlow<Set<Int>>(emptySet())
    val trip = MutableStateFlow(Trip())
    val scan = MutableStateFlow<List<ScanItem>>(emptyList())
    val scanning = MutableStateFlow(false)
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    val gmeter = GMeter(app)

    /** Last hour of every channel, on disk and in memory. */
    val recorder = FlightRecorder(app, viewModelScope, values, gmeter.state, conn)
    val exportMsg = MutableStateFlow<String?>(null)

    /** Gear, pedal/brake and launch/braking timers for the race screen. */
    val race = MutableStateFlow(RaceState())
    private val raceTracker = RaceTracker(prefs)
    fun resetRaceBests() { raceTracker.resetBests() }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(100) // first: replay and metric are declared further down
                val v = values.value
                if (v.isNotEmpty()) {
                    raceTracker.setMetric(metric.value)
                    val t = replay.value?.takeIf { !it.loading }?.pos ?: System.currentTimeMillis()
                    race.value = raceTracker.feed(v, gmeter.state.value.lon, t)
                } else if (race.value.gear != null || race.value.lastGear != null || race.value.pedal > 0) {
                    race.value = raceTracker.idle()
                }
            }
        }
    }

    fun exportLastHour() {
        viewModelScope.launch {
            exportMsg.value = "saving…"
            val msg = try {
                recorder.exportLastHour().also { logLine(it) }
                "saved → Download/PonyDash"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLine("Save failed: ${e.message}")
                "save failed"
            }
            exportMsg.value = msg
            delay(8_000)
            if (exportMsg.value == msg) exportMsg.value = null
        }
    }

    /** Raw adapter traffic, newest last (for the Matrix view). */
    val traffic = MutableStateFlow<List<String>>(emptyList())
    private val trafficBuf = ArrayDeque<String>()

    /** Recent samples per channel key, for sparklines. */
    val history = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    private val histBuf = HashMap<String, ArrayDeque<Float>>()
    private val minMax = HashMap<String, Pair<Double, Double>>()
    val extremes = MutableStateFlow<Map<String, Pair<Double, Double>>>(emptyMap())

    /** OBD requests answered per second. */
    val pollRate = MutableStateFlow(0.0)
    private var reqCount = 0
    private var rateT0 = 0L

    private fun onTraffic(cmd: String, resp: String) {
        recorder.event("> $cmd")
        resp.lines().forEach { if (it.isNotBlank()) recorder.event("< $it") }
        synchronized(trafficBuf) {
            trafficBuf.addLast("> $cmd")
            resp.lines().forEach { trafficBuf.addLast("< $it") }
            while (trafficBuf.size > 80) trafficBuf.removeFirst()
            traffic.value = trafficBuf.toList()
        }
        reqCount++
        val now = System.nanoTime()
        if (rateT0 == 0L) rateT0 = now
        val dt = (now - rateT0) / 1e9
        if (dt >= 2) {
            pollRate.value = reqCount / dt
            reqCount = 0
            rateT0 = now
        }
    }

    private fun record(vals: Map<String, Double>) {
        for ((k, v) in vals) {
            val d = histBuf.getOrPut(k) { ArrayDeque() }
            d.addLast(v.toFloat())
            while (d.size > 120) d.removeFirst()
            val mm = minMax[k]
            minMax[k] = if (mm == null) v to v else minOf(mm.first, v) to maxOf(mm.second, v)
        }
    }

    private fun publishHistory() {
        history.value = histBuf.mapValues { it.value.toList() }
        extremes.value = HashMap(minMax)
    }

    fun resetExtremes() {
        gmeter.resetPeaks()
        minMax.clear()
        extremes.value = emptyMap()
    }

    /** true = °C, bar, km/h, km, L/100 km. */
    val metric = MutableStateFlow(prefs.getBoolean("metric", true))

    /** "Auto" follows the system light/dark setting; otherwise a theme name. */
    private var themeChoice = prefs.getString("theme", "Auto") ?: "Auto"
    private var systemDark = true

    fun setSystemDark(dark: Boolean) {
        systemDark = dark
        applyTheme()
    }

    private fun applyTheme() {
        val auto = themeChoice == "Auto"
        dev.ponydash.ui.C.auto = auto
        dev.ponydash.ui.C.palette = dev.ponydash.ui.Themes.byName(
            if (auto) (if (systemDark) "Midnight" else "Daylight") else themeChoice,
        )
    }

    fun cycleTheme() {
        val names = listOf("Auto") + dev.ponydash.ui.Themes.all.map { it.name }
        themeChoice = names[(names.indexOf(themeChoice) + 1) % names.size]
        prefs.edit().putString("theme", themeChoice).apply()
        applyTheme()
    }

    fun toggleUnits() {
        metric.update { !it }
        prefs.edit().putBoolean("metric", metric.value).apply()
    }

    private var sourceJob: Job? = null
    private var session: ObdSession? = null
    private var lastTripTick = 0L
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Held by one poll cycle, or by a probe/console command for its whole run. */
    private val busLock = kotlinx.coroutines.sync.Mutex()

    val savedDevice: String? get() = prefs.getString("addr", null)

    init {
        gmeter.start()
    }

    fun logLine(s: String) {
        android.util.Log.i("PonyDash", s)
        recorder.event(s)
        val line = "${timeFmt.format(Date())}  $s"
        _log.update { (it + line).takeLast(400) }
    }

    /** Called once permissions are settled: resume the saved adapter, or show the simulator. */
    fun autoStart(permitted: Boolean) {
        if (sourceJob != null) return
        val addr = savedDevice
        if (permitted && addr != null) connect(addr, prefs.getString("name", null) ?: addr) else startSim()
    }

    // ---------- scanning ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            val item = ScanItem(name, r.device.address, r.rssi)
            scan.update { list ->
                (list.filterNot { it.address == item.address } + item)
                    .sortedWith(compareByDescending<ScanItem> { it.looksLikeObd }.thenByDescending { it.rssi })
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logLine("BLE scan failed ($errorCode)")
            scanning.value = false
        }
    }

    fun startScan() {
        val scanner = bt?.bluetoothLeScanner
        if (bt == null || !bt.isEnabled || scanner == null) {
            logLine("Bluetooth is off")
            return
        }
        scan.value = emptyList()
        scanning.value = true
        scanner.startScan(scanCb)
        viewModelScope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning.value) return
        scanning.value = false
        runCatching { bt?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    // ---------- sources ----------

    fun startSim() {
        stopSource()
        conn.value = ConnState(Link.SIM, "Simulated data")
        supported.value = Pids.all.map { it.code }.toSet()
        sourceJob = viewModelScope.launch {
            var t = 0.0
            while (isActive) {
                t += 0.1
                val pull = (sin(t / 6) + 1) / 2 // 0..1 slow throttle cycle
                // speed follows the throttle; the gearbox picks the gear that keeps rpm near the driver's ask
                val speed = 40 + pull * 70
                val ask = 1500 + pull * 4700
                val gear = (1..10).minBy { abs(Gearbox.rpmIn(it, speed) - ask) }
                val rpm = Gearbox.rpmIn(gear, speed) + sin(t * 3) * 20
                val map = 30 + pull * 200
                values.value = mapOf(
                    K.RPM to rpm,
                    K.SPEED to speed,
                    K.MAP to map,
                    K.BARO to 100.0,
                    K.MAF to 3 + pull * 140,
                    K.THROTTLE to pull * 100,
                    K.IAT to 32 + pull * 18,
                    K.VOLTS to 14.1 + sin(t) * 0.15,
                    K.COOLANT to 92 + sin(t / 30) * 3,
                    K.OIL to 98 + pull * 14,
                    K.AMBIENT to 22.0,
                    K.FUEL to 61.0,
                    K.MIL to 0.0,
                    K.DTC to 0.0,
                )
                values.update { base ->
                    base + mapOf(
                        "load" to 20 + pull * 75,
                        "abs_load" to 18 + pull * 90,
                        "timing" to 18 - pull * 12,
                        "stft1" to sin(t * 2) * 3,
                        "ltft1" to 1.6,
                        "lambda1" to 1.0 - pull * 0.18,
                        "cmd_lambda" to 1.0 - pull * 0.2,
                        "pedal_d" to pull * 90,
                        "cmd_throttle" to pull * 85,
                        "torque_act" to pull * 92,
                        "torque_dmd" to pull * 95,
                        "fuel_rate" to 0.8 + pull * 30,
                        "rail_kpa" to 5000 + pull * 15000,
                        "cat11" to 520 + pull * 200,
                        "cat12" to 430 + pull * 120,
                        "evap" to 12.0,
                        "runtime" to t,
                        "dist_clr" to 4210.0,
                        "warmups" to 87.0,
                        "torque_ref" to 474.0,
                        "odometer" to 12873.4,
                        K.TRANS to 70 + pull * 15,
                        K.KNOCK to if (pull > 0.9) 1.5 else 0.0,
                        K.TIRE_FL to 197.0, K.TIRE_FR to 200.5, K.TIRE_RR to 198.6, K.TIRE_RL to 197.0,
                    )
                }
                values.update { Pids.derive(it + mapOf(K.IAT_B to 30 + pull * 22, "egt11" to 450 + pull * 420, "exh_kpa" to 103 + pull * 95)) }
                record(values.value)
                if ((t * 10).toInt() % 3 == 0) {
                    publishHistory()
                    onTraffic("010C1", "410C%04X".format((rpm * 4).toInt()))
                    onTraffic("010B1", "410B%02X".format(map.toInt().coerceIn(0, 255)))
                }
                tripTick()
                delay(100)
            }
        }
    }

    fun connect(address: String, name: String) {
        stopScan()
        stopSource()
        prefs.edit().putString("addr", address).putString("name", name).apply()
        supported.value = emptySet()
        sourceJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                conn.value = ConnState(Link.CONNECTING, if (attempt == 1) "Connecting" else "Reconnecting (try $attempt)", name)
                var transport: BleTransport? = null
                try {
                    val device = bt?.getRemoteDevice(address) ?: error("Bluetooth unavailable")
                    transport = BleTransport(getApplication(), device, ::logLine).also { it.open() }
                    val s = ObdSession(Elm327(transport, ::onTraffic), ::logLine)
                    s.init()
                    session = s
                    supported.value = s.supported
                    attempt = 0
                    conn.value = ConnState(Link.LIVE, "Live", name)
                    pollLoop(s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logLine("Link error: ${e.message}")
                    conn.value = ConnState(Link.RETRYING, e.message ?: "Error", name)
                } finally {
                    session = null
                    transport?.close()
                }
                values.value = emptyMap()
                delay(if (attempt < 3) 3_000 else 10_000)
            }
        }
    }

    // ---------- background ----------

    private var bgJob: Job? = null
    private var pausedAddr: Pair<String, String>? = null

    /** Out of sight for a minute: drop the adapter so Bluetooth and the CPU can sleep. */
    fun onBackground() {
        bgJob?.cancel()
        if (conn.value.link == Link.REPLAY) {
            replay.update { it?.copy(playing = false) }
            return
        }
        bgJob = viewModelScope.launch {
            delay(60_000)
            val addr = savedDevice
            if (addr != null && conn.value.link != Link.SIM && conn.value.link != Link.IDLE) {
                pausedAddr = addr to (prefs.getString("name", null) ?: addr)
                logLine("In background for a minute; pausing the OBD link")
            }
            stopSource()
            conn.value = ConnState(Link.IDLE, "Paused")
        }
    }

    fun onForeground() {
        bgJob?.cancel()
        bgJob = null
        if (sourceJob != null) return
        val p = pausedAddr
        pausedAddr = null
        when {
            p != null -> connect(p.first, p.second)
            conn.value.detail == "Paused" -> startSim()
        }
    }

    // ---------- replay ----------

    val replay = MutableStateFlow<ReplayState?>(null)
    val recordings = MutableStateFlow<List<Recording>>(emptyList())
    private var replayData: ReplayData? = null
    @Volatile private var replaySeekTo: Long? = null

    fun refreshRecordings() {
        viewModelScope.launch {
            recordings.value = withContext(Dispatchers.IO) { ReplayData.list(getApplication(), recorder.dir) }
        }
    }

    /** Zoomed-plot data: the replay around its playhead, otherwise the live recorder. */
    fun series(key: String, spanMs: Long): Series {
        val d = replayData
        val st = replay.value
        return if (d != null && st != null && !st.loading) d.series(key, st.pos, spanMs) else recorder.series(key, spanMs)
    }

    fun startReplay(rec: Recording) {
        stopSource()
        replay.value = ReplayState(rec.name, 0, 0, 0, playing = false, speed = 1, loading = true)
        conn.value = ConnState(Link.REPLAY, "Loading ${rec.name}")
        sourceJob = viewModelScope.launch {
            val d = try {
                withContext(Dispatchers.IO) { ReplayData.parse(rec.name, rec.open()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLine("Replay failed: ${e.message}")
                replay.value = null
                startSim()
                return@launch
            }
            replayData = d
            logLine("Replaying ${rec.name}: ${d.size} samples")
            histBuf.clear()
            minMax.clear()
            supported.value = Pids.all.map { it.code }.toSet()
            gmeter.external = true
            conn.value = ConnState(Link.REPLAY, "Replay", rec.name)
            val gLat = d.col("g_lat")
            val gLon = d.col("g_lon")
            var vt = d.t[0]
            var idx = -1
            replay.value = ReplayState(rec.name, d.t[0], d.t[d.size - 1], vt, playing = true, speed = 1)
            while (isActive) {
                val st = replay.value ?: break
                replaySeekTo?.let { vt = it.coerceIn(d.t[0], d.t[d.size - 1]); replaySeekTo = null; idx = -1 }
                if (st.playing) {
                    vt += 100L * st.speed
                    // jump over stops where the engine was off
                    val i = d.indexAt(vt)
                    if (i + 1 < d.size && d.t[i + 1] - d.t[i] > 5_000 && vt - d.t[i] > 1_000) vt = d.t[i + 1]
                }
                val i = d.indexAt(vt)
                if (i != idx) {
                    idx = i
                    val row = Pids.derive(d.row(i))
                    values.value = row
                    record(row)
                    publishHistory()
                    if (gLat != null && gLon != null) {
                        gmeter.feed(gLat[i].takeIf { !it.isNaN() } ?: 0f, gLon[i].takeIf { !it.isNaN() } ?: 0f)
                    }
                }
                val atEnd = vt >= d.t[d.size - 1]
                replay.value = st.copy(pos = vt.coerceAtMost(d.t[d.size - 1]), playing = st.playing && !atEnd)
                delay(100)
            }
        }
    }

    fun replayToggle() = replay.update { it?.copy(playing = !it.playing) }
    fun replaySpeed(x: Int) = replay.update { it?.copy(speed = x) }
    fun replaySeek(ms: Long) {
        replaySeekTo = ms
        replay.update { it?.copy(pos = ms) }
    }

    /** Leave replay and go back to the car (or the simulator). */
    fun stopReplay() {
        stopSource()
        histBuf.clear()
        minMax.clear()
        publishHistory()
        val addr = savedDevice
        if (addr != null) connect(addr, prefs.getString("name", null) ?: addr) else startSim()
    }

    fun forgetDevice() {
        prefs.edit().remove("addr").remove("name").apply()
        startSim()
    }

    private fun stopSource() {
        sourceJob?.cancel()
        replay.value = null
        replayData = null
        gmeter.external = false
        sourceJob = null
        session = null
        values.value = emptyMap()
        lastTripTick = 0L
    }

    private suspend fun pollLoop(s: ObdSession) {
        val std = Rate.entries.associateWith { r -> Pids.all.filter { it.rate == r && it.code in s.supported } }
        // Ford reads that fail twice in a row are dropped for this session.
        val fordFails = HashMap<String, Int>()
        var cycle = 0
        while (true) {
            fun due(r: Rate) = when (r) {
                Rate.FAST -> true
                Rate.MEDIUM -> cycle % 3 == 0
                Rate.SLOW -> cycle % 12 == 0
                Rate.RARE -> cycle % 60 == 0
            }
            var did = 0
            busLock.lock()
            try {
            for (r in Rate.entries) {
                if (!due(r)) continue
                for (p in std.getValue(r)) {
                    val v = s.read(p) ?: continue
                    values.update { Pids.derive(it + v) }
                    record(if (K.MAP in v) v + (K.BOOST to values.value.getValue(K.BOOST)) else v)
                    did++
                }
            }
            // Ford reads last, grouped by header so the RARE TPMS batch costs one ATSH switch each way.
            for (f in FordPids.all.filter { due(it.rate) && (fordFails[it.did] ?: 0) < 2 }.sortedBy { it.header }) {
                val v = s.readFord(f)
                if (v == null) {
                    fordFails[f.did] = (fordFails[f.did] ?: 0) + 1
                    continue
                }
                fordFails[f.did] = 0
                values.update { it + v }
                record(v)
                did++
            }
            } finally {
                busLock.unlock()
            }
            publishHistory()
            tripTick()
            cycle++
            if (did == 0) delay(500)
        }
    }

    /** Integrate speed and MAF into distance and fuel. */
    private fun tripTick() {
        val now = System.nanoTime()
        val prev = lastTripTick
        lastTripTick = now
        if (prev == 0L) return
        val dt = (now - prev) / 1e9
        if (dt <= 0 || dt > 5) return
        val v = values.value
        if ((v[K.RPM] ?: 0.0) < 100) return
        val mph = (v[K.SPEED] ?: 0.0) * 0.621371
        // Prefer the ECU's fuel rate (L/h). The 2.3 EcoBoost reports no MAF PID;
        // MAF g/s ÷ stoich 14.7 ÷ 745 g/L is the fallback for other cars.
        val galPerSec = v["fuel_rate"]?.let { it / 3600 / 3.78541 }
            ?: ((v[K.MAF] ?: 0.0) / 14.7 / 745.0 / 3.78541)
        trip.update { Trip(it.miles + mph * dt / 3600, it.gallons + galPerSec * dt, it.seconds + dt) }
    }

    fun resetTrip() {
        trip.value = Trip()
    }

    override fun onCleared() {
        gmeter.stop()
        stopScan()
    }
}

/** Unit helpers (car reports metric). */
fun cToF(c: Double) = c * 9 / 5 + 32
fun kpaToPsi(k: Double) = k * 0.145038
fun kmhToMph(k: Double) = k * 0.621371
fun boostPsi(v: Map<String, Double>): Double? {
    val map = v[K.MAP] ?: return null
    return kpaToPsi(map - (v[K.BARO] ?: 101.3))
}
