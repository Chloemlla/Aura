package com.google.mlkit.vision.segmentation.subject

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import java.nio.FloatBuffer

object SubjectSegmentation {
    fun getClient(options: SubjectSegmenterOptions): SubjectSegmenter = SubjectSegmenter()
}

class SubjectSegmenterOptions private constructor() {
    class Builder {
        fun enableForegroundConfidenceMask(): Builder = this
        fun build(): SubjectSegmenterOptions = SubjectSegmenterOptions()
    }
}

class SubjectSegmenter {
    fun process(image: InputImage): Task<SubjectSegmentationResult> = Task.failure()
    fun close() = Unit
}

class SubjectSegmentationResult {
    val foregroundConfidenceMask: FloatBuffer? = null
}
