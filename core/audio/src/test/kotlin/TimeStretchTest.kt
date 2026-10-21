import com.mundymorning.touchreader.core.audio.TimeStretch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeStretchTest {
    private val rate = 24000
    private fun tone(hz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size) = sqrt((from until to).sumOf { (a[it] * a[it]).toDouble() } / (to - from))
    private fun crossings(a: FloatArray) = (1 until a.size).count { a[it - 1] < 0 && a[it] >= 0 }

    @Test fun normalPaceReturnsTheSameAudio() {
        val x = tone(220.0, 1.0)
        assertSame(x, TimeStretch.stretch(x, 1.0))
    }

    @Test fun slowerPaceMakesItLongerByTheRightAmount() {
        val x = tone(220.0, 2.0)
        for (pace in listOf(0.95, 0.9, 0.85, 0.8)) {
            val y = TimeStretch.stretch(x, pace)
            assertTrue(abs(y.size / (x.size / pace) - 1.0) < 0.02, "pace $pace gave ${y.size} samples from ${x.size}")
        }
    }

    @Test fun pitchIsPreserved() {
        val x = tone(220.0, 2.0)
        val y = TimeStretch.stretch(x, 0.8)
        val hzIn = crossings(x) / 2.0
        val hzOut = crossings(y) / (y.size.toDouble() / rate)
        assertTrue(abs(hzOut / hzIn - 1.0) < 0.03, "pitch moved from $hzIn to $hzOut")
    }

    @Test fun loudnessStaysTheSameThroughoutIncludingTheEnds() {
        val x = tone(300.0, 2.0)
        val y = TimeStretch.stretch(x, 0.85)
        val want = rms(x)
        for ((a, b) in listOf(0 to 4000, y.size / 2 - 2000 to y.size / 2 + 2000, y.size - 4000 to y.size)) {
            assertTrue(abs(rms(y, a, b) / want - 1.0) < 0.12, "level in $a..$b is ${rms(y, a, b)} against $want")
        }
    }

    @Test fun speechLikeSignalWithVaryingPitchKeepsItsShape() {
        // a sweep from 120 to 180 Hz: the stretched version should still sweep over the same range
        val n = rate * 2
        var phase = 0.0
        val x = FloatArray(n) { i -> phase += 2 * PI * (120 + 60.0 * i / n) / rate; (0.5 * sin(phase)).toFloat() }
        val y = TimeStretch.stretch(x, 0.8)
        val firstThird = crossings(y.copyOfRange(0, y.size / 3)) / (y.size / 3.0 / rate)
        val lastThird = crossings(y.copyOfRange(y.size * 2 / 3, y.size)) / (y.size / 3.0 / rate)
        assertTrue(firstThird in 115.0..145.0, "start pitch $firstThird")
        assertTrue(lastThird in 155.0..185.0, "end pitch $lastThird")
    }

    @Test fun tooShortToProcessIsReturnedAsIs() {
        val x = tone(220.0, 0.05)
        assertEquals(x.size, TimeStretch.stretch(x, 0.8).size)
    }
}
