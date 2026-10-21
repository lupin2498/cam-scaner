package com.camscaner.app.scanner

import org.opencv.core.Mat

/** Tracks OpenCV matrices created inside a block and releases their native memory afterwards. */
class MatScope {
    private val tracked = ArrayList<Mat>()

    fun <M : Mat> M.track(): M {
        tracked += this
        return this
    }

    fun mat(): Mat = Mat().track()

    fun release() {
        tracked.forEach { it.release() }
        tracked.clear()
    }
}

inline fun <T> withMats(block: MatScope.() -> T): T {
    val scope = MatScope()
    try {
        return scope.block()
    } finally {
        scope.release()
    }
}
