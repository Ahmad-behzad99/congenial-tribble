package ir.faktorsaz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catches incoming SMS while the app is closed. If the text looks like a bank deposit
 * ("واریز"), the amount is queued in SharedPreferences; the web page picks the queue up
 * the next time the app is opened (MainActivity.Bridge.takePendingSms) and adds a row to «دریافتی‌ها».
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = context.getSharedPreferences("faktorsaz", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sms_on", false)) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val sender = parts[0].originatingAddress ?: ""
        val ts = parts[0].timestampMillis
        val toman = parseDeposit(body) ?: return
        synchronized(LOCK) {
            val arr = try { JSONArray(prefs.getString("sms_pending", "[]")) } catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().put("a", toman).put("ts", ts).put("from", sender))
            prefs.edit().putString("sms_pending", arr.toString()).apply()
        }
    }

    companion object {
        val LOCK = Any()

        private fun digits(s: String): String {
            val sb = StringBuilder()
            for (c in s) sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    '٬', '،' -> ','
                    else -> c
                }
            )
            return sb.toString()
        }

        /** Returns the deposited amount in Toman, or null when the text is not a deposit. */
        fun parseDeposit(raw: String): Long? {
            var t = digits(raw)
            if (!t.contains("واریز") && !Regex("(^|\\s)\\+\\s?\\d").containsMatchIn(t)) return null
            // Not a deposit: withdrawals, purchases, one-time passwords.
            if (Regex("برداشت|خرید|پرداخت|انتقال از|کارمزد|رمز|کد\\s*(تایید|تأیید|فعال|یکبار)|otp|password", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                if (!t.contains("واریز")) return null
                if (Regex("رمز|کد\\s*(تایید|تأیید|فعال|یکبار)|otp|password", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
            }
            // Strip things that look like numbers but are not the amount.
            t = t.replace(Regex("(مانده|موجودی)[^\\d\\n]{0,20}[\\d,]+"), " ")
            t = t.replace(Regex("\\d+\\s*[/:\\-.]\\s*\\d+(\\s*[/:\\-.]\\s*\\d+)*"), " ")
            t = t.replace(Regex("[*xX•]+\\s*\\d+|\\d+\\s*[*xX•]+"), " ")
            t = t.replace(Regex("(حساب|کارت|شماره|شبا|IR|ir)[^\\d\\n]{0,12}\\d[\\d\\- ]*"), " ")
            t = t.replace(Regex("\\d{11,}"), " ")
            val m = Regex("\\d{1,3}(,\\d{3})+|\\d{4,}").find(t) ?: return null
            val v = m.value.replace(",", "").toLongOrNull() ?: return null
            if (v <= 0) return null
            // Bank texts are normally in Rial; the app uses Toman.
            return if (t.contains("تومان")) v else v / 10
        }
    }
}
