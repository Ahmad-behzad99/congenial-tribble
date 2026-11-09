package ir.faktorsaz.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Blue verified-style tick drawn at runtime. */
object TickBadge {
    fun bitmap(px: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = px / 2f
        val cy = px / 2f
        val r = px / 2f * 0.86f
        p.color = Color.parseColor("#1D9BF0")
        val path = android.graphics.Path()
        val n = 64
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            val rr = r * (1f + 0.12f * Math.cos(8 * a).toFloat())
            val x = cx + rr * Math.cos(a).toFloat()
            val y = cy + rr * Math.sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
        p.color = Color.WHITE
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = px * 0.11f
        p.strokeCap = android.graphics.Paint.Cap.ROUND
        p.strokeJoin = android.graphics.Paint.Join.ROUND
        val t = android.graphics.Path()
        t.moveTo(cx - r * 0.4f, cy + r * 0.02f)
        t.lineTo(cx - r * 0.1f, cy + r * 0.33f)
        t.lineTo(cx + r * 0.42f, cy - r * 0.3f)
        c.drawPath(t, p)
        return bmp
    }
}

/** App code (PIN) kept as a salted PBKDF2 hash. */
object PinStore {
    private const val ITER = 60_000

    /** The app code is always exactly 4 digits and unlocks as soon as the 4th digit is typed. */
    const val LEN = 4

    fun has(p: SharedPreferences, k: String = "pin") = p.contains("${k}_hash") && p.getInt("${k}_len", 0) == LEN

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    fun set(p: SharedPreferences, pin: String, k: String = "pin") {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("${k}_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("${k}_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("${k}_len", pin.length)
            .apply()
    }

    fun check(p: SharedPreferences, pin: String, k: String = "pin"): Boolean {
        val s = p.getString("${k}_salt", null) ?: return false
        val h = p.getString("${k}_hash", null) ?: return false
        return MessageDigest.isEqual(
            Base64.decode(h, Base64.NO_WRAP),
            hash(pin, Base64.decode(s, Base64.NO_WRAP))
        )
    }

    fun clear(p: SharedPreferences, k: String = "pin") {
        p.edit().remove("${k}_hash").remove("${k}_salt").remove("${k}_len").apply()
    }
}

/** Full-screen number pad used for the lock screen and for choosing the app code. */
class PinView(ctx: Context) : FrameLayout(ctx) {
    var onDigit: (Int) -> Unit = {}
    var onBack: () -> Unit = {}
    var onAlt: () -> Unit = {}
    var onBio: () -> Unit = {}
    var onExtra: () -> Unit = {}

    private val navy = 0xFF15305F.toInt()
    private val grey = 0xFF6B7A8D.toInt()
    private val dotsRow = LinearLayout(ctx)
    private val titleTv = TextView(ctx)
    private val msgTv = TextView(ctx)
    private val altTv = TextView(ctx)
    private val bioTv = TextView(ctx)
    private val extraTv = TextView(ctx)
    private val box = LinearLayout(ctx)
    private val fa = "۰۱۲۳۴۵۶۷۸۹"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circle(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    init {
        setBackgroundResource(R.color.app_bg)
        isClickable = true
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(24), dp(48), dp(24), dp(24))

        // App logo (assets/applogo.png), same one shown in the app header.
        val img = ImageView(ctx)
        try {
            ctx.assets.open("applogo.png").use { img.setImageBitmap(BitmapFactory.decodeStream(it)) }
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, v.width * 0.24f)
            }
            img.clipToOutline = true
            box.addView(img, LinearLayout.LayoutParams(dp(120), dp(120)).apply { bottomMargin = dp(12) })
        } catch (_: Exception) {
        }

        titleTv.visibility = View.GONE
        titleTv.textSize = 24f
        titleTv.setTextColor(navy)
        titleTv.setTypeface(Typeface.DEFAULT_BOLD)
        titleTv.gravity = Gravity.CENTER
        box.addView(titleTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        msgTv.textSize = 14f
        msgTv.setTextColor(grey)
        msgTv.gravity = Gravity.CENTER
        msgTv.minLines = 2
        box.addView(msgTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(14) })

        dotsRow.orientation = LinearLayout.HORIZONTAL
        dotsRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dotsRow.gravity = Gravity.CENTER
        box.addView(dotsRow, LinearLayout.LayoutParams(-2, dp(20)).apply { bottomMargin = dp(22) })

        // Digits are always laid out left-to-right like a phone keypad.
        val pad = LinearLayout(ctx)
        pad.orientation = LinearLayout.VERTICAL
        pad.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "alt", "0", "back")
        for (r in 0 until 4) {
            val row = LinearLayout(ctx)
            row.gravity = Gravity.CENTER
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val tv = if (k == "alt") altTv else TextView(ctx)
                tv.gravity = Gravity.CENTER
                when (k) {
                    "alt" -> {
                        tv.textSize = 13f
                        tv.setTextColor(grey)
                        tv.setOnClickListener { onAlt() }
                    }
                    "back" -> {
                        tv.text = "⌫"
                        tv.textSize = 24f
                        tv.setTextColor(grey)
                        tv.setOnClickListener { haptic(it); onBack() }
                    }
                    else -> {
                        tv.text = fa[k.toInt()].toString()
                        tv.textSize = 28f
                        tv.setTextColor(navy)
                        tv.background = circle(Color.WHITE, 0xFFD5DEE8.toInt())
                        tv.setOnClickListener { haptic(it); onDigit(k.toInt()) }
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                    setMargins(dp(10), dp(6), dp(10), dp(6))
                })
            }
            pad.addView(row)
        }
        box.addView(pad)

        bioTv.textSize = 14f
        bioTv.setTextColor(navy)
        bioTv.gravity = Gravity.CENTER
        bioTv.setPadding(dp(18), dp(10), dp(18), dp(10))
        bioTv.background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat(); setColor(Color.WHITE); setStroke(dp(1), 0xFFD5DEE8.toInt())
        }
        bioTv.setOnClickListener { onBio() }
        bioTv.visibility = View.GONE
        box.addView(bioTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        extraTv.textSize = 13f
        extraTv.setTextColor(0xFF2A55B5.toInt())
        extraTv.setPadding(dp(12), dp(12), dp(12), dp(12))
        extraTv.setOnClickListener { onExtra() }
        extraTv.visibility = View.GONE
        box.addView(extraTv, LinearLayout.LayoutParams(-2, -2))

        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        sv.addView(box, ViewGroup.LayoutParams(-1, -2))
        addView(sv, LayoutParams(-1, -1))
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun setMessage(text: String, error: Boolean = false) {
        msgTv.text = text
        msgTv.setTextColor(if (error) 0xFFC0392B.toInt() else grey)
    }

    fun setDots(total: Int, filled: Int) {
        dotsRow.removeAllViews()
        for (i in 0 until total) {
            val d = View(context)
            d.background = if (i < filled) circle(navy, navy) else circle(Color.TRANSPARENT, navy)
            dotsRow.addView(d, LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(7), 0, dp(7), 0) })
        }
    }

    fun setAlt(label: String?) {
        altTv.text = label ?: ""
        altTv.isClickable = label != null
    }

    fun setBio(label: String?) {
        bioTv.text = label ?: ""
        bioTv.visibility = if (label == null) View.GONE else View.VISIBLE
    }

    fun setExtra(label: String?) {
        extraTv.text = label ?: ""
        extraTv.visibility = if (label == null) View.GONE else View.VISIBLE
    }

    fun setTitle(t: String) {
        titleTv.text = t
        titleTv.visibility = View.VISIBLE
    }

    fun shake() {
        dotsRow.animate().translationX(dp(14).toFloat()).setDuration(50).withEndAction {
            dotsRow.animate().translationX(-dp(14).toFloat()).setDuration(80).withEndAction {
                dotsRow.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }
}
