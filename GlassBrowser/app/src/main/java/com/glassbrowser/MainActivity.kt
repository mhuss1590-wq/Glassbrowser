package com.glassbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Tab(val wv: WebView, val priv: Boolean) {
    var url = ""
    var title = ""
    var lazy: String? = null
    var thumb: String? = null
    var upgraded = ""
    val blocked = AtomicInteger(0)
}

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    companion object {
        val DEF = mapOf(
            "theme" to "dark", "gradient" to "1", "accent" to "#0a84ff", "blur" to "24", "sat" to "180",
            "radius" to "24", "text" to "16", "jelly" to "1",
            "toolbar" to "autohide", "addr" to "bottom", "tabview" to "grid",
            "showFav" to "1", "showAI" to "1", "showRL" to "1", "showPR" to "1",
            "aiOn" to "1", "summ" to "1", "voice" to "1",
            "safe" to "2", "cookies" to "1", "block" to "1", "https" to "1", "lockPriv" to "0",
            "popups" to "1", "dnt" to "1", "cbanner" to "1",
            "engine" to "1", "hz" to "1", "desk" to "0", "dark" to "0", "askSave" to "0", "zoom" to "100"
        )
        const val RC_FILE = 41; const val RC_SAVE = 42; const val RC_VOICE = 43; const val RC_ROLE = 44
        const val RC_BACKUP = 45; const val RC_RESTORE = 46; const val RC_AUTH = 47; const val RC_PERM = 77
    }

    private lateinit var root: FrameLayout
    private lateinit var ui: WebView
    private lateinit var ctl: WindowInsetsControllerCompat
    private lateinit var sp: android.content.SharedPreferences
    private lateinit var site: android.content.SharedPreferences
    private lateinit var stats: android.content.SharedPreferences
    private val cfg = ConcurrentHashMap<String, String>()
    private val tabs = ArrayList<Tab>()
    private val httpOk = HashSet<String>()
    private val pendBlocked = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var uiReady = false
    private var cur = 0
    private var mode = "ui"
    private var stripDp = 138
    private var topI = 0
    private var botI = 0
    private var navI = 0
    private var hid = false
    private var desk = false
    private var pendingUrl: String? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var permCb: ((Boolean) -> Unit)? = null
    private var saveReq: Triple<String, String, String>? = null
    private var backupData = ""
    private val dm get() = resources.displayMetrics.density
    private val deskUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val baseUa by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace("Version/4.0 ", "")
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(b)
        splash.setKeepOnScreenCondition { !uiReady }
        splash.setOnExitAnimationListener { p ->
            try {
                val icon = p.iconView
                val view = p.view
                icon.animate().scaleX(1.35f).scaleY(1.35f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(3.2f))
                    .withEndAction {
                        view.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(230)
                            .withEndAction { p.remove() }.start()
                    }.start()
            } catch (e: Throwable) { p.remove() }
        }
        main.postDelayed({ uiReady = true }, 2500)

        sp = getSharedPreferences("settings", MODE_PRIVATE)
        site = getSharedPreferences("site", MODE_PRIVATE)
        stats = getSharedPreferences("stats", MODE_PRIVATE)
        DEF.forEach { (k, v) -> cfg[k] = sp.getString(k, v) ?: v }
        desk = cfg["desk"] == "1"
        thread { AdBlock.load(this); AdBlock.maybeUpdate(this) }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1A0626"))
        setContentView(root)
        ctl = WindowInsetsControllerCompat(window, root)
        CookieManager.getInstance().setAcceptCookie(cfg["cookies"] != "2")

        ui = WebView(this)
        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.settings.javaScriptEnabled = true
        ui.settings.domStorageEnabled = true
        ui.addJavascriptInterface(Bridge(), "Android")
        root.addView(ui, FrameLayout.LayoutParams(-1, -1))
        ui.loadUrl("file:///android_asset/ui.html")

        restoreSession()
        if (tabs.isEmpty()) addTab(false)
        applyGlobal()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            topI = i.top; botI = i.bottom
            navI = ins.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
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

    override fun onStop() {
        super.onStop()
        flushStats()
        saveSession()
        ui.evaluateJavascript("lockPriv()", null)
    }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: return
        if (uiReady && ::ui.isInitialized) openExternal(u) else pendingUrl = u
    }

    private fun openExternal(u: String) {
        val t = if (tabs[cur].url.isEmpty() && !tabs[cur].priv) tabs[cur] else addTab(false)
        t.wv.loadUrl(u, hdrs())
        ui.evaluateJavascript("show('page')", null)
    }

    // ------------------------------------------------------------------ settings

    private fun applyGlobal() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(cfg["cookies"] != "2")
        val light = cfg["theme"] == "light"
        ctl.isAppearanceLightStatusBars = light
        ctl.isAppearanceLightNavigationBars = light
        val lp = window.attributes
        lp.preferredRefreshRate = if (cfg["hz"] == "1") 120f else 60f
        window.attributes = lp
        tabs.forEach { applyTab(it) }
    }

    private fun applyTab(t: Tab) {
        val s = t.wv.settings
        CookieManager.getInstance().setAcceptThirdPartyCookies(t.wv, cfg["cookies"] == "0")
        s.safeBrowsingEnabled = cfg["safe"] != "0"
        s.mixedContentMode = if (cfg["safe"] == "2") WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.textZoom = (cfg["zoom"] ?: "100").toIntOrNull() ?: 100
        s.userAgentString = if (desk) deskUa else baseUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = desk
        if (t.priv) s.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    private fun hdrs(): Map<String, String> =
        if (cfg["dnt"] == "1") mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    private fun cfgJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in cfg) o.put(k, v)
        return o
    }

    private fun day() = System.currentTimeMillis() / 86_400_000L

    private fun flushStats() {
        val n = pendBlocked.getAndSet(0)
        if (n == 0) return
        val d = day()
        stats.edit().putInt("d$d", stats.getInt("d$d", 0) + n).apply()
    }

    private fun weekBlocked(): Int {
        val d = day(); var s = 0
        for (i in 0..6) s += stats.getInt("d${d - i}", 0)
        return s + pendBlocked.get()
    }

    // ------------------------------------------------------------------ session

    private fun saveSession() {
        val arr = JSONArray()
        var idx = 0; var n = 0
        tabs.forEachIndexed { i, t ->
            if (!t.priv) {
                if (i == cur) idx = n
                arr.put(JSONObject().put("u", t.url).put("t", t.title)); n++
            }
        }
        sp.edit().putString("session", arr.toString()).putInt("sessionCur", idx).apply()
    }

    private fun restoreSession() {
        try {
            val arr = JSONArray(sp.getString("session", "[]"))
            if (arr.length() == 0) return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = addTab(false, false)
                val u = o.getString("u")
                if (u.isNotEmpty()) { t.lazy = u; t.url = u; t.title = o.optString("t") }
            }
            select(sp.getInt("sessionCur", 0).coerceIn(0, tabs.size - 1))
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ tabs

    private fun addTab(priv: Boolean, select: Boolean = true): Tab {
        val w = WebView(this)
        val t = Tab(w, priv)
        w.setBackgroundColor(Color.WHITE)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = false
        s.setGeolocationEnabled(true)
        w.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        applyTab(t)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, cfg["cookies"] == "0")

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val sch = u.scheme ?: return false
                if (sch == "glass") {
                    val orig = u.getQueryParameter("u")
                    if (orig != null) { Uri.parse(orig).host?.let { httpOk.add(it) }; v.loadUrl(orig) }
                    return true
                }
                if (sch == "http" || sch == "https") {
                    val h = u.host ?: ""
                    if (sch == "http" && r.isForMainFrame && cfg["https"] == "1" && !httpOk.contains(h) && !isLocal(h)) {
                        val up = u.buildUpon().scheme("https").build().toString()
                        t.upgraded = up
                        v.loadUrl(up, hdrs())
                        return true
                    }
                    return false
                }
                try {
                    val i = if (sch == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    startActivity(i)
                } catch (e: Exception) { }
                return true
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (!r.isForMainFrame && cfg["block"] == "1" && AdBlock.blocks(r.url.host, r.url.toString())) {
                    t.blocked.incrementAndGet()
                    pendBlocked.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                t.url = if (url == null || url == "about:blank") "" else url
                t.blocked.set(0)
                if (view != null) inject(view, url)
                sync(t)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view != null) inject(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null) inject(view, url)
                CookieManager.getInstance().flush()
                flushStats()
                sync(t)
                pushStats()
            }

            override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val failing = r.url.toString()
                if (t.upgraded.isNotEmpty() && failing == t.upgraded) {
                    val http = Uri.parse(failing).buildUpon().scheme("http").build().toString()
                    view.loadDataWithBaseURL(
                        failing,
                        errPage("This site doesn't support HTTPS", "HTTPS-only mode is on, so the browser did not load the unencrypted version.",
                            "Continue to HTTP (not secure)", "glass://http?u=" + Uri.encode(http)),
                        "text/html", "utf-8", failing
                    )
                    t.upgraded = ""
                } else {
                    view.loadDataWithBaseURL(
                        failing,
                        errPage("Can't open this page", e.description.toString(), "Try again", failing),
                        "text/html", "utf-8", failing
                    )
                }
            }

            override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: android.net.http.SslError) {
                h.cancel()
                view.loadDataWithBaseURL(
                    null,
                    errPage("Your connection isn't private", "The site's security certificate can't be trusted (${e.url}).", "Back to safety", "about:blank"),
                    "text/html", "utf-8", null
                )
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) { t.title = title ?: ""; sync(t) }

            override fun onProgressChanged(view: WebView?, p: Int) {
                if (tabs.isNotEmpty() && t === tabs[cur]) ui.post { ui.evaluateJavascript("pp($p)", null) }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (cfg["popups"] == "1" && !isUserGesture) return false
                val nt = addTab(t.priv)
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.wv
                resultMsg.sendToTarget()
                ui.evaluateJavascript("show('page')", null)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val kinds = request.resources.mapNotNull {
                        when (it) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "cam"
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "mic"
                            else -> null
                        }
                    }
                    if (kinds.isEmpty()) { request.deny(); return@runOnUiThread }
                    val granted = request.resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }.toTypedArray()
                    askSite(request.origin.toString(), kinds) { ok ->
                        if (!ok) request.deny()
                        else {
                            val need = ArrayList<String>()
                            if ("cam" in kinds) need.add(Manifest.permission.CAMERA)
                            if ("mic" in kinds) need.add(Manifest.permission.RECORD_AUDIO)
                            ensure(need) { g -> if (g) request.grant(granted) else request.deny() }
                        }
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                runOnUiThread {
                    askSite(origin, listOf("loc")) { ok ->
                        if (!ok) cb.invoke(origin, false, false)
                        else ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { g -> cb.invoke(origin, g, false) }
                    }
                }
            }

            override fun onShowFileChooser(wv: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { startActivityForResult(p.createIntent(), RC_FILE); true }
                catch (e: Exception) { fileCb = null; false }
            }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view; customCb = cb
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                ui.visibility = View.INVISIBLE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() { hideCustom() }
        }

        w.setOnScrollChangeListener { _, _, y, _, oy -> scrolled(y - oy) }
        w.setDownloadListener { u, ua, cd, mime, _ -> download(u, ua, cd, mime) }
        tabs.add(t)
        if (select) select(tabs.size - 1)
        return t
    }

    private fun isLocal(h: String) =
        h == "localhost" || h.endsWith(".local") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        ui.visibility = View.VISIBLE
        if (!hid) ctl.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun select(i: Int) {
        tabs.forEach { root.removeView(it.wv); it.wv.onPause() }
        cur = i
        val t = tabs[i]
        root.addView(t.wv, 0, FrameLayout.LayoutParams(-1, -1))
        t.wv.onResume()
        t.lazy?.let { t.lazy = null; t.wv.loadUrl(it, hdrs()) }
        if (t.priv) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        layout()
        sync(t)
    }

    private fun sync(t: Tab) {
        if (tabs.isEmpty() || t !== tabs[cur]) return
        ui.post { ui.evaluateJavascript("pi(${JSONObject.quote(t.url)},${JSONObject.quote(t.title)},${if (t.priv) 1 else 0})", null) }
    }

    private fun pushStats() {
        ui.post { ui.evaluateJavascript("trk(${weekBlocked()})", null) }
    }

    private fun thumb(t: Tab) {
        if (t.priv || t.url.isEmpty()) return
        try {
            val w = t.wv
            if (w.width <= 0 || w.height <= 0) return
            val sc = 240f / w.width
            val bmp = Bitmap.createBitmap(240, (w.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(sc, sc)
            w.draw(c)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 55, bo)
            t.thumb = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) { }
    }

    // ------------------------------------------------------------------ layout

    private fun layout() {
        if (tabs.isEmpty() || !::ui.isInitialized) return
        val top = cfg["addr"] == "top"
        val docked = cfg["toolbar"] == "docked"
        val strip = (stripDp * dm).toInt() + (if (top) topI else botI)
        val page = mode == "page"
        val wl = FrameLayout.LayoutParams(-1, -1)
        wl.topMargin = topI
        wl.bottomMargin = if (top) navI else 0
        if (page && docked) { if (top) wl.topMargin = strip else wl.bottomMargin = strip }
        val w = tabs[cur].wv
        w.layoutParams = wl
        w.visibility = if (mode == "ui") View.GONE else View.VISIBLE
        val u = FrameLayout.LayoutParams(-1, if (page) strip else -1)
        u.gravity = if (page && top) Gravity.TOP else Gravity.BOTTOM
        ui.layoutParams = u
    }

    private fun setMode(m: String, dp: Int) {
        if (m == "ui" && mode != "ui" && tabs.isNotEmpty()) thumb(tabs[cur])
        mode = m
        if (dp > 0) stripDp = dp
        if (m == "ui" && hid) { hid = false; ctl.show(WindowInsetsCompat.Type.systemBars()) }
        layout()
    }

    private fun scrolled(d: Int) {
        if (mode != "page" || cfg["toolbar"] != "autohide") return
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

    // ------------------------------------------------------------------ page injection

    private fun inject(v: WebView, url: String?) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
        val host = Uri.parse(url).host ?: ""
        val sb = StringBuilder()
        if (cfg["block"] == "1") {
            sb.append(AdBlock.adsJs())
            if (host.endsWith("youtube.com")) sb.append(AdBlock.YT_JS)
        }
        if (cfg["cbanner"] == "1") sb.append(AdBlock.cookieJs())
        if (cfg["dark"] == "1") {
            sb.append("if(!document.getElementById('__gb_dark')){var d=document.createElement('style');d.id='__gb_dark';d.textContent=")
                .append(JSONObject.quote(AdBlock.DARK_CSS)).append(";(document.head||document.documentElement).appendChild(d);}")
        }
        if (cfg["dnt"] == "1") {
            sb.append("try{Object.defineProperty(navigator,'doNotTrack',{get:function(){return '1'}});")
                .append("Object.defineProperty(navigator,'globalPrivacyControl',{get:function(){return true}})}catch(e){};")
        }
        try {
            val us = JSONArray(sp.getString("us", "[]"))
            for (i in 0 until us.length()) {
                val o = us.getJSONObject(i)
                if (!o.optBoolean("on", true)) continue
                val m = o.optString("m", "*")
                if (m == "*" || m.isEmpty() || host.contains(m)) {
                    sb.append("try{").append(o.optString("c")).append("\n}catch(e){};")
                }
            }
        } catch (e: Exception) { }
        if (sb.isNotEmpty()) v.evaluateJavascript("(function(){try{" + sb + "}catch(e){}})()", null)
    }

    private fun errPage(title: String, msg: String, btn: String, href: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val h = href.replace("\"", "&quot;")
        return """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:sans-serif;color:#fff;
background:radial-gradient(70% 50% at 20% 10%,#7c4dff88,transparent),radial-gradient(60% 50% at 90% 90%,#ff2d9588,transparent),#12082e">
<div style="max-width:340px;margin:24px;padding:28px;border-radius:28px;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.25);text-align:center">
<div style="font-size:44px">&#9888;&#65039;</div><h2 style="margin:8px 0">${esc(title)}</h2>
<p style="opacity:.8;line-height:1.4;font-size:14px">${esc(msg)}</p>
<a href="$h" style="display:inline-block;margin-top:10px;padding:12px 22px;border-radius:99px;background:#ff375f;color:#fff;text-decoration:none;font-weight:600">${esc(btn)}</a>
</div></body></html>"""
    }

    // ------------------------------------------------------------------ permissions / site settings

    private fun askSite(origin: String, kinds: List<String>, cb: (Boolean) -> Unit) {
        val host = Uri.parse(origin).host ?: origin
        val saved = kinds.map { site.getString("$host|$it", null) }
        if (saved.all { it == "1" }) { cb(true); return }
        if (saved.any { it == "0" }) { cb(false); return }
        val names = kinds.joinToString(" & ") { when (it) { "cam" -> "camera"; "mic" -> "microphone"; else -> "location" } }
        fun remember(v: String) { val e = site.edit(); kinds.forEach { e.putString("$host|$it", v) }; e.apply() }
        AlertDialog.Builder(this)
            .setTitle(host)
            .setMessage("wants to use your $names")
            .setPositiveButton("Allow") { _, _ -> remember("1"); cb(true) }
            .setNegativeButton("Block") { _, _ -> remember("0"); cb(false) }
            .setOnCancelListener { cb(false) }
            .show()
    }

    private fun ensure(perms: List<String>, cb: (Boolean) -> Unit) {
        val need = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { cb(true); return }
        permCb = cb
        requestPermissions(need.toTypedArray(), RC_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cb = permCb; permCb = null
        cb?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    // ------------------------------------------------------------------ downloads

    private fun download(u: String, ua: String, cd: String?, mime: String?) {
        val name = URLUtil.guessFileName(u, cd, mime)
        val cookie = CookieManager.getInstance().getCookie(u) ?: ""
        if (cfg["askSave"] == "1") {
            saveReq = Triple(u, ua, cookie)
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime ?: "*/*").putExtra(Intent.EXTRA_TITLE, name), RC_SAVE
                )
            } catch (e: Exception) { toast("Can't pick a save location") }
            return
        }
        try {
            val r = DownloadManager.Request(Uri.parse(u))
            if (mime != null) r.setMimeType(mime)
            r.addRequestHeader("User-Agent", ua)
            if (cookie.isNotEmpty()) r.addRequestHeader("Cookie", cookie)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            recordDownload(name)
            toast("Downloading $name")
        } catch (e: Exception) { toast("This download can't be started") }
    }

    private fun recordDownload(name: String) {
        try {
            val a = JSONArray(sp.getString("dl", "[]"))
            val n = JSONArray()
            n.put(JSONArray().put(name).put(System.currentTimeMillis()))
            for (i in 0 until minOf(a.length(), 59)) n.put(a.get(i))
            sp.edit().putString("dl", n.toString()).apply()
        } catch (e: Exception) { }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ------------------------------------------------------------------ activity results

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            RC_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
            RC_VOICE -> if (res == RESULT_OK) {
                val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (r != null) ui.evaluateJavascript("go(${JSONObject.quote(r)})", null)
            }
            RC_AUTH -> ui.evaluateJavascript("authDone(${if (res == RESULT_OK) 1 else 0})", null)
            RC_SAVE -> {
                val uri = data?.data; val rq = saveReq; saveReq = null
                if (res == RESULT_OK && uri != null && rq != null) thread {
                    try {
                        val c = URL(rq.first).openConnection() as HttpURLConnection
                        c.setRequestProperty("User-Agent", rq.second)
                        if (rq.third.isNotEmpty()) c.setRequestProperty("Cookie", rq.third)
                        c.inputStream.use { i -> contentResolver.openOutputStream(uri)?.use { o -> i.copyTo(o) } }
                        runOnUiThread { recordDownload(uri.lastPathSegment?.substringAfterLast('/') ?: "download") }
                        toast("Saved")
                    } catch (e: Exception) { toast("Download failed") }
                }
            }
            RC_BACKUP -> {
                val uri = data?.data
                if (res == RESULT_OK && uri != null) thread {
                    try { contentResolver.openOutputStream(uri)?.use { it.write(backupData.toByteArray()) }; toast("Backup saved") }
                    catch (e: Exception) { toast("Backup failed") }
                }
            }
            RC_RESTORE -> {
                val uri = data?.data
                if (res == RESULT_OK && uri != null) thread {
                    try {
                        val txt = contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                        val o = JSONObject(txt)
                        val st = o.optJSONObject("settings")
                        val ls = o.optJSONObject("ls") ?: JSONObject()
                        runOnUiThread {
                            if (st != null) {
                                val it = st.keys()
                                while (it.hasNext()) {
                                    val k = it.next()
                                    if (DEF.containsKey(k)) { cfg[k] = st.getString(k); sp.edit().putString(k, st.getString(k)).apply() }
                                }
                            }
                            sp.edit().putString("us", o.optString("us", "[]")).apply()
                            desk = cfg["desk"] == "1"
                            applyGlobal(); layout()
                            ui.evaluateJavascript("restoreLS(" + ls.toString() + ")", null)
                        }
                        toast("Backup restored")
                    } catch (e: Exception) { toast("Not a valid backup file") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ AI

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

    // ------------------------------------------------------------------ misc helpers

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun clearGoogleCookies() {
        val cm = CookieManager.getInstance()
        for (u in listOf("https://accounts.google.com", "https://www.google.com", "https://google.com", "https://www.youtube.com", "https://myaccount.google.com")) {
            val c = cm.getCookie(u) ?: continue
            val host = Uri.parse(u).host ?: continue
            c.split(";").forEach {
                val n = it.substringBefore("=").trim()
                cm.setCookie(u, "$n=; Max-Age=0; Path=/")
                cm.setCookie(u, "$n=; Max-Age=0; Path=/; Domain=.${host.removePrefix("www.")}")
            }
        }
        cm.flush()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { hideCustom(); return }
        val w = tabs[cur].wv
        if (mode != "ui" && w.canGoBack()) w.goBack()
        else ui.evaluateJavascript("backUi()") { if (it == "true") ui.evaluateJavascript("bye()", null) }
    }

    // ------------------------------------------------------------------ JS bridge

    inner class Bridge {
        // navigation
        @JavascriptInterface fun nav(u: String) { runOnUiThread { tabs[cur].wv.loadUrl(u, hdrs()) } }
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
                    tabs[0].url = ""; tabs[0].thumb = null
                }
            }
        }
        @JavascriptInterface fun tabsJson(): String = JSONArray(tabs.mapIndexed { i, t ->
            JSONObject().put("title", if (t.url.isEmpty()) "New Tab" else t.title.ifEmpty { t.url })
                .put("url", t.url).put("priv", t.priv).put("cur", i == cur).put("thumb", t.thumb ?: "")
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
        @JavascriptInterface fun reload() { runOnUiThread { tabs[cur].wv.reload() } }
        @JavascriptInterface fun desktop() {
            runOnUiThread {
                desk = !desk
                cfg["desk"] = if (desk) "1" else "0"
                sp.edit().putString("desk", cfg["desk"]).apply()
                tabs.forEach { applyTab(it) }
                tabs[cur].wv.reload()
            }
        }
        @JavascriptInterface fun isDesktop(): Boolean = desk
        @JavascriptInterface fun reader() {
            val dark = cfg["theme"] != "light"
            val bg = if (dark) "#121214" else "#fbfbfd"
            val fg = if (dark) "#e8e8ea" else "#111111"
            runOnUiThread {
                tabs[cur].wv.evaluateJavascript(
                    "(function(){var a=document.querySelector('article')||document.querySelector('main')||document.body;" +
                        "var c=a.cloneNode(true);c.querySelectorAll('script,style,iframe,nav,footer,aside,header,form,button,noscript').forEach(function(e){e.remove()});" +
                        "var t=document.title;document.head.innerHTML='<meta name=viewport content=\"width=device-width,initial-scale=1\">';" +
                        "document.body.removeAttribute('style');document.body.style.cssText='margin:0;background:" + bg + ";color:" + fg + "';" +
                        "document.body.innerHTML='<div style=\"max-width:680px;margin:auto;padding:24px 20px 80px;font:19px/1.65 Georgia,serif\"><h1 style=\"font:700 28px/1.2 sans-serif\">'+t+'</h1>'+c.innerHTML+'</div>';" +
                        "document.querySelectorAll('img').forEach(function(i){i.style.maxWidth='100%';i.style.height='auto'});" +
                        "document.querySelectorAll('a').forEach(function(i){i.style.color='#0a84ff'})})()", null
                )
            }
        }
        @JavascriptInterface fun find() {
            runOnUiThread {
                val et = EditText(this@MainActivity)
                et.inputType = InputType.TYPE_CLASS_TEXT
                et.hint = "Find on page"
                val w = tabs[cur].wv
                val d = AlertDialog.Builder(this@MainActivity).setTitle("Find on page").setView(et)
                    .setPositiveButton("Find", null).setNeutralButton("Next", null)
                    .setNegativeButton("Close") { _, _ -> w.clearMatches() }.create()
                d.show()
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { w.findAllAsync(et.text.toString()) }
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { w.findNext(true) }
            }
        }
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
        @JavascriptInterface fun copyLink() {
            runOnUiThread {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", tabs[cur].url))
                toast("Link copied")
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
        @JavascriptInterface fun voice() {
            runOnUiThread {
                try {
                    startActivityForResult(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), RC_VOICE
                    )
                } catch (e: Exception) { toast("Voice search isn't available on this device") }
            }
        }
        @JavascriptInterface fun auth() {
            runOnUiThread {
                val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                val i = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("Private tabs", "Verify it's you") else null
                if (i == null) { toast("Set a screen lock to protect private tabs"); ui.evaluateJavascript("authDone(1)", null) }
                else startActivityForResult(i, RC_AUTH)
            }
        }

        // lifecycle
        @JavascriptInterface fun ready() {
            runOnUiThread {
                uiReady = true
                pendingUrl?.let { pendingUrl = null; openExternal(it) }
                pushStats()
            }
        }
        @JavascriptInterface fun quit() { runOnUiThread { saveSession(); finish() } }

        // settings
        @JavascriptInterface fun settings(): String = cfgJson().toString()
        @JavascriptInterface fun set(k: String, v: String) {
            if (!DEF.containsKey(k)) return
            cfg[k] = v
            sp.edit().putString(k, v).apply()
            runOnUiThread {
                if (k == "desk") { desk = v == "1"; tabs.forEach { applyTab(it) }; if (tabs[cur].url.isNotEmpty()) tabs[cur].wv.reload() }
                applyGlobal(); layout()
            }
        }
        @JavascriptInterface fun resetAll() {
            runOnUiThread {
                sp.edit().clear().apply()
                DEF.forEach { (k, v) -> cfg[k] = v }
                desk = false
                applyGlobal(); layout()
                ui.reload()
            }
        }
        @JavascriptInterface fun version(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (e: Exception) { "1.0" }

        // ad blocker / privacy
        @JavascriptInterface fun stats(): Int = weekBlocked()
        @JavascriptInterface fun pageBlocked(): Int = tabs[cur].blocked.get()
        @JavascriptInterface fun filterInfo(): String = AdBlock.info
        @JavascriptInterface fun updateFilters() {
            thread {
                val m = AdBlock.update(this@MainActivity)
                ui.post { ui.evaluateJavascript("filtersDone(${JSONObject.quote(m)})", null) }
            }
        }
        @JavascriptInterface fun sites(): String {
            val a = JSONArray()
            site.all.forEach { (k, v) -> a.put(JSONArray().put(k).put(v.toString())) }
            return a.toString()
        }
        @JavascriptInterface fun siteRemove(k: String) { site.edit().remove(k).apply() }
        @JavascriptInterface fun siteClear() { site.edit().clear().apply() }
        @JavascriptInterface fun clearData(flags: String) {
            runOnUiThread {
                if ("cookies" in flags) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
                if ("cache" in flags) { tabs.forEach { it.wv.clearCache(true) }; cacheDir.deleteRecursively() }
                if ("history" in flags) { tabs.forEach { it.wv.clearHistory(); it.wv.clearFormData() } }
                if ("perms" in flags) site.edit().clear().apply()
                if ("downloads" in flags) sp.edit().remove("dl").apply()
                toast("Browsing data cleared")
            }
        }
        @JavascriptInterface fun cacheSize(): String {
            val b = dirSize(cacheDir) + dirSize(File(applicationInfo.dataDir, "app_webview"))
            return String.format("%.1f MB", b / 1048576.0)
        }
        @JavascriptInterface fun clearCache() {
            runOnUiThread { tabs.forEach { it.wv.clearCache(true) }; cacheDir.deleteRecursively(); toast("Cache cleared") }
        }

        // Google account
        @JavascriptInterface fun googleSignedIn(): Boolean {
            val c = CookieManager.getInstance().getCookie("https://accounts.google.com") ?: return false
            return c.contains("SAPISID") || c.contains("SID=") || c.contains("LSID")
        }
        @JavascriptInterface fun googleSignOut() { runOnUiThread { clearGoogleCookies(); toast("Signed out of Google") } }
        @JavascriptInterface fun autofill() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:"))) }
                catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { } }
            }
        }

        // general
        @JavascriptInterface fun defaultState(): Boolean = try {
            val ri = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName == packageName
        } catch (e: Exception) { false }
        @JavascriptInterface fun defaultBrowser() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val rm = getSystemService(RoleManager::class.java)
                        if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                            if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) { toast("Glass Browser is already your default browser"); return@runOnUiThread }
                            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), RC_ROLE)
                            return@runOnUiThread
                        }
                    }
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { } }
            }
        }
        @JavascriptInterface fun languages() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= 33) startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS).setData(Uri.parse("package:$packageName")))
                    else startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
                } catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) } catch (_: Exception) { } }
            }
        }
        @JavascriptInterface fun notifSettings() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                catch (e: Exception) { }
            }
        }
        @JavascriptInterface fun a11y() { runOnUiThread { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } } }

        // downloads / userscripts / backup
        @JavascriptInterface fun downloads(): String = sp.getString("dl", "[]") ?: "[]"
        @JavascriptInterface fun openDownloads() {
            runOnUiThread { try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
        }
        @JavascriptInterface fun usGet(): String = sp.getString("us", "[]") ?: "[]"
        @JavascriptInterface fun usSet(j: String) { sp.edit().putString("us", j).apply() }
        @JavascriptInterface fun backup() {
            runOnUiThread {
                ui.evaluateJavascript("JSON.stringify({b:localStorage.b||'[]',h:localStorage.h||'[]',f:localStorage.f||'[]',rl:localStorage.rl||'[]'})") { r ->
                    val ls = try { JSONArray("[$r]").getString(0) } catch (e: Exception) { "{}" }
                    val o = JSONObject()
                    o.put("ls", JSONObject(ls))
                    o.put("settings", cfgJson())
                    o.put("us", sp.getString("us", "[]"))
                    backupData = o.toString()
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "glassbrowser-backup.json"), RC_BACKUP
                        )
                    } catch (e: Exception) { toast("Can't pick a save location") }
                }
            }
        }
        @JavascriptInterface fun restore() {
            runOnUiThread {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), RC_RESTORE)
                } catch (e: Exception) { toast("Can't open the file picker") }
            }
        }
    }
}
