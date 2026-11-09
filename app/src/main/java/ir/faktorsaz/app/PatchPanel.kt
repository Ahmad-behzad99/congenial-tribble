package ir.faktorsaz.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The hidden panel: edit CSS / JS / full HTML or pull a patch from a URL, then apply instantly. */
class PatchPanel(
    private val a: Activity,
    private val store: PatchStore,
    private val onApply: () -> Unit
) {
    private val titles = arrayOf("CSS ظاهر", "JS رفتار", "HTML کامل", "آدرس")
    private val hints = arrayOf(
        "CSS دلخواه. بعد از استایل‌های خود برنامه اعمال می‌شود؛ مثلاً: :root{--orange:#2E7D32}",
        "JavaScript دلخواه. بعد از کد خود برنامه اجرا می‌شود.",
        "پیشرفته: جایگزینی کامل index.html. خالی = استفاده از HTML داخل برنامه. بعد از به‌روزرسانی APK خودکار نادیده گرفته می‌شود.",
        "آدرس https یک فایل JSON به شکل {\"css\":\"...\",\"js\":\"...\",\"html\":\"...\"} (مثلاً raw.githubusercontent.com). کلید حذف‌شده یعنی پاک شدن آن وصله."
    )
    private val vals = arrayOf(store.get("custom.css"), store.get("custom.js"), store.getHtml(), store.url)
    private var cur = 0

    private fun px(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun show() {
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor("#1E1914"))
            setPadding(px(12), px(36), px(12), px(12))
        }
        fun tv(text: String, size: Float, color: String) = TextView(a).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor(color))
        }

        root.addView(tv("پنل مخفی (وصله‌ها)", 18f, "#FFB35C"))

        val enabled = CheckBox(a).apply {
            text = "وصله‌ها فعال باشند"; isChecked = store.enabled
            setTextColor(Color.WHITE)
        }
        root.addView(enabled)

        val tabRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = titles.mapIndexed { i, t ->
            Button(a).apply {
                text = t; textSize = 12f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { tabRow.addView(it) }
        }
        root.addView(tabRow)

        val hint = tv("", 12f, "#C9BBAA")
        root.addView(hint)

        val editor = EditText(a).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2B241D"))
            gravity = Gravity.TOP or Gravity.LEFT
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHorizontallyScrolling(false)
            setPadding(px(8), px(8), px(8), px(8))
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = px(6); bottomMargin = px(6)
        })

        val auto = CheckBox(a).apply {
            text = "هنگام باز شدن برنامه، از آدرس خودکار به‌روز شود"; isChecked = store.autoFetch
            setTextColor(Color.WHITE)
        }
        root.addView(auto)

        val extra = Button(a).apply { isAllCaps = false }
        root.addView(extra)

        fun refresh() {
            tabs.forEachIndexed { i, b -> b.alpha = if (i == cur) 1f else 0.5f }
            hint.text = hints[cur]
            editor.setText(vals[cur])
            editor.hint = if (cur == 3) "https://raw.githubusercontent.com/.../patch.json" else ""
            auto.visibility = if (cur == 3) View.VISIBLE else View.GONE
            when (cur) {
                2 -> { extra.visibility = View.VISIBLE; extra.text = "بارگذاری HTML فعلی برنامه برای ویرایش" }
                3 -> { extra.visibility = View.VISIBLE; extra.text = "دریافت و اعمال از آدرس" }
                else -> extra.visibility = View.GONE
            }
        }
        fun stash() { vals[cur] = editor.text.toString() }
        tabs.forEachIndexed { i, b -> b.setOnClickListener { stash(); cur = i; refresh() } }

        extra.setOnClickListener {
            if (cur == 2) {
                try {
                    editor.setText(a.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() })
                } catch (e: Exception) {
                    Toast.makeText(a, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else if (cur == 3) {
                stash()
                val link = vals[3].trim()
                Toast.makeText(a, "در حال دریافت…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val o = store.fetchRemote(link)
                        store.url = link
                        store.applyJson(o)
                        a.runOnUiThread {
                            Toast.makeText(a, "اعمال شد", Toast.LENGTH_SHORT).show()
                            d.dismiss(); onApply()
                        }
                    } catch (e: Exception) {
                        a.runOnUiThread { Toast.makeText(a, "خطا: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }

        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, click: () -> Unit) = Button(a).apply {
            text = t; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { click() }
        }.also { row.addView(it) }

        btn("ذخیره و اعمال") {
            stash()
            store.enabled = enabled.isChecked
            store.autoFetch = auto.isChecked
            store.put("custom.css", vals[0])
            store.put("custom.js", vals[1])
            store.putHtml(vals[2])
            store.url = vals[3]
            d.dismiss(); onApply()
        }
        btn("بازنشانی همه") {
            store.reset(); store.autoFetch = false; store.enabled = true
            d.dismiss(); onApply()
            Toast.makeText(a, "همه وصله‌ها پاک شد", Toast.LENGTH_SHORT).show()
        }
        btn("بستن") { d.dismiss() }
        root.addView(row)

        refresh()
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.show()
    }
}
