package com.nexwork.edith.jarvis.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nexwork.edith.jarvis.core.CapturedNotification
import com.nexwork.edith.jarvis.core.JarvisEventRecord
import com.nexwork.edith.jarvis.core.JarvisRule
import com.nexwork.edith.jarvis.core.NotificationTriage
import com.nexwork.edith.jarvis.core.ThreadMessage
import com.nexwork.edith.jarvis.core.TriageStatus
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
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Milestone 1 starts at v1; future schema changes will perform additive migrations.
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
    const val DB_VERSION = 1
    private const val DEFAULT_FEED_LIMIT = 50
    private const val MAX_EVENT_ROWS = 500

    const val TABLE_EVENT_LOG = "event_log"
    const val TABLE_USER_RULEBOOK = "user_rulebook"

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
  }
}
