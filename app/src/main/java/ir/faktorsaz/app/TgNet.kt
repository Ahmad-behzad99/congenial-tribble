package ir.faktorsaz.app

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Telegram Bot API client (blocking: call it from a background thread). */
object TgNet {
    private fun finish(c: HttpURLConnection): Boolean {
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code in 200..299 && JSONObject(text).optBoolean("ok", false)
        } catch (_: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    fun sendMessage(token: String, chat: String, text: String): Boolean {
        return try {
            val t = if (text.length > 4000) text.substring(0, 3990) + "\n…" else text
            val c = URL("https://api.telegram.org/bot" + URLEncoder.encode(token, "UTF-8") + "/sendMessage")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(t, "UTF-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            finish(c)
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a PNG as a photo-like document or a PDF as a document (document keeps full quality). */
    fun sendFile(token: String, chat: String, file: File, mime: String, caption: String): Boolean {
        return try {
            val boundary = "----faktorsaz" + System.currentTimeMillis()
            val c = URL("https://api.telegram.org/bot" + URLEncoder.encode(token, "UTF-8") + "/sendDocument")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray(Charsets.UTF_8))
                }
                field("chat_id", chat)
                field("caption", caption)
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            finish(c)
        } catch (_: Exception) {
            false
        }
    }
}
