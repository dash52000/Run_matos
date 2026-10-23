package com.example.domain

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lever et coucher du soleil calculés localement (équations de la NOAA, précision ~1-2 min).
 * Aucun appel réseau : fonctionne aussi pour le cache hors-ligne et les sorties passées.
 */
object SunTimes {

    /** Minutes depuis minuit (heure locale du fuseau [tz]) du lever et du coucher. */
    data class Times(val sunriseMinutes: Int, val sunsetMinutes: Int)

    /** Marge de crépuscule civil : il fait encore assez clair ~30 min après le coucher (et avant le lever). */
    const val TWILIGHT_MARGIN_MINUTES = 30

    fun compute(
        latitude: Double,
        longitude: Double,
        year: Int,
        month: Int,
        dayOfMonth: Int,
        tz: TimeZone = TimeZone.getDefault()
    ): Times? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val cal = Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, dayOfMonth, 12, 0, 0)
        }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val gamma = 2.0 * PI / daysInYear * (dayOfYear - 1)

        val eqTimeMin = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(latitude)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null // jour ou nuit polaire
        val haDeg = Math.toDegrees(acos(cosHa))

        val offsetMinutes = tz.getOffset(cal.timeInMillis) / 60000.0
        val sunriseUtc = 720.0 - 4.0 * (longitude + haDeg) - eqTimeMin
        val sunsetUtc = 720.0 - 4.0 * (longitude - haDeg) - eqTimeMin
        return Times(
            sunriseMinutes = (sunriseUtc + offsetMinutes).roundToInt(),
            sunsetMinutes = (sunsetUtc + offsetMinutes).roundToInt()
        )
    }

    /**
     * Vrai si une partie du créneau [startMinuteOfDay, startMinuteOfDay + durationMinutes]
     * tombe dans la nuit (avant le lever - 30 min ou après le coucher + 30 min).
     * En cas de jour/nuit polaire inconnu, on reste prudent : frontale conseillée.
     */
    fun isSlotInDarkness(
        times: Times?,
        startMinuteOfDay: Int,
        durationMinutes: Int
    ): Boolean {
        if (times == null) return true
        val end = startMinuteOfDay + durationMinutes.coerceAtLeast(0)
        val lightStart = times.sunriseMinutes - TWILIGHT_MARGIN_MINUTES
        val lightEnd = times.sunsetMinutes + TWILIGHT_MARGIN_MINUTES
        return startMinuteOfDay < lightStart || end > lightEnd
    }

    fun formatMinutes(minutes: Int): String {
        val norm = ((minutes % 1440) + 1440) % 1440
        return String.format("%02dh%02d", norm / 60, norm % 60)
    }
}
