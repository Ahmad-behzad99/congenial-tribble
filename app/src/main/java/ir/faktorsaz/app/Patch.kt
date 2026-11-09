package ir.faktorsaz.app

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Hidden panel" storage: user patches that live inside the installed app
 * (filesDir/patch) and are applied on top of the bundled index.html at load time.
 * No rebuild of the APK is needed to change look or behaviour.
 *
 *  - custom.css  : extra CSS appended last (wins over the built-in styles)
 *  - custom.js   : extra JS run after the app's own script
 *  - index.html  : optional FULL replacement of the bundled page (advanced).
 *                  Ignored automatically after an app update (version changed).
 */
class PatchStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "patch").also { it.mkdirs() }
    private val sp = ctx.getSharedPreferences("faktorsaz_patch", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("on", true)
        set(v) { sp.edit().putBoolean("on", v).commit() }

    /** Set right before loading; cleared once the page has been alive for a few seconds. */
    var pending: Boolean
        get() = sp.getBoolean("pending", false)
        set(v) { sp.edit().putBoolean("pending", v).commit() }

    var autoFetch: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) { sp.edit().putBoolean("auto", v).commit() }

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v.trim()).commit() }

    private fun versionCode(): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Exception) { -1L }

    fun get(name: String): String {
        val f = File(dir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun put(name: String, text: String) {
        val f = File(dir, name)
        if (text.isBlank()) f.delete() else f.writeText(text, Charsets.UTF_8)
    }

    fun getHtml(): String = if (sp.getLong("html_ver", -1L) == versionCode()) get("index.html") else ""

    fun putHtml(text: String) {
        put("index.html", text)
        sp.edit().putLong("html_ver", versionCode()).commit()
    }

    fun hasAny(): Boolean =
        get("custom.css").isNotBlank() || get("custom.js").isNotBlank() || getHtml().isNotBlank()

    fun reset() {
        put("custom.css", ""); put("custom.js", ""); put("index.html", "")
    }

    /** Downloads a JSON patch: {"css": "...", "js": "...", "html": "..."} (https only). */
    fun fetchRemote(link: String): JSONObject {
        require(link.startsWith("https://")) { "آدرس باید با https:// شروع شود" }
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** The JSON is the full truth: a missing key clears that patch. Returns true if anything changed. */
    fun applyJson(o: JSONObject): Boolean {
        val css = o.optString("css", ""); val js = o.optString("js", ""); val html = o.optString("html", "")
        val changed = css != get("custom.css") || js != get("custom.js") || html != getHtml()
        if (changed) { put("custom.css", css); put("custom.js", js); putHtml(html) }
        return changed
    }
}

/** Serves bundled assets, but rewrites index.html with the user's patches. */
class PatchHandler(private val ctx: Context, private val store: PatchStore) :
    WebViewAssetLoader.PathHandler {

    private val assets = WebViewAssetLoader.AssetsPathHandler(ctx)

    override fun handle(path: String): WebResourceResponse? {
        if (path != "index.html") return assets.handle(path)
        return try {
            val on = store.enabled
            var html = if (on) store.getHtml() else ""
            if (html.isEmpty()) html = ctx.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

            val sb = StringBuilder()
            // Always present (even in safe mode) so the panel can always be opened.
            sb.append("<script>").append(TRIGGER).append("</script>")
            if (on) {
                val css = store.get("custom.css")
                val js = store.get("custom.js")
                if (css.isNotBlank()) sb.append("<style id=\"user-css\">").append(css.replace("</style", "<\\/style")).append("</style>")
                if (js.isNotBlank()) sb.append("<script id=\"user-js\">").append(js.replace("</script", "<\\/script")).append("</script>")
            }
            val i = html.lastIndexOf("</body>")
            val out = if (i >= 0) html.substring(0, i) + sb + html.substring(i) else html + sb
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            assets.handle(path)
        }
    }

    companion object {
        // Tap any page title (the <h1> in the top bar) 7 times within 2 seconds each.
        private const val TRIGGER =
            "(function(){var n=0,t=0;document.addEventListener('click',function(e){" +
                "var h=e.target&&e.target.closest?e.target.closest('.bar h1'):null;if(!h)return;" +
                "var now=Date.now();if(now-t>2000)n=0;t=now;n++;" +
                "if(n>=7){n=0;try{Android.openPatchPanel()}catch(x){}}},true)})();"
    }
}
