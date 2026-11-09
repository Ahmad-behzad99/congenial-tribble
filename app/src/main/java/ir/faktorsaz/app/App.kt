package ir.faktorsaz.app

import android.app.Application
import android.webkit.WebView

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Needed so an offscreen WebView can draw its whole page into a bitmap (invoice export).
        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Throwable) {
        }
    }
}
