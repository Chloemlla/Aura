package com.freevibe.service

import android.graphics.Bitmap
import java.io.File

object BitmapSampling {
    fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val targetWidth = reqWidth.coerceAtLeast(1)
        val targetHeight = reqHeight.coerceAtLeast(1)
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return decodeImageFile(file, maxLongEdge = maxOf(targetWidth, targetHeight))
    }

    fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (rawWidth <= 0 || rawHeight <= 0) return 1

        val targetWidth = reqWidth.coerceAtLeast(1)
        val targetHeight = reqHeight.coerceAtLeast(1)
        var sampleSize = 1

        while (rawWidth / (sampleSize * 2) >= targetWidth &&
            rawHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }

        return sampleSize
    }
}
