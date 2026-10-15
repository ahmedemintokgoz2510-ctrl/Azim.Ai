package com.azim.app.ai

import android.content.Context
import com.azim.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class GeminiException(val code: Int, message: String) : Exception(message)

sealed class Part {
    data class Text(val text: String) : Part()
    data class Image(val mime: String, val base64: String) : Part()
}

data class Turn(val role: String, val parts: List<Part>) // role: "user" | "model"

/**
 * Gemini REST API istemcisi. Model: gemini-3.5-flash.
 * En fazla 4 anahtar sırayla kullanılır: bir anahtar günlük yumuşak sınıra (SOFT_CAP) yaklaşınca
 * ya da kota/geçersiz anahtar hatası verince sıradakine geçilir.
 */
object Gemini {
    const val MODEL = "gemini-3.5-flash"
    const val SOFT_CAP = 100 // anahtar başına günlük istek; dolunca öncelik sıradaki anahtara geçer
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val cooldown = ConcurrentHashMap<String, Long>()

    suspend fun generate(ctx: Context, system: String, turns: List<Turn>, json: Boolean = false): String {
        val prefs = Prefs.get(ctx)
        val keys = prefs.keys()
        if (keys.isEmpty()) throw GeminiException(0, "Gemini API anahtarı girilmemiş (Ayarlar)")

        val now = System.currentTimeMillis()
        val usable = keys.filter { (cooldown[it] ?: 0L) <= now }
        val ordered = usable.filter { prefs.keyUseToday(it) < SOFT_CAP } +
            usable.filter { prefs.keyUseToday(it) >= SOFT_CAP }.sortedBy { prefs.keyUseToday(it) }
        if (ordered.isEmpty()) throw GeminiException(429, "Tüm anahtarların kotası doldu, biraz sonra tekrar dene")

        var last: GeminiException? = null
        for (k in ordered) {
            try {
                val result = call(k, system, turns, json)
                prefs.addKeyUse(k)
                return result
            } catch (e: GeminiException) {
                last = e
                val badKey = e.code == 403 || (e.code == 400 && e.message?.contains("API key", true) == true)
                when {
                    e.code == 429 -> cooldown[k] = now + 2 * 60_000L
                    badKey -> cooldown[k] = now + 6 * 3_600_000L
                    e.code >= 500 -> Unit // sıradaki anahtarı dene
                    else -> throw e
                }
            }
        }
        throw last ?: GeminiException(0, "Yanıt alınamadı")
    }

    private suspend fun call(apiKey: String, system: String, turns: List<Turn>, json: Boolean): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )

            val contents = JSONArray()
            for (t in turns) {
                val parts = JSONArray()
                for (p in t.parts) {
                    when (p) {
                        is Part.Text -> parts.put(JSONObject().put("text", p.text))
                        is Part.Image -> parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", p.mime).put("data", p.base64)
                            )
                        )
                    }
                }
                contents.put(JSONObject().put("role", t.role).put("parts", parts))
            }
            body.put("contents", contents)
            if (json) {
                body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                        .getOrDefault("HTTP $code")
                    val friendly = when (code) {
                        429 -> "Ücretsiz kota doldu, biraz sonra tekrar dene"
                        else -> msg
                    }
                    throw GeminiException(code, friendly)
                }

                val root = JSONObject(text)
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.optBoolean("thought", false)) continue
                        if (part.has("text")) sb.append(part.getString("text"))
                    }
                }
                if (sb.isBlank()) throw GeminiException(0, "Boş yanıt döndü")
                sb.toString()
            } finally {
                conn.disconnect()
            }
        }
}
