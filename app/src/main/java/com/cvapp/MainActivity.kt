package com.cvapp

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Single status screen — used for initial load, the local⇄remote switch
    // animation, and masking connection trouble. See activity_main.xml.
    private lateinit var loadingView: View
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingTitle: TextView
    private lateinit var loadingSubtitle: TextView
    private lateinit var loadingStatus: TextView
    private lateinit var loadingEndpoint: TextView
    private lateinit var loadingBrand: TextView
    private lateinit var loadingVersion: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingRetryHint: TextView
    private lateinit var switchLocalColumn: LinearLayout
    private lateinit var switchRemoteColumn: LinearLayout

    private val prefs by lazy { getSharedPreferences("cvapp_prefs", Context.MODE_PRIVATE) }
    private var breathingAnimator: ObjectAnimator? = null
    @Volatile private var loadingVisible = false

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var networkMonitor: NetworkMonitor
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resolveJob: Job? = null

    private var currentTarget: HaTarget? = null
    private var consecutiveFailures = 0
    private var retryBackoffMs = INITIAL_BACKOFF_MS

    // True right after a fresh navigation to a target's root — the next
    // real HA app URL we see gets checked against the last remembered path.
    private var restoreArmed = false

    // Path from a link we were opened with, waiting for an HA page to land on.
    // Takes priority over KEY_LAST_PATH: an explicit link beats "where you were".
    private var pendingDeepLinkPath: String? = null

    companion object {
        private const val ERROR_SSL      = -999
        private const val ERROR_RENDERER = -998

        private const val RECHECK_INTERVAL_MS   = 20_000L
        private const val SWITCH_REVEAL_HOLD_MS = 900L
        private const val SILENT_RETRY_ATTEMPTS = 2       // blips retried quietly before we surface a retry hint
        private const val SILENT_RETRY_DELAY_MS = 1_200L
        private const val INITIAL_BACKOFF_MS    = 3_000L
        private const val MAX_BACKOFF_MS        = 15_000L

        private const val KEY_LAST_PATH          = "last_ha_path"
        private const val KEY_LAST_UPDATE_CHECK  = "last_update_check"
        private const val KEY_LINK_HINT_DISMISSED = "link_hint_dismissed"

        // An update we have already been told about. Kept on disk so it survives
        // the process, because the moment it is most needed is after the user has
        // been sent out to Settings and comes back. See offerPendingUpdate().
        private const val KEY_PENDING_VERSION = "pending_update_version"
        private const val KEY_PENDING_NOTES   = "pending_update_notes"
        private const val KEY_PENDING_URL     = "pending_update_url"
        private const val KEY_PENDING_NAME    = "pending_update_name"
        private const val KEY_PENDING_SIZE    = "pending_update_size"

        /** Let the loading animation play before a dialog lands on top of it. */
        private const val LINK_HINT_DELAY_MS = 1_400L

        /**
         * cvha://app-settings — opens the app's own settings.
         *
         * The loading screen is the normal way in (long-press it), but it is only
         * on screen while the app is connecting, and in home-screen mode the app
         * can stay resident for days without ever showing it again. This link
         * always works: from a dashboard button, a home-screen shortcut, or adb.
         */
        private const val LINK_APP_SETTINGS = "app-settings"
    }

    // Entry point for remote — OTP portal that redirects to this build's HA host.
    // Local instance is hit directly since it's already on the trusted LAN.
    // Both come from build.gradle so one APK stays bound to exactly one house.
    private enum class HaTarget(val url: String) {
        LOCAL(BuildConfig.LOCAL_URL),
        REMOTE(BuildConfig.LOGIN_URL)
    }

    private val recheckRunnable = object : Runnable {
        override fun run() {
            checkAvailabilityAndActOn()
            mainHandler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    private val errorRetryRunnable = Runnable { checkAvailabilityAndActOn(forceReload = true) }

    // ── CV Panel bridge ───────────────────────────────────────────────────────
    // Exposed as window.CVAppBridge to all frames/iframes, on every origin this
    // WebView loads (local or remote) — it's attached to the WebView instance,
    // not scoped per-page. Stores the CV Panel Long-Lived Access Token across
    // origins and app restarts.
    inner class CVAppBridge {
        @JavascriptInterface fun getToken(): String = prefs.getString("cv_panel_token", "") ?: ""
        @JavascriptInterface fun setToken(t: String) = prefs.edit().putString("cv_panel_token", t).apply()
        @JavascriptInterface fun clearToken() = prefs.edit().remove("cv_panel_token").apply()

        /** Lets the panel show its own "check for updates" control. */
        @JavascriptInterface fun getAppVersion(): String = BuildConfig.VERSION_NAME

        // JS interface methods arrive on a WebView background thread; everything
        // downstream of here touches dialogs, so hop to main first.
        @JavascriptInterface fun checkForUpdate() = mainHandler.post { checkForUpdate(force = true) }

        /** Home-screen mode, link handling and the update check, from the panel. */
        @JavascriptInterface fun openAppSettings() = mainHandler.post { showAppSettings() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge is mandatory from targetSdk 35, which means the window is
        // no longer resized when the keyboard opens — the IME arrives as an inset
        // and padding for it is this listener's job. Reading only systemBars()
        // here left the WebView at full height with the keyboard drawn over it,
        // hiding whatever field the user had just tapped.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime  = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Whichever is taller, not their sum: the keyboard is drawn over the
            // navigation bar, so adding them leaves a navbar-sized dead strip.
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        webView            = findViewById(R.id.webView)
        loadingView        = findViewById(R.id.loadingView)
        loadingLogo        = findViewById(R.id.loadingLogo)
        loadingTitle       = findViewById(R.id.loadingTitle)
        loadingSubtitle    = findViewById(R.id.loadingSubtitle)
        loadingStatus      = findViewById(R.id.loadingStatus)
        loadingEndpoint    = findViewById(R.id.loadingEndpoint)
        loadingBrand       = findViewById(R.id.loadingBrand)
        loadingVersion     = findViewById(R.id.loadingVersion)
        loadingProgress    = findViewById(R.id.loadingProgress)
        loadingRetryHint   = findViewById(R.id.loadingRetryHint)
        switchLocalColumn  = findViewById(R.id.switchLocalColumn)
        switchRemoteColumn = findViewById(R.id.switchRemoteColumn)

        loadingVersion.text = "v${runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("1.0")}"

        // The version label is the manual "check now" control. It only exists while
        // the loading screen is up, which is fine — the automatic check on start
        // covers the normal case, and the panel can call CVAppBridge.checkForUpdate().
        loadingVersion.setOnClickListener { checkForUpdate(force = true) }

        // Long-press the loading screen for the app's own settings. The app has no
        // chrome of its own by design — every pixel after the connection lands
        // belongs to Home Assistant — so this screen is where its controls live.
        // Registered on the version label too: it is clickable, so it would
        // otherwise swallow the press over its own corner.
        val openSettings = View.OnLongClickListener { showAppSettings(); true }
        loadingView.setOnLongClickListener(openSettings)
        loadingVersion.setOnLongClickListener(openSettings)

        networkMonitor = NetworkMonitor(this) { onNetworkSignal() }

        setupWebView()
        loadingRetryHint.setOnClickListener {
            consecutiveFailures = 0
            retryBackoffMs = INITIAL_BACKOFF_MS
            mainHandler.removeCallbacks(errorRetryRunnable)
            hideRetryHint()
            checkAvailabilityAndActOn(forceReload = true)
        }
        animateLoadingIn()

        val launchedForSettings = isAppSettingsLink(intent?.data)
        if (!launchedForSettings) pendingDeepLinkPath = deepLinkPathOrWarn(intent?.data)
        resolveInitialTarget()

        // These wait out the loading animation rather than landing on top of it,
        // and only one of them can be what this launch is about. An update we
        // already know of outranks the link hint: the hint can wait for the next
        // launch, an update the user has already been offered cannot keep vanishing.
        mainHandler.postDelayed({
            when {
                launchedForSettings   -> showAppSettings()
                offerPendingUpdate()  -> Unit
                else                  -> maybeShowLinkHint()
            }
        }, LINK_HINT_DELAY_MS)
    }

    // singleTask: a link tapped while we're already running arrives here rather
    // than through a fresh onCreate, so the live WebView gets to handle it.
    // In home-screen mode the Home button lands here too — action MAIN with no
    // data, which every branch below correctly treats as "nothing was asked for".
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAppSettingsLink(intent.data)) { showAppSettings(); return }
        deepLinkPathOrWarn(intent.data)?.let { applyDeepLink(it) }
    }

    override fun onResume() {
        super.onResume()
        // Home-app and link approvals are changed in Settings, i.e. in another
        // activity, with our dialog still open behind it. Re-read on the way back
        // so it never shows a state that stopped being true.
        refreshSettings?.invoke()
        resumeUpdateAfterPermission()
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
        mainHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
        checkForUpdate(force = false)
    }

    override fun onStop() {
        super.onStop()
        networkMonitor.stop()
        mainHandler.removeCallbacks(recheckRunnable)
        retireDuplicateOfHomeTask()
    }

    /**
     * Android runs the home app in a task of its own, which an already-running
     * ordinary task cannot be merged into. So switching home mode on while the
     * app is open and then pressing Home leaves the old instance behind — two
     * live activities, two WebViews, two Home Assistant sessions. Measured on
     * device, not a worry: singleTask cannot prevent it, because the two tasks
     * are different kinds of task.
     *
     * Nothing else duplicates. The launcher icon and cvha:// links both reach the
     * home-task instance through onNewIntent once it exists. So this one leftover
     * retires as soon as it is off screen, and the survivor picks the user's page
     * back up from KEY_LAST_PATH.
     */
    private fun retireDuplicateOfHomeTask() {
        if (isFinishing || isChangingConfigurations) return
        if (LauncherMode.isHomeInstance(this)) return
        if (LauncherMode.isCurrentHome(this)) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        // A dialog still attached to a dying Activity is a leaked window.
        updateDialog?.takeIf { it.isShowing }?.dismiss()
        updateDialog = null
        linkHintDialog?.takeIf { it.isShowing }?.dismiss()
        linkHintDialog = null
        settingsDialog?.takeIf { it.isShowing }?.dismiss()
        settingsDialog = null
        refreshSettings = null
        activityScope.cancel()
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Transparent background: when Chromium creates a new compositing layer for
        // a popup animation, the unpainted layer shows the parent's dark #111111
        // surface instead of pure black — eliminates the single-frame black flash.
        // LAYER_TYPE_HARDWARE keeps the WebView as a persistent GPU texture so the
        // surface is never discarded between loading transitions.
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true   // persists HA auth tokens (hassTokens) to disk
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            allowFileAccess                  = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(CVAppBridge(), "CVAppBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) = r.grant(r.resources)

            // target="_blank" links → open in system browser
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: Message
            ): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String, fav: android.graphics.Bitmap?) {
                        if (url != "about:blank") { openExternal(url); tmp.stopLoading() }
                    }
                }
                (msg.obj as WebView.WebViewTransport).webView = tmp
                msg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url    = req.url.toString()
                val scheme = req.url.scheme ?: ""
                // Our own scheme can show up inside HA itself (notification actions,
                // dashboard buttons). Handle it here — openExternal would bounce it
                // through the system and land right back in this activity.
                if (scheme.equals(BuildConfig.LINK_SCHEME, ignoreCase = true)) {
                    if (isAppSettingsLink(req.url)) showAppSettings()
                    else deepLinkPathOrWarn(req.url)?.let { applyDeepLink(it) }
                    return true
                }
                if (scheme !in listOf("http", "https")) { openExternal(url); return true }
                // Keep the service (any instance subdomain plus the login portal)
                // and the local instance inside the WebView; everything else is
                // somebody else's site and goes to the browser.
                val host = hostOf(url)
                val allowed = isServiceHost(host) || host == BuildConfig.LOCAL_HOST
                return if (!allowed) { openExternal(url); true } else false
            }

            override fun onPageStarted(view: WebView, url: String, fav: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, fav)
                updateStatus("Loading...")
                if (matchesTarget(url, HaTarget.LOCAL)) injectLocalAuthToken(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                consecutiveFailures = 0
                retryBackoffMs = INITIAL_BACKOFF_MS
                hideRetryHint()

                // Pre-warm GPU compositing layers for HA popup elements.
                // Without this, Chromium allocates a new layer when a popup opens
                // for the first time — the unpainted layer shows as one black frame.
                // translateZ(0) promotes these elements immediately so the layer
                // is ready before any animation starts.
                // Applies to whichever instance we're on. This was previously
                // gated on the remote host alone, but the black-frame problem is
                // a Home Assistant frontend behaviour, not a hosting one — the
                // local instance serves the same UI and needs it just as much.
                if (isHaAppHost(url)) {
                    view.evaluateJavascript("""
                        (function(){
                            if(document.getElementById('_cvapp_lf'))return;
                            var s=document.createElement('style');
                            s.id='_cvapp_lf';
                            s.textContent=
                                'ha-more-info-dialog,mwc-dialog,ha-dialog,' +
                                '.mdc-dialog__container,.mdc-dialog__surface,' +
                                '.bubble-pop-up,.bubble-header-container{' +
                                '-webkit-transform:translateZ(0);transform:translateZ(0);' +
                                '-webkit-backface-visibility:hidden;backface-visibility:hidden}';
                            (document.head||document.documentElement).appendChild(s);
                        })();
                    """.trimIndent(), null)
                }

                view.postDelayed({ hideLoading() }, 200)
            }

            // Fires on both full navigations and SPA route changes (pushState,
            // location.hash) — this is how we track where in HA the user actually is.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!isHaAppHost(url)) return   // ignore the login.coyotes.vision OTP portal

                val deepLink = pendingDeepLinkPath
                if (deepLink != null) {
                    // A link brought us here — it outranks wherever the user left off.
                    pendingDeepLinkPath = null
                    restoreArmed = false
                    if (pathOf(url) != deepLink) {
                        view.loadUrl(originOf(url) + deepLink)
                        return   // the resulting navigation persists the path
                    }
                } else if (restoreArmed) {
                    restoreArmed = false
                    val lastPath = prefs.getString(KEY_LAST_PATH, null)
                    val currentPath = pathOf(url)
                    if (!lastPath.isNullOrBlank() && lastPath != currentPath) {
                        view.loadUrl(originOf(url) + lastPath)
                        return   // the reload below will persist the path once it lands
                    }
                }
                prefs.edit().putString(KEY_LAST_PATH, pathOf(url)).apply()
            }

            // Technical WebView errors never reach the user as-is — they're
            // absorbed into the loading screen's status text, with retries
            // continuing silently in the background (see enterTrouble()).
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) enterTrouble(err.errorCode)
            }

            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, err: SslError) {
                handler.cancel()
                enterTrouble(ERROR_SSL)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.webViewRenderProcessClient = object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(v: WebView, r: WebViewRenderProcess?) =
                    enterTrouble(ERROR_RENDERER)
                override fun onRenderProcessResponsive(v: WebView, r: WebViewRenderProcess?) {}
            }
        }

        webView.setDownloadListener { url, _, _, _, _ -> runCatching { openExternal(url) } }
    }

    private fun matchesTarget(url: String, target: HaTarget): Boolean = when (target) {
        HaTarget.LOCAL  -> hostOf(url) == BuildConfig.LOCAL_HOST
        HaTarget.REMOTE -> isServiceHost(hostOf(url))
    }

    // ── Deep links ────────────────────────────────────────────────────────────
    // A link only ever carries a *path*, never a host. Which instance is live is
    // this app's call (see resolveTarget) and the sender cannot know whether we're
    // on the LAN right now — CVBot builds its links from hass.config.external_url,
    // so they name the remote host even while you're standing in the kitchen.
    // Dropping the host is exactly what makes one link work on both sides.
    //
    // Shapes we accept:
    //   cvha://cv-panel#trips                    — our own scheme, always ours
    //   cvha://open?p=/cv-panel%23trips          — path in a query, fragment intact
    //   https://<slug>.coyotes.vision/cv-panel#trips — what CVBot already sends
    //   http://ha.local:8123/cv-panel#trips      — the same link seen from the LAN
    private fun pathFromDeepLink(uri: Uri?): String? {
        if (uri == null) return null

        // ?p= wins when present: a fragment is dropped by any server-side redirect
        // on the way to us, a query parameter survives one.
        runCatching { uri.getQueryParameter("p") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return sanitizePath(it) }

        val fragment = uri.fragment?.let { "#$it" } ?: ""
        val raw = when (uri.scheme?.lowercase()) {
            BuildConfig.LINK_SCHEME -> {
                // "cvha://cv-panel#trips" parses cv-panel as the authority, not the path
                val head = uri.authority.orEmpty()
                if (head.isBlank()) uri.path.orEmpty() + fragment
                else "/$head${uri.path.orEmpty()}$fragment"
            }
            "http", "https" -> uri.path.orEmpty() + fragment
            else -> return null
        }
        return sanitizePath(raw)
    }

    /**
     * cvha://app-settings — a command, not a destination.
     *
     * Checked before any path parsing, or it would be read as a request to open
     * "/app-settings" on the instance, which is not a page that exists. Only our
     * own scheme qualifies: an https link is somebody else's to send, and no
     * website gets to open this app's settings.
     */
    private fun isAppSettingsLink(uri: Uri?): Boolean =
        uri != null &&
            uri.scheme.equals(BuildConfig.LINK_SCHEME, ignoreCase = true) &&
            uri.authority.equals(LINK_APP_SETTINGS, ignoreCase = true)

    /**
     * Anything that isn't an unambiguous same-origin path is dropped. "//evil.tld/x"
     * is a protocol-relative URL, not a path — appending it to our origin would send
     * an already-authenticated WebView somewhere else entirely.
     */
    private fun sanitizePath(candidate: String): String? {
        val path = candidate.trim()
        if (!path.startsWith("/") || path.startsWith("//")) return null
        if (path.contains('\\')) return null
        return path
    }

    /**
     * Turns an incoming link into a path, and says something when it can't.
     * A silent no-op is indistinguishable from the app ignoring the tap, so a
     * refused link gets named out loud — but a link that merely points at the
     * bare origin is not an error, it just means "open the app", and the normal
     * restore-where-you-were path handles that.
     */
    private fun deepLinkPathOrWarn(uri: Uri?): String? {
        if (uri == null) return null      // ordinary launch, nothing was asked for
        val path = pathFromDeepLink(uri)
        if (path == null) {
            Toast.makeText(this, R.string.deep_link_rejected, Toast.LENGTH_LONG).show()
            return null
        }
        return path.takeIf { it != "/" }
    }

    private fun applyDeepLink(path: String) {
        val current = webView.url
        if (current != null && isHaAppHost(current)) {
            val target = originOf(current) + path
            if (current == target) {
                // Same URL: no navigation, so no hashchange, so the CV Panel router
                // never re-runs and a repeat tap looks like the app ignored the link.
                webView.evaluateJavascript("window.dispatchEvent(new Event('hashchange'));", null)
            } else {
                // Only a different document reloads the HA frontend and needs the
                // loading screen — a fragment-only change is instant, and would
                // leave the screen up forever since onPageFinished never fires.
                if (current.substringBefore('#') != target.substringBefore('#')) showLoading()
                webView.loadUrl(target)
            }
            return
        }
        // No HA page under us yet — cold WebView, the OTP portal, or an error retry
        // in flight. Hand it to the restore path, which already knows how to wait
        // for the first real HA URL to appear.
        pendingDeepLinkPath = path
        restoreArmed = true
    }

    // ── Host matching ─────────────────────────────────────────────────────────
    // Parsed hosts, never substrings. "url.contains(domain)" would happily accept
    // https://coyotes.vision.attacker.example/ and load it in a WebView holding
    // live Home Assistant sessions.

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase().orEmpty()

    /** A house: <slug>.coyotes.vision. Suffix match, so the app is not tied to any one instance. */
    private fun isInstanceHost(host: String) =
        host.endsWith(".${BuildConfig.INSTANCE_DOMAIN}")

    private fun isServiceHost(host: String) =
        host == BuildConfig.INSTANCE_DOMAIN || isInstanceHost(host)

    // ── Path memory (return the user to where they were, on whichever host) ──
    // The local and remote instances are the same HA install, so the same
    // path (e.g. "/cv-panel#cvmail/settings") is valid on both — we just swap
    // the origin. The login portal is excluded: it's the OTP front door, not an
    // HA app route, and its URLs must never be remembered as "where you were".
    private fun isHaAppHost(url: String): Boolean {
        val host = hostOf(url)
        if (host == BuildConfig.LOGIN_HOST) return false
        return host == BuildConfig.LOCAL_HOST || isInstanceHost(host)
    }

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun pathOf(url: String): String =
        url.removePrefix(originOf(url)).ifBlank { "/" }

    // ── Local instance auth ──────────────────────────────────────────────────
    // TODO(verify against a live instance): the local HA origin (http://ha.local:8123)
    // has its own localStorage, separate from the remote instance's, so a token saved
    // while authenticated remotely isn't automatically visible here. CVAppBridge itself
    // IS already reachable as window.CVAppBridge on this page (JS interfaces are bound
    // to the WebView, not per-origin) — if the coyotes.vision frontend bundle deployed
    // on the local instance already calls CVAppBridge.getToken() to authenticate, no
    // further action is needed. If it doesn't, this is the place to inject HA's expected
    // `hassTokens` localStorage entry — deliberately left as a no-op until we confirm the
    // exact shape HA expects, since writing a malformed token would break login harder
    // than showing the normal HA login screen.
    private fun injectLocalAuthToken(view: WebView) {
        val token = prefs.getString("cv_panel_token", "") ?: return
        if (token.isBlank()) return
        // Intentionally not writing to localStorage yet — see TODO above.
    }

    // ── Availability resolution & switching ──────────────────────────────────

    private fun resolveInitialTarget() {
        showLoading()
        showSwitchEmoji(pending = true)
        resolveJob = activityScope.launch {
            val target = resolveTarget()
            currentTarget = target
            loadTarget(target)
            revealSwitchEmoji(target)
            mainHandler.postDelayed({ hideSwitchEmoji() }, SWITCH_REVEAL_HOLD_MS)
        }
    }

    private suspend fun resolveTarget(): HaTarget =
        if (networkMonitor.isOnWifi() && HaProbe.isReachable(HaTarget.LOCAL.url)) HaTarget.LOCAL else HaTarget.REMOTE

    /** Central re-evaluation entry point: periodic timer, network changes, and error retries all funnel through here. */
    private fun checkAvailabilityAndActOn(forceReload: Boolean = false) {
        if (resolveJob?.isActive == true) return
        resolveJob = activityScope.launch {
            val target = resolveTarget()
            val current = currentTarget
            when {
                current == null   -> { currentTarget = target; loadTarget(target) }
                target != current -> switchTo(target)
                forceReload        -> retryCurrentTarget()
            }
        }
    }

    private fun onNetworkSignal() {
        // NetworkCallback fires on a binder thread — hop back to main before touching views.
        mainHandler.post { checkAvailabilityAndActOn() }
    }

    private fun switchTo(target: HaTarget) {
        currentTarget = target
        showLoading()
        showSwitchEmoji(pending = false)
        revealSwitchEmoji(target)
        mainHandler.postDelayed({ hideSwitchEmoji() }, SWITCH_REVEAL_HOLD_MS)
        loadTarget(target)
    }

    private fun retryCurrentTarget() {
        val target = currentTarget ?: return
        loadTarget(target)
    }

    private fun loadTarget(target: HaTarget) {
        if (!loadingVisible) showLoading()
        updateStatus(if (target == HaTarget.LOCAL) "Connecting locally..." else "Connecting remotely...")
        loadingEndpoint.text = endpointLabel(target)
        restoreArmed = true
        webView.loadUrl(target.url)
    }

    /** host[:port] of a target — the address, without the scheme noise. */
    private fun endpointLabel(target: HaTarget): String {
        val uri = Uri.parse(target.url)
        val host = uri.host ?: return ""
        return if (uri.port > 0) "$host:${uri.port}" else host
    }

    private fun openExternal(url: String) =
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    // ── First-run link hint ───────────────────────────────────────────────────
    // Android 12 stopped offering a chooser for unverified web links: they go
    // straight to the browser, silently, and nothing tells the user that this app
    // could have handled them. Until assetlinks.json is in place the only fix is a
    // manual tick in Settings, which nobody discovers on their own.
    //
    // The hint is asked for once per launch but answers itself first: if the links
    // already work it never appears, and the checkbox silences it for good.

    private var linkHintDialog: AlertDialog? = null

    private fun maybeShowLinkHint() {
        if (isFinishing || isDestroyed) return
        if (prefs.getBoolean(KEY_LINK_HINT_DISMISSED, false)) return
        if (linkHintDialog?.isShowing == true) return
        if (updateDialog?.isShowing == true) return   // don't stack dialogs
        if (webLinksAlreadyHandled()) return

        val host = "*.${BuildConfig.INSTANCE_DOMAIN}"
        val view = layoutInflater.inflate(R.layout.dialog_link_hint, null)
        val dontShow = view.findViewById<CheckBox>(R.id.linkHintDontShow)

        linkHintDialog = AlertDialog.Builder(this)
            .setTitle(R.string.link_hint_title)
            .setMessage(getString(R.string.link_hint_body, host))
            .setView(view)
            .setPositiveButton(R.string.link_hint_open_settings) { _, _ -> openLinkSettings() }
            .setNegativeButton(R.string.link_hint_later, null)
            .setOnDismissListener {
                // Read on dismiss, not per-button: the choice should stick whether
                // they opened settings, tapped Not now, or dismissed the dialog.
                if (dontShow.isChecked) {
                    prefs.edit().putBoolean(KEY_LINK_HINT_DISMISSED, true).apply()
                }
            }
            .show()
    }

    /**
     * True when web links already reach this app, so there is nothing to ask for.
     *
     * Before Android 12 an unverified link still raised a chooser that lists this
     * app, so the hint would be noise. From 12 on, the system tracks a per-host
     * state and we can read the real answer instead of guessing. Anything
     * unexpected — no service, an exception, an unknown host set — counts as
     * "handled" so that a broken query can never nag on every launch.
     */
    private fun webLinksAlreadyHandled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        val manager = runCatching {
            getSystemService(DomainVerificationManager::class.java)
        }.getOrNull() ?: return true

        val states = runCatching {
            manager.getDomainVerificationUserState(packageName)?.hostToStateMap
        }.getOrNull() ?: return true

        val instanceHosts = states.filterKeys {
            it == "*.${BuildConfig.INSTANCE_DOMAIN}" || it.endsWith(BuildConfig.INSTANCE_DOMAIN)
        }
        if (instanceHosts.isEmpty()) return true

        return instanceHosts.values.any {
            it == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
    }

    /** Android's "Open by default" screen for this app. */
    private fun openLinkSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            // Some OEM builds don't expose the per-app screen; the app details
            // page always exists and is one tap away from it.
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    // ── App settings ──────────────────────────────────────────────────────────
    // The app deliberately has no chrome: once connected, the screen belongs to
    // Home Assistant. So its own controls live in one dialog, reached by
    // long-pressing the loading screen, by cvha://app-settings, or from the panel
    // through CVAppBridge.openAppSettings().

    private var settingsDialog: AlertDialog? = null
    private var refreshSettings: (() -> Unit)? = null

    private fun showAppSettings() {
        if (isFinishing || isDestroyed) return
        if (settingsDialog?.isShowing == true) return

        val view         = layoutInflater.inflate(R.layout.dialog_app_settings, null)
        val homeRow      = view.findViewById<View>(R.id.settingsHomeRow)
        val homeSwitch   = view.findViewById<SwitchCompat>(R.id.settingsHomeSwitch)
        val homeSubtitle = view.findViewById<TextView>(R.id.settingsHomeSubtitle)
        val linksRow     = view.findViewById<View>(R.id.settingsLinksRow)
        val updateRow    = view.findViewById<View>(R.id.settingsUpdateRow)

        view.findViewById<TextView>(R.id.settingsUpdateSubtitle).text =
            getString(R.string.settings_update_subtitle, BuildConfig.VERSION_NAME)

        val refresh = {
            val offered = LauncherMode.isOffered(this)
            val active  = LauncherMode.isCurrentHome(this)
            homeSubtitle.setText(
                when {
                    active  -> R.string.settings_home_state_active
                    offered -> R.string.settings_home_state_offered
                    else    -> R.string.settings_home_state_off
                }
            )
            // Detached first: assigning isChecked fires the listener, which would
            // turn a read of the current state into a write of it.
            homeSwitch.setOnCheckedChangeListener(null)
            homeSwitch.isChecked = offered
            homeSwitch.setOnCheckedChangeListener { _, checked -> setHomeAppOffered(checked) }
        }
        refresh()
        refreshSettings = refresh

        // Tapping the row rather than the switch reopens the picker. That is the
        // step between "CVapp can be a home app" and "CVapp is the home app" —
        // only the user makes that choice, and Android will not ask on our behalf.
        homeRow.setOnClickListener {
            if (LauncherMode.isOffered(this)) openHomePicker() else homeSwitch.isChecked = true
        }
        linksRow.setOnClickListener { openLinkSettings() }
        updateRow.setOnClickListener {
            settingsDialog?.dismiss()      // the update prompt takes it from here
            checkForUpdate(force = true)
        }

        settingsDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setNegativeButton(R.string.settings_close, null)
            .setOnDismissListener { refreshSettings = null }
            .show()
    }

    private fun setHomeAppOffered(offered: Boolean) {
        if (offered) {
            LauncherMode.setOffered(this, true)
            // Being listed is not being chosen; go straight to where that happens.
            openHomePicker()
            return
        }

        // Switching off while we *are* the home screen ends the home task under our
        // own feet, and Android raises its own "Select a Home app" sheet on the
        // spot — measured, and the right question at the right moment, so there is
        // nothing here to add to it. The toast is what it would otherwise be
        // missing: why the phone is suddenly asking.
        toast(getString(R.string.settings_home_removed))
        LauncherMode.setOffered(this, false)
        refreshSettings?.invoke()
    }

    private fun openHomePicker() {
        runCatching { startActivity(LauncherMode.pickerIntent()) }
            .onFailure { toast(getString(R.string.settings_home_no_picker)) }
    }

    // ── Self-update ───────────────────────────────────────────────────────────
    // No store to do this for us, so the app watches its own GitHub releases.
    // Three ways in: automatically on start (throttled), tapping the version on
    // the loading screen, and CVAppBridge.checkForUpdate() from the panel.

    private var updateJob: Job? = null
    private var updateDialog: AlertDialog? = null

    /** Set while the user is out in Settings granting "install unknown apps". */
    private var awaitingInstallPermission = false

    // ── Remembering an update we have already found ──────────────────────────
    // The six-hour interval exists to be polite to GitHub's API. It must not also
    // decide whether the user gets *told*, and it used to: the only route to the
    // prompt was a fresh network check, so an update that was offered and not
    // installed went quiet for the rest of the day — including across restarts.
    // Writing it down separates the two: the network is asked rarely, the user is
    // reminded on every launch until the update is actually installed.

    private fun rememberPending(release: Updater.Release) {
        prefs.edit()
            .putString(KEY_PENDING_VERSION, release.versionName)
            .putString(KEY_PENDING_NOTES, release.notes)
            .putString(KEY_PENDING_URL, release.apkUrl)
            .putString(KEY_PENDING_NAME, release.apkName)
            .putLong(KEY_PENDING_SIZE, release.sizeBytes)
            .apply()
    }

    private fun forgetPending() {
        prefs.edit()
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_NOTES)
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_SIZE)
            .apply()
    }

    /** The update already found, if it is still newer than what is installed. */
    private fun pendingRelease(): Updater.Release? {
        val version = prefs.getString(KEY_PENDING_VERSION, null) ?: return null
        val url     = prefs.getString(KEY_PENDING_URL, null) ?: return null
        val name    = prefs.getString(KEY_PENDING_NAME, null) ?: return null

        val release = Updater.Release(
            versionName = version,
            notes       = prefs.getString(KEY_PENDING_NOTES, "").orEmpty(),
            apkUrl      = url,
            apkName     = name,
            sizeBytes   = prefs.getLong(KEY_PENDING_SIZE, 0L)
        )
        // The install may have gone through since it was written — including by
        // hand, from a file the user downloaded themselves.
        if (!Updater.isNewerThanInstalled(release)) { forgetPending(); return null }
        return release
    }

    /** Re-offers a known update without touching the network. True if it prompted. */
    private fun offerPendingUpdate(): Boolean {
        if (isFinishing || isDestroyed) return false
        val release = pendingRelease() ?: return false
        promptUpdate(release)
        return true
    }

    /**
     * The user tapped Update, Android sent them out to grant "install unknown
     * apps", and they came back. This is where the update used to die quietly:
     * beginUpdate had already returned, the release it was holding was gone, and
     * the check throttle meant even restarting the app said nothing for hours.
     * Granting the permission now leads straight into the download it was for.
     */
    private fun resumeUpdateAfterPermission() {
        if (!awaitingInstallPermission) return
        if (!Updater.canInstallPackages(this)) return
        awaitingInstallPermission = false
        val release = pendingRelease() ?: return
        updateDialog?.takeIf { it.isShowing }?.dismiss()
        beginUpdate(release)
    }

    private fun checkForUpdate(force: Boolean) {
        if (updateJob?.isActive == true) return

        val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (!force && elapsed < Updater.CHECK_INTERVAL_MS) return

        if (force) Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()

        updateJob = activityScope.launch {
            val release = Updater.fetchLatest()
            // Stamped even when the check fails, so a phone with no route to
            // GitHub doesn't retry on every single onStart. A forced check
            // ignores the stamp anyway, so nothing is actually locked out.
            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()

            if (isFinishing || isDestroyed) return@launch
            when {
                // Unreachable: keep whatever we already knew rather than forgetting it.
                release == null ->
                    if (force) toast(getString(R.string.update_check_failed))
                Updater.isNewerThanInstalled(release) -> {
                    rememberPending(release)
                    promptUpdate(release)
                }
                else -> {
                    forgetPending()
                    if (force) toast(getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                }
            }
        }
    }

    private fun promptUpdate(release: Updater.Release) {
        if (updateDialog?.isShowing == true) return

        val body = buildString {
            append(getString(R.string.update_available_body, BuildConfig.VERSION_NAME))
            if (release.sizeBytes > 0) {
                append(String.format(" %.1f MB.", release.sizeBytes / 1_048_576.0))
            }
            if (release.notes.isNotBlank()) append("\n\n").append(release.notes)
        }

        updateDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, release.versionName))
            .setMessage(body)
            .setPositiveButton(R.string.update_action_install) { _, _ -> beginUpdate(release) }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun beginUpdate(release: Updater.Release) {
        if (!Updater.canInstallPackages(this)) {
            // Written down before we send them away, so that coming back with the
            // permission granted has something to carry on with.
            rememberPending(release)
            awaitingInstallPermission = true
            promptInstallPermission()
            return
        }
        awaitingInstallPermission = false

        val view     = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgressBar)
        val label    = view.findViewById<TextView>(R.id.updateProgressLabel)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> updateJob?.cancel() }
            .show()
        updateDialog = dialog

        updateJob = activityScope.launch {
            // onProgress arrives on the IO thread doing the reading.
            val apk = Updater.download(applicationContext, release) { pct ->
                mainHandler.post {
                    if (pct < 0) {
                        progress.isIndeterminate = true
                        label.text = ""
                    } else {
                        progress.isIndeterminate = false
                        progress.progress = pct
                        label.text = "$pct%"
                    }
                }
            }
            dialog.dismiss()
            if (apk == null) toast(getString(R.string.update_download_failed))
            else launchInstaller(apk)
        }
    }

    private fun promptInstallPermission() {
        updateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_needs_permission_title)
            .setMessage(R.string.update_needs_permission_body)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                runCatching { startActivity(Updater.unknownSourcesSettingsIntent(this)) }
            }
            // Backing out here ends this attempt, so a grant made later for some
            // unrelated reason doesn't start a download nobody asked for. The
            // update itself stays remembered and is offered again next launch.
            .setNegativeButton(R.string.update_action_later) { _, _ ->
                awaitingInstallPermission = false
            }
            .show()
    }

    private fun launchInstaller(apk: File) {
        val intent = Updater.installIntent(this, apk)
        if (!Updater.hasInstaller(this, intent)) {
            toast(getString(R.string.update_no_installer))
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.update_no_installer)) }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // ── View state ────────────────────────────────────────────────────────────

    private fun showLoading() {
        loadingVisible = true
        // Keep WebView VISIBLE at alpha=0 — INVISIBLE releases the GPU surface,
        // which causes a black flash when the surface is recreated on next show.
        webView.alpha = 0f
        if (webView.visibility != View.VISIBLE) webView.visibility = View.VISIBLE
        loadingView.visibility = View.VISIBLE
        loadingView.alpha      = 1f
        if (breathingAnimator?.isRunning != true) startBreathing()
    }

    private fun hideLoading() {
        if (!loadingVisible) return   // guard: prevent duplicate calls resetting alpha
        loadingVisible = false
        breathingAnimator?.cancel()
        hideSwitchEmoji()
        hideRetryHint()
        loadingView.animate().alpha(0f).setDuration(350).withEndAction {
            loadingView.visibility = View.GONE
            loadingView.alpha = 1f
        }.start()
        webView.animate().alpha(1f).setDuration(350).start()
    }

    private fun updateStatus(msg: String) { loadingStatus.text = msg }

    // ── Loading animation ─────────────────────────────────────────────────────

    private fun animateLoadingIn() {
        val dp = resources.displayMetrics.density
        loadingLogo.alpha = 0f;    loadingLogo.scaleX = 0.82f; loadingLogo.scaleY = 0.82f
        loadingTitle.alpha = 0f;   loadingTitle.translationY = 18f * dp
        loadingSubtitle.alpha = 0f; loadingProgress.alpha = 0f
        loadingStatus.alpha = 0f;  loadingVersion.alpha = 0f
        loadingEndpoint.alpha = 0f; loadingBrand.alpha = 0f

        loadingLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(450).setStartDelay(80).withEndAction { startBreathing() }.start()
        loadingTitle.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(280).start()
        loadingSubtitle.animate().alpha(0.65f).setDuration(300).setStartDelay(400).start()
        loadingProgress.animate().alpha(1f).setDuration(280).setStartDelay(520).start()
        loadingStatus.animate().alpha(0.7f).setDuration(280).setStartDelay(600).start()
        loadingEndpoint.animate().alpha(0.55f).setDuration(280).setStartDelay(660).start()
        loadingBrand.animate().alpha(0.4f).setDuration(300).setStartDelay(700).start()
        loadingVersion.animate().alpha(0.4f).setDuration(300).setStartDelay(700).start()
    }

    private fun startBreathing() {
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(
            loadingLogo,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.05f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.05f)
        ).apply {
            duration = 1800; repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ── Local ⇄ remote switch animation ───────────────────────────────────────
    // Plays out on top of the loading screen: 🏠 on the left, ☁️ on the right,
    // the winning side snaps forward while the other fades back, then both
    // settle away again after a short hold — the breathing logo underneath
    // keeps communicating "still working" for however long the real page load takes.

    private fun showSwitchEmoji(pending: Boolean) {
        switchLocalColumn.alpha = 0.5f;  switchLocalColumn.scaleX = 0.88f;  switchLocalColumn.scaleY = 0.88f
        switchRemoteColumn.alpha = 0.5f; switchRemoteColumn.scaleX = 0.88f; switchRemoteColumn.scaleY = 0.88f
        updateStatus(if (pending) "Finding connection..." else "Switching...")
    }

    private fun revealSwitchEmoji(to: HaTarget) {
        val (winner, loser) = if (to == HaTarget.LOCAL)
            switchLocalColumn to switchRemoteColumn
        else
            switchRemoteColumn to switchLocalColumn

        winner.animate().alpha(1f).scaleX(1.3f).scaleY(1.3f).setDuration(320)
            .setInterpolator(OvershootInterpolator()).start()
        loser.animate().alpha(0.18f).scaleX(0.7f).scaleY(0.7f).setDuration(320).start()

        updateStatus(if (to == HaTarget.LOCAL) "Local Network" else "Remote Connection")
    }

    private fun hideSwitchEmoji() {
        switchLocalColumn.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(250).start()
        switchRemoteColumn.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(250).start()
    }

    // ── Connection trouble (replaces any browser-shown error) ────────────────
    // A couple of blips are retried silently (e.g. a DNS hiccup right after a
    // network handoff). Only once failures persist do we surface the retry hint —
    // the loading screen itself never changes into a scary "error screen".

    private fun enterTrouble(code: Int) {
        consecutiveFailures++
        hideSwitchEmoji()
        if (!loadingVisible) showLoading()
        updateStatus(troubleMessage(code))

        val persistent = consecutiveFailures > SILENT_RETRY_ATTEMPTS
        if (persistent) showRetryHint() else hideRetryHint()

        val delay = if (persistent) {
            val d = retryBackoffMs
            retryBackoffMs = (retryBackoffMs * 17 / 10).coerceAtMost(MAX_BACKOFF_MS)
            d
        } else SILENT_RETRY_DELAY_MS
        mainHandler.removeCallbacks(errorRetryRunnable)
        mainHandler.postDelayed(errorRetryRunnable, delay)
    }

    private fun troubleMessage(code: Int): String = when (code) {
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, ERROR_SSL -> "Security warning — check your connection"
        WebViewClient.ERROR_AUTHENTICATION                  -> "Sign-in required"
        else                                                -> "Reconnecting..."
    }

    private fun showRetryHint() {
        loadingRetryHint.isClickable = true
        loadingRetryHint.animate().alpha(0.8f).setDuration(250).start()
    }

    private fun hideRetryHint() {
        loadingRetryHint.isClickable = false
        loadingRetryHint.animate().alpha(0f).setDuration(200).start()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        when {
            webView.canGoBack() -> webView.goBack()
            // As the home screen there is nothing behind us. Finishing would just
            // make Android relaunch the home app — this one — from cold, so Back
            // at the root is swallowed here exactly as a launcher swallows it.
            LauncherMode.isCurrentHome(this) -> Unit
            else -> super.onBackPressed()
        }
    }
}
