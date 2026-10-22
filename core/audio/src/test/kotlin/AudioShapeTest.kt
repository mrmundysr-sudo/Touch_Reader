import com.mundymorning.touchreader.core.audio.AudioPolish
import com.mundymorning.touchreader.core.audio.AudioSplit
import com.mundymorning.touchreader.core.audio.AudioTrim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioShapeTest {
    private val rate = 24000
    private fun burst(ms: Int, amp: Double = 0.4) = FloatArray(rate * ms / 1000) { (amp * sin(2 * PI * 200 * it / rate)).toFloat() }
    private fun quiet(ms: Int) = FloatArray(rate * ms / 1000)
    private fun cat(vararg p: FloatArray) = p.reduce { a, b -> a + b }

    @Test fun trimKeepsTheSpeechAndALittleMarginEitherSide() {
        val x = cat(quiet(500), burst(1000), quiet(700))
        val y = AudioTrim.trim(x, rate, leadMs = 40, tailMs = 90)
        val expected = (1000 + 40 + 90) * rate / 1000
        assertTrue(abs(y.size - expected) < rate / 50, "got ${y.size}, expected about $expected")
        assertTrue(y.take(rate * 20 / 1000).all { abs(it) < 0.012f }, "starts with the quiet margin")
    }

    @Test fun trimLeavesSilentOrShortAudioAlone() {
        val silent = quiet(800)
        assertSame(silent, AudioTrim.trim(silent, rate))
        val tiny = FloatArray(10)
        assertSame(tiny, AudioTrim.trim(tiny, rate))
    }

    @Test fun trimDoesNotCutQuietSpeechThatIsAboveTheThreshold() {
        val x = cat(quiet(300), burst(500, amp = 0.05), quiet(300))
        val y = AudioTrim.trim(x, rate)
        assertTrue(y.size >= 500 * rate / 1000)
    }

    @Test fun splitCutsInThePausesBetweenSentences() {
        // sentences of 1.0 s, 2.0 s and 1.0 s of speech, separated by 300 ms pauses; weights are rough text lengths
        val s1 = burst(1000); val s2 = burst(2000); val s3 = burst(1000)
        val x = cat(s1, quiet(300), s2, quiet(300), s3)
        val pcm = ShortArray(x.size) { (x[it] * 32767).toInt().toShort() }
        val parts = AudioSplit.boundaries(pcm, listOf(25, 50, 25), rate)
        assertEquals(3, parts.size); assertEquals(pcm.size, parts[2])
        val gap1 = rate * 1000 / 1000..rate * 1300 / 1000
        val gap2 = rate * 3300 / 1000..rate * 3600 / 1000
        assertTrue(parts[0] in gap1, "first cut ${parts[0]} should be in the first pause $gap1")
        assertTrue(parts[1] in gap2, "second cut ${parts[1]} should be in the second pause $gap2")
    }

    @Test fun splitStillWorksWhenTheLengthGuessIsOff() {
        val s1 = burst(1500); val s2 = burst(1500)
        val x = cat(s1, quiet(400), s2)
        val pcm = ShortArray(x.size) { (x[it] * 32767).toInt().toShort() }
        // text lengths suggest 70/30 but the speech is 50/50: the cut should still find the pause if it is in reach
        val b = AudioSplit.boundaries(pcm, listOf(55, 45), rate)
        assertTrue(b[0] in rate * 1500 / 1000..rate * 1900 / 1000, "cut ${b[0]}")
    }

    @Test fun splitPartsAddUpAndNeverGoBackwards() {
        val pcm = ShortArray(rate) { (1000 * sin(it * 0.05)).toInt().toShort() }
        val parts = AudioSplit.split(pcm, listOf(10, 1, 1, 30), rate)
        assertEquals(4, parts.size)
        assertEquals(pcm.size, parts.sumOf { it.size })
        assertTrue(AudioSplit.boundaries(pcm, listOf(10, 1, 1, 30), rate).toList().zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun oneSentenceIsReturnedWhole() {
        val pcm = ShortArray(5000)
        assertEquals(listOf(5000), AudioSplit.split(pcm, listOf(7), rate).map { it.size })
    }
}

class AudioPolishTest {
    private val rate = 24000
    private fun speech(amp: Double, ms: Int = 1000) = FloatArray(rate * ms / 1000) { (amp * sin(2 * PI * 180 * it / rate)).toFloat() }
    private fun activeRms(x: FloatArray): Double {
        val frame = rate / 100
        var sum = 0.0; var n = 0
        var f = 0
        while (f + frame <= x.size) {
            var e = 0.0
            for (i in f until f + frame) e += x[i].toDouble() * x[i]
            if (Math.sqrt(e / frame) > 0.012) { sum += e; n += frame }
            f += frame
        }
        return Math.sqrt(sum / n)
    }

    @Test fun quietAndLoudPiecesEndUpAboutAsLoud() {
        val quiet = AudioPolish.normalizeLevel(speech(0.12), rate)
        val loud = AudioPolish.normalizeLevel(speech(0.4), rate)
        assertTrue(abs(activeRms(quiet) / activeRms(loud) - 1.0) < 0.35, "${activeRms(quiet)} vs ${activeRms(loud)}")
    }

    @Test fun pausesDoNotCountAsQuietSpeech() {
        val withPause = speech(0.3, 500) + FloatArray(rate) + speech(0.3, 500)
        val gained = AudioPolish.normalizeLevel(withPause, rate)
        val ratio = activeRms(gained) / activeRms(withPause)
        // the same speech with or without the pause gets the same gain
        val plain = AudioPolish.normalizeLevel(speech(0.3, 1000), rate)
        assertTrue(abs(ratio - activeRms(plain) / activeRms(speech(0.3, 1000))) < 0.02)
    }

    @Test fun gainIsLimitedAndNeverClips() {
        val veryQuiet = speech(0.03)
        assertTrue(activeRms(AudioPolish.normalizeLevel(veryQuiet, rate)) <= activeRms(veryQuiet) * 1.71)
        val hot = speech(0.9)
        assertTrue(AudioPolish.normalizeLevel(hot, rate).maxOf { abs(it) } <= 0.971f)
    }

    @Test fun silenceIsLeftAlone() {
        val silent = FloatArray(rate)
        assertSame(silent, AudioPolish.normalizeLevel(silent, rate))
    }

    @Test fun fadesMakeBothEndsStartAndStopAtZeroWithoutTouchingTheMiddle() {
        val x = FloatArray(rate) { 0.5f }
        val y = AudioPolish.fade(x, rate)
        assertEquals(0f, y.first(), 1e-6f); assertEquals(0f, y.last(), 1e-6f)
        assertEquals(0.5f, y[rate / 2], 1e-6f)
        val fadeIn = rate * 10 / 1000; val fadeOut = rate * 40 / 1000
        assertTrue(y[fadeIn / 2] in 0.2f..0.3f, "halfway through the fade in: ${y[fadeIn / 2]}")
        assertTrue(y[y.size - fadeOut / 2] in 0.2f..0.3f)
    }

    @Test fun veryShortAudioIsFadedWithoutErrors() {
        val y = AudioPolish.fade(FloatArray(100) { 1f }, rate)
        assertEquals(100, y.size); assertEquals(0f, y.first(), 1e-6f)
    }
}
