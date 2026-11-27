# Glass Browser
Liquid-glass Android browser (native Chromium WebView + HTML glass UI in `app/src/main/assets/ui.html`).

## Build
1. Upload this folder's contents to a new GitHub repo (branch `main`).
2. Actions → **Build APK** → wait for green.
3. Download artifact **GlassBrowser-debug-apk**, install the APK.

## Real features
- Ad/tracker blocker: network-level (built-in + auto-updated Peter Lowe list), cosmetic hiding, YouTube ad skipper, cookie-banner hiding
- HTTPS-only mode, Safe Browsing, cookie controls, DNT/GPC, pop-up blocking, private tabs (screen-lock gated, screenshots blocked)
- Site permissions (camera/mic/location), file upload, fullscreen video, downloads (ask-where option)
- Tabs with thumbnails + session restore; Grid/List/Stack; toolbar floating/docked/auto-hide; address bar top/bottom
- Userscripts, backup/restore, default-browser prompt, voice search
- Adaptive glass-orb launcher icon, jelly splash + open/close animations
