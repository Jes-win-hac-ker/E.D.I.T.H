package com.nexwork.edith.jarvis.brain

import com.nexwork.edith.jarvis.core.ContactPersona
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared Manglish (Romanized Malayalam + English) slang/intent guide and per-contact style memory
 * formatting reused by both [GeminiClient] (`gemini-3.5-flash-lite`) and [PlannerClient]
 * (`gemini-4-hydrogen`) so triage, drafted replies, and planner actions share one voice.
 */
object JarvisLanguagePrompt {

  private val DATE_HEADER_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)", Locale.US)

  val MANGLISH_RULEBOOK = """
    <manglish_rulebook>
    Messages may use Manglish (Romanized Malayalam mixed with English). Understand true intent and reply naturally:
    - Peers/Friends (`da`, `edi`, `machane`, `aliyan`, `mone`, `bro`): casual peer register -> brief, warm reply.
    - Family/Elders (`amma`, `acha`, `chetta`, `chechi`): respectful affectionate register.
    - Campus/Work (`sir`, `madam`, `hod`, `class undo`, `assignment`, `submission`): polite concise English/academic tone.
    - Key phrases & urgency:
      * `evideya` / `evide ethi` = where are you / reached where; `ippo varam` / `ithaa` / `ernagi` = coming right now / left already
      * `vegam` / `pettennu` / `urgent` = hurry / fast -> raise urgency (HIGH/CRITICAL if time-bound)
      * `scene aano` / `scene illa` / `dark scene` = trouble? / no issue / big trouble!
      * `pani paali` / `moonji` / `potti` = things went wrong / failed -> inspect urgently
      * `set aakku` / `okke set` / `sheri` / `okda` / `mathi` / `pinnalla` = arrange it / all set / okay / enough / of course
      * `enthua` / `entha karyam` = what happened / what's up; `vilikku` / `call aakku` = call me
      * `paisa` / `gpay aakku` = money / send UPI; `nale`/`innu`/`ippo`/`raavile`/`vaikittu` = tomorrow/today/now/morning/evening
    Always mirror the user's exact tone, slang mix, capitalization, and brevity from <contact_persona> sent_samples.
    </manglish_rulebook>
  """.trimIndent()

  fun formatTemporalContext(nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val zoned = Instant.ofEpochMilli(nowMs).atZone(zoneId)
    val formatted = DATE_HEADER_FORMATTER.format(zoned)
    return """
      <temporal_context>
      current_local_datetime: $formatted
      current_epoch_ms: $nowMs
      timezone: ${zoneId.id}
      </temporal_context>
    """.trimIndent()
  }

  fun formatContactPersona(persona: ContactPersona?): String {
    if (persona == null || (persona.sentSamples.isEmpty() && persona.relationshipTag == "general")) {
      return "<contact_persona>\nrelationship_tag: unknown (default to concise natural English/Manglish matching sender)\nsent_samples: none yet\n</contact_persona>"
    }
    val samplesBlock = if (persona.sentSamples.isEmpty()) {
      "none yet"
    } else {
      persona.sentSamples.takeLast(5).joinToString("\n") { sample ->
        "- \"${sample.replace("\"", "'").take(140)}\""
      }
    }
    return """
      <contact_persona>
      contact: ${persona.displayName}
      relationship_tag: ${persona.relationshipTag}
      sent_samples (mirror this exact style, length, and Manglish/English mix):
      $samplesBlock
      </contact_persona>
    """.trimIndent()
  }

  fun inferRelationshipTag(displayName: String, sampleOrText: String): String {
    val combined = "$displayName $sampleOrText".lowercase(Locale.ROOT)
    return when {
      Regex("""\b(?:amma|acha|appa|mom|dad|mother|father|chetta|chechi|sister|brother)\b""").containsMatchIn(combined) ->
        "family"
      Regex("""\b(?:sir|madam|ma'am|hod|prof|professor|hr|manager|client)\b""").containsMatchIn(combined) ->
        "work"
      Regex("""\b(?:machane|aliyan|da|edi|mone|bro|bruh|dude)\b""").containsMatchIn(combined) ->
        "friend"
      else -> "general"
    }
  }
}
