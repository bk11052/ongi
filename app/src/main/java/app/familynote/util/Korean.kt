package app.familynote.util

/** Tiny Korean grammar helpers for generated sentences. */
object Korean {
    private fun hasFinalConsonant(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        return (c - '가') % 28 != 0
    }

    /** Subject particle: 딸 → 딸이, 아빠 → 아빠가. */
    fun subject(word: String) = word + if (hasFinalConsonant(word)) "이" else "가"
}
