package app.familynote.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.familynote.util.ColorMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class NoteRepository(
    private val dao: NoteDao,
    filesDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val noteDir = File(filesDir, "notes")

    /** Decoded drawings keyed by path; sized in kilobytes (about 1/8 of the heap). */
    private val bitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    val notes: Flow<List<Note>> = dao.observeAll()

    suspend fun saveDrawing(bitmap: Bitmap, from: String, to: String, at: Long = System.currentTimeMillis()): Note = withContext(io) {
        val id = UUID.randomUUID().toString()
        noteDir.mkdirs()
        val file = File(noteDir, "$id.png")
        try {
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
            }
            val note = Note(
                id = id,
                kind = NoteKind.DRAWING,
                imagePath = file.absolutePath,
                text = null,
                fromMember = from,
                toMember = to,
                createdAt = at,
                accentColor = accentOf(bitmap),
            )
            dao.upsert(note)
            note
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun saveText(
        text: String,
        from: String,
        to: String,
        replyToId: String? = null,
        at: Long = System.currentTimeMillis(),
    ): Note = withContext(io) {
        val note = Note(
            id = UUID.randomUUID().toString(),
            kind = NoteKind.TEXT,
            imagePath = null,
            text = text.trim(),
            fromMember = from,
            toMember = to,
            createdAt = at,
            accentColor = ColorMath.WARM_DEFAULT,
            replyToId = replyToId,
        )
        dao.upsert(note)
        note
    }

    suspend fun markSeen(id: String) = withContext(io) { dao.markSeen(id) }

    suspend fun delete(note: Note) = withContext(io) {
        dao.delete(note.id)
        note.imagePath?.let { path ->
            bitmapCache.snapshot().keys.filter { it.startsWith("$path@") }.forEach { bitmapCache.remove(it) }
            File(path).delete()
        }
    }

    suspend fun isEmpty(): Boolean = withContext(io) { dao.count() == 0 }

    /** Loads a drawing scaled down to roughly [maxSide] pixels on its longest edge. */
    suspend fun loadBitmap(path: String, maxSide: Int = 1600): Bitmap? = withContext(io) {
        val key = "$path@$maxSide"
        bitmapCache.get(key)?.let { return@withContext it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp != null) bitmapCache.put(key, bmp)
        bmp
    }

    companion object {
        /** Ambient-ready accent color of a drawing, sampled on a small grid for speed. */
        fun accentOf(bitmap: Bitmap): Int {
            val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 96)
            val w = (bitmap.width + step - 1) / step
            val h = (bitmap.height + step - 1) / step
            val pixels = IntArray(w * h)
            var i = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    pixels[i++] = bitmap.getPixel(x, y)
                    x += step
                }
                y += step
            }
            val accent = ColorMath.pickAccent(pixels.copyOf(i)) ?: return ColorMath.WARM_DEFAULT
            return ColorMath.toAmbient(accent)
        }
    }
}
