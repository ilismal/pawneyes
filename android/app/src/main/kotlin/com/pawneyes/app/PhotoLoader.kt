package com.pawneyes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.pawneyes.vision.VisionSpec
import kotlin.math.max

/**
 * Carga una foto derecha (según su EXIF) y sin más resolución de la necesaria:
 * el escáner la reduce a PHOTO_PX de lado largo, así que basta con algo más.
 */
object PhotoLoader {
    fun load(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loadWithImageDecoder(context, uri)
        else loadLegacy(context, uri)

    /** Mayor submuestreo (potencia de 2) que deja el lado largo por encima de PHOTO_PX. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= VisionSpec.PHOTO_PX) sample *= 2
        return sample
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun loadWithImageDecoder(context: Context, uri: Uri): Bitmap {
        // ImageDecoder ya aplica la orientación EXIF.
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // OpenCV no lee bitmaps de GPU
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun loadLegacy(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("no se pudo leer la imagen")
        val orientation = resolver.openInputStream(uri).use {
            ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
