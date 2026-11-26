package com.glassbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class Tab(val wv: WebView, val priv: Boolean) {
    var url = ""
    var title = ""
}

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var ui: WebView
    private lateinit var ctl: WindowInsetsControllerCompat
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private var mode = "ui"
    private var stripDp = 170
    private var topI = 0
    private var botI = 0
    private var hid = false
    private var desk = false
    private val dm get() = resources.displayMetrics.density
    private val deskUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        ctl = WindowInsetsControllerCompat(window, root)
        ui = WebView(this)
        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.settings.javaScriptEnabled = true
        ui.settings.domStorageEnabled = true
        ui.addJavascriptInterface(Bridge(), "Android")
        root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        ui.loadUrl("file:///android_asset/ui.html")
        addTab(false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            topI = i.top; botI = i.bottom
            layout()
            ui.evaluateJavascript("setInsets(${topI / dm},${botI / dm})", null)
            ins
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: return
        ui.postDelayed({
            tabs[cur].wv.loadUrl(u)
            ui.evaluateJavascript("show('page')", null)
        }, 900)
    }

    private fun addTab(priv: Boolean) {
        val w = WebView(this)
        val t = Tab(w, priv)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        w.settings.setSupportZoom(true)
        w.settings.builtInZoomControls = true
        w.settings.displayZoomControls = false
        if (priv) w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        if (desk) { w.settings.userAgentString = deskUa; w.settings.useWideViewPort = true; w.settings.loadWithOverviewMode = true }
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                t.url = if (url == null || url == "about:blank") "" else url
                sync(t)
            }
            override fun onPageFinished(view: WebView?, url: String?) { sync(t) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) { t.title = title ?: ""; sync(t) }
        }
        w.setOnScrollChangeListener { _, _, y, _, oy -> scrolled(y - oy) }
        w.setDownloadListener { u, ua, cd, mime, _ ->
            val r = DownloadManager.Request(Uri.parse(u))
            r.setMimeType(mime)
            r.addRequestHeader("User-Agent", ua)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(u, cd, mime))
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
        }
        tabs.add(t)
        select(tabs.size - 1)
    }

    private fun select(i: Int) {
        tabs.forEach { root.removeView(it.wv) }
        cur = i
        root.addView(tabs[i].wv, 0, FrameLayout.LayoutParams(-1, -1))
        layout()
        sync(tabs[i])
    }

    private fun sync(t: Tab) {
        if (tabs.isEmpty() || t !== tabs[cur]) return
        ui.post { ui.evaluateJavascript("pi(${JSONObject.quote(t.url)},${JSONObject.quote(t.title)})", null) }
    }

    private fun layout() {
        if (tabs.isEmpty()) return
        val w = tabs[cur].wv
        val lp = FrameLayout.LayoutParams(-1, -1)
        lp.topMargin = topI
        w.layoutParams = lp
        w.visibility = if (mode == "ui") View.GONE else View.VISIBLE
        val h = if (mode == "page") (stripDp * dm).toInt() + botI else -1
        val u = FrameLayout.LayoutParams(-1, h)
        u.gravity = Gravity.BOTTOM
        ui.layoutParams = u
    }

    private fun setMode(m: String, dp: Int) {
        mode = m
        if (dp > 0) stripDp = dp
        if (m == "ui" && hid) { hid = false; ctl.show(WindowInsetsCompat.Type.systemBars()) }
        layout()
    }

    private fun scrolled(d: Int) {
        if (mode != "page") return
        if (d > 12 && !hid) {
            hid = true
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ui.evaluateJavascript("sd(1)", null)
        } else if (d < -12 && hid) {
            hid = false
            ctl.show(WindowInsetsCompat.Type.systemBars())
            ui.evaluateJavascript("sd(-1)", null)
        }
    }

    private fun ask(q: String) {
        thread {
            val out = try {
                val c = URL("https://text.pollinations.ai/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "application/json")
                val sys = JSONObject().put("role", "system").put("content", "You are a fast, concise assistant inside a web browser. Answer clearly in a few sentences.")
                val usr = JSONObject().put("role", "user").put("content", q)
                val body = JSONObject().put("model", "openai").put("messages", JSONArray().put(sys).put(usr)).toString()
                c.outputStream.use { it.write(body.toByteArray()) }
                c.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                "Sorry, the AI service could not be reached. Check your internet and try again."
            }
            ui.post { ui.evaluateJavascript("aiReply(${JSONObject.quote(out)})", null) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val w = tabs[cur].wv
        if (mode != "ui" && w.canGoBack()) w.goBack()
        else ui.evaluateJavascript("backUi()") { if (it == "true") finish() }
    }

    inner class Bridge {
        @JavascriptInterface fun nav(u: String) { runOnUiThread { tabs[cur].wv.loadUrl(u) } }
        @JavascriptInterface fun mode(m: String, dp: Int) { runOnUiThread { setMode(m, dp) } }
        @JavascriptInterface fun insets(): String = "${topI / dm},${botI / dm}"
        @JavascriptInterface fun newTab(p: Int) { runOnUiThread { addTab(p == 1) } }
        @JavascriptInterface fun switchTab(i: Int): Boolean {
            val h = tabs[i].url.isNotEmpty()
            runOnUiThread { select(i) }
            return h
        }
        @JavascriptInterface fun closeTab(i: Int) {
            runOnUiThread {
                if (tabs.size > 1) {
                    val t = tabs.removeAt(i)
                    root.removeView(t.wv)
                    t.wv.destroy()
                    select(if (i < cur) cur - 1 else minOf(cur, tabs.size - 1))
                } else {
                    tabs[0].wv.loadUrl("about:blank")
                    tabs[0].url = ""
                }
            }
        }
        @JavascriptInterface fun tabsJson(): String = JSONArray(tabs.map {
            JSONObject().put("title", if (it.url.isEmpty()) "New Tab" else it.title.ifEmpty { it.url }).put("url", it.url)
        }).toString()
        @JavascriptInterface fun back() {
            runOnUiThread {
                val w = tabs[cur].wv
                if (w.canGoBack()) w.goBack() else ui.evaluateJavascript("show('home')", null)
            }
        }
        @JavascriptInterface fun fwd() {
            runOnUiThread {
                val w = tabs[cur].wv
                if (w.canGoForward()) { w.goForward(); ui.evaluateJavascript("show('page')", null) }
            }
        }
        @JavascriptInterface fun desktop() {
            runOnUiThread {
                desk = !desk
                tabs.forEach {
                    it.wv.settings.userAgentString = if (desk) deskUa else null
                    it.wv.settings.useWideViewPort = desk
                    it.wv.settings.loadWithOverviewMode = desk
                }
                tabs[cur].wv.reload()
            }
        }
        @JavascriptInterface fun reader() {
            runOnUiThread {
                tabs[cur].wv.evaluateJavascript(
                    "(function(){var a=document.querySelector('article')||document.querySelector('main')||document.body;" +
                        "var h=a.innerHTML;document.body.innerHTML='<div style=\"max-width:680px;margin:auto;padding:20px;font:19px/1.6 serif\">'+h+'</div>';" +
                        "document.querySelectorAll('script,iframe,nav,footer,aside,header').forEach(function(e){e.remove()})})()", null)
            }
        }
        @JavascriptInterface fun find(s: String) { runOnUiThread { tabs[cur].wv.findAllAsync(s) } }
        @JavascriptInterface fun translate() {
            runOnUiThread {
                val u = tabs[cur].url
                if (u.isNotEmpty()) tabs[cur].wv.loadUrl("https://translate.google.com/translate?sl=auto&u=" + Uri.encode(u))
            }
        }
        @JavascriptInterface fun share() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tabs[cur].url)
                startActivity(Intent.createChooser(i, null))
            }
        }
        @JavascriptInterface fun ai(q: String) { ask(q) }
        @JavascriptInterface fun summarize() {
            runOnUiThread {
                tabs[cur].wv.evaluateJavascript("(document.body.innerText||'').slice(0,6000)") { r ->
                    val t = try { JSONArray("[$r]").getString(0) } catch (e: Exception) { "" }
                    ask("Summarize this web page in 5 short bullet points:\n\n$t")
                }
            }
        }
    }
}
