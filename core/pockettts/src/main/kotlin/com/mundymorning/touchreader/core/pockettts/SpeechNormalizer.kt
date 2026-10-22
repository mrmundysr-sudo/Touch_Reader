package com.mundymorning.touchreader.core.pockettts

/**
 * Small clean-ups applied to the text before the voice model sees it. The model treats every full stop as the end of a
 * sentence and pauses there, so "Mr. Holmes" sounds like "Mister ... Holmes"; titles are written out in full instead.
 */
object SpeechNormalizer {
    private val titles = mapOf(
        "Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miss", "Dr" to "Doctor", "Prof" to "Professor", "Sr" to "Senior",
        "Jr" to "Junior", "Mt" to "Mount", "Gen" to "General", "Col" to "Colonel", "Capt" to "Captain", "Lt" to "Lieutenant",
        "Sgt" to "Sergeant", "Rev" to "Reverend", "vs" to "versus",
    )
    private val pattern = Regex("\\b(" + titles.keys.joinToString("|") + ")\\.(?=\\s+\\S)")

    fun normalize(text: String): String = pattern.replace(text) { titles.getValue(it.groupValues[1]) }
}
