package app.familynote

import app.familynote.util.Korean
import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanTest {
    @Test
    fun subjectParticle() {
        assertEquals("딸이", Korean.subject("딸"))
        assertEquals("아빠가", Korean.subject("아빠"))
        assertEquals("우리 가족이", Korean.subject("우리 가족"))
        assertEquals("엄마가", Korean.subject("엄마"))
        assertEquals("Mia가", Korean.subject("Mia"))
    }
}
