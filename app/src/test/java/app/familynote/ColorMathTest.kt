package app.familynote

import app.familynote.util.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    private val red = 0xFFE53935.toInt()
    private val blue = 0xFF1E88E5.toInt()

    @Test
    fun hslRoundTripKeepsColor() {
        for (c in listOf(red, blue, ColorMath.WARM_DEFAULT, 0xFF43A047.toInt())) {
            val hsl = ColorMath.toHsl(c)
            val back = ColorMath.fromHsl(hsl[0], hsl[1], hsl[2])
            assertTrue(abs(ColorMath.red(back) - ColorMath.red(c)) <= 1)
            assertTrue(abs(ColorMath.green(back) - ColorMath.green(c)) <= 1)
            assertTrue(abs(ColorMath.blue(back) - ColorMath.blue(c)) <= 1)
        }
    }

    @Test
    fun blankPaperHasNoAccent() {
        assertNull(ColorMath.pickAccent(IntArray(500) { ColorMath.PAPER }))
    }

    @Test
    fun grayInkIsIgnored() {
        assertNull(ColorMath.pickAccent(IntArray(500) { 0xFF555555.toInt() }))
    }

    @Test
    fun dominantInkWinsOverPaper() {
        val pixels = IntArray(1000) { i ->
            when {
                i < 700 -> ColorMath.PAPER
                i < 950 -> blue
                else -> red
            }
        }
        val accent = ColorMath.pickAccent(pixels)
        assertNotNull(accent)
        assertEquals(blue, accent)
    }

    @Test
    fun ambientIsNeverDimOrGray() {
        val dim = ColorMath.toAmbient(0xFF201010.toInt())
        assertTrue(ColorMath.toHsl(dim)[2] >= 0.49f)
        assertEquals(ColorMath.WARM_DEFAULT, ColorMath.toAmbient(0xFF808080.toInt()))
    }

    @Test
    fun stepsEndExactlyAtTarget() {
        val s = ColorMath.steps(red, blue, 8)
        assertEquals(8, s.size)
        assertEquals(blue, s.last())
        assertEquals(1, ColorMath.steps(red, blue, 1).size)
    }

    @Test
    fun lerpEndpoints() {
        assertEquals(red, ColorMath.lerp(red, blue, 0f))
        assertEquals(blue, ColorMath.lerp(red, blue, 1f))
    }

    @Test
    fun paleSkinTonesDoNotBeatTheMainColor() {
        val skin = 0xFFF2C9A0.toInt()   // pale crayon skin / beige wall
        val pixels = IntArray(1000) { i ->
            when {
                i < 500 -> ColorMath.PAPER
                i < 800 -> skin
                else -> blue
            }
        }
        assertEquals(blue, ColorMath.pickAccent(pixels))
    }
}
