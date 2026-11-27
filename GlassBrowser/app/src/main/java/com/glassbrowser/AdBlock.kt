package com.glassbrowser

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network-level ad / tracker blocker.
 *  - host list (built-in assets/blocklist.txt + downloaded Peter Lowe list), subdomains matched automatically
 *  - URL pattern rules for first-party ad paths
 *  - cosmetic CSS / JS injected into pages (hides ad containers, YouTube ad skipper, cookie banners)
 */
object AdBlock {
    @Volatile private var hosts: HashSet<String> = HashSet()
    @Volatile var info: String = "Loading…"
        private set

    private const val LIST_URL =
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"

    private val pathRx = Regex(
        "(/pagead/|/adsbygoogle|/gpt\\.js|/pubads|/prebid|/adserver/|/ads/js/|[?&]ad_type=|/doubleclick/|" +
            "/gtm\\.js|/gtag/js|/fbevents\\.js|/ga\\.js|/analytics\\.js|/hotjar-|/mixpanel\\.|/amplitude\\.|" +
            "/adframe|/advert[s]?/|/adview|/banner_ads/|/sponsored_ad)",
        RegexOption.IGNORE_CASE
    )

    private fun extra(ctx: Context) = File(ctx.filesDir, "filters_extra.txt")

    fun load(ctx: Context) {
        val set = HashSet<String>(8192)
        try { ctx.assets.open("blocklist.txt").bufferedReader().useLines { it.forEach { l -> add(set, l) } } } catch (_: Exception) {}
        val f = extra(ctx)
        if (f.exists()) try { f.bufferedReader().useLines { it.forEach { l -> add(set, l) } } } catch (_: Exception) {}
        hosts = set
        refreshInfo(ctx)
    }

    private fun add(set: HashSet<String>, raw: String) {
        var l = raw.trim()
        if (l.isEmpty() || l.startsWith("#")) return
        val hash = l.indexOf('#'); if (hash > 0) l = l.substring(0, hash).trim()
        val parts = l.split(Regex("\\s+"))
        val d = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]).lowercase()
        if (d == "localhost" || d == "local" || d.isEmpty() || !d.contains('.')) return
        set.add(d)
    }

    private fun refreshInfo(ctx: Context) {
        val f = extra(ctx)
        val age = if (f.exists()) {
            val d = (System.currentTimeMillis() - f.lastModified()) / 86_400_000L
            if (d <= 0) "updated today" else "updated ${d}d ago"
        } else "built-in list"
        info = "${hosts.size} domains · $age"
    }

    /** Downloads the latest list. Returns a human message. Call off the UI thread. */
    fun update(ctx: Context): String {
        return try {
            val c = URL(LIST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val text = c.inputStream.bufferedReader().readText()
            if (text.length < 2000) return "Update failed: list looked empty"
            extra(ctx).writeText(text)
            load(ctx)
            "Filters updated · $info"
        } catch (e: Exception) {
            "Update failed: check your connection"
        }
    }

    fun maybeUpdate(ctx: Context) {
        val f = extra(ctx)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) update(ctx)
    }

    fun blocks(host: String?, url: String): Boolean {
        if (host == null) return false
        val s = hosts
        var h = host.lowercase()
        while (true) {
            if (s.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
            if (!h.contains('.')) break
        }
        return pathRx.containsMatchIn(url)
    }

    // ---------- cosmetic filtering ----------

    private const val ADS_CSS = """
.adsbygoogle,ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[id^="gpt-ad"],[id*="google_ads_iframe"],
iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="adservice"],iframe[id^="google_ads"],
iframe[src*="amazon-adsystem"],iframe[src*="adnxs"],iframe[src*="criteo"],iframe[src*="taboola"],
.ad-banner,.ad-container,.ad-wrapper,.ad-slot,.ad-unit,.ad-box,.adbox,.advert,.advertisement,.advertising,
.ads-container,.ads-wrapper,.ad_container,.ad_wrapper,.ad_unit,.ad_slot,.banner-ad,.banner_ad,.bannerad,
.sponsored-ad,.sponsored-post,.sponsored-content,.native-ad,.nativead,.sticky-ad,.stickyad,.top-ad,.bottom-ad,
[class*="ad-slot"],[class*="adslot"],[class*="ad-placeholder"],[class*="advert-"],[class*="AdContainer"],
[data-ad-slot],[data-ad-unit],[data-ad-client],[data-google-query-id],[data-testid="ad"],[data-ad],
[aria-label="Advertisement" i],[aria-label="Ads" i],[aria-label="Sponsored" i],
#taboola-below-article-thumbnails,[id^="taboola-"],.trc_related_container,.OUTBRAIN,[data-widget-id^="AR_"],
.outbrain-widget,#ad,#ads,#adbox,#ad-banner,#ad-container,#top-ad,#bottom-ad,#sidebar-ad,#header-ad,#footer-ad,
.mediavine-ad,.mv-ad-box,[id^="mediavine"],.ez-ad,[id^="ezoic"],[class*="ezoic"],.adthrive-ad,[class*="adthrive"],
.revcontent,#rc-widget,.mgid-widget,[id^="mgid"],.google-auto-placed,.gpt-ad,.dfp-ad,.ad-leaderboard,.ad-rectangle,
amp-ad,amp-embed[type="taboola"],amp-sticky-ad,.amp-ad,.pub_300x250,.pub_728x90,.textAd,.text-ad,.text_ad,
.promoted-tweet,.promotedlink,.promoted-link,.ytd-ad-slot-renderer,ytd-ad-slot-renderer,ytd-display-ad-renderer,
ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytm-promoted-sparkles-web-renderer,
ytm-promoted-video-renderer,.ytp-ad-overlay-container,.ytp-ad-module,#masthead-ad,#player-ads,.ytd-merch-shelf-renderer
{display:none !important;visibility:hidden !important;height:0 !important;min-height:0 !important;margin:0 !important;padding:0 !important}
"""

    private const val COOKIE_CSS = """
#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,
.cc-window,.cc-banner,.cookie-banner,.cookie-notice,.cookie-consent,.cookie-bar,.cookies-banner,#cookie-banner,#cookie-notice,
#cookieConsent,#cookie-consent,#cookie-law-info-bar,.cli-modal-dialog,.fc-consent-root,.qc-cmp2-container,#qc-cmp2-container,
.truste_overlay,.truste_box_overlay,#truste-consent-track,#didomi-host,#didomi-popup,.didomi-popup-backdrop,
.sp_message_container,[id^="sp_message_container"],.osano-cm-window,.osano-cm-dialog,#usercentrics-root,
.klaro,#gdpr-cookie-notice,.gdpr-banner,#gdpr-consent,.consent-banner,[class*="cookie-banner"],[id*="cookie-banner"],
[class*="CookieBanner"],[id*="CookieBanner"],[aria-label="cookieconsent"],.evidon-banner,#hs-eu-cookie-confirmation
{display:none !important;visibility:hidden !important}
html,body{overflow:auto !important}
"""

    private fun cssJs(id: String, css: String) =
        "if(!document.getElementById('$id')){var s=document.createElement('style');s.id='$id';" +
            "s.textContent=" + JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);}"

    fun adsJs() = cssJs("__gb_ads", ADS_CSS)
    fun cookieJs() = cssJs("__gb_cookie", COOKIE_CSS)

    /** Best-effort YouTube ad skipper (network blocking can't separate YouTube ads from video). */
    const val YT_JS = """
if(!window.__gbYt){window.__gbYt=1;var __m=false;setInterval(function(){try{
var p=document.querySelector('.html5-video-player');
var ad=p&&p.classList.contains('ad-showing');
var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button');
if(b)b.click();
var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
if(ad&&v&&isFinite(v.duration)&&v.duration>0){v.muted=true;__m=true;v.currentTime=v.duration;}
else if(!ad&&__m&&v){v.muted=false;__m=false;}
var c=document.querySelector('.ytp-ad-overlay-close-button');if(c)c.click();
}catch(e){}},350);}
"""

    const val DARK_CSS =
        "html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important}" +
            "img,video,picture,canvas,svg image,iframe,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg) !important}"
}
