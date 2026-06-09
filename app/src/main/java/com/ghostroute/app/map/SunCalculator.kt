package com.ghostroute.app.map

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes whether it is night at a given location and time — entirely offline, from
 * a low-precision solar-position formula (good to a minute or two, far more than enough
 * to flip the map between day and night styles at sunset/sunrise).
 *
 * No network, no timezone database: solar position depends only on UTC and longitude.
 */
object SunCalculator {

    /** Sun altitude (degrees above the horizon) at [timeMillis] for [lat]/[lon]. */
    fun solarAltitudeDeg(lat: Double, lon: Double, timeMillis: Long): Double {
        // Days since the J2000.0 epoch (2000-01-01 12:00 UTC).
        val n = timeMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0

        val meanLong = Math.toRadians(norm360(280.460 + 0.9856474 * n))
        val meanAnom = Math.toRadians(norm360(357.528 + 0.9856003 * n))
        val eclLong = meanLong + Math.toRadians(1.915) * sin(meanAnom) +
            Math.toRadians(0.020) * sin(2 * meanAnom)
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val declination = asin(sin(obliquity) * sin(eclLong))
        val rightAsc = atan2(cos(obliquity) * sin(eclLong), cos(eclLong))

        // Greenwich mean sidereal time → local hour angle.
        val gmst = norm360(280.46061837 + 360.98564736629 * n)
        val localSidereal = Math.toRadians(norm360(gmst + lon))
        val hourAngle = localSidereal - rightAsc

        val latR = Math.toRadians(lat)
        val altitude = asin(
            sin(latR) * sin(declination) + cos(latR) * cos(declination) * cos(hourAngle),
        )
        return Math.toDegrees(altitude)
    }

    /**
     * True when the sun is below the horizon (with the standard −0.833° refraction
     * allowance) — i.e. after sunset / before sunrise, when the night style should show.
     */
    fun isNight(lat: Double, lon: Double, timeMillis: Long): Boolean =
        solarAltitudeDeg(lat, lon, timeMillis) < -0.833

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
