package ir.faktorsaz.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Sends the daily report and unpaid-invoice reminders to Telegram even when the app is closed.
 * The web page stores a compact snapshot of the data in SharedPreferences ("tg_snap", see Bridge.tgSnap);
 * this worker runs about once an hour and mirrors the logic of the page (reportText / reminderText).
 * Android may delay it in battery-saver mode, so it is "about on time", not exact.
 */
class TgWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        try {
            runOnce(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "tg_assistant"

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            val req = PeriodicWorkRequestBuilder<TgWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun fa(s: String): String {
            val sb = StringBuilder()
            for (ch in s) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
            return sb.toString()
        }

        private fun money(n: Double): String =
            fa(Regex("\\B(?=(\\d{3})+(?!\\d))").replace(Math.round(n).toString(), ","))

        private fun dayKey(cal: Calendar): String =
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"

        private fun faDate(): String = try {
            android.icu.text.DateFormat.getDateInstance(
                android.icu.text.DateFormat.FULL, android.icu.util.ULocale("fa_IR")
            ).format(java.util.Date())
        } catch (_: Throwable) {
            ""
        }

        fun runOnce(ctx: Context) {
            val prefs = ctx.getSharedPreferences("faktorsaz", Context.MODE_PRIVATE)
            val raw = prefs.getString("tg_snap", null) ?: return
            val snap = JSONObject(raw)
            val token = snap.optString("token")
            val chat = snap.optString("chat")
            if (token.isEmpty() || chat.isEmpty()) return
            val inv = snap.optJSONArray("inv")
            val nowCal = Calendar.getInstance()
            val key = dayKey(nowCal)
            val now = System.currentTimeMillis()

            if (snap.optBoolean("rep") && prefs.getString("tg_last_rep", "") != key &&
                nowCal.get(Calendar.HOUR_OF_DAY) >= snap.optInt("repHour", 21)
            ) {
                var inc = 0.0
                var exp = 0.0
                var n = 0
                var openN = 0
                var openSum = 0.0
                if (inv != null) for (i in 0 until inv.length()) {
                    val o = inv.getJSONObject(i)
                    val t = o.optString("t")
                    val a = o.optDouble("a", 0.0)
                    val c = Calendar.getInstance().apply { timeInMillis = o.optLong("ts") }
                    if (dayKey(c) == key) {
                        if (t == "sale") { inc += a; n++ } else if (t == "buy" || t == "rent" || t == "ship") { exp += a; n++ }
                    }
                    if (t == "sale" && o.optInt("cr") == 1 && o.optInt("st") == 0) { openN++; openSum += a }
                }
                val lines = ArrayList<String>()
                lines.add("📊 گزارش روزانه — " + faDate())
                lines.add("")
                lines.add("💰 فروش امروز: " + money(inc) + " تومان")
                lines.add("🧾 خرید و هزینه امروز: " + money(exp) + " تومان")
                lines.add("📈 مانده امروز: " + money(inc - exp) + " تومان")
                lines.add("🗂 تعداد فاکتور امروز: " + fa(n.toString()))
                lines.add("")
                lines.add("⏳ نسیه‌های تسویه‌نشده: " + fa(openN.toString()) + " فاکتور، جمعاً " + money(openSum) + " تومان")
                val wage = snap.optDouble("wage", 0.0)
                if (wage > 0) lines.add("👷 مانده‌ی دستمزد کارگرها (لیست کار): " + money(wage) + " تومان")
                if (TgNet.sendMessage(token, chat, lines.joinToString("\n"))) {
                    prefs.edit().putString("tg_last_rep", key).apply()
                }
            }

            if (snap.optBoolean("rem") && prefs.getString("tg_last_rem", "") != key) {
                val minMs = snap.optInt("remDays", 3) * 86400000L
                val list = ArrayList<JSONObject>()
                if (inv != null) for (i in 0 until inv.length()) {
                    val o = inv.getJSONObject(i)
                    if (o.optString("t") == "sale" && o.optInt("cr") == 1 && o.optInt("st") == 0 &&
                        now - o.optLong("ts") >= minMs
                    ) list.add(o)
                }
                if (list.isEmpty()) {
                    prefs.edit().putString("tg_last_rem", key).apply()
                } else {
                    list.sortBy { it.optLong("ts") }
                    val lines = ArrayList<String>()
                    lines.add("⏰ یادآوری فاکتورهای پرداخت‌نشده (نسیه)")
                    lines.add("")
                    var sum = 0.0
                    list.forEachIndexed { k, o ->
                        val a = o.optDouble("a", 0.0)
                        sum += a
                        if (k < 25) {
                            val who = o.optString("c").ifEmpty { "بدون نام" }
                            val days = (now - o.optLong("ts")) / 86400000L
                            lines.add("• فاکتور " + fa(o.optString("no")) + " — " + who + " — " + money(a) +
                                " تومان — " + fa(days.toString()) + " روز پیش")
                        }
                    }
                    if (list.size > 25) lines.add("… و " + fa((list.size - 25).toString()) + " مورد دیگر")
                    lines.add("")
                    lines.add("جمع: " + money(sum) + " تومان")
                    if (TgNet.sendMessage(token, chat, lines.joinToString("\n"))) {
                        prefs.edit().putString("tg_last_rem", key).apply()
                    }
                }
            }
        }
    }
}
