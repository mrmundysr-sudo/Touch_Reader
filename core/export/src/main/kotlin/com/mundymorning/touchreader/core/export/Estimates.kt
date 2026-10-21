package com.mundymorning.touchreader.core.export

import com.mundymorning.touchreader.core.reader.ReaderBook

/** How a voice's speed compares with real time, from timing a few sentences on this phone. */
enum class LiveSpeed { FAST_ENOUGH, MARGINAL, TOO_SLOW }

object SpeedClass {
    /** Real-time factor = seconds of work per second of speech. */
    fun classify(rtf: Double): LiveSpeed = when {
        rtf <= 0.8 -> LiveSpeed.FAST_ENOUGH
        rtf <= 1.0 -> LiveSpeed.MARGINAL
        else -> LiveSpeed.TOO_SLOW
    }
}

object ExportEstimator {
    /** Typical narration pace, used before a book has been timed: about 14.5 characters of text per second. */
    const val CHARS_PER_SECOND = 14.5

    fun audioSeconds(book: ReaderBook, chapters: List<Int>, charsPerSecond: Double = CHARS_PER_SECOND): Double {
        var chars = 0L
        for (c in chapters) {
            val ch = book.chapters.getOrNull(c) ?: continue
            for (i in ch.sentences.indices) chars += ch.text(i).length + 1
            chars += ch.title.length + 2
        }
        return chars / charsPerSecond
    }

    fun sizeBytes(audioSeconds: Double, kbps: Int): Long = (audioSeconds * kbps * 1000 / 8).toLong()

    fun renderSeconds(audioSeconds: Double, rtf: Double): Double = audioSeconds * rtf

    /** A timing taken on this phone: the neural voice at [steps], with or without voice conversion. */
    data class Measurement(val steps: Int, val custom: Boolean, val rtf: Double, val engine: String = ENGINE_SUPERTONIC)

    const val ENGINE_SUPERTONIC = "supertonic"
    /** Saved voices made by copying the voice (Pocket TTS): one timing per quality level. */
    const val ENGINE_POCKET = "pocket"

    /** Assumed time of Better quality over Faster for Pocket voices until both have been timed on this phone (the extra work is small flow-network steps). */
    const val POCKET_BETTER_OVER_FASTER = 1.15

    /** From CI: 4 steps took about 0.55x the time of 8 steps. */
    const val STEPS_4_OVER_8 = 0.55

    /** CI measured the converted voice at 1.3-1.6x the base voice's time; phones may weigh the converter more, so assume 2x until this phone has been timed. */
    const val CUSTOM_OVER_BUILTIN = 2.0

    /**
     * Best guess of the speed for ([steps], [custom]) given what has been measured. Prefers an exact timing;
     * otherwise scales the nearest one using the ratios above. Returns null when nothing has been measured.
     */
    fun predictRtf(all: List<Measurement>, steps: Int, custom: Boolean, engine: String = ENGINE_SUPERTONIC): Double? {
        if (engine == ENGINE_POCKET) {
            val pocket = all.filter { it.engine == ENGINE_POCKET }
            pocket.firstOrNull { it.steps == steps }?.let { return it.rtf }
            val other = pocket.firstOrNull() ?: return null
            return if (steps > other.steps) other.rtf * POCKET_BETTER_OVER_FASTER else other.rtf / POCKET_BETTER_OVER_FASTER
        }
        val known = all.filter { it.engine != ENGINE_POCKET }
        known.firstOrNull { it.steps == steps && it.custom == custom }?.let { return it.rtf }
        val base = known.firstOrNull() ?: return null
        val stepFactor = when {
            base.steps == steps -> 1.0
            steps == 4 -> STEPS_4_OVER_8
            else -> 1 / STEPS_4_OVER_8
        }
        // a measured custom/built-in pair at the same step count gives a real ratio for this phone
        val ratio = known.filter { it.custom }.firstNotNullOfOrNull { c ->
            known.firstOrNull { !it.custom && it.steps == c.steps }?.let { c.rtf / it.rtf }
        } ?: CUSTOM_OVER_BUILTIN
        var rtf = base.rtf * stepFactor
        if (base.custom != custom) rtf *= if (custom) ratio else 1 / ratio
        return rtf
    }
}
