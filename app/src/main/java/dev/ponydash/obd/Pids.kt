package dev.ponydash.obd

enum class Rate { FAST, MEDIUM, SLOW, RARE }

/** How a value converts between metric and imperial on screen. */
enum class Kind { TEMP, PRESSURE, SPEED, DISTANCE, PLAIN }

/** One displayable quantity (a PID can produce several). Stored in the unit the car reports. */
class Channel(val key: String, val label: String, val kind: Kind, val unit: String = "", val decimals: Int = 0)

/**
 * A standard (mode 01) OBD-II reading. Values are stored in SI/metric units as
 * the car reports them (°C, kPa, km/h, km); the UI converts for imperial.
 */
class Pid(
    val code: Int,
    val name: String,
    val rate: Rate,
    val bytes: Int,
    val channels: List<Channel>,
    val decode: (IntArray) -> List<Double>,
) {
    fun decodeMap(b: IntArray): Map<String, Double> = channels.map { it.key }.zip(decode(b)).toMap()
}

private fun ab(b: IntArray) = b[0] * 256 + b[1]

object Pids {
    private fun p(code: Int, rate: Rate, bytes: Int, ch: Channel, f: (IntArray) -> Double) =
        Pid(code, ch.label, rate, bytes, listOf(ch)) { listOf(f(it)) }

    private fun temp(key: String, label: String) = Channel(key, label, Kind.TEMP, "°C")
    private fun pct(key: String, label: String, d: Int = 0) = Channel(key, label, Kind.PLAIN, "%", d)
    private fun kpa(key: String, label: String) = Channel(key, label, Kind.PRESSURE, "kPa")

    val all: List<Pid> = listOf(
        // fast: things that move with your right foot
        p(0x0C, Rate.FAST, 2, Channel(K.RPM, "Engine speed", Kind.PLAIN, "rpm")) { ab(it) / 4.0 },
        p(0x0D, Rate.FAST, 1, Channel(K.SPEED, "Vehicle speed", Kind.SPEED, "km/h")) { it[0].toDouble() },
        p(0x0B, Rate.FAST, 1, kpa(K.MAP, "Manifold abs. pressure")) { it[0].toDouble() },
        p(0x10, Rate.FAST, 2, Channel(K.MAF, "MAF air flow", Kind.PLAIN, "g/s", 1)) { ab(it) / 100.0 },
        // medium
        p(0x11, Rate.MEDIUM, 1, pct(K.THROTTLE, "Throttle position")) { it[0] * 100.0 / 255 },
        p(0x04, Rate.MEDIUM, 1, pct("load", "Calculated load")) { it[0] * 100.0 / 255 },
        p(0x43, Rate.MEDIUM, 2, pct("abs_load", "Absolute load")) { ab(it) * 100.0 / 255 },
        p(0x0E, Rate.MEDIUM, 1, Channel("timing", "Timing advance", Kind.PLAIN, "°", 1)) { it[0] / 2.0 - 64 },
        p(0x06, Rate.MEDIUM, 1, pct("stft1", "Short fuel trim B1", 1)) { (it[0] - 128) * 100.0 / 128 },
        p(0x34, Rate.MEDIUM, 4, Channel("lambda1", "O₂ S1 lambda", Kind.PLAIN, "λ", 3)) { ab(it) * 2.0 / 65536 },
        p(0x44, Rate.MEDIUM, 2, Channel("cmd_lambda", "Commanded lambda", Kind.PLAIN, "λ", 3)) { ab(it) * 2.0 / 65536 },
        p(0x49, Rate.MEDIUM, 1, pct("pedal_d", "Accelerator pedal D")) { it[0] * 100.0 / 255 },
        p(0x4C, Rate.MEDIUM, 1, pct("cmd_throttle", "Commanded throttle")) { it[0] * 100.0 / 255 },
        p(0x62, Rate.MEDIUM, 1, pct("torque_act", "Actual torque")) { it[0] - 125.0 },
        p(0x61, Rate.MEDIUM, 1, pct("torque_dmd", "Driver demand torque")) { it[0] - 125.0 },
        p(0x5E, Rate.MEDIUM, 2, Channel("fuel_rate", "Fuel rate", Kind.PLAIN, "L/h", 2)) { ab(it) / 20.0 },
        p(0x23, Rate.MEDIUM, 2, kpa("rail_kpa", "Fuel rail pressure")) { ab(it) * 10.0 },
        p(0x0F, Rate.MEDIUM, 1, temp(K.IAT, "Intake air temp")) { it[0] - 40.0 },
        p(0x42, Rate.MEDIUM, 2, Channel(K.VOLTS, "Module voltage", Kind.PLAIN, "V", 2)) { ab(it) / 1000.0 },
        // slow
        p(0x05, Rate.SLOW, 1, temp(K.COOLANT, "Coolant temp")) { it[0] - 40.0 },
        p(0x5C, Rate.SLOW, 1, temp(K.OIL, "Engine oil temp")) { it[0] - 40.0 },
        p(0x46, Rate.SLOW, 1, temp(K.AMBIENT, "Ambient air temp")) { it[0] - 40.0 },
        p(0x3C, Rate.SLOW, 2, temp("cat11", "Catalyst temp B1S1")) { ab(it) / 10.0 - 40 },
        p(0x3E, Rate.SLOW, 2, temp("cat12", "Catalyst temp B1S2")) { ab(it) / 10.0 - 40 },
        p(0x07, Rate.SLOW, 1, pct("ltft1", "Long fuel trim B1", 1)) { (it[0] - 128) * 100.0 / 128 },
        p(0x2F, Rate.SLOW, 1, pct(K.FUEL, "Fuel level")) { it[0] * 100.0 / 255 },
        p(0x33, Rate.SLOW, 1, kpa(K.BARO, "Barometric pressure")) { it[0].toDouble() },
        p(0x2E, Rate.SLOW, 1, pct("evap", "Evap purge")) { it[0] * 100.0 / 255 },
        p(0x2C, Rate.SLOW, 1, pct("egr", "Commanded EGR")) { it[0] * 100.0 / 255 },
        p(0x52, Rate.SLOW, 1, pct("ethanol", "Ethanol content")) { it[0] * 100.0 / 255 },
        p(0x1F, Rate.SLOW, 2, Channel("runtime", "Run time this start", Kind.PLAIN, "s")) { ab(it).toDouble() },
        p(0x31, Rate.SLOW, 2, Channel("dist_clr", "Dist. since codes cleared", Kind.DISTANCE, "km")) { ab(it).toDouble() },
        p(0x30, Rate.SLOW, 1, Channel("warmups", "Warm-ups since cleared", Kind.PLAIN)) { it[0].toDouble() },
        p(0x21, Rate.SLOW, 2, Channel("dist_mil", "Dist. with MIL on", Kind.DISTANCE, "km")) { ab(it).toDouble() },
        p(0x63, Rate.SLOW, 2, Channel("torque_ref", "Reference torque", Kind.PLAIN, "Nm")) { ab(it).toDouble() },
        p(0xA6, Rate.SLOW, 4, Channel("odometer", "Odometer", Kind.DISTANCE, "km", 1)) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        },
        // turbo and airflow (the 2025 EcoBoost reports these instead of PID 10 / 70)
        Pid(0x66, "MAF sensor", Rate.FAST, 3, listOf(Channel(K.MAF, "MAF air flow", Kind.PLAIN, "g/s", 1))) {
            listOf(if (it[0] and 1 != 0) (it[1] * 256 + it[2]) / 32.0 else Double.NaN)
        },
        Pid(0x68, "Intake air temp sensors", Rate.MEDIUM, 3, listOf(
            Channel(K.IAT_A, "Intake air temp A", Kind.TEMP, "°C"),
            Channel(K.IAT_B, "Intake air temp B", Kind.TEMP, "°C"),
        )) { listOf(if (it[0] and 1 != 0) it[1] - 40.0 else Double.NaN, if (it[0] and 2 != 0) it[2] - 40.0 else Double.NaN) },
        Pid(0x73, "Exhaust pressure", Rate.MEDIUM, 3, listOf(Channel("exh_kpa", "Exhaust back-pressure", Kind.PRESSURE, "kPa"))) {
            listOf(if (it[0] and 1 != 0) (it[1] * 256 + it[2]) * 0.01 else Double.NaN)
        },
        Pid(0x78, "Exhaust gas temp B1", Rate.MEDIUM, 5, listOf(
            Channel("egt11", "Exhaust gas temp S1", Kind.TEMP, "°C"),
            Channel("egt12", "Exhaust gas temp S2", Kind.TEMP, "°C"),
        )) {
            listOf(
                if (it[0] and 1 != 0) (it[1] * 256 + it[2]) / 10.0 - 40 else Double.NaN,
                if (it[0] and 2 != 0 && it.size >= 5) (it[3] * 256 + it[4]) / 10.0 - 40 else Double.NaN,
            )
        },
        Pid(0x01, "MIL / DTC count", Rate.SLOW, 4, listOf(
            Channel(K.MIL, "Check-engine light", Kind.PLAIN),
            Channel(K.DTC, "Stored trouble codes", Kind.PLAIN),
        )) { listOf(if (it[0] and 0x80 != 0) 1.0 else 0.0, (it[0] and 0x7F).toDouble()) },
    )

    /** Values computed from other readings rather than polled. */
    private val derived = listOf(Channel(K.BOOST, "Boost", Kind.PRESSURE, "kPa"))

    /** What matters most when driving hard comes first; everything else follows in PID order. */
    private val raceOrder = listOf(
        K.RPM, K.SPEED, K.BOOST, "pedal_d", "cmd_throttle", K.KNOCK, "timing", K.TRANS, K.OIL, K.COOLANT,
        K.IAT_B, K.IAT, K.IAT_A, "lambda1", "cmd_lambda", "rail_kpa", "egt11", "egt12", "exh_kpa", K.MAP, K.MAF,
        "torque_act", "torque_dmd", K.THROTTLE, "load", "abs_load", "stft1", "ltft1", "fuel_rate", K.VOLTS,
        K.TIRE_FL, K.TIRE_FR, K.TIRE_RL, K.TIRE_RR, K.FUEL,
    )

    /** Every channel in display order, keyed for lookup. */
    val channels: List<Channel> = (all.flatMap { it.channels } + FordPids.all.flatMap { it.channels } + derived)
        .distinctBy { it.key }
        .sortedBy { raceOrder.indexOf(it.key).let { i -> if (i < 0) raceOrder.size else i } }

    /** Add computed values (boost = manifold − barometric) to a value map. */
    fun derive(v: Map<String, Double>): Map<String, Double> {
        val map = v[K.MAP] ?: return v
        return v + (K.BOOST to map - (v[K.BARO] ?: 101.3))
    }
    val channelByKey: Map<String, Channel> = channels.associateBy { it.key }

    fun name(code: Int) = all.firstOrNull { it.code == code }?.name
}

/** Keys of the live value map. */
object K {
    const val RPM = "rpm"
    const val SPEED = "speed_kmh"
    const val MAP = "map_kpa"
    const val MAF = "maf_gs"
    const val THROTTLE = "throttle_pct"
    const val IAT = "iat_c"
    const val IAT_A = "iat_a_c"
    const val IAT_B = "iat_b_c"
    const val BOOST = "boost_kpa"
    const val LAMBDA = "lambda1"
    const val CMD_LAMBDA = "cmd_lambda"
    const val VOLTS = "volts"
    const val COOLANT = "coolant_c"
    const val OIL = "oil_c"
    const val AMBIENT = "ambient_c"
    const val FUEL = "fuel_pct"
    const val BARO = "baro_kpa"
    const val MIL = "mil"
    const val DTC = "dtc_count"
    const val TRANS = "trans_c"
    const val KNOCK = "knock_deg"
    const val TIRE_FL = "tire_fl_kpa"
    const val TIRE_FR = "tire_fr_kpa"
    const val TIRE_RR = "tire_rr_kpa"
    const val TIRE_RL = "tire_rl_kpa"
}

/**
 * Ford enhanced (mode 22) reads to try from the diagnostics screen. These are
 * read-only ReadDataByIdentifier requests. The IDs come from community lists
 * and are NOT verified on a 2025 Mustang; the raw replies tell us which work.
 */
data class FordProbe(val header: String, val cmd: String, val what: String)

val FORD_PROBES = listOf(
    FordProbe("7E0", "22F40C", "RPM via mode 22 (sanity check: should echo 01 0C)"),
    FordProbe("7E0", "22F45C", "Oil temp via mode 22 mirror of 01 5C"),
    FordProbe("7E0", "221310", "Engine oil temp (community ID)"),
    FordProbe("7E0", "221E1C", "Transmission fluid temp (PCM)"),
    FordProbe("7E1", "221E1C", "Transmission fluid temp (TCM)"),
    FordProbe("7E0", "2203EC", "Knock / spark retard (community ID)"),
    FordProbe("726", "222813", "TPMS front-left pressure (BCM)"),
    FordProbe("726", "222814", "TPMS front-right pressure (BCM)"),
    FordProbe("726", "222815", "TPMS rear-right pressure (BCM)"),
    FordProbe("726", "222816", "TPMS rear-left pressure (BCM)"),
)

/**
 * Ford enhanced reads (mode 22, ReadDataByIdentifier) confirmed on the 2025
 * Mustang EcoBoost by the probe on 2026-11-21. Scales marked "unverified" were
 * inferred from plausible values and still need a cross-check.
 */
class FordPid(
    val header: String,
    val did: String,
    val rate: Rate,
    val channels: List<Channel>,
    val decode: (IntArray) -> List<Double>,
)

object FordPids {
    private fun tire(key: String, label: String, did: String) =
        // raw / 20 = psi, stored as kPa; matches the car's own tire display
        FordPid("726", did, Rate.RARE, listOf(Channel(key, label, Kind.PRESSURE, "kPa"))) {
            listOf((it[0] * 256 + it[1]) / 20.0 * 6.894757)
        }

    val all = listOf(
        // raw/16 = °C (47 °C after a few minutes idling, rising)
        FordPid("7E0", "1E1C", Rate.SLOW, listOf(Channel(K.TRANS, "Transmission fluid", Kind.TEMP, "°C"))) {
            listOf((it[0] * 256 + it[1]) / 16.0)
        },
        // unverified scale: signed 16-bit / 16 = degrees of retard (read 0 at idle)
        FordPid("7E0", "03EC", Rate.MEDIUM, listOf(Channel(K.KNOCK, "Knock retard", Kind.PLAIN, "°", 1))) {
            listOf((it[0] * 256 + it[1]).toShort() / 16.0)
        },
        tire(K.TIRE_FL, "Tire front-left", "2813"),
        tire(K.TIRE_FR, "Tire front-right", "2814"),
        tire(K.TIRE_RR, "Tire rear-right", "2815"),
        tire(K.TIRE_RL, "Tire rear-left", "2816"),
    )
}
