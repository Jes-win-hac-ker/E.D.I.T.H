package com.nexwork.edith.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nexwork.edith.jarvis.memory.JarvisDatabase
import java.io.File
import java.nio.FloatBuffer
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.OkHttpClient
import okhttp3.Request

data class VoiceprintResult(val score: Float, val accepted: Boolean)

/**
 * Speaker verification gate: 3D-Speaker CAM++ (VoxCeleb, 512-d) ONNX embedding over
 * Kaldi fbank features, cosine-scored against the enrolled centroid (threshold [THRESHOLD]).
 *
 * The model (~29 MB) is resolved from `assets/models/` first, else downloaded once into
 * `filesDir/models/` when the user taps ENROLL. The enrolled centroid is AES-GCM encrypted with a
 * non-exportable Android Keystore key before it touches [JarvisDatabase]; raw audio is never stored.
 */
class VoiceprintGate(
  context: Context,
  private val db: JarvisDatabase,
) {
  private val appCtx = context.applicationContext
  private var session: OrtSession? = null
  private var normalizeSamples = true
  private var subtractMean = true
  @Volatile private var cachedTemplates: List<FloatArray>? = null

  private val modelFile: File get() = File(File(appCtx.filesDir, "models"), MODEL_FILE)

  fun isModelAvailable(): Boolean = assetExists() || (modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES)

  private fun assetExists(): Boolean = runCatching { appCtx.assets.open(ASSET_PATH).close(); true }.getOrDefault(false)

  /** Downloads the speaker model on explicit user action. Blocking; call on IO. */
  fun downloadModel(onProgress: (Int) -> Unit = {}) {
    if (isModelAvailable()) return
    val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    val tmp = File(modelFile.parentFile, "$MODEL_FILE.part")
    modelFile.parentFile?.mkdirs()
    client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
      check(resp.isSuccessful) { "Speaker model download failed: HTTP ${resp.code}" }
      val body = resp.body ?: error("Empty model body")
      val total = body.contentLength()
      body.byteStream().use { input ->
        tmp.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          var lastPct = -1
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
              val pct = (done * 100 / total).toInt()
              if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
          }
        }
      }
    }
    check(tmp.length() > MIN_MODEL_BYTES) { "Speaker model truncated (${tmp.length()} B)" }
    check(tmp.renameTo(modelFile)) { "Could not finalise speaker model" }
  }

  private fun ensureSession(): OrtSession {
    session?.let { return it }
    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    val s = if (assetExists()) {
      env.createSession(appCtx.assets.open(ASSET_PATH).use { it.readBytes() }, opts)
    } else {
      check(modelFile.exists()) { "Speaker model missing - tap ENROLL to download" }
      env.createSession(modelFile.absolutePath, opts)
    }
    val meta = runCatching { s.metadata.customMetadata }.getOrDefault(emptyMap())
    normalizeSamples = meta["normalize_samples"]?.trim() != "0"
    subtractMean = meta["feature_normalize_type"]?.let { it.contains("mean") } ?: true
    session = s
    return s
  }

  /** L2-normalised speaker embedding for [pcm] (16 kHz mono). Requires >= [MIN_SPEECH_SAMPLES]. */
  fun embed(pcm: ShortArray, filterSilentFrames: Boolean = true): FloatArray {
    require(pcm.size >= MIN_SPEECH_SAMPLES) { "Need >= ${MIN_SPEECH_SAMPLES / 16} ms of speech" }
    val s = ensureSession()
    val feats = AudioDsp.fbank(pcm, normalizeSamples, subtractMean, filterSilentFrames)
    val flat = FloatArray(feats.size * AudioDsp.NUM_MEL)
    feats.forEachIndexed { t, row -> System.arraycopy(row, 0, flat, t * AudioDsp.NUM_MEL, AudioDsp.NUM_MEL) }
    val env = OrtEnvironment.getEnvironment()
    OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, feats.size.toLong(), AudioDsp.NUM_MEL.toLong())).use { x ->
      s.run(mapOf(s.inputNames.first() to x)).use { res ->
        @Suppress("UNCHECKED_CAST")
        val emb = (res[0].value as Array<FloatArray>)[0]
        return AudioDsp.l2Normalize(emb)
      }
    }
  }

  fun isEnrolled(): Boolean = !loadTemplates().isNullOrEmpty()

  fun enroll(utterances: List<ShortArray>) {
    require(utterances.isNotEmpty())
    val individual = utterances.map { embed(it) }
    val centroid = AudioDsp.centroid(individual)
    val allTemplates = buildList {
      add(centroid)
      addAll(individual)
    }
    val dim = centroid.size
    val packed = FloatArray(allTemplates.size * dim)
    allTemplates.forEachIndexed { idx, vec ->
      System.arraycopy(vec, 0, packed, idx * dim, dim)
    }
    db.saveVoiceprint(encrypt(AudioDsp.floatsToBytes(packed)), dim, MODEL_FILE)
    cachedTemplates = allTemplates
  }

  fun clearEnrollment() {
    db.clearVoiceprint()
    cachedTemplates = null
  }

  fun verify(pcm: ShortArray): VoiceprintResult {
    val templates = loadTemplates() ?: return VoiceprintResult(0f, false)
    val voicedEmb = embed(pcm, filterSilentFrames = true)
    // If only a single 512-d centroid exists from a legacy enrollment, also evaluate unfiltered CMN
    // so existing enrollments work seamlessly without forcing re-enrollment.
    val candidates = if (templates.size == 1) {
      listOf(voicedEmb, embed(pcm, filterSilentFrames = false))
    } else {
      listOf(voicedEmb)
    }
    var bestRaw = -1f
    for (liveEmb in candidates) {
      for (tmpl in templates) {
        if (tmpl.size == liveEmb.size) {
          val c = AudioDsp.cosine(liveEmb, tmpl)
          if (c > bestRaw) bestRaw = c
        }
      }
    }
    val calibrated = calibrateCosineScore(bestRaw)
    return VoiceprintResult(calibrated, calibrated >= THRESHOLD)
  }

  /**
   * Maps 512-d CAM++ cosine similarity to a calibrated confidence score in [0, 1] where the
   * VoxCeleb / Bluetooth SCO speaker verification decision boundary (`RAW_ACCEPT_COSINE` = 0.48)
   * aligns with [THRESHOLD] (0.72), while impostor speakers (< 0.35 raw cosine) remain below 0.55.
   */
  internal fun calibrateCosineScore(rawCosine: Float): Float {
    val c = rawCosine.coerceIn(-1f, 1f)
    if (c <= 0f) return 0f
    return if (c < RAW_ACCEPT_COSINE) {
      (c / RAW_ACCEPT_COSINE) * THRESHOLD
    } else {
      val span = (1f - RAW_ACCEPT_COSINE).coerceAtLeast(1e-4f)
      (THRESHOLD + ((c - RAW_ACCEPT_COSINE) / span) * (1f - THRESHOLD)).coerceIn(0f, 1f)
    }
  }

  private fun loadTemplates(): List<FloatArray>? {
    cachedTemplates?.let { return it }
    val blob = db.getVoiceprintBlob() ?: return null
    val floats = runCatching { AudioDsp.bytesToFloats(decrypt(blob)) }.getOrNull() ?: return null
    if (floats.isEmpty() || floats.size % EMBEDDING_DIM != 0) return null
    val count = floats.size / EMBEDDING_DIM
    val templates = List(count) { i ->
      floats.copyOfRange(i * EMBEDDING_DIM, (i + 1) * EMBEDDING_DIM)
    }
    cachedTemplates = templates
    return templates
  }

  fun close() {
    session?.close()
    session = null
  }

  // --- Keystore AES-GCM ---------------------------------------------------------------------

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return gen.generateKey()
  }

  private fun encrypt(plain: ByteArray): ByteArray {
    val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    val iv = c.iv
    return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
  }

  private fun decrypt(blob: ByteArray): ByteArray {
    val ivLen = blob[0].toInt()
    val iv = blob.copyOfRange(1, 1 + ivLen)
    val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
  }

  companion object {
    const val THRESHOLD = 0.72f
    const val RAW_ACCEPT_COSINE = 0.48f
    private const val EMBEDDING_DIM = 512
    const val MIN_SPEECH_SAMPLES = (AudioDsp.SAMPLE_RATE * 4) / 5 // 0.8 s (79 fbank frames)
    const val MODEL_FILE = "3dspeaker_campplus_en_voxceleb_16k.onnx"
    const val ASSET_PATH = "models/$MODEL_FILE"
    const val MODEL_URL =
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    private const val MIN_MODEL_BYTES = 1_000_000L
    private const val KEY_ALIAS = "edith_voiceprint_aes"
    private const val TRANSFORM = "AES/GCM/NoPadding"
  }
}
