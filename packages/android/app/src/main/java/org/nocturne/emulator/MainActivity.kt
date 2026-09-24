package org.nocturne.emulator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min
import org.nocturne.emulator.flasher.FlasherActivity

/**
 * Kiosk host for the emulated Car Thing: a WebView rendering the real UI
 * served by the bundled nocturned daemon (http://127.0.0.1:8080) plus a
 * drawn bezel that injects the hardware events the UI expects (dial wheel
 * deltaX, dial press = Enter, back = Escape, presets = Digit1-4,
 * settings = KeyM).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var loadedOnce = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        DaemonService.start(this)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.BLACK)
        }

        val bezel = BezelView(this, ::dispatchHardwareKey, ::dispatchDialDelta)

        // The UI is authored for a fixed 800x480 CSS viewport, so the WebView
        // must be laid out in dp: a view W dp wide always produces an W css px
        // device-width viewport regardless of screen density.
        val displayW = (DISPLAY_W_DP * density).toInt()
        val displayH = (DISPLAY_H_DP * density).toInt()
        val bezelW = (BEZEL_W_DP * density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(webView, LinearLayout.LayoutParams(displayW, displayH))
            addView(bezel, LinearLayout.LayoutParams(bezelW, displayH))
        }

        val status = TextView(this).apply {
            text = "starting nocturned..."
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }

        val flasherLink = TextView(this).apply {
            text = "flasher"
            setTextColor(0xFF616161.toInt())
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FlasherActivity::class.java))
            }
        }

        val importLink = TextView(this).apply {
            text = "import"
            setTextColor(0xFF616161.toInt())
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ImportActivity::class.java))
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // The panel is scaled around its top-left corner and centered by
            // translation in fitPanel; it must sit at 0,0 unscaled.
            addView(panel, FrameLayout.LayoutParams(
                displayW + bezelW, displayH, Gravity.TOP or Gravity.START))
            addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addView(flasherLink, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ))
            addView(importLink, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ))
        }
        setContentView(root)

        root.post { fitPanel(panel, root) }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPanel(panel, root) }

        Thread {
            waitForDaemon()
            runOnUiThread {
                status.visibility = View.GONE
                loadedOnce = true
                webView.loadUrl(DAEMON_UI_URL)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Returning from ImportActivity after a daemon restart needs a reload
        // so the WebView picks up the newly served bundle.
        if (loadedOnce) webView.reload()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> dispatchHardwareKey("Escape")
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun fitPanel(panel: View, root: View) {
        val scale = min(
            root.width.toFloat() / panel.width,
            root.height.toFloat() / panel.height,
        )
        panel.scaleX = scale
        panel.scaleY = scale
        panel.pivotX = 0f
        panel.pivotY = 0f
        panel.translationX = (root.width - panel.width * scale) / 2f
        panel.translationY = (root.height - panel.height * scale) / 2f
    }

    private fun dispatchHardwareKey(keyAndCode: String) {
        val key = keyAndCode.substringBefore('|')
        val code = keyAndCode.substringAfter('|')
        // A document-targeted event propagates window(capture) -> document ->
        // window(bubble), reaching both window and document listeners exactly
        // once; dispatching on each would double-fire UI handlers.
        val js = """
            const o={key:'$key',code:'$code',bubbles:true};
            document.dispatchEvent(new KeyboardEvent('keydown',o));
            document.dispatchEvent(new KeyboardEvent('keyup',o));
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun dispatchDialDelta(direction: Int) {
        val js = """
            document.dispatchEvent(new WheelEvent('wheel',{deltaX:${direction * 120},bubbles:true}));
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun waitForDaemon() {
        repeat(300) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", DAEMON_HTTP_PORT), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
    }

    private val density: Float
        get() = resources.displayMetrics.density

    companion object {
        const val DISPLAY_W_DP = 800
        const val DISPLAY_H_DP = 480
        const val BEZEL_W_DP = 160
        const val DAEMON_HTTP_PORT = 8080
        const val DAEMON_UI_URL = "http://127.0.0.1:8080/"
    }
}
