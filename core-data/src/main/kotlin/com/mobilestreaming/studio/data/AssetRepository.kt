package com.mobilestreaming.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AssetRepository(private val context: Context) {
    suspend fun importImage(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/")) { "Select a PNG, JPEG, GIF or WebP image" }
        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "image"
        }
        val directory = File(context.filesDir, "assets").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ASSET_BYTES) { "Image exceeds the 20 MB asset limit" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("The selected image could not be opened")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported or damaged image" }
            require(options.outWidth <= MAX_DIMENSION && options.outHeight <= MAX_DIMENSION) {
                "Image dimensions exceed $MAX_DIMENSION pixels"
            }
            Uri.fromFile(target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}

private const val MAX_ASSET_BYTES = 20L * 1024L * 1024L
private const val MAX_DIMENSION = 8192
