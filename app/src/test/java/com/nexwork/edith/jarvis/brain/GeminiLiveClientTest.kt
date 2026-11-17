package com.nexwork.edith.jarvis.brain

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveClientTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun buildSetupFrame_includesLiveModelManglishAndAllFourTools() {
    val frame = GeminiLiveClient.buildSetupFrame()
    val setup = frame["setup"]!!.jsonObject
    assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)

    val modalities = setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray
    assertEquals("AUDIO", modalities.first().jsonPrimitive.content)

    val sysText = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
      .first().jsonObject["text"]!!.jsonPrimitive.content
    assertTrue(sysText.contains("manglish_rulebook"))

    val fnNames = setup["tools"]!!.jsonArray.first().jsonObject["functionDeclarations"]!!
      .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertEquals(
      listOf("reply_notification", "shizuku_ui_action", "open_maps", "search_memory"),
      fnNames,
    )
  }

  @Test
  fun buildRealtimeAudioFrame_encodes16kPcmBase64() {
    val raw = byteArrayOf(0x01, 0x02, 0x7F, -0x80)
    val frame = GeminiLiveClient.buildRealtimeAudioFrame(raw)
    val chunk = frame["realtimeInput"]!!.jsonObject["mediaChunks"]!!.jsonArray.first().jsonObject
    assertEquals("audio/pcm;rate=16000", chunk["mimeType"]!!.jsonPrimitive.content)
    val decoded = Base64.getDecoder().decode(chunk["data"]!!.jsonPrimitive.content)
    assertArrayEquals(raw, decoded)

    val streamEnd = GeminiLiveClient.buildAudioStreamEndFrame()
    assertTrue(streamEnd["realtimeInput"]!!.jsonObject["audioStreamEnd"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun parseServerEvent_handlesSetupInterruptedAudioAndToolCalls() {
    val audioBytes = byteArrayOf(10, 20, 30, 40)
    val b64 = Base64.getEncoder().encodeToString(audioBytes)
    val rawJson = """
      {
        "setupComplete": {},
        "serverContent": {
          "interrupted": true,
          "turnComplete": true,
          "modelTurn": {
            "parts": [
              { "text": "Checking messages now" },
              { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$b64" } }
            ]
          }
        },
        "toolCall": {
          "functionCalls": [
            {
              "id": "call_99",
              "name": "search_memory",
              "args": { "query": "Rahul", "limit": 3 }
            }
          ]
        }
      }
    """.trimIndent()

    val parsed = GeminiLiveClient.parseServerEvent(json.parseToJsonElement(rawJson).jsonObject)
    assertTrue(parsed.setupComplete)
    assertTrue(parsed.interrupted)
    assertTrue(parsed.turnComplete)
    assertEquals("Checking messages now", parsed.transcriptText)
    assertEquals(1, parsed.audioChunks.size)
    assertArrayEquals(audioBytes, parsed.audioChunks.first())
    assertEquals(1, parsed.toolCalls.size)
    assertEquals("call_99", parsed.toolCalls[0].id)
    assertEquals("search_memory", parsed.toolCalls[0].name)
    assertEquals("Rahul", parsed.toolCalls[0].args["query"]!!.jsonPrimitive.content)
  }
}
