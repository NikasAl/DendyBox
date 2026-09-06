package com.dendybox.app.saves

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Сейв-стейты по слотам, привязанные к хэшу ROM.
 * quick (0) — квик-слот с иконок, 1..10 — слоты из меню, AUTO (-1) — автосейв при сворачивании.
 * Рядом с .state хранится PNG-миниатюра экрана.
 */
class SaveManager(context: Context, val romHash: String) {

    companion object {
        const val AUTO = -1
        const val QUICK = 0
        const val FIRST_SLOT = 1
        const val LAST_SLOT = 10

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    private val dir = File(context.filesDir, "states/$romHash").apply { mkdirs() }

    private fun stateFile(n: Int) = File(dir, when (n) {
        AUTO -> "auto.state"
        QUICK -> "quick.state"
        else -> "slot_$n.state"
    })

    private fun thumbFile(n: Int) = File(stateFile(n).absolutePath + ".png")

    fun save(n: Int, data: ByteArray, thumb: Bitmap?) {
        stateFile(n).writeBytes(data)
        if (thumb != null) {
            val scaled = Bitmap.createScaledBitmap(thumb, 160, 150, true)
            thumbFile(n).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
    }

    fun load(n: Int): ByteArray? = stateFile(n).takeIf { it.exists() }?.readBytes()

    fun exists(n: Int): Boolean = stateFile(n).exists()

    fun modifiedAt(n: Int): Long = stateFile(n).lastModified()

    fun thumb(n: Int): Bitmap? =
        thumbFile(n).takeIf { it.exists() }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }

    fun delete(n: Int) {
        stateFile(n).delete()
        thumbFile(n).delete()
    }
}
