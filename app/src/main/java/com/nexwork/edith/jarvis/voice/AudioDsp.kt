package com.nexwork.edith.jarvis.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin DSP used by [VoiceprintGate]: a Kaldi-compatible 80-bin log-mel fbank
 * (matches `torchaudio.compliance.kaldi.fbank` / kaldi-native-fbank defaults with dither=0)
 * plus vector helpers. No Android dependencies so it runs in JVM unit tests.
 */
object AudioDsp {

  const val SAMPLE_RATE = 16_000
  private const val FRAME_LEN = 400 // 25 ms
  private const val FRAME_SHIFT = 160 // 10 ms
  private const val FFT_SIZE = 512
  const val NUM_MEL = 80
  private const val PREEMPH = 0.97f
  private const val LOW_FREQ = 20.0
  private const val FLT_EPSILON = 1.1920929e-7f

  private val poveyWindow: FloatArray = FloatArray(FRAME_LEN) { i ->
    (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_LEN - 1))).pow(0.85).toFloat()
  }

  /** [NUM_MEL] x (FFT_SIZE/2) triangular weights in Kaldi mel space (nyquist bin excluded). */
  private val melBanks: Array<FloatArray> = buildMelBanks()

  private fun mel(freq: Double): Double = 1127.0 * ln(1.0 + freq / 700.0)

  private fun buildMelBanks(): Array<FloatArray> {
    val numBins = FFT_SIZE / 2
    val melLow = mel(LOW_FREQ)
    val melHigh = mel(SAMPLE_RATE / 2.0)
    val delta = (melHigh - melLow) / (NUM_MEL + 1)
    val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE
    return Array(NUM_MEL) { b ->
      val left = melLow + b * delta
      val center = melLow + (b + 1) * delta
      val right = melLow + (b + 2) * delta
      FloatArray(numBins) { i ->
        val m = mel(binHz * i)
        if (m > left && m < right) {
          (if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)).toFloat()
        } else {
          0f
        }
      }
    }
  }

  /**
   * Computes log-mel fbank features ([frames][NUM_MEL]) with snip_edges=true.
   * @param normalizeSamples true -> samples scaled to [-1, 1]; false -> raw int16 magnitudes.
   * @param subtractMean applies per-dimension mean normalisation over time ("global-mean").
   */
  fun fbank(
    pcm: ShortArray,
    normalizeSamples: Boolean = true,
    subtractMean: Boolean = true,
    filterSilentFrames: Boolean = true,
  ): Array<FloatArray> {
    if (pcm.size < FRAME_LEN) return emptyArray()
    val numFrames = 1 + (pcm.size - FRAME_LEN) / FRAME_SHIFT
    val scale = if (normalizeSamples) 1f / 32768f else 1f
    val frame = FloatArray(FRAME_LEN)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val power = FloatArray(FFT_SIZE / 2)
    val out = Array(numFrames) { FloatArray(NUM_MEL) }

    for (f in 0 until numFrames) {
      val start = f * FRAME_SHIFT
      var mean = 0f
      for (i in 0 until FRAME_LEN) {
        val v = pcm[start + i] * scale
        frame[i] = v
        mean += v
      }
      mean /= FRAME_LEN
      for (i in 0 until FRAME_LEN) frame[i] -= mean
      for (i in FRAME_LEN - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1]
      frame[0] -= PREEMPH * frame[0]

      for (i in 0 until FFT_SIZE) {
        re[i] = if (i < FRAME_LEN) frame[i] * poveyWindow[i] else 0f
        im[i] = 0f
      }
      fftInPlace(re, im)
      for (k in 0 until FFT_SIZE / 2) power[k] = re[k] * re[k] + im[k] * im[k]

      val row = out[f]
      for (b in 0 until NUM_MEL) {
        val w = melBanks[b]
        var e = 0f
        for (k in w.indices) {
          val wk = w[k]
          if (wk != 0f) e += wk * power[k]
        }
        row[b] = ln(max(e, FLT_EPSILON))
      }
    }

    // Filter out silent / hangover frames before Cepstral Mean Normalization (CMN) so trailing
    // or intra-utterance silence (-15.9 log-energy) cannot skew the per-bin cepstral mean.
    val activeRows: Array<FloatArray> = if (filterSilentFrames && numFrames >= 24) {
      val framePeakEnergies = FloatArray(numFrames) { f ->
        val r = out[f]
        var maxBin = -16f
        // Use voiceband bins (0..59, < 4 kHz) so 8 kHz Bluetooth SCO and 16 kHz wideband behave identically.
        for (b in 0 until 60) {
          if (r[b] > maxBin) maxBin = r[b]
        }
        maxBin
      }
      val utterancePeak = framePeakEnergies.maxOrNull() ?: -16f
      val threshold = utterancePeak - 5.5f
      val voiced = ArrayList<FloatArray>(numFrames)
      for (f in 0 until numFrames) {
        if (framePeakEnergies[f] >= threshold) {
          voiced.add(out[f])
        }
      }
      if (voiced.size >= 16) voiced.toTypedArray() else out
    } else {
      out
    }

    if (subtractMean && activeRows.isNotEmpty()) {
      val means = FloatArray(NUM_MEL)
      for (row in activeRows) for (b in 0 until NUM_MEL) means[b] += row[b]
      for (b in 0 until NUM_MEL) means[b] /= activeRows.size
      for (row in activeRows) for (b in 0 until NUM_MEL) row[b] -= means[b]
    }
    return activeRows
  }

  /** Iterative radix-2 Cooley-Tukey FFT; size must be a power of two. */
  internal fun fftInPlace(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j xor bit
      if (i < j) {
        var t = re[i]; re[i] = re[j]; re[j] = t
        t = im[i]; im[i] = im[j]; im[j] = t
      }
    }
    var len = 2
    while (len <= n) {
      val ang = -2.0 * PI / len
      val wRe = cos(ang).toFloat()
      val wIm = sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var curRe = 1f
        var curIm = 0f
        for (k in 0 until len / 2) {
          val a = i + k
          val b = a + len / 2
          val tRe = re[b] * curRe - im[b] * curIm
          val tIm = re[b] * curIm + im[b] * curRe
          re[b] = re[a] - tRe
          im[b] = im[a] - tIm
          re[a] += tRe
          im[a] += tIm
          val nRe = curRe * wRe - curIm * wIm
          curIm = curRe * wIm + curIm * wRe
          curRe = nRe
        }
        i += len
      }
      len = len shl 1
    }
  }

  fun l2Normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat()
    if (n < 1e-9f) return v.copyOf()
    return FloatArray(v.size) { v[it] / n }
  }

  fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Embedding dim mismatch ${a.size} vs ${b.size}" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom < 1e-9) 0f else (dot / denom).toFloat()
  }

  /** Mean of L2-normalised embeddings, re-normalised (enrollment centroid). */
  fun centroid(embeddings: List<FloatArray>): FloatArray {
    require(embeddings.isNotEmpty())
    val dim = embeddings.first().size
    val acc = FloatArray(dim)
    for (e in embeddings) {
      val n = l2Normalize(e)
      for (i in 0 until dim) acc[i] += n[i]
    }
    return l2Normalize(acc)
  }

  fun shortsToLeBytes(pcm: ShortArray, count: Int = pcm.size): ByteArray {
    val out = ByteArray(count * 2)
    for (i in 0 until count) {
      val s = pcm[i].toInt()
      out[2 * i] = (s and 0xFF).toByte()
      out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return out
  }

  fun floatsToBytes(v: FloatArray): ByteArray {
    val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    v.forEach { bb.putFloat(it) }
    return bb.array()
  }

  fun bytesToFloats(b: ByteArray): FloatArray {
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(b.size / 4) { bb.getFloat() }
  }
}
