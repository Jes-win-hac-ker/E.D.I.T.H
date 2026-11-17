package com.nexwork.edith.jarvis.voice

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspTest {

  private fun twoTone(n: Int = 4000): ShortArray = ShortArray(n) { i ->
    (8000 * sin(2 * PI * 440 * i / 16000) + 3000 * sin(2 * PI * 1230 * i / 16000 + 0.3)).roundToInt().toShort()
  }

  /** Golden values from torchaudio.compliance.kaldi.fbank(num_mel_bins=80, dither=0). */
  @Test
  fun fbank_matchesTorchaudioKaldiGolden() {
    val feats = AudioDsp.fbank(twoTone(), normalizeSamples = true, subtractMean = false)
    assertEquals(23, feats.size)
    val golden = mapOf(
      0 to floatArrayOf(-13.4611f, -6.0077f, -13.4898f, -14.6394f),
      10 to floatArrayOf(-13.4611f, -6.0077f, -13.4898f, -14.6394f),
      22 to floatArrayOf(-12.0866f, -6.0015f, -13.6202f, -15.1718f),
    )
    val bins = intArrayOf(0, 10, 40, 79)
    for ((row, expected) in golden) {
      for (j in bins.indices) {
        assertEquals("row=$row bin=${bins[j]}", expected[j], feats[row][bins[j]], 0.02f)
      }
    }
  }

  @Test
  fun fbank_globalMeanIsZeroPerDim() {
    val feats = AudioDsp.fbank(twoTone(), subtractMean = true)
    for (b in 0 until AudioDsp.NUM_MEL) {
      val mean = feats.sumOf { it[b].toDouble() } / feats.size
      assertEquals(0.0, mean, 1e-4)
    }
  }

  @Test
  fun cosine_andCentroid() {
    val a = floatArrayOf(1f, 0f, 0f)
    val b = floatArrayOf(0f, 2f, 0f)
    assertEquals(0f, AudioDsp.cosine(a, b), 1e-6f)
    assertEquals(1f, AudioDsp.cosine(a, floatArrayOf(5f, 0f, 0f)), 1e-6f)
    val c = AudioDsp.centroid(listOf(a, b))
    assertEquals(0.7071f, c[0], 1e-3f)
    assertEquals(0.7071f, c[1], 1e-3f)
  }

  @Test
  fun floatBytesRoundTrip_andPcmLittleEndian() {
    val v = floatArrayOf(0.25f, -1.5f, 3e-4f)
    assertTrue(v.contentEquals(AudioDsp.bytesToFloats(AudioDsp.floatsToBytes(v))))
    val bytes = AudioDsp.shortsToLeBytes(shortArrayOf(0x1234, -2))
    assertEquals(0x34.toByte(), bytes[0])
    assertEquals(0x12.toByte(), bytes[1])
    assertEquals(0xFE.toByte(), bytes[2])
    assertEquals(0xFF.toByte(), bytes[3])
  }

  @Test
  fun fbank_filterSilentFrames_stripsTrailingSilenceFromCmn() {
    val voiced = twoTone(n = 8000) // ~50 frames
    val withSilence = ShortArray(16000) { idx -> if (idx < voiced.size) voiced[idx] else 0 }
    val featsClean = AudioDsp.fbank(voiced, subtractMean = true, filterSilentFrames = true)
    val featsFiltered = AudioDsp.fbank(withSilence, subtractMean = true, filterSilentFrames = true)
    // Silent half should be stripped so frame count stays close to the voiced-only clip
    assertTrue("Expected silent frames to be filtered, got ${featsFiltered.size}", featsFiltered.size in 45..55)
    assertEquals(featsClean[10][10], featsFiltered[10][10], 0.15f)
  }
}
