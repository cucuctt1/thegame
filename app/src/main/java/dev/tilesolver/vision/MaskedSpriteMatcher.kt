package dev.tilesolver.vision

import kotlin.math.sqrt

/** Compare only visible sprite pixels; the top card must not identify the bottom one. */
internal object MaskedSpriteMatcher {
    fun similarity(a: FloatArray, b: FloatArray, mask: FloatArray): Float {
        if (a.size != 256 || b.size != 256 || mask.size != 256) return 0f
        var best = 0f
        for (dy in -1..1) for (dx in -1..1) {
            var count = 0
            var sa = 0.0; var sb = 0.0; var aa = 0.0; var bb = 0.0; var ab = 0.0
            for (y in 2..13) for (x in 2..13) {
                val index = y * 16 + x
                if (mask[index] < .99f) continue
                val av = a[index].toDouble()
                val bv = b[(y + dy) * 16 + x + dx].toDouble()
                count++; sa += av; sb += bv; aa += av * av; bb += bv * bv; ab += av * bv
            }
            if (count < 50) continue
            val va = aa - sa * sa / count
            val vb = bb - sb * sb / count
            if (va < .01 || vb < .01) continue
            val correlation = ((ab - sa * sb / count) / sqrt(va * vb)).toFloat()
            best = maxOf(best, correlation * (.9f + .1f * count / 144f))
        }
        return best.coerceIn(0f, 1f)
    }
}
