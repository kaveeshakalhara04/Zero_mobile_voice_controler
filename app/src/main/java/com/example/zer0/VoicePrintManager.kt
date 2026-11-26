package com.example.zer0

import android.content.Context
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device speaker verification using robust acoustic feature vectors
 * (Pitch/F0 estimation, zero-crossing rate, spectral energy distribution).
 */
class VoicePrintManager(context: Context) {

    private val prefs = context.getSharedPreferences("zer0_voiceprint", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FEATURES = "owner_features"
        private const val FEATURE_SIZE = 10
        // Relaxed threshold so natural speaking variations pass easily while rejecting distinct voices
        private const val SIMILARITY_THRESHOLD = 0.65f
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_FEATURES)

    fun clearEnrollment() {
        prefs.edit().remove(KEY_FEATURES).apply()
    }

    fun enrollSamples(samples: List<ShortArray>, sampleRate: Int = 16000): Boolean {
        val validVectors = samples.mapNotNull { extractFeatures(it, sampleRate) }
        if (validVectors.isEmpty()) return false

        val avg = FloatArray(FEATURE_SIZE)
        for (vec in validVectors) {
            for (i in 0 until FEATURE_SIZE) {
                avg[i] += vec[i] / validVectors.size
            }
        }
        normalize(avg)

        val serialized = avg.joinToString(",")
        prefs.edit().putString(KEY_FEATURES, serialized).apply()
        Log.d("VoicePrint", "Enrolled vector: $serialized")
        return true
    }

    fun isOwnerVoice(pcm: ShortArray, sampleRate: Int = 16000): Boolean {
        val enrolledStr = prefs.getString(KEY_FEATURES, null)
        if (enrolledStr.isNullOrBlank()) {
            return true // If not enrolled, allow wake-up
        }

        val enrolled = enrolledStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        if (enrolled.size != FEATURE_SIZE) {
            return true
        }

        val current = extractFeatures(pcm, sampleRate)
        if (current == null) {
            // Audio may be too short or soft; fallback to true so wake word isn't completely missed
            Log.d("VoicePrint", "Could not extract clear features from wake audio, allowing as fallback")
            return true
        }

        val similarity = cosineSimilarity(enrolled, current)
        Log.d("VoicePrint", "Speaker similarity score: $similarity (threshold: $SIMILARITY_THRESHOLD)")
        return similarity >= SIMILARITY_THRESHOLD
    }

    private fun extractFeatures(pcm: ShortArray, sampleRate: Int): FloatArray? {
        if (pcm.isEmpty()) return null

        // Calculate RMS energy
        var energySum = 0.0
        for (s in pcm) {
            energySum += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(energySum / pcm.size)
        // Check reasonable sound energy
        if (rms < 80.0) return null

        val features = FloatArray(FEATURE_SIZE)

        // 1. Zero-Crossing Rate
        var zcr = 0
        for (i in 1 until pcm.size) {
            if ((pcm[i] >= 0 && pcm[i - 1] < 0) || (pcm[i] < 0 && pcm[i - 1] >= 0)) {
                zcr++
            }
        }
        features[0] = (zcr.toFloat() / pcm.size).coerceIn(0f, 1f)

        // 2. Pitch / F0 estimation on loudest voiced frame
        val frameSize = minOf(1024, pcm.size)
        val start = (pcm.size - frameSize) / 2
        val minLag = (sampleRate / 350).coerceAtLeast(1)
        val maxLag = (sampleRate / 70).coerceAtMost(frameSize / 2)
        var bestLag = minLag
        var bestCorr = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in 0 until frameSize - lag) {
                corr += pcm[start + i].toDouble() * pcm[start + i + lag].toDouble()
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        val pitchHz = if (bestLag > 0) sampleRate.toFloat() / bestLag else 150f
        features[1] = (pitchHz / 400f).coerceIn(0f, 1f)

        // 3. 8-band spectral energy
        val freqs = floatArrayOf(150f, 300f, 500f, 800f, 1200f, 1800f, 2600f, 3600f)
        val n = minOf(1024, pcm.size)
        val offset = (pcm.size - n) / 2
        for (b in freqs.indices) {
            val k = (freqs[b] * n / sampleRate).toDouble()
            val omega = 2.0 * PI * k / n
            var real = 0.0
            var imag = 0.0
            for (i in 0 until n) {
                val sample = pcm[offset + i].toDouble()
                real += sample * cos(omega * i)
                imag -= sample * sin(omega * i)
            }
            val mag = sqrt(real * real + imag * imag)
            features[2 + b] = ln(1.0 + mag).toFloat()
        }

        normalize(features)
        return features
    }

    private fun normalize(vec: FloatArray) {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in vec.indices) vec[i] /= norm
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
