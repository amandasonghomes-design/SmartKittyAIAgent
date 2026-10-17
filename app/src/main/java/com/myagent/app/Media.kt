package com.myagent.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

/** Photo helpers: a place for the camera to save, and loading photos at a sensible size. */
object Media {
    private const val MAX_SIDE = 1280

    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest photo
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Saves a picture into the phone's gallery (Pictures/猫猫小助手). */
    fun saveToGallery(context: Context, image: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kitty_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/猫猫小助手")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        }.getOrDefault(false)
    }

    /** Loads a photo, upright and scaled down so it's quick to send. */
    fun load(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Couldn't read that photo.")
    }
}
