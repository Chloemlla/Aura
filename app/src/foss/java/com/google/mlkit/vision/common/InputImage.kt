package com.google.mlkit.vision.common

import android.graphics.Bitmap

class InputImage private constructor() {
    companion object {
        fun fromBitmap(bitmap: Bitmap, rotationDegrees: Int): InputImage = InputImage()
    }
}
