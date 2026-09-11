package io.github.jqssun.gpssetter.xposed

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Produces plausible values for the location fields in "Auto" mode.
 *
 * The goal is to look like a real GNSS receiver, not to look random. Three properties matter:
 *
 * 1. **Realistic ranges.** A phone does not report 0.0 m accuracy, nor 87 m while claiming a good
 *    fix. Values stay inside what consumer hardware actually produces.
 * 2. **Stability over time.** Values drift slowly rather than jumping every call. A field that
 *    changes wildly between two reads a second apart is as much a tell as one that never moves.
 * 3. **Internal consistency.** Vertical accuracy tracks horizontal accuracy; speed accuracy
 *    tracks speed. Fields that should correlate on real hardware do so here.
 *
 * Everything is derived deterministically from the position and a slow time bucket, so two reads
 * close together agree without any shared mutable state between processes.
 */
object DynamicValues {

    /** Values change on this cadence; slow enough to look like drift, not noise. */
    private const val BUCKET_MS = 20_000.0

    private fun bucket() = System.currentTimeMillis() / BUCKET_MS

    /** Stable per-location seed so different places do not all share one signature. */
    private fun seed(lat: Double, lng: Double): Double =
        (floor(lat * 1000).toInt() * 31 + floor(lng * 1000).toInt()).toDouble()

    /** Smooth 0..1 oscillation, unique per location and per field. */
    private fun wave(lat: Double, lng: Double, field: Int): Double {
        val phase = bucket() * 0.35 + seed(lat, lng) * 0.017 + field * 1.7
        return (sin(phase) + cos(phase * 0.61)) / 4.0 + 0.5
    }

    /**
     * Horizontal accuracy in metres.
     *
     * Real phones with a good open-sky fix report roughly 3-12 m; a flat 10.0 every single time is
     * itself unusual, so this drifts within that band.
     */
    fun accuracy(lat: Double, lng: Double): Float =
        (3.0 + wave(lat, lng, 0) * 9.0).toFloat()

    /**
     * Vertical accuracy in metres.
     *
     * GNSS altitude is consistently worse than the horizontal fix -- typically 1.5x to 2.5x -- so
     * this is derived from [accuracy] rather than picked independently.
     */
    fun verticalAccuracy(lat: Double, lng: Double): Float =
        accuracy(lat, lng) * (1.5f + wave(lat, lng, 1).toFloat())

    /**
     * Mean sea level altitude in metres.
     *
     * [terrainHint] is the real ground elevation looked up for this coordinate, which is already
     * an MSL value, so it is used directly with only a metre or two of drift for realism. Without
     * it, falls back to a modest coastal value -- a device reporting 800 m in a seaside city is
     * more suspicious than one reporting 20 m.
     */
    fun meanSeaLevel(lat: Double, lng: Double, terrainHint: Float?): Float {
        val ground = terrainHint?.toDouble() ?: 20.0
        return (ground + (wave(lat, lng, 2) - 0.5) * 2.0).toFloat()
    }

    /**
     * Altitude above the WGS84 ellipsoid in metres, which is what `Location.getAltitude()` means.
     *
     * This differs from mean sea level by the local geoid separation. A proper value needs an
     * EGM96 grid; the approximation below is smooth, deterministic and of the right magnitude
     * (tens of metres, either sign), which is what matters here -- reporting altitude and MSL as
     * *identical* would be the giveaway, since on real hardware they never are.
     */
    fun altitude(lat: Double, lng: Double, terrainHint: Float?, geoidHint: Float? = null): Double =
        meanSeaLevel(lat, lng, terrainHint) + (geoidHint?.toDouble() ?: geoidSeparation(lat, lng))

    /**
     * Fallback used only when the real offset has not been looked up. Deliberately rough: it is
     * the right order of magnitude, which keeps the two height figures from being identical, but
     * the looked-up value should be preferred whenever one is available.
     */
    private fun geoidSeparation(lat: Double, lng: Double): Double =
        20.0 * sin(Math.toRadians(lat)) - 12.0 * cos(Math.toRadians(lng))

    /** MSL accuracy tracks vertical accuracy, slightly wider. */
    fun meanSeaLevelAccuracy(lat: Double, lng: Double): Float =
        verticalAccuracy(lat, lng) * 1.2f

    /**
     * Speed accuracy in metres per second.
     *
     * Real receivers report a small absolute floor plus a component proportional to speed. A
     * stationary device reporting 0.0 speed accuracy is not what hardware does.
     */
    fun speedAccuracy(speed: Float, lat: Double, lng: Double): Float =
        (0.25f + speed * 0.05f + wave(lat, lng, 3).toFloat() * 0.35f).coerceIn(0.25f, 3.0f)

    /**
     * Bearing accuracy in degrees. Poor at low speed, better when actually moving -- which is how
     * a real receiver behaves, since heading is derived from displacement.
     */
    fun bearingAccuracy(speed: Float, lat: Double, lng: Double): Float =
        if (speed < 0.5f) (25f + wave(lat, lng, 4).toFloat() * 20f)
        else (5f + wave(lat, lng, 4).toFloat() * 10f).coerceAtMost(20f)

    /**
     * Randomisation radius in metres.
     *
     * A few metres of wander, matching the jitter a stationary phone actually shows. A position
     * that reads back byte-identical every single time is not something GNSS produces.
     */
    fun randomizeRadius(lat: Double, lng: Double): Float =
        (3.0 + wave(lat, lng, 5) * 5.0).toFloat()

    /** Whether a value looks like it came from a real receiver rather than a round default. */
    fun isSuspiciouslyRound(value: Float): Boolean = abs(value - value.toInt()) < 0.0001f
}
