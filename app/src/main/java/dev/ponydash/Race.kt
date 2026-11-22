package dev.ponydash

import android.content.SharedPreferences
import dev.ponydash.obd.K
import kotlin.math.abs
import kotlin.math.ln

/**
 * Gearbox model for the 2025 Mustang EcoBoost: Ford 10R80 ratios with the
 * 3.15 axle. RPM ÷ km/h = ratio × 25.6. 25.6 is the best fit to the 22 Nov drive,
 * where 73 % of moving samples fall within 3 % of one of these gears.
 */
object Gearbox {
    val RATIOS = doubleArrayOf(4.70, 2.99, 2.15, 1.80, 1.52, 1.28, 1.00, 0.85, 0.69, 0.64)
    const val K_RPM_PER_KMH = 25.6

    /** Shift lights start at [LIGHTS_FROM], ask for the shift at [SHIFT], and flash at [LIMIT]. */
    const val LIGHTS_FROM = 4000.0
    const val SHIFT = 6000.0
    const val LIMIT = 6300.0

    /** Gear 1..10 when rpm/speed matches a ratio within 4 %, else null (shifting, converter slip, stopped). */
    fun estimate(rpm: Double?, kmh: Double?): Int? {
        if (rpm == null || kmh == null || kmh < 8 || rpm < 600) return null
        val r = rpm / kmh / K_RPM_PER_KMH
        var best = -1
        var err = 1.0
        for (i in RATIOS.indices) {
            val e = abs(ln(r / RATIOS[i]))
            if (e < err) { err = e; best = i }
        }
        return if (err < 0.04) best + 1 else null
    }

    /** RPM after an upshift from [gear] at the current speed. */
    fun rpmIn(gear: Int, kmh: Double) = kmh * K_RPM_PER_KMH * RATIOS[(gear - 1).coerceIn(0, RATIOS.size - 1)]
}

data class Run(val seconds: Double? = null, val meters: Double? = null, val at: Long = 0)

data class RaceState(
    val gear: Int? = null,
    /** Last gear seen; shown dimmed while the estimate is lost mid-shift. */
    val lastGear: Int? = null,
    /** Pedal travel 0..1 above its resting position. */
    val pedal: Double = 0.0,
    /** Braking effort 0..1, from deceleration (no brake-pressure PID): 1.0 = 1 g. */
    val brake: Double = 0.0,
    val peakBoostKpa: Double = 0.0,
    val topSpeedKmh: Double = 0.0,
    /** Live timer while a launch is in progress, seconds. */
    val launchLive: Double? = null,
    val launch: Run = Run(),
    val launchBest: Run = Run(),
    val brakeLive: Double? = null,
    val stop: Run = Run(),
    val stopBest: Run = Run(),
)

/**
 * Track-day bookkeeping from the OBD stream: gear, pedal/brake, peak boost,
 * and automatic launch (0–100 km/h or 0–60 mph) and braking (100–0 / 60–0)
 * timers. Fed at ~10 Hz with the car's clock (the replay clock during replay).
 */
class RaceTracker(private val prefs: SharedPreferences) {
    private var metric = true
    private val target get() = if (metric) 100.0 else 96.5606 // 60 mph

    private var st = RaceState(
        launchBest = Run(prefs.getFloat("best_launch_${unitKey()}", -1f).takeIf { it > 0 }?.toDouble()),
        stopBest = Run(meters = prefs.getFloat("best_stop_${unitKey()}", -1f).takeIf { it > 0 }?.toDouble()),
    )
    private fun unitKey() = if (metric) "kmh" else "mph"

    private var pedalRest = 14.9
    private var pedalMax = 70.0

    private var prevT = 0L
    private var prevV = 0.0
    private var stillSince = 0L
    private var launchT0 = 0L
    private var brakeT0 = 0L
    private var brakeDist = 0.0
    private var brakeMin = 0.0

    /** Drop any run in progress: the data stopped, or time jumped (replay seek, reconnect). */
    private fun breakRuns() {
        launchT0 = 0; brakeT0 = 0; stillSince = 0; prevT = 0
        st = st.copy(launchLive = null, brakeLive = null)
    }

    /** No data: clear the live readouts but keep results and bests. */
    fun idle(): RaceState {
        breakRuns()
        st = st.copy(gear = null, lastGear = null, pedal = 0.0, brake = 0.0)
        return st
    }

    fun setMetric(m: Boolean) {
        if (m == metric) return
        metric = m
        st = st.copy(
            launch = Run(), stop = Run(), launchLive = null, brakeLive = null,
            launchBest = Run(prefs.getFloat("best_launch_${unitKey()}", -1f).takeIf { it > 0 }?.toDouble()),
            stopBest = Run(meters = prefs.getFloat("best_stop_${unitKey()}", -1f).takeIf { it > 0 }?.toDouble()),
        )
        launchT0 = 0; brakeT0 = 0
    }

    fun resetBests() {
        prefs.edit().remove("best_launch_kmh").remove("best_launch_mph").remove("best_stop_kmh").remove("best_stop_mph").apply()
        st = st.copy(launchBest = Run(), stopBest = Run(), launch = Run(), stop = Run(), peakBoostKpa = 0.0, topSpeedKmh = 0.0)
    }

    fun feed(v: Map<String, Double>, gLon: Float, t: Long): RaceState {
        val kmh = v[K.SPEED]
        val rpm = v[K.RPM]
        val gear = Gearbox.estimate(rpm, kmh)

        val p = v["pedal_d"]
        if (p != null) {
            if (p < pedalRest && p > 5) pedalRest = p
            if (p > pedalMax) pedalMax = p
        }
        val pedal = p?.let { ((it - pedalRest) / (pedalMax - pedalRest)).coerceIn(0.0, 1.0) }
            ?: v["cmd_throttle"]?.let { (it / 100).coerceIn(0.0, 1.0) } ?: 0.0
        val brake = (-gLon.toDouble()).coerceIn(0.0, 1.2) / 1.0

        var s = st.copy(
            gear = gear,
            lastGear = gear ?: st.lastGear.takeIf { (kmh ?: 0.0) >= 8 },
            pedal = pedal,
            brake = if (brake > 0.08) brake else 0.0,
            peakBoostKpa = maxOf(st.peakBoostKpa, v[K.BOOST] ?: 0.0),
            topSpeedKmh = maxOf(st.topSpeedKmh, kmh ?: 0.0),
        )

        // a jump in time or an impossible change in speed (> 1.5 g) is a seek or a glitch, not a run
        if (kmh != null && prevT != 0L && (t <= prevT || t - prevT >= 2_000 ||
                abs(kmh - prevV) / 3.6 / ((t - prevT) / 1000.0) > 1.5 * 9.81)) {
            breakRuns()
            s = s.copy(launchLive = null, brakeLive = null)
        }
        if (kmh != null && prevT != 0L && t > prevT && t - prevT < 2_000) {
            val dt = (t - prevT) / 1000.0

            // Launch: armed after half a second stationary, starts when the car moves, ends at the target.
            if (kmh < 1.0) {
                if (stillSince == 0L) stillSince = t
                if (launchT0 != 0L) { launchT0 = 0; s = s.copy(launchLive = null) }
            } else {
                if (launchT0 == 0L && stillSince != 0L && prevT - stillSince >= 500) launchT0 = prevT
                stillSince = 0
            }
            if (launchT0 != 0L) {
                if (kmh >= target) {
                    val f = ((target - prevV) / (kmh - prevV)).coerceIn(0.0, 1.0)
                    val secs = (prevT + f * (t - prevT) - launchT0) / 1000.0
                    val run = Run(secs, at = t)
                    val best = if (s.launchBest.seconds == null || secs < s.launchBest.seconds!!) run.also {
                        prefs.edit().putFloat("best_launch_${unitKey()}", secs.toFloat()).apply()
                    } else s.launchBest
                    s = s.copy(launch = run, launchBest = best, launchLive = null)
                    launchT0 = 0
                } else if (t - launchT0 > 25_000) {
                    launchT0 = 0; s = s.copy(launchLive = null)
                } else {
                    s = s.copy(launchLive = (t - launchT0) / 1000.0)
                }
            }

            // Braking: starts when speed falls through the target, distance integrated to a stop.
            if (brakeT0 == 0L && prevV >= target && kmh < target) {
                brakeT0 = t
                val f = ((prevV - target) / (prevV - kmh)).coerceIn(0.0, 1.0)
                brakeDist = (target + kmh) / 2 / 3.6 * dt * (1 - f)
                brakeMin = kmh
            } else if (brakeT0 != 0L) {
                brakeDist += (prevV + kmh) / 2 / 3.6 * dt
                brakeMin = minOf(brakeMin, kmh)
                when {
                    kmh < 1.0 -> {
                        val run = Run(brakeDist, brakeDist, t)
                        val best = if (s.stopBest.meters == null || brakeDist < s.stopBest.meters!!) Run(meters = brakeDist, at = t).also {
                            prefs.edit().putFloat("best_stop_${unitKey()}", brakeDist.toFloat()).apply()
                        } else s.stopBest
                        s = s.copy(stop = Run(meters = run.meters, at = t), stopBest = best, brakeLive = null)
                        brakeT0 = 0
                    }
                    // not a stop: speed came back up, it isn't slowing like a stop (100→85 within 2 s
                    // is only ~0.2 g), or it took too long
                    kmh > brakeMin + 2 || (t - brakeT0 > 2_000 && kmh > target - 15) || t - brakeT0 > 12_000 ->
                        { brakeT0 = 0; s = s.copy(brakeLive = null) }
                    // only show it once it is clearly a stop, so cruise dips under the target never flash "timing"
                    else -> s = s.copy(brakeLive = brakeDist.takeIf { kmh <= target - 10 })
                }
            }
        }
        if (kmh != null) { prevT = t; prevV = kmh }
        st = s
        return s
    }
}
