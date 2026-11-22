package dev.ponydash.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GForce(
    val lat: Float,
    val lon: Float,
    val trail: List<Pair<Float, Float>>,
    val calibrated: Boolean,
    /** ~12 s of (lat, lon) samples for the time graph. */
    val history: List<Pair<Float, Float>> = emptyList(),
    val peakLat: Float = 0f,
    val peakAccel: Float = 0f,
    val peakBrake: Float = 0f,
)

/**
 * G-meter from the tablet's accelerometer (the OnePlus Pad 3 has no GPS).
 *
 * Mount assumption: screen faces the occupants, so the back of the tablet
 * points toward the front of the car (device −Z ≈ forward). "Calibrate" while
 * parked locks the gravity vector, which removes the mount tilt. Until then a
 * slow low-pass filter estimates gravity.
 */
class GMeter(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val _state = MutableStateFlow(GForce(0f, 0f, emptyList(), false))
    val state: StateFlow<GForce> = _state

    private val smooth = FloatArray(3)
    private val gravEst = FloatArray(3)
    private var locked: FloatArray? = null
    private var primed = false
    private val trail = ArrayDeque<Pair<Float, Float>>()
    private val longHist = ArrayDeque<Pair<Float, Float>>()
    private var peakLat = 0f
    private var peakAccel = 0f
    private var peakBrake = 0f

    fun resetPeaks() {
        peakLat = 0f; peakAccel = 0f; peakBrake = 0f
    }
    private var lastEmit = 0L

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sm.unregisterListener(this)

    fun calibrate() {
        locked = smooth.copyOf()
        trail.clear()
    }

    /** While true, the accelerometer is ignored and [feed] drives the meter (drive replay). */
    @Volatile var external = false

    /** Push a recorded sample, as if the accelerometer had produced it. */
    fun feed(lat: Float, lon: Float) = emit(lat, lon)

    override fun onSensorChanged(e: SensorEvent) {
        if (external) return
        val v = e.values
        if (!primed) {
            for (i in 0..2) { smooth[i] = v[i]; gravEst[i] = v[i] }
            primed = true
        }
        for (i in 0..2) {
            smooth[i] += 0.2f * (v[i] - smooth[i])
            gravEst[i] += 0.01f * (v[i] - gravEst[i])
        }
        val g = locked ?: gravEst
        val gn = norm(g)
        if (gn < 1f) return
        val up = floatArrayOf(g[0] / gn, g[1] / gn, g[2] / gn)
        // forward = device −Z projected onto the horizontal plane
        val f0 = floatArrayOf(0f, 0f, -1f)
        val d = dot(f0, up)
        val f = floatArrayOf(f0[0] - d * up[0], f0[1] - d * up[1], f0[2] - d * up[2])
        val fn = norm(f)
        if (fn < 0.1f) return // tablet lying flat: forward undefined
        for (i in 0..2) f[i] /= fn
        val right = cross(f, up)
        val a = floatArrayOf(smooth[0] - g[0], smooth[1] - g[1], smooth[2] - g[2])
        val lon = dot(a, f) / SensorManager.GRAVITY_EARTH
        val lat = dot(a, right) / SensorManager.GRAVITY_EARTH

        val now = e.timestamp / 1_000_000
        if (now - lastEmit < 40) return
        lastEmit = now
        emit(lat, lon)
    }

    private fun emit(lat: Float, lon: Float) {
        trail.addLast(lat to lon)
        while (trail.size > 75) trail.removeFirst()
        longHist.addLast(lat to lon)
        while (longHist.size > 300) longHist.removeFirst()
        peakLat = maxOf(peakLat, kotlin.math.abs(lat))
        peakAccel = maxOf(peakAccel, lon)
        peakBrake = maxOf(peakBrake, -lon)
        _state.value = GForce(lat, lon, trail.toList(), locked != null, longHist.toList(), peakLat, peakAccel, peakBrake)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun norm(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
