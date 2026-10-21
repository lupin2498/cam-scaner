package com.camscaner.app.scanner

import kotlin.math.abs

/**
 * Robust least-squares fit of a quadratic surface z = a + bx + cy + dx² + exy + fy² to a grid.
 * Used to model the smooth illumination of a sheet of paper. Samples far below the fit
 * (ink, photos, colored blocks) are discarded between iterations.
 */
object SurfaceFit {

    fun fit(values: FloatArray, width: Int, height: Int, iterations: Int = 4, tolerance: Float = 8f): FloatArray {
        require(values.size == width * height)
        val n = values.size
        val basis = Array(n) { i ->
            val x = (i % width) / (width - 1f).coerceAtLeast(1f) * 2f - 1f
            val y = (i / width) / (height - 1f).coerceAtLeast(1f) * 2f - 1f
            doubleArrayOf(1.0, x.toDouble(), y.toDouble(), (x * x).toDouble(), (x * y).toDouble(), (y * y).toDouble())
        }
        val keep = BooleanArray(n) { true }
        var coef = DoubleArray(6).also { it[0] = values.average() }
        repeat(iterations) {
            val solved = solveLeastSquares(basis, values, keep) ?: return@repeat
            coef = solved
            var kept = 0
            for (i in 0 until n) {
                keep[i] = values[i] >= evaluate(coef, basis[i]) - tolerance
                if (keep[i]) kept++
            }
            if (kept < 30) return evaluateAll(coef, basis)
        }
        return evaluateAll(coef, basis)
    }

    private fun evaluate(c: DoubleArray, b: DoubleArray): Float {
        var s = 0.0
        for (k in 0 until 6) s += c[k] * b[k]
        return s.toFloat()
    }

    private fun evaluateAll(c: DoubleArray, basis: Array<DoubleArray>) = FloatArray(basis.size) { evaluate(c, basis[it]) }

    private fun solveLeastSquares(basis: Array<DoubleArray>, values: FloatArray, keep: BooleanArray): DoubleArray? {
        val ata = Array(6) { DoubleArray(6) }
        val atb = DoubleArray(6)
        for (i in basis.indices) {
            if (!keep[i]) continue
            val b = basis[i]
            for (r in 0 until 6) {
                atb[r] += b[r] * values[i]
                for (c in 0 until 6) ata[r][c] += b[r] * b[c]
            }
        }
        return gaussianSolve(ata, atb)
    }

    /** Solves A·x = b in place with partial pivoting. */
    fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
                val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}
