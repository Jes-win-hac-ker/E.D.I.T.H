package com.nexwork.edith.jarvis.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nexwork.edith.jarvis.brain.JarvisLanguagePrompt
import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.ContactPersona
import com.nexwork.edith.jarvis.core.EventSource
import com.nexwork.edith.jarvis.core.EventStatus
import com.nexwork.edith.jarvis.core.GroupEngagementRecord
import com.nexwork.edith.jarvis.core.JarvisEventRecord
import com.nexwork.edith.jarvis.core.JarvisRule
import com.nexwork.edith.jarvis.core.NotificationTriage
import com.nexwork.edith.jarvis.core.PlaceTag
import com.nexwork.edith.jarvis.core.SavedPlaceProfile
import com.nexwork.edith.jarvis.core.ScheduledEvent
import com.nexwork.edith.jarvis.core.ThreadMessage
import com.nexwork.edith.jarvis.core.TriageStatus
import com.nexwork.edith.jarvis.core.TrustedContact
import com.nexwork.edith.jarvis.core.UpcomingDestination
import com.nexwork.edith.jarvis.security.SecurityGateway
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lightweight native SQLite store configured with Write-Ahead Logging (WAL) for low memory
 * overhead and concurrent read/write safety on Moto G45.
 */
class JarvisDatabase(
  context: Context,
  dbName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  init {
    setWriteAheadLoggingEnabled(true)
  }

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.enableWriteAheadLogging()
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_EVENT_LOG (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_SBN_KEY TEXT NOT NULL,
        $COL_PACKAGE_NAME TEXT NOT NULL,
        $COL_APP_LABEL TEXT NOT NULL DEFAULT '',
        $COL_SENDER TEXT NOT NULL,
        $COL_TITLE TEXT NOT NULL,
        $COL_BODY TEXT NOT NULL,
        $COL_THREAD_JSON TEXT,
        $COL_CONTENT_HASH TEXT NOT NULL,
        $COL_HAS_REMOTE_INPUT INTEGER NOT NULL DEFAULT 0,
        $COL_TRIAGE_STATUS TEXT NOT NULL,
        $COL_TRIAGE_JSON TEXT,
        $COL_TRIAGE_SENDER TEXT,
        $COL_TRIAGE_INTENT TEXT,
        $COL_TRIAGE_URGENCY TEXT,
        $COL_SUGGESTED_REPLY TEXT,
        $COL_ERROR_MESSAGE TEXT,
        $COL_REPLY_SENT TEXT,
        $COL_CREATED_AT_MS INTEGER NOT NULL,
        $COL_TRIAGED_AT_MS INTEGER
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_event_log_created_at ON $TABLE_EVENT_LOG($COL_CREATED_AT_MS DESC, $COL_ID DESC)"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_event_log_sbn_key ON $TABLE_EVENT_LOG($COL_SBN_KEY)"
    )

    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_USER_RULEBOOK (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_RULE_KEY TEXT NOT NULL UNIQUE,
        $COL_RULE_TYPE TEXT NOT NULL,
        $COL_PATTERN TEXT NOT NULL,
        $COL_INSTRUCTION TEXT NOT NULL,
        $COL_ENABLED INTEGER NOT NULL DEFAULT 1,
        $COL_CREATED_AT_MS INTEGER NOT NULL,
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_rulebook_enabled ON $TABLE_USER_RULEBOOK($COL_ENABLED)"
    )
    createV2Tables(db)
    createV3Tables(db)
    createV4Tables(db)
    createV5Tables(db)
    createV6Tables(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      createV2Tables(db)
    }
    if (oldVersion < 3) {
      createV3Tables(db)
    }
    if (oldVersion < 4) {
      upgradeToV4(db)
    }
    if (oldVersion < 5) {
      createV5Tables(db)
    }
    if (oldVersion < 6) {
      createV6Tables(db)
    }
  }

  private fun createV6Tables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_SCHEDULED_EVENTS (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_EVENT_TITLE TEXT NOT NULL,
        $COL_START_EPOCH_MS INTEGER NOT NULL,
        $COL_END_EPOCH_MS INTEGER NOT NULL,
        $COL_EVENT_LOCATION TEXT NOT NULL DEFAULT '',
        $COL_EVENT_SOURCE TEXT NOT NULL DEFAULT 'MESSAGE',
        $COL_SENDER TEXT NOT NULL DEFAULT '',
        $COL_SOURCE_SUMMARY TEXT NOT NULL DEFAULT '',
        $COL_EVENT_STATUS TEXT NOT NULL DEFAULT 'ACTIVE',
        $COL_CREATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_scheduled_events_start ON $TABLE_SCHEDULED_EVENTS($COL_START_EPOCH_MS ASC)"
    )
  }

  private fun createV5Tables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_VOICEPRINT (
        $COL_ID INTEGER PRIMARY KEY CHECK ($COL_ID = 1),
        $COL_EMBEDDING_BLOB BLOB NOT NULL,
        $COL_EMBEDDING_DIM INTEGER NOT NULL,
        $COL_MODEL_ID TEXT NOT NULL,
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
  }

  /** Stores the (already Keystore-encrypted) enrolled speaker centroid. Single-row table. */
  fun saveVoiceprint(encryptedBlob: ByteArray, dim: Int, modelId: String, nowMs: Long = System.currentTimeMillis()) {
    val values = ContentValues().apply {
      put(COL_ID, 1)
      put(COL_EMBEDDING_BLOB, encryptedBlob)
      put(COL_EMBEDDING_DIM, dim)
      put(COL_MODEL_ID, modelId)
      put(COL_UPDATED_AT_MS, nowMs)
    }
    writableDatabase.insertWithOnConflict(TABLE_VOICEPRINT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getVoiceprintBlob(): ByteArray? =
    readableDatabase.query(TABLE_VOICEPRINT, arrayOf(COL_EMBEDDING_BLOB), "$COL_ID = 1", null, null, null, null)
      .use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

  fun clearVoiceprint() {
    writableDatabase.delete(TABLE_VOICEPRINT, null, null)
  }

  /**
   * Keyword search across recent notifications/messages, active user rules, saved places,
   * and upcoming destinations for Gemini Live's `search_memory` tool.
   */
  fun searchMemory(query: String, limit: Int = 6): List<String> {
    val clean = query.trim().lowercase()
    if (clean.isEmpty()) return emptyList()
    val likeArg = "%$clean%"
    val hits = mutableListOf<String>()

    readableDatabase.query(
      TABLE_EVENT_LOG,
      arrayOf(COL_APP_LABEL, COL_SENDER, COL_TITLE, COL_BODY, COL_TRIAGE_INTENT, COL_CREATED_AT_MS),
      "LOWER($COL_SENDER) LIKE ? OR LOWER($COL_TITLE) LIKE ? OR LOWER($COL_BODY) LIKE ? OR LOWER(COALESCE($COL_TRIAGE_INTENT,'')) LIKE ?",
      arrayOf(likeArg, likeArg, likeArg, likeArg),
      null,
      null,
      "$COL_CREATED_AT_MS DESC",
      limit.coerceAtLeast(1).toString(),
    ).use { c ->
      while (c.moveToNext()) {
        val app = c.getString(0).orEmpty()
        val sender = c.getString(1).orEmpty().ifBlank { c.getString(2).orEmpty() }
        val body = c.getString(3).orEmpty().take(120)
        val intent = c.getString(4).orEmpty()
        hits += if (intent.isNotBlank()) "[$app] $sender ($intent): $body" else "[$app] $sender: $body"
      }
    }

    if (hits.size < limit) {
      readableDatabase.query(
        TABLE_USER_RULEBOOK,
        arrayOf(COL_RULE_TYPE, COL_PATTERN, COL_INSTRUCTION),
        "$COL_ENABLED = 1 AND (LOWER($COL_PATTERN) LIKE ? OR LOWER($COL_INSTRUCTION) LIKE ?)",
        arrayOf(likeArg, likeArg),
        null,
        null,
        "$COL_UPDATED_AT_MS DESC",
        (limit - hits.size).toString(),
      ).use { c ->
        while (c.moveToNext()) {
          hits += "[RULE:${c.getString(0)}] ${c.getString(1)} -> ${c.getString(2)}"
        }
      }
    }

    if (hits.size < limit) {
      readableDatabase.query(
        TABLE_UPCOMING_DESTINATIONS,
        arrayOf(COL_PLACE_NAME, COL_SOURCE_SUMMARY),
        "LOWER($COL_PLACE_NAME) LIKE ? OR LOWER($COL_SOURCE_SUMMARY) LIKE ?",
        arrayOf(likeArg, likeArg),
        null,
        null,
        "$COL_CREATED_AT_MS DESC",
        (limit - hits.size).toString(),
      ).use { c ->
        while (c.moveToNext()) {
          hits += "[DEST] ${c.getString(0)} (${c.getString(1)})"
        }
      }
    }

    return hits.take(limit)
  }

  private fun createV2Tables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_UI_PLAYBOOKS (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_SKILL_ID TEXT NOT NULL UNIQUE,
        $COL_TRIGGER_INTENT TEXT NOT NULL,
        $COL_TARGET_PACKAGE TEXT NOT NULL,
        $COL_STEPS_JSON TEXT NOT NULL,
        $COL_SUCCESS_COUNT INTEGER NOT NULL DEFAULT 0,
        $COL_CREATED_AT_MS INTEGER NOT NULL,
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_playbooks_intent ON $TABLE_UI_PLAYBOOKS($COL_TRIGGER_INTENT, $COL_SUCCESS_COUNT DESC)"
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_TRUSTED_CONTACTS (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_CONTACT_KEY TEXT NOT NULL UNIQUE,
        $COL_DISPLAY_NAME TEXT NOT NULL,
        $COL_PACKAGE_NAME TEXT NOT NULL DEFAULT '',
        $COL_AUTO_REPLY_ENABLED INTEGER NOT NULL DEFAULT 1,
        $COL_MAX_TIER INTEGER NOT NULL DEFAULT 2,
        $COL_CREATED_AT_MS INTEGER NOT NULL,
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_trusted_contacts_name ON $TABLE_TRUSTED_CONTACTS($COL_DISPLAY_NAME)"
    )
  }

  private fun createV3Tables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_UPCOMING_DESTINATIONS (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_PLACE_NAME TEXT NOT NULL,
        $COL_SOURCE_SUMMARY TEXT NOT NULL,
        $COL_EXPIRES_AT INTEGER NOT NULL,
        $COL_VALID_FROM_EPOCH INTEGER NOT NULL DEFAULT 0,
        $COL_VALID_UNTIL_EPOCH INTEGER NOT NULL DEFAULT 0,
        $COL_USED INTEGER NOT NULL DEFAULT 0,
        $COL_CREATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_destinations_active ON $TABLE_UPCOMING_DESTINATIONS($COL_USED, $COL_VALID_FROM_EPOCH, $COL_VALID_UNTIL_EPOCH DESC, $COL_EXPIRES_AT DESC)"
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_SAVED_PLACES (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_SSID_NORMALIZED TEXT NOT NULL UNIQUE,
        $COL_DISPLAY_SSID TEXT NOT NULL,
        $COL_PLACE_TYPE TEXT NOT NULL,
        $COL_AUTO_FOCUS_QUIET INTEGER NOT NULL DEFAULT 1,
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
  }

  private fun upgradeToV4(db: SQLiteDatabase) {
    runCatching {
      db.execSQL("ALTER TABLE $TABLE_UPCOMING_DESTINATIONS ADD COLUMN $COL_VALID_FROM_EPOCH INTEGER NOT NULL DEFAULT 0")
    }
    runCatching {
      db.execSQL("ALTER TABLE $TABLE_UPCOMING_DESTINATIONS ADD COLUMN $COL_VALID_UNTIL_EPOCH INTEGER NOT NULL DEFAULT 0")
    }
    db.execSQL(
      "UPDATE $TABLE_UPCOMING_DESTINATIONS SET $COL_VALID_UNTIL_EPOCH = $COL_EXPIRES_AT WHERE $COL_VALID_UNTIL_EPOCH = 0"
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_destinations_window ON $TABLE_UPCOMING_DESTINATIONS($COL_USED, $COL_VALID_FROM_EPOCH, $COL_VALID_UNTIL_EPOCH DESC)"
    )
    createV4Tables(db)
  }

  private fun createV4Tables(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_CONTACT_PERSONAS (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_CONTACT_KEY TEXT NOT NULL UNIQUE,
        $COL_DISPLAY_NAME TEXT NOT NULL,
        $COL_PACKAGE_NAME TEXT NOT NULL DEFAULT '',
        $COL_RELATIONSHIP_TAG TEXT NOT NULL DEFAULT 'general',
        $COL_SENT_SAMPLES_JSON TEXT NOT NULL DEFAULT '[]',
        $COL_UPDATED_AT_MS INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE INDEX IF NOT EXISTS idx_personas_name ON $TABLE_CONTACT_PERSONAS($COL_DISPLAY_NAME)"
    )
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS $TABLE_GROUP_ENGAGEMENT (
        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $COL_GROUP_KEY TEXT NOT NULL UNIQUE,
        $COL_PACKAGE_NAME TEXT NOT NULL,
        $COL_GROUP_NAME TEXT NOT NULL,
        $COL_RECEIVED_COUNT INTEGER NOT NULL DEFAULT 0,
        $COL_USER_REPLIED_COUNT INTEGER NOT NULL DEFAULT 0,
        $COL_LAST_RECEIVED_AT_MS INTEGER NOT NULL DEFAULT 0,
        $COL_LAST_REPLIED_AT_MS INTEGER NOT NULL DEFAULT 0
      )
      """.trimIndent()
    )
  }

  /** Verifies whether SQLite is operating in Write-Ahead Logging mode. */
  fun isWalEnabled(): Boolean {
    return writableDatabase.rawQuery("PRAGMA journal_mode", null).use { cursor ->
      cursor.moveToFirst() && cursor.getString(0).equals("wal", ignoreCase = true)
    }
  }

  fun insertIncomingEvent(
    captured: CapturedNotification,
    contentHash: String,
    createdAtMs: Long = System.currentTimeMillis(),
  ): Long {
    val db = writableDatabase
    val threadJson = if (captured.threadMessages.isEmpty()) {
      null
    } else {
      json.encodeToString(captured.threadMessages)
    }

    val values = ContentValues().apply {
      put(COL_SBN_KEY, captured.sbnKey)
      put(COL_PACKAGE_NAME, captured.packageName)
      put(COL_APP_LABEL, captured.appLabel)
      put(COL_SENDER, captured.sender)
      put(COL_TITLE, captured.title)
      put(COL_BODY, captured.body)
      put(COL_THREAD_JSON, threadJson)
      put(COL_CONTENT_HASH, contentHash)
      put(COL_HAS_REMOTE_INPUT, if (captured.hasRemoteInput) 1 else 0)
      put(COL_TRIAGE_STATUS, TriageStatus.PENDING.name)
      put(COL_CREATED_AT_MS, createdAtMs)
    }

    val rowId = db.insertOrThrow(TABLE_EVENT_LOG, null, values)
    pruneOldEvents(db)
    return rowId
  }

  fun updateTriageResult(
    eventId: Long,
    triage: NotificationTriage,
    triageJson: String,
    triagedAtMs: Long = System.currentTimeMillis(),
  ) {
    val values = ContentValues().apply {
      put(COL_TRIAGE_STATUS, TriageStatus.TRIAGED.name)
      put(COL_TRIAGE_JSON, triageJson)
      put(COL_TRIAGE_SENDER, triage.sender)
      put(COL_TRIAGE_INTENT, triage.intent)
      put(COL_TRIAGE_URGENCY, triage.urgency)
      put(COL_SUGGESTED_REPLY, triage.suggestedReply)
      putNull(COL_ERROR_MESSAGE)
      put(COL_TRIAGED_AT_MS, triagedAtMs)
    }
    writableDatabase.update(TABLE_EVENT_LOG, values, "$COL_ID = ?", arrayOf(eventId.toString()))
  }

  fun updateTriageStatus(
    eventId: Long,
    status: TriageStatus,
    errorMessage: String? = null,
    updatedAtMs: Long = System.currentTimeMillis(),
  ) {
    val values = ContentValues().apply {
      put(COL_TRIAGE_STATUS, status.name)
      if (errorMessage != null) put(COL_ERROR_MESSAGE, errorMessage) else putNull(COL_ERROR_MESSAGE)
      put(COL_TRIAGED_AT_MS, updatedAtMs)
    }
    writableDatabase.update(TABLE_EVENT_LOG, values, "$COL_ID = ?", arrayOf(eventId.toString()))
  }

  fun markReplySent(eventId: Long, replyText: String) {
    val values = ContentValues().apply {
      put(COL_REPLY_SENT, replyText)
    }
    writableDatabase.update(TABLE_EVENT_LOG, values, "$COL_ID = ?", arrayOf(eventId.toString()))
  }

  fun getRecentEvents(
    limit: Int = DEFAULT_FEED_LIMIT,
    canReplyChecker: (String) -> Boolean = { false },
  ): List<JarvisEventRecord> {
    val result = mutableListOf<JarvisEventRecord>()
    readableDatabase.query(
      TABLE_EVENT_LOG,
      null,
      null,
      null,
      null,
      null,
      "$COL_CREATED_AT_MS DESC, $COL_ID DESC",
      limit.coerceIn(1, MAX_EVENT_ROWS).toString(),
    ).use { c ->
      val idxId = c.getColumnIndexOrThrow(COL_ID)
      val idxSbnKey = c.getColumnIndexOrThrow(COL_SBN_KEY)
      val idxPkg = c.getColumnIndexOrThrow(COL_PACKAGE_NAME)
      val idxAppLabel = c.getColumnIndexOrThrow(COL_APP_LABEL)
      val idxSender = c.getColumnIndexOrThrow(COL_SENDER)
      val idxTitle = c.getColumnIndexOrThrow(COL_TITLE)
      val idxBody = c.getColumnIndexOrThrow(COL_BODY)
      val idxThreadJson = c.getColumnIndexOrThrow(COL_THREAD_JSON)
      val idxHash = c.getColumnIndexOrThrow(COL_CONTENT_HASH)
      val idxHasRemoteInput = c.getColumnIndexOrThrow(COL_HAS_REMOTE_INPUT)
      val idxTriageStatus = c.getColumnIndexOrThrow(COL_TRIAGE_STATUS)
      val idxTriageJson = c.getColumnIndexOrThrow(COL_TRIAGE_JSON)
      val idxTriageSender = c.getColumnIndexOrThrow(COL_TRIAGE_SENDER)
      val idxTriageIntent = c.getColumnIndexOrThrow(COL_TRIAGE_INTENT)
      val idxTriageUrgency = c.getColumnIndexOrThrow(COL_TRIAGE_URGENCY)
      val idxSuggestedReply = c.getColumnIndexOrThrow(COL_SUGGESTED_REPLY)
      val idxError = c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)
      val idxReplySent = c.getColumnIndexOrThrow(COL_REPLY_SENT)
      val idxCreatedAt = c.getColumnIndexOrThrow(COL_CREATED_AT_MS)
      val idxTriagedAt = c.getColumnIndexOrThrow(COL_TRIAGED_AT_MS)

      while (c.moveToNext()) {
        val sbnKey = c.getString(idxSbnKey)
        val rawThreadJson = c.getString(idxThreadJson)
        val threadMessages = rawThreadJson?.let { raw ->
          runCatching { json.decodeFromString<List<ThreadMessage>>(raw) }.getOrNull()
        }.orEmpty()

        val rawTriageJson = c.getString(idxTriageJson)
        val parsedTriage = rawTriageJson?.let { raw ->
          runCatching { json.decodeFromString<NotificationTriage>(raw) }.getOrNull()
        } ?: run {
          val tSender = c.getString(idxTriageSender)
          val tIntent = c.getString(idxTriageIntent)
          val tUrgency = c.getString(idxTriageUrgency)
          val tReply = c.getString(idxSuggestedReply)
          if (tSender != null || tIntent != null || tUrgency != null || tReply != null) {
            NotificationTriage(
              sender = tSender.orEmpty(),
              intent = tIntent ?: "other",
              urgency = tUrgency ?: "LOW",
              suggestedReply = tReply.orEmpty(),
            )
          } else {
            null
          }
        }

        val status = runCatching {
          TriageStatus.valueOf(c.getString(idxTriageStatus))
        }.getOrDefault(TriageStatus.PENDING)

        val hasRemoteInput = c.getInt(idxHasRemoteInput) == 1
        result.add(
          JarvisEventRecord(
            id = c.getLong(idxId),
            sbnKey = sbnKey,
            packageName = c.getString(idxPkg),
            appLabel = c.getString(idxAppLabel),
            sender = c.getString(idxSender),
            title = c.getString(idxTitle),
            body = c.getString(idxBody),
            threadMessages = threadMessages,
            contentHash = c.getString(idxHash),
            hasRemoteInput = hasRemoteInput,
            canReplyNow = hasRemoteInput && canReplyChecker(sbnKey),
            triageStatus = status,
            triage = parsedTriage,
            triageJson = rawTriageJson,
            errorMessage = c.getString(idxError),
            replySent = c.getString(idxReplySent),
            createdAtMs = c.getLong(idxCreatedAt),
            triagedAtMs = if (c.isNull(idxTriagedAt)) null else c.getLong(idxTriagedAt),
          )
        )
      }
    }
    return result
  }

  fun upsertRule(rule: JarvisRule, nowMs: Long = System.currentTimeMillis()): Long {
    val db = writableDatabase
    val values = ContentValues().apply {
      put(COL_RULE_KEY, rule.ruleKey)
      put(COL_RULE_TYPE, rule.ruleType)
      put(COL_PATTERN, rule.pattern)
      put(COL_INSTRUCTION, rule.instruction)
      put(COL_ENABLED, if (rule.enabled) 1 else 0)
      put(COL_UPDATED_AT_MS, nowMs)
    }
    val updated = db.update(TABLE_USER_RULEBOOK, values, "$COL_RULE_KEY = ?", arrayOf(rule.ruleKey))
    if (updated > 0) {
      db.query(
        TABLE_USER_RULEBOOK,
        arrayOf(COL_ID),
        "$COL_RULE_KEY = ?",
        arrayOf(rule.ruleKey),
        null,
        null,
        null,
      ).use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
      }
    }
    values.put(COL_CREATED_AT_MS, if (rule.createdAtMs > 0L) rule.createdAtMs else nowMs)
    return db.insertOrThrow(TABLE_USER_RULEBOOK, null, values)
  }

  fun getActiveRules(): List<JarvisRule> {
    val rules = mutableListOf<JarvisRule>()
    readableDatabase.query(
      TABLE_USER_RULEBOOK,
      null,
      "$COL_ENABLED = 1",
      null,
      null,
      null,
      "$COL_UPDATED_AT_MS DESC, $COL_ID DESC",
    ).use { c ->
      val idxId = c.getColumnIndexOrThrow(COL_ID)
      val idxKey = c.getColumnIndexOrThrow(COL_RULE_KEY)
      val idxType = c.getColumnIndexOrThrow(COL_RULE_TYPE)
      val idxPattern = c.getColumnIndexOrThrow(COL_PATTERN)
      val idxInstruction = c.getColumnIndexOrThrow(COL_INSTRUCTION)
      val idxEnabled = c.getColumnIndexOrThrow(COL_ENABLED)
      val idxCreatedAt = c.getColumnIndexOrThrow(COL_CREATED_AT_MS)
      val idxUpdatedAt = c.getColumnIndexOrThrow(COL_UPDATED_AT_MS)
      while (c.moveToNext()) {
        rules.add(
          JarvisRule(
            id = c.getLong(idxId),
            ruleKey = c.getString(idxKey),
            ruleType = c.getString(idxType),
            pattern = c.getString(idxPattern),
            instruction = c.getString(idxInstruction),
            enabled = c.getInt(idxEnabled) == 1,
            createdAtMs = c.getLong(idxCreatedAt),
            updatedAtMs = c.getLong(idxUpdatedAt),
          )
        )
      }
    }
    return rules
  }

  fun deleteRule(ruleId: Long): Int {
    return writableDatabase.delete(TABLE_USER_RULEBOOK, "$COL_ID = ?", arrayOf(ruleId.toString()))
  }

  fun deletePlaybook(skillId: String): Int {
    return writableDatabase.delete(TABLE_UI_PLAYBOOKS, "$COL_SKILL_ID = ?", arrayOf(skillId))
  }

  fun upsertTrustedContact(
    displayName: String,
    packageName: String = "",
    autoReplyEnabled: Boolean = true,
    maxTier: Int = 2,
    nowMs: Long = System.currentTimeMillis(),
  ): Long {
    val cleanName = displayName.trim()
    val cleanPkg = packageName.trim().lowercase()
    val contactKey = buildContactKey(cleanName, cleanPkg)
    val values = ContentValues().apply {
      put(COL_CONTACT_KEY, contactKey)
      put(COL_DISPLAY_NAME, cleanName)
      put(COL_PACKAGE_NAME, cleanPkg)
      put(COL_AUTO_REPLY_ENABLED, if (autoReplyEnabled) 1 else 0)
      put(COL_MAX_TIER, maxTier.coerceIn(0, 3))
      put(COL_UPDATED_AT_MS, nowMs)
    }
    val db = writableDatabase
    val updated = db.update(TABLE_TRUSTED_CONTACTS, values, "$COL_CONTACT_KEY = ?", arrayOf(contactKey))
    if (updated > 0) {
      db.query(
        TABLE_TRUSTED_CONTACTS,
        arrayOf(COL_ID),
        "$COL_CONTACT_KEY = ?",
        arrayOf(contactKey),
        null,
        null,
        null,
      ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
    }
    values.put(COL_CREATED_AT_MS, nowMs)
    return db.insertOrThrow(TABLE_TRUSTED_CONTACTS, null, values)
  }

  fun setTrustedContactEnabled(
    contactKey: String,
    enabled: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ): Int {
    val values = ContentValues().apply {
      put(COL_AUTO_REPLY_ENABLED, if (enabled) 1 else 0)
      put(COL_UPDATED_AT_MS, nowMs)
    }
    return writableDatabase.update(TABLE_TRUSTED_CONTACTS, values, "$COL_CONTACT_KEY = ?", arrayOf(contactKey))
  }

  fun isContactTrusted(displayName: String, packageName: String = ""): Boolean {
    val cleanName = displayName.trim().lowercase()
    if (cleanName.isEmpty()) return false
    val cleanPkg = packageName.trim().lowercase()
    val exactKey = buildContactKey(cleanName, cleanPkg)
    val wildcardKey = buildContactKey(cleanName, "")
    readableDatabase.query(
      TABLE_TRUSTED_CONTACTS,
      arrayOf(COL_ID),
      "$COL_AUTO_REPLY_ENABLED = 1 AND ($COL_CONTACT_KEY = ? OR $COL_CONTACT_KEY = ?)",
      arrayOf(exactKey, wildcardKey),
      null,
      null,
      null,
      "1",
    ).use { c -> return c.moveToFirst() }
  }

  fun getTrustedContacts(limit: Int = 50): List<TrustedContact> {
    val list = mutableListOf<TrustedContact>()
    readableDatabase.query(
      TABLE_TRUSTED_CONTACTS,
      null,
      null,
      null,
      null,
      null,
      "$COL_AUTO_REPLY_ENABLED DESC, $COL_UPDATED_AT_MS DESC",
      limit.coerceIn(1, 200).toString(),
    ).use { c ->
      val idxId = c.getColumnIndexOrThrow(COL_ID)
      val idxKey = c.getColumnIndexOrThrow(COL_CONTACT_KEY)
      val idxName = c.getColumnIndexOrThrow(COL_DISPLAY_NAME)
      val idxPkg = c.getColumnIndexOrThrow(COL_PACKAGE_NAME)
      val idxEnabled = c.getColumnIndexOrThrow(COL_AUTO_REPLY_ENABLED)
      val idxTier = c.getColumnIndexOrThrow(COL_MAX_TIER)
      val idxCreated = c.getColumnIndexOrThrow(COL_CREATED_AT_MS)
      val idxUpdated = c.getColumnIndexOrThrow(COL_UPDATED_AT_MS)
      while (c.moveToNext()) {
        list.add(
          TrustedContact(
            id = c.getLong(idxId),
            contactKey = c.getString(idxKey),
            displayName = c.getString(idxName),
            packageName = c.getString(idxPkg),
            autoReplyEnabled = c.getInt(idxEnabled) == 1,
            maxTier = c.getInt(idxTier),
            createdAtMs = c.getLong(idxCreated),
            updatedAtMs = c.getLong(idxUpdated),
          )
        )
      }
    }
    return list
  }

  fun deleteTrustedContact(contactKey: String): Int {
    return writableDatabase.delete(TABLE_TRUSTED_CONTACTS, "$COL_CONTACT_KEY = ?", arrayOf(contactKey))
  }

  fun insertUpcomingDestination(
    placeName: String,
    sourceSummary: String,
    nowMs: Long = System.currentTimeMillis(),
    validFromEpochMs: Long = 0L,
    validUntilEpochMs: Long = 0L,
    ttlMs: Long = DESTINATION_TTL_24H_MS,
  ): Long {
    val cleanPlace = placeName.trim()
    if (cleanPlace.isEmpty()) return -1L
    val db = writableDatabase
    db.delete(
      TABLE_UPCOMING_DESTINATIONS,
      "COALESCE(NULLIF($COL_VALID_UNTIL_EPOCH, 0), $COL_EXPIRES_AT) <= ?",
      arrayOf(nowMs.toString()),
    )
    val resolvedFrom = if (validFromEpochMs > 0L) validFromEpochMs else nowMs
    val resolvedUntil = when {
      validUntilEpochMs > resolvedFrom -> validUntilEpochMs
      validFromEpochMs > 0L -> validFromEpochMs + FUTURE_EVENT_DEFAULT_WINDOW_MS
      else -> nowMs + ttlMs
    }
    val values = ContentValues().apply {
      put(COL_PLACE_NAME, cleanPlace)
      put(COL_SOURCE_SUMMARY, sourceSummary.trim())
      put(COL_EXPIRES_AT, resolvedUntil)
      put(COL_VALID_FROM_EPOCH, resolvedFrom)
      put(COL_VALID_UNTIL_EPOCH, resolvedUntil)
      put(COL_USED, 0)
      put(COL_CREATED_AT_MS, nowMs)
    }
    return db.insertOrThrow(TABLE_UPCOMING_DESTINATIONS, null, values)
  }

  fun getActiveUpcomingDestinations(
    nowMs: Long = System.currentTimeMillis(),
    limit: Int = 20,
  ): List<UpcomingDestination> {
    return queryDestinations(
      selection = "$COL_USED = 0 AND $COL_VALID_FROM_EPOCH <= ? AND COALESCE(NULLIF($COL_VALID_UNTIL_EPOCH, 0), $COL_EXPIRES_AT) > ?",
      args = arrayOf(nowMs.toString(), nowMs.toString()),
      orderBy = "$COL_VALID_FROM_EPOCH ASC, $COL_CREATED_AT_MS DESC, $COL_ID DESC",
      limit = limit,
    )
  }

  fun getAllUnexpiredDestinations(
    nowMs: Long = System.currentTimeMillis(),
    limit: Int = 20,
  ): List<UpcomingDestination> {
    return queryDestinations(
      selection = "$COL_USED = 0 AND COALESCE(NULLIF($COL_VALID_UNTIL_EPOCH, 0), $COL_EXPIRES_AT) > ?",
      args = arrayOf(nowMs.toString()),
      orderBy = "$COL_VALID_FROM_EPOCH ASC, $COL_CREATED_AT_MS DESC, $COL_ID DESC",
      limit = limit,
    )
  }

  private fun queryDestinations(
    selection: String,
    args: Array<String>,
    orderBy: String,
    limit: Int,
  ): List<UpcomingDestination> {
    val list = mutableListOf<UpcomingDestination>()
    readableDatabase.query(
      TABLE_UPCOMING_DESTINATIONS,
      null,
      selection,
      args,
      null,
      null,
      orderBy,
      limit.coerceIn(1, 50).toString(),
    ).use { c ->
      val idxId = c.getColumnIndexOrThrow(COL_ID)
      val idxPlace = c.getColumnIndexOrThrow(COL_PLACE_NAME)
      val idxSource = c.getColumnIndexOrThrow(COL_SOURCE_SUMMARY)
      val idxExp = c.getColumnIndexOrThrow(COL_EXPIRES_AT)
      val idxFrom = c.getColumnIndex(COL_VALID_FROM_EPOCH)
      val idxUntil = c.getColumnIndex(COL_VALID_UNTIL_EPOCH)
      val idxUsed = c.getColumnIndexOrThrow(COL_USED)
      val idxCreated = c.getColumnIndexOrThrow(COL_CREATED_AT_MS)
      while (c.moveToNext()) {
        val exp = c.getLong(idxExp)
        val validFrom = if (idxFrom >= 0) c.getLong(idxFrom) else 0L
        val rawUntil = if (idxUntil >= 0) c.getLong(idxUntil) else 0L
        val validUntil = if (rawUntil > 0L) rawUntil else exp
        list.add(
          UpcomingDestination(
            id = c.getLong(idxId),
            placeName = c.getString(idxPlace),
            sourceSummary = c.getString(idxSource),
            expiresAtMs = validUntil,
            used = c.getInt(idxUsed) == 1,
            createdAtMs = c.getLong(idxCreated),
            validFromEpochMs = validFrom,
            validUntilEpochMs = validUntil,
          )
        )
      }
    }
    return list
  }

  fun recordOutgoingReplySample(
    displayName: String,
    packageName: String = "",
    sampleText: String,
    relationshipTag: String? = null,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    val cleanName = displayName.trim()
    val cleanSample = sampleText.trim().take(MAX_PERSONA_SAMPLE_CHARS)
    if (cleanName.isEmpty() || cleanSample.isEmpty()) return false
    if (SecurityGateway.inspectOutboundText(cleanSample) != null) return false

    val cleanPkg = packageName.trim().lowercase()
    val contactKey = buildContactKey(cleanName, cleanPkg)
    val existing = getPersonaByKey(contactKey)

    val mergedSamples = buildList {
      existing?.sentSamples?.forEach { prev ->
        if (!prev.equals(cleanSample, ignoreCase = false)) add(prev)
      }
      add(cleanSample)
    }.takeLast(MAX_PERSONA_SAMPLES)

    val resolvedTag = when {
      !relationshipTag.isNullOrBlank() -> relationshipTag.trim().lowercase()
      existing != null && existing.relationshipTag != "general" -> existing.relationshipTag
      else -> JarvisLanguagePrompt.inferRelationshipTag(cleanName, cleanSample)
    }

    val values = ContentValues().apply {
      put(COL_CONTACT_KEY, contactKey)
      put(COL_DISPLAY_NAME, cleanName)
      put(COL_PACKAGE_NAME, cleanPkg)
      put(COL_RELATIONSHIP_TAG, resolvedTag)
      put(COL_SENT_SAMPLES_JSON, json.encodeToString(mergedSamples))
      put(COL_UPDATED_AT_MS, nowMs)
    }
    val db = writableDatabase
    val updated = db.update(TABLE_CONTACT_PERSONAS, values, "$COL_CONTACT_KEY = ?", arrayOf(contactKey))
    if (updated == 0) {
      db.insertOrThrow(TABLE_CONTACT_PERSONAS, null, values)
    }
    return true
  }

  fun getContactPersona(displayName: String, packageName: String = ""): ContactPersona? {
    val cleanName = displayName.trim()
    if (cleanName.isEmpty()) return null
    val cleanPkg = packageName.trim().lowercase()
    return getPersonaByKey(buildContactKey(cleanName, cleanPkg))
      ?: getPersonaByKey(buildContactKey(cleanName, ""))
      ?: getPersonaByDisplayName(cleanName)
  }

  fun getContactPersonaCount(): Int {
    return readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CONTACT_PERSONAS", null).use { c ->
      if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  private fun getPersonaByKey(contactKey: String): ContactPersona? {
    readableDatabase.query(
      TABLE_CONTACT_PERSONAS,
      null,
      "$COL_CONTACT_KEY = ?",
      arrayOf(contactKey),
      null,
      null,
      null,
      "1",
    ).use { c ->
      return if (c.moveToFirst()) readPersonaRow(c) else null
    }
  }

  private fun getPersonaByDisplayName(displayName: String): ContactPersona? {
    readableDatabase.query(
      TABLE_CONTACT_PERSONAS,
      null,
      "LOWER($COL_DISPLAY_NAME) = ?",
      arrayOf(displayName.trim().lowercase()),
      null,
      null,
      "$COL_UPDATED_AT_MS DESC",
      "1",
    ).use { c ->
      return if (c.moveToFirst()) readPersonaRow(c) else null
    }
  }

  private fun readPersonaRow(c: android.database.Cursor): ContactPersona {
    val rawSamples = c.getString(c.getColumnIndexOrThrow(COL_SENT_SAMPLES_JSON))
    val samples = runCatching { json.decodeFromString<List<String>>(rawSamples) }.getOrDefault(emptyList())
    return ContactPersona(
      id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
      contactKey = c.getString(c.getColumnIndexOrThrow(COL_CONTACT_KEY)),
      displayName = c.getString(c.getColumnIndexOrThrow(COL_DISPLAY_NAME)),
      packageName = c.getString(c.getColumnIndexOrThrow(COL_PACKAGE_NAME)),
      relationshipTag = c.getString(c.getColumnIndexOrThrow(COL_RELATIONSHIP_TAG)),
      sentSamples = samples,
      updatedAtMs = c.getLong(c.getColumnIndexOrThrow(COL_UPDATED_AT_MS)),
    )
  }

  fun recordGroupMessageReceived(
    groupName: String,
    packageName: String,
    nowMs: Long = System.currentTimeMillis(),
  ): GroupEngagementRecord? {
    val cleanGroup = groupName.trim()
    val cleanPkg = packageName.trim().lowercase()
    if (cleanGroup.isEmpty() || cleanPkg.isEmpty()) return null
    val groupKey = buildContactKey(cleanGroup, cleanPkg)
    val db = writableDatabase
    db.execSQL(
      """
      INSERT INTO $TABLE_GROUP_ENGAGEMENT (
        $COL_GROUP_KEY, $COL_PACKAGE_NAME, $COL_GROUP_NAME,
        $COL_RECEIVED_COUNT, $COL_USER_REPLIED_COUNT, $COL_LAST_RECEIVED_AT_MS, $COL_LAST_REPLIED_AT_MS
      ) VALUES (?, ?, ?, 1, 0, ?, 0)
      ON CONFLICT($COL_GROUP_KEY) DO UPDATE SET
        $COL_GROUP_NAME = excluded.$COL_GROUP_NAME,
        $COL_RECEIVED_COUNT = $COL_RECEIVED_COUNT + 1,
        $COL_LAST_RECEIVED_AT_MS = excluded.$COL_LAST_RECEIVED_AT_MS
      """.trimIndent(),
      arrayOf<Any>(groupKey, cleanPkg, cleanGroup, nowMs),
    )
    return getGroupEngagement(cleanGroup, cleanPkg)
  }

  fun recordGroupUserReply(
    groupName: String,
    packageName: String,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    val cleanGroup = groupName.trim()
    val cleanPkg = packageName.trim().lowercase()
    if (cleanGroup.isEmpty() || cleanPkg.isEmpty()) return
    val groupKey = buildContactKey(cleanGroup, cleanPkg)
    writableDatabase.execSQL(
      """
      INSERT INTO $TABLE_GROUP_ENGAGEMENT (
        $COL_GROUP_KEY, $COL_PACKAGE_NAME, $COL_GROUP_NAME,
        $COL_RECEIVED_COUNT, $COL_USER_REPLIED_COUNT, $COL_LAST_RECEIVED_AT_MS, $COL_LAST_REPLIED_AT_MS
      ) VALUES (?, ?, ?, 0, 1, 0, ?)
      ON CONFLICT($COL_GROUP_KEY) DO UPDATE SET
        $COL_USER_REPLIED_COUNT = $COL_USER_REPLIED_COUNT + 1,
        $COL_LAST_REPLIED_AT_MS = excluded.$COL_LAST_REPLIED_AT_MS
      """.trimIndent(),
      arrayOf<Any>(groupKey, cleanPkg, cleanGroup, nowMs),
    )
  }

  fun getGroupEngagement(groupName: String, packageName: String): GroupEngagementRecord? {
    val cleanGroup = groupName.trim()
    val cleanPkg = packageName.trim().lowercase()
    if (cleanGroup.isEmpty() || cleanPkg.isEmpty()) return null
    val groupKey = buildContactKey(cleanGroup, cleanPkg)
    readableDatabase.query(
      TABLE_GROUP_ENGAGEMENT,
      null,
      "$COL_GROUP_KEY = ?",
      arrayOf(groupKey),
      null,
      null,
      null,
      "1",
    ).use { c ->
      if (!c.moveToFirst()) return null
      return GroupEngagementRecord(
        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
        groupKey = c.getString(c.getColumnIndexOrThrow(COL_GROUP_KEY)),
        packageName = c.getString(c.getColumnIndexOrThrow(COL_PACKAGE_NAME)),
        groupName = c.getString(c.getColumnIndexOrThrow(COL_GROUP_NAME)),
        receivedCount = c.getInt(c.getColumnIndexOrThrow(COL_RECEIVED_COUNT)),
        userRepliedCount = c.getInt(c.getColumnIndexOrThrow(COL_USER_REPLIED_COUNT)),
        lastReceivedAtMs = c.getLong(c.getColumnIndexOrThrow(COL_LAST_RECEIVED_AT_MS)),
        lastRepliedAtMs = c.getLong(c.getColumnIndexOrThrow(COL_LAST_REPLIED_AT_MS)),
      )
    }
  }

  fun markDestinationUsed(id: Long): Int {
    val values = ContentValues().apply { put(COL_USED, 1) }
    return writableDatabase.update(TABLE_UPCOMING_DESTINATIONS, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun upsertSavedPlace(
    ssid: String,
    placeTag: PlaceTag,
    autoFocusQuiet: Boolean = true,
    nowMs: Long = System.currentTimeMillis(),
  ): Long {
    val cleanDisplay = normalizeSsidDisplay(ssid)
    val normKey = cleanDisplay.lowercase()
    if (normKey.isEmpty() || placeTag == PlaceTag.NONE) return -1L
    val values = ContentValues().apply {
      put(COL_SSID_NORMALIZED, normKey)
      put(COL_DISPLAY_SSID, cleanDisplay)
      put(COL_PLACE_TYPE, placeTag.name)
      put(COL_AUTO_FOCUS_QUIET, if (autoFocusQuiet) 1 else 0)
      put(COL_UPDATED_AT_MS, nowMs)
    }
    val db = writableDatabase
    val updated = db.update(TABLE_SAVED_PLACES, values, "$COL_SSID_NORMALIZED = ?", arrayOf(normKey))
    if (updated > 0) {
      db.query(
        TABLE_SAVED_PLACES,
        arrayOf(COL_ID),
        "$COL_SSID_NORMALIZED = ?",
        arrayOf(normKey),
        null,
        null,
        null,
      ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
    }
    return db.insertOrThrow(TABLE_SAVED_PLACES, null, values)
  }

  fun getSavedPlaces(): List<SavedPlaceProfile> {
    val list = mutableListOf<SavedPlaceProfile>()
    readableDatabase.query(
      TABLE_SAVED_PLACES,
      null,
      null,
      null,
      null,
      null,
      "$COL_UPDATED_AT_MS DESC",
    ).use { c ->
      val idxId = c.getColumnIndexOrThrow(COL_ID)
      val idxNorm = c.getColumnIndexOrThrow(COL_SSID_NORMALIZED)
      val idxDisp = c.getColumnIndexOrThrow(COL_DISPLAY_SSID)
      val idxType = c.getColumnIndexOrThrow(COL_PLACE_TYPE)
      val idxFocus = c.getColumnIndexOrThrow(COL_AUTO_FOCUS_QUIET)
      val idxUpdated = c.getColumnIndexOrThrow(COL_UPDATED_AT_MS)
      while (c.moveToNext()) {
        val tag = runCatching { PlaceTag.valueOf(c.getString(idxType)) }.getOrDefault(PlaceTag.NONE)
        list.add(
          SavedPlaceProfile(
            id = c.getLong(idxId),
            ssidNormalized = c.getString(idxNorm),
            displaySsid = c.getString(idxDisp),
            placeTag = tag,
            autoFocusQuiet = c.getInt(idxFocus) == 1,
            updatedAtMs = c.getLong(idxUpdated),
          )
        )
      }
    }
    return list
  }

  fun insertOrUpdateEvent(event: ScheduledEvent): Long {
    val title = event.title.trim()
    if (title.isEmpty() || event.startEpochMs <= 0L) return -1L
    val endMs = if (event.endEpochMs > event.startEpochMs) event.endEpochMs else event.startEpochMs + 3_600_000L
    // Deduplicate identical title + start window (within 5 min) if still active.
    readableDatabase.query(
      TABLE_SCHEDULED_EVENTS,
      arrayOf(COL_ID),
      "LOWER($COL_EVENT_TITLE) = ? AND ABS($COL_START_EPOCH_MS - ?) <= 300000 AND $COL_EVENT_STATUS = ?",
      arrayOf(title.lowercase(), event.startEpochMs.toString(), EventStatus.ACTIVE.name),
      null,
      null,
      null,
      "1",
    ).use { c ->
      if (c.moveToFirst()) {
        val existingId = c.getLong(0)
        val cv = ContentValues().apply {
          put(COL_END_EPOCH_MS, endMs)
          put(COL_EVENT_LOCATION, event.location.trim())
          put(COL_SOURCE_SUMMARY, event.sourceSummary.trim())
        }
        writableDatabase.update(TABLE_SCHEDULED_EVENTS, cv, "$COL_ID = ?", arrayOf(existingId.toString()))
        return existingId
      }
    }
    val values = ContentValues().apply {
      put(COL_EVENT_TITLE, title)
      put(COL_START_EPOCH_MS, event.startEpochMs)
      put(COL_END_EPOCH_MS, endMs)
      put(COL_EVENT_LOCATION, event.location.trim())
      put(COL_EVENT_SOURCE, event.source.name)
      put(COL_SENDER, event.sender.trim())
      put(COL_SOURCE_SUMMARY, event.sourceSummary.trim())
      put(COL_EVENT_STATUS, event.status.name)
      put(COL_CREATED_AT_MS, event.createdAtMs)
    }
    return writableDatabase.insert(TABLE_SCHEDULED_EVENTS, null, values)
  }

  fun getUpcomingEvents(nowMs: Long = System.currentTimeMillis(), limit: Int = 50): List<ScheduledEvent> {
    val cutoffMs = nowMs - 2L * 60L * 60L * 1000L
    val out = mutableListOf<ScheduledEvent>()
    readableDatabase.query(
      TABLE_SCHEDULED_EVENTS,
      arrayOf(
        COL_ID,
        COL_EVENT_TITLE,
        COL_START_EPOCH_MS,
        COL_END_EPOCH_MS,
        COL_EVENT_LOCATION,
        COL_EVENT_SOURCE,
        COL_SENDER,
        COL_SOURCE_SUMMARY,
        COL_EVENT_STATUS,
        COL_CREATED_AT_MS,
      ),
      "$COL_END_EPOCH_MS >= ? AND $COL_EVENT_STATUS = ?",
      arrayOf(cutoffMs.toString(), EventStatus.ACTIVE.name),
      null,
      null,
      "$COL_START_EPOCH_MS ASC",
      limit.coerceAtLeast(1).toString(),
    ).use { c ->
      while (c.moveToNext()) {
        out += ScheduledEvent(
          id = c.getLong(0),
          title = c.getString(1).orEmpty(),
          startEpochMs = c.getLong(2),
          endEpochMs = c.getLong(3),
          location = c.getString(4).orEmpty(),
          source = runCatching { EventSource.valueOf(c.getString(5)) }.getOrDefault(EventSource.MESSAGE),
          sender = c.getString(6).orEmpty(),
          sourceSummary = c.getString(7).orEmpty(),
          status = runCatching { EventStatus.valueOf(c.getString(8)) }.getOrDefault(EventStatus.ACTIVE),
          createdAtMs = c.getLong(9),
        )
      }
    }
    return out
  }

  fun updateEventStatus(id: Long, status: EventStatus): Int {
    val values = ContentValues().apply { put(COL_EVENT_STATUS, status.name) }
    return writableDatabase.update(TABLE_SCHEDULED_EVENTS, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun deleteEvent(id: Long): Int {
    return writableDatabase.delete(TABLE_SCHEDULED_EVENTS, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun deleteSavedPlace(ssidNormalized: String): Int {
    return writableDatabase.delete(
      TABLE_SAVED_PLACES,
      "$COL_SSID_NORMALIZED = ?",
      arrayOf(normalizeSsidDisplay(ssidNormalized).lowercase()),
    )
  }

  private fun pruneOldEvents(db: SQLiteDatabase) {
    db.execSQL(
      """
      DELETE FROM $TABLE_EVENT_LOG
      WHERE $COL_ID NOT IN (
        SELECT $COL_ID FROM $TABLE_EVENT_LOG
        ORDER BY $COL_CREATED_AT_MS DESC, $COL_ID DESC
        LIMIT $MAX_EVENT_ROWS
      )
      """.trimIndent()
    )
  }

  companion object {
    const val DB_NAME = "jarvis_memory.db"
    const val DB_VERSION = 6
    const val DESTINATION_TTL_24H_MS = 24L * 60L * 60L * 1000L
    const val FUTURE_EVENT_DEFAULT_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val DEFAULT_FEED_LIMIT = 50
    private const val MAX_EVENT_ROWS = 500
    private const val MAX_PERSONA_SAMPLES = 8
    private const val MAX_PERSONA_SAMPLE_CHARS = 160

    const val TABLE_EVENT_LOG = "event_log"
    const val TABLE_USER_RULEBOOK = "user_rulebook"
    const val TABLE_UI_PLAYBOOKS = "ui_playbooks"
    const val TABLE_TRUSTED_CONTACTS = "trusted_contacts"
    const val TABLE_UPCOMING_DESTINATIONS = "upcoming_destinations"
    const val TABLE_SAVED_PLACES = "saved_places"
    const val TABLE_CONTACT_PERSONAS = "contact_personas"
    const val TABLE_GROUP_ENGAGEMENT = "group_engagement"
    const val TABLE_VOICEPRINT = "voiceprint"
    const val TABLE_SCHEDULED_EVENTS = "scheduled_events"

    private const val COL_EVENT_TITLE = "event_title"
    private const val COL_START_EPOCH_MS = "start_epoch_ms"
    private const val COL_END_EPOCH_MS = "end_epoch_ms"
    private const val COL_EVENT_LOCATION = "event_location"
    private const val COL_EVENT_SOURCE = "event_source"
    private const val COL_EVENT_STATUS = "event_status"

    private const val COL_EMBEDDING_BLOB = "embedding_blob"
    private const val COL_EMBEDDING_DIM = "embedding_dim"
    private const val COL_MODEL_ID = "model_id"

    private const val COL_ID = "id"
    private const val COL_SBN_KEY = "sbn_key"
    private const val COL_PACKAGE_NAME = "package_name"
    private const val COL_APP_LABEL = "app_label"
    private const val COL_SENDER = "sender"
    private const val COL_TITLE = "title"
    private const val COL_BODY = "body"
    private const val COL_THREAD_JSON = "thread_json"
    private const val COL_CONTENT_HASH = "content_hash"
    private const val COL_HAS_REMOTE_INPUT = "has_remote_input"
    private const val COL_TRIAGE_STATUS = "triage_status"
    private const val COL_TRIAGE_JSON = "triage_json"
    private const val COL_TRIAGE_SENDER = "triage_sender"
    private const val COL_TRIAGE_INTENT = "triage_intent"
    private const val COL_TRIAGE_URGENCY = "triage_urgency"
    private const val COL_SUGGESTED_REPLY = "suggested_reply"
    private const val COL_ERROR_MESSAGE = "error_message"
    private const val COL_REPLY_SENT = "reply_sent"
    private const val COL_CREATED_AT_MS = "created_at_ms"
    private const val COL_TRIAGED_AT_MS = "triaged_at_ms"

    private const val COL_RULE_KEY = "rule_key"
    private const val COL_RULE_TYPE = "rule_type"
    private const val COL_PATTERN = "pattern"
    private const val COL_INSTRUCTION = "instruction"
    private const val COL_ENABLED = "enabled"
    private const val COL_UPDATED_AT_MS = "updated_at_ms"

    private const val COL_SKILL_ID = "skill_id"
    private const val COL_TRIGGER_INTENT = "trigger_intent"
    private const val COL_TARGET_PACKAGE = "target_package"
    private const val COL_STEPS_JSON = "steps_json"
    private const val COL_SUCCESS_COUNT = "success_count"

    private const val COL_CONTACT_KEY = "contact_key"
    private const val COL_DISPLAY_NAME = "display_name"
    private const val COL_AUTO_REPLY_ENABLED = "auto_reply_enabled"
    private const val COL_MAX_TIER = "max_tier"

    private const val COL_PLACE_NAME = "place_name"
    private const val COL_SOURCE_SUMMARY = "source_summary"
    private const val COL_EXPIRES_AT = "expires_at"
    private const val COL_VALID_FROM_EPOCH = "valid_from_epoch"
    private const val COL_VALID_UNTIL_EPOCH = "valid_until_epoch"
    private const val COL_USED = "used"

    private const val COL_RELATIONSHIP_TAG = "relationship_tag"
    private const val COL_SENT_SAMPLES_JSON = "sent_samples_json"

    private const val COL_GROUP_KEY = "group_key"
    private const val COL_GROUP_NAME = "group_name"
    private const val COL_RECEIVED_COUNT = "received_count"
    private const val COL_USER_REPLIED_COUNT = "user_replied_count"
    private const val COL_LAST_RECEIVED_AT_MS = "last_received_at_ms"
    private const val COL_LAST_REPLIED_AT_MS = "last_replied_at_ms"

    private const val COL_SSID_NORMALIZED = "ssid_normalized"
    private const val COL_DISPLAY_SSID = "display_ssid"
    private const val COL_PLACE_TYPE = "place_type"
    private const val COL_AUTO_FOCUS_QUIET = "auto_focus_quiet"

    internal fun buildContactKey(displayName: String, packageName: String): String {
      val name = displayName.trim().lowercase().replace(Regex("""\s+"""), " ")
      val pkg = packageName.trim().lowercase()
      return "$pkg|$name"
    }

    internal fun normalizeKey(raw: String): String =
      raw.trim().lowercase().replace(Regex("""\s+"""), " ")

    internal fun normalizeSsidDisplay(raw: String): String =
      raw.trim().removePrefix("\"").removeSuffix("\"").trim()
  }
}
