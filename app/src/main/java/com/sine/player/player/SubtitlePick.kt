package com.sine.player.player

enum class SubtitleMode(val label: String, val description: String) {
    FOREIGN("Only for other languages", "On when the audio isn't in your language; otherwise just forced subtitles"),
    ALWAYS("Always", "On whenever there are subtitles in your language"),
    FORCED("Forced only", "Only the parts in another language (signs, foreign dialogue)"),
    OFF("Off", "Never turned on by themselves"),
}

data class SubtitleCandidate(val language: String?, val forced: Boolean, val hearingImpaired: Boolean, val supported: Boolean = true)

/** Which subtitle track (if any) to start with, from the mode, the audio language and yours. */
object SubtitlePick {
    /** Index to select, or null for subtitles off. */
    fun decide(
        mode: SubtitleMode,
        candidates: List<SubtitleCandidate>,
        audioLanguage: String?,
        myLanguage: String,
        avoidHearingImpaired: Boolean,
    ): Int? {
        val mine = AudioRank.norm(myLanguage)
        val inMyLanguage = candidates.indices.filter { candidates[it].supported && candidates[it].language?.let(AudioRank::norm) == mine }
        val forced = inMyLanguage.firstOrNull { candidates[it].forced }
        // Full subtitles: plain first, then SDH (last of all when SDH is avoided); never forced-only tracks.
        val full = inMyLanguage.filter { !candidates[it].forced }
            .sortedBy { if (candidates[it].hearingImpaired) (if (avoidHearingImpaired) 2 else 1) else 0 }
            .firstOrNull()
        val audioIsMine = audioLanguage?.let(AudioRank::norm) == mine
        return when (mode) {
            SubtitleMode.OFF -> null
            SubtitleMode.FORCED -> forced
            SubtitleMode.ALWAYS -> full ?: forced
            // An untagged audio track counts as foreign: better subtitles you can turn off than missing ones.
            SubtitleMode.FOREIGN -> if (audioIsMine) forced else full ?: forced
        }
    }
}
