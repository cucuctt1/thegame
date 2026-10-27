package dev.tilesolver.vision

import dev.tilesolver.core.VisualClusterer
import dev.tilesolver.core.VisualSignature
import dev.tilesolver.model.ClusterCandidate
import dev.tilesolver.model.ClusterId
import kotlin.math.sqrt

class HashVisualClusterer(
    private val acceptThreshold: Float = .86f,
    private val ambiguityMargin: Float = .08f,
    private val cacheCapacity: Int = 256,
) : VisualClusterer {
    private data class Prototype(val id: ClusterId, var signature: VisualSignature, var samples: Int)
    private val prototypes = mutableListOf<Prototype>()
    private var nextId = 1
    // Exact-content keys, not just perceptual hashes: two icons can share a hash.
    private class SignatureKey(val value: VisualSignature) {
        override fun hashCode(): Int = listOf(
            value.perceptualHash.hashCode(), value.colorHistogram.contentHashCode(),
            value.edgeHistogram.contentHashCode(), value.appearance.contentHashCode(),
            value.appearanceMask.contentHashCode(),
        ).fold(1) { hash, part -> 31 * hash + part }
        override fun equals(other: Any?): Boolean = other is SignatureKey &&
            value.perceptualHash == other.value.perceptualHash &&
            value.colorHistogram.contentEquals(other.value.colorHistogram) &&
            value.edgeHistogram.contentEquals(other.value.edgeHistogram) &&
            value.appearance.contentEquals(other.value.appearance) &&
            value.appearanceMask.contentEquals(other.value.appearanceMask)
    }
    private val classifications = object : LinkedHashMap<SignatureKey, List<ClusterCandidate>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SignatureKey, List<ClusterCandidate>>) =
            size > cacheCapacity
    }

    @Synchronized
    override fun classify(signature: VisualSignature): List<ClusterCandidate> {
        val key = SignatureKey(signature)
        classifications[key]?.let { return it }
        val result = prototypes.map { ClusterCandidate(it.id, similarity(signature, it.signature)) }
            .sortedByDescending { it.confidence }.take(3)
        if (cacheCapacity > 0) classifications[SignatureKey(signature.copy(
            colorHistogram = signature.colorHistogram.copyOf(), edgeHistogram = signature.edgeHistogram.copyOf(),
            appearance = signature.appearance.copyOf(), appearanceMask = signature.appearanceMask.copyOf(),
        ))] = result
        return result
    }

    @Synchronized
    override fun learn(signature: VisualSignature, accepted: ClusterId?, forceNew: Boolean): ClusterId {
        val match = accepted?.let { id -> prototypes.firstOrNull { it.id == id } }
            ?: prototypes.takeUnless { forceNew }.orEmpty().map { it to similarity(signature, it.signature) }
                .maxByOrNull { it.second }?.takeIf { it.second >= acceptThreshold }?.first
        if (match == null) {
            classifications.clear()
            return ClusterId(nextId++).also { prototypes += Prototype(it, signature, 1) }
        }
        match.samples++
        if (match.samples <= 8) {
            match.signature = blend(match.signature, signature, 1f / match.samples)
            classifications.clear()
        }
        return match.id
    }

    @Synchronized override fun clearTransient() {
        // A type may have only one exposed copy for a long time. Removing its
        // prototype on an unchanged frame makes the later two copies look like new
        // types, especially near the end of a level. Keep identities across capture
        // restarts for the same game; only discard reusable classification results.
        classifications.clear()
    }

    @Synchronized override fun reset() {
        prototypes.clear()
        classifications.clear()
        nextId = 1
    }

    private fun similarity(a: VisualSignature, b: VisualSignature): Float {
        if (a.appearanceMask.isNotEmpty()) {
            return MaskedSpriteMatcher.similarity(a.appearance, b.appearance, a.appearanceMask)
        }
        val hash = 1f - java.lang.Long.bitCount(a.perceptualHash xor b.perceptualHash) / 64f
        val color = cosine(a.colorHistogram, b.colorHistogram)
        val edge = cosine(a.edgeHistogram, b.edgeHistogram)
        if (a.appearance.isEmpty() || b.appearance.isEmpty()) {
            return .45f * hash + .10f * color + .45f * edge
        }
        val appearance = shiftedCosine(a.appearance, b.appearance)
        // Normalized sprite correlation dominates. Hash and edge shape keep unrelated
        // white-background cards apart while allowing small contour/crop offsets.
        return .20f * hash + .05f * color + .20f * edge + .55f * appearance
    }

    private fun shiftedCosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val side = sqrt(a.size.toDouble()).toInt()
        if (side * side != a.size) return cosine(a, b)
        var best = -1f
        for (dy in -2..2) for (dx in -2..2) {
            var dot = 0f; var aa = 0f; var bb = 0f
            for (y in 2 until side - 2) for (x in 2 until side - 2) {
                val bx = x + dx; val by = y + dy
                if (bx !in 0 until side || by !in 0 until side) continue
                val av = a[y * side + x]; val bv = b[by * side + bx]
                dot += av * bv; aa += av * av; bb += bv * bv
            }
            if (aa > 0f && bb > 0f) best = maxOf(best, dot / sqrt(aa * bb))
        }
        return best.coerceIn(0f, 1f)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var aa = 0f; var bb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0f || bb == 0f) 0f else dot / sqrt(aa * bb)
    }

    private fun blend(a: VisualSignature, b: VisualSignature, weight: Float) = VisualSignature(
        a.perceptualHash,
        FloatArray(a.colorHistogram.size) { a.colorHistogram[it] * (1 - weight) + b.colorHistogram[it] * weight },
        FloatArray(a.edgeHistogram.size) { a.edgeHistogram[it] * (1 - weight) + b.edgeHistogram[it] * weight },
        if (a.appearance.size == b.appearance.size) {
            FloatArray(a.appearance.size) { a.appearance[it] * (1 - weight) + b.appearance[it] * weight }
        } else a.appearance,
    )
}
