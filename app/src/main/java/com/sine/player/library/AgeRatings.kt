package com.sine.player.library

import org.json.JSONObject
import java.util.Locale

/**
 * Turns certifications from many countries into a minimum age, so a kids profile can say
 * "up to 12" whatever system the title was rated under.
 */
object AgeRatings {
    /** Ages a profile can be limited to. */
    val limits = listOf(0, 7, 10, 13, 16, 18)

    fun age(certification: String?): Int? {
        val c = certification?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: return null
        named[c]?.let { return it }
        // "12A", "MA15+", "R-16", "FSK 12", "16+": the number is the age.
        return Regex("""(\d{1,2})""").find(c)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..21 }
    }

    private val named = mapOf(
        // United States (films and TV)
        "G" to 0, "PG" to 10, "PG-13" to 13, "R" to 17, "NC-17" to 18, "NR" to null, "UNRATED" to null,
        "TV-Y" to 0, "TV-Y7" to 7, "TV-Y7-FV" to 7, "TV-G" to 0, "TV-PG" to 10, "TV-14" to 14, "TV-MA" to 17,
        // United Kingdom
        "U" to 0, "UC" to 0, "R18" to 18,
        // Philippines (MTRCB)
        "SPG" to 13, "X" to 18,
        // Australia
        "M" to 15, "E" to 0, "P" to 0, "C" to 0, "AV15+" to 15,
        // Canada (English) and Brazil/others with letters
        "14A" to 14, "18A" to 18, "A" to 18, "L" to 0,
    ).filterValues { it != null }.mapValues { it.value!! }

    /** Certification for [region] from movie details with `append_to_response=release_dates`. */
    fun movieCertification(details: JSONObject, region: String): String? {
        val results = details.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
        fun forRegion(code: String): String? {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (!r.optString("iso_3166_1").equals(code, true)) continue
                val dates = r.optJSONArray("release_dates") ?: continue
                // Theatrical (3) first, then any release with a certification.
                val all = (0 until dates.length()).map { dates.getJSONObject(it) }.filter { it.optString("certification").isNotBlank() }
                return (all.firstOrNull { it.optInt("type") == 3 } ?: all.firstOrNull())?.optString("certification")
            }
            return null
        }
        return forRegion(region) ?: forRegion("US")
    }

    /** Rating for [region] from TV details with `append_to_response=content_ratings`. */
    fun tvCertification(details: JSONObject, region: String): String? {
        val results = details.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
        fun forRegion(code: String) = (0 until results.length()).map { results.getJSONObject(it) }
            .firstOrNull { it.optString("iso_3166_1").equals(code, true) }?.optString("rating")?.ifBlank { null }
        return forRegion(region) ?: forRegion("US")
    }

    /** Whether a title rated for [age] (null = unrated) fits a profile limited to [maxAge] (null = no limit). */
    fun fits(age: Int?, maxAge: Int?): Boolean = maxAge == null || age == null || age <= maxAge

    fun label(maxAge: Int?) = when (maxAge) {
        null -> "Any"
        0 -> "All ages only"
        else -> "Up to $maxAge"
    }
}
