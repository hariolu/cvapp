package com.cvapp

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.*
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Single status screen — used for initial load, the local⇄remote switch
    // animation, and masking connection trouble. See activity_main.xml.
    private lateinit var rootLayout: FrameLayout
    private lateinit var loadingView: View
    private lateinit var loadingSky: BackdropView
    private lateinit var loadingSettings: ImageView
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingTitle: TextView
    private lateinit var loadingGreeting: TextView
    private lateinit var routeRow: LinearLayout
    private lateinit var routeLocal: TextView
    private lateinit var routeRemote: TextView
    private lateinit var loadingLog: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusName: TextView
    private lateinit var statusEndpoint: TextView
    private lateinit var loadingBrand: TextView
    private lateinit var loadingVersion: TextView
    private lateinit var loadingRetryHint: TextView

    private val prefs by lazy { getSharedPreferences("cvapp_prefs", Context.MODE_PRIVATE) }
    private var breathingAnimator: ObjectAnimator? = null
    @Volatile private var loadingVisible = false

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var networkMonitor: NetworkMonitor
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resolveJob: Job? = null

    // Tracks connectRemote() specifically — it can sit for minutes waiting on the
    // OTP dialog, which outlives the resolveJob that launched it. Without this,
    // a network blip mid-wait could start loading LOCAL_URL while connectRemote()
    // (and its dialog) is still running in the background.
    private var connectJob: Job? = null

    // ── VPN / Remote access ───────────────────────────────────────────────────
    // REMOTE no longer means login.coyotes.vision — it means this tunnel is up
    // and BuildConfig.LOCAL_URL is reachable through it. See VpnManager.kt,
    // CvAuthApi.kt, SecureStore.kt.
    private val vpnManager by lazy { VpnManager(this) }
    private val secureStore by lazy { SecureStore(this) }
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private var vpnPermissionContinuation: CancellableContinuation<Boolean>? = null

    // ── In-app dialog host ────────────────────────────────────────────────────
    private lateinit var dialogScrim: FrameLayout
    private lateinit var dialogCard: FrameLayout
    private var activeDialog: ActiveDialog? = null

    // The host's ordinary shape, kept from the layout so a full-screen dialog can
    // hand it back afterwards — one slot serves every card in the app.
    private var cardMarginStart = 0
    private var cardMarginEnd   = 0
    private var cardElevation   = 0f

    private var currentTarget: HaTarget? = null

    // Set in onStop() when it tears down an active REMOTE tunnel, so onStart()
    // knows to force a fresh connect rather than sitting on a dead one until
    // the next periodic recheck (up to RECHECK_INTERVAL_MS later).
    private var vpnDownOnStop = false

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
        private const val ERROR_VPN      = -997

        private const val TAG = "CVapp"

        /** Closes Home Assistant's slid-in side menu; true when there was one to close. */
        private const val CLOSE_DRAWER_JS = """(function(){try{
            var m=document.querySelector('home-assistant');
            m=m&&m.shadowRoot&&m.shadowRoot.querySelector('home-assistant-main');
            var d=m&&m.shadowRoot&&m.shadowRoot.querySelector('ha-drawer');
            if(d&&d.open&&d.type==='modal'){d.open=false;return true;}
        }catch(e){}return false;})()"""

        private const val RECHECK_INTERVAL_MS   = 20_000L
        private const val SILENT_RETRY_ATTEMPTS = 2       // blips retried quietly before we surface a retry hint
        private const val SILENT_RETRY_DELAY_MS = 1_200L
        private const val INITIAL_BACKOFF_MS    = 3_000L
        private const val MAX_BACKOFF_MS        = 15_000L

        // ── Keeping the remote session ───────────────────────────────────────
        // cv-auth expires a session a month after it last heard from it, and it
        // slides on every renewal. Two days keeps it far from that edge without
        // asking anything of the network on most opens: a session younger than
        // this is simply used, tunnel first.
        private const val RENEW_AFTER_MS          = 2L * 24 * 60 * 60 * 1000
        private const val RENEW_ATTEMPTS          = 3
        private const val RENEW_RETRY_DELAY_MS    = 1_500L
        // "Interface up" is not "packets arrive" — see raiseTunnel().
        private const val TUNNEL_PROBE_ATTEMPTS   = 3
        private const val TUNNEL_PROBE_DELAY_MS   = 700L
        private const val TUNNEL_PROBE_TIMEOUT_MS = 2_500

        /** How many finished steps of the connect log stay above the status card. */
        private const val LOG_LINES = 3
        /** Their strength, newest first. */
        private val LOG_FADES = floatArrayOf(0.85f, 0.55f, 0.3f)

        // How wide a column may grow on a tablet: the loading screen's, the
        // settings page's (the CV Panel's own 600, plus its padding), sign-in's.
        private const val LOADING_COLUMN_DP  = 468
        private const val SETTINGS_COLUMN_DP = 624
        private const val OTP_COLUMN_DP      = 460
        private const val CARD_MAX_DP        = 440
        /** Behind a card: Bubble's pop-up scrim (the panel's rgba(0,0,0,.45)). */
        private const val SCRIM = 0x73000000

        private const val KEY_LAST_PATH          = "last_ha_path"
        private const val KEY_LAST_UPDATE_CHECK  = "last_update_check"
        private const val KEY_LINK_HINT_DISMISSED = "link_hint_dismissed"

        // App settings → Screen.
        private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        private const val KEY_HIDE_NAV_BAR    = "hide_nav_bar"
        /** How long "Forget" waits for its second tap. */
        private const val FORGET_ARMED_MS     = 5_000L
        private const val KEY_AWAKE_CHARGING  = "awake_while_charging"
        /** The name Home Assistant knows this phone's person by, for the greeting. */
        private const val KEY_USER_NAME       = "ha_user_name"

        /** Where a person picks their look: the CV Panel's Settings (Appearance). */
        private const val PANEL_APPEARANCE = "/cv-panel/settings"

        /**
         * When to read a page that has just loaded: once Home Assistant has had
         * time to answer the page's own questions (who is this, what is their
         * look), and once more for a slow house.
         */
        private val READ_PAGE_AFTER_MS = longArrayOf(2_000L, 8_000L)

        /**
         * Whether Home Assistant's frontend has drawn itself: it removes its own
         * launch screen when the first panel is up. A page without one (the
         * sign-in page, anything else) counts as drawn.
         */
        private const val FRONTEND_READY_JS = "!document.getElementById('ha-launch-screen')"
        private const val FRONTEND_CHECK_MS = 150L
        /** Six seconds: a frontend that never says so is shown anyway. */
        private const val FRONTEND_CHECKS = 40

        /**
         * What the app reads off a Home Assistant page: the person's look (see
         * Look.READ_JS), the name to greet them by, and whether this is the
         * sign-in page — whoever was signed in has left, and their name goes too.
         */
        private val PAGE_JS = """(function () {
  var ha = document.querySelector('home-assistant'), user = ha && ha.hass && ha.hass.user;
  return { login: location.pathname.indexOf('/auth/') === 0, user: user ? user.name : null, look: ${Look.READ_JS} };
})()"""

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

    // LOCAL and REMOTE both end up loading the exact same BuildConfig.LOCAL_URL
    // — the only difference is how we get there: real Wi-Fi, or the WireGuard
    // tunnel (see VpnManager/CvAuthApi). There is no longer a second host or a
    // separate login portal for this app to know about.
    private enum class HaTarget { LOCAL, REMOTE }

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

        // ── CV Chat notifications (PushService) ──────────────────────────────
        // The chat page registers this phone with its house: the app hands over
        // the public half of its own key, the house answers with this phone's
        // topic on the relay. Only the house's own pages may switch it.

        /** This phone's public key for sealed notifications, made on first ask. */
        @JavascriptInterface fun pushKey(): String {
            val (_, pub) = secureStore.pushKeys()
            return JSONObject()
                .put("key", java.util.Base64.getEncoder().encodeToString(pub))
                .put("device", secureStore.pushDevice ?: "")
                .put("name", Build.MODEL)
                .put("on", secureStore.pushReady())
                .toString()
        }

        @JavascriptInterface fun pushSet(json: String) = mainHandler.post {
            if (!isHaAppHost(webView.url.orEmpty())) return@post
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@post
            val relay = o.optString("relay").trimEnd('/')
            val topic = o.optString("topic")
            val device = o.optString("device")
            if (!relay.startsWith("https://") || topic.isBlank() || device.isBlank()) return@post
            secureStore.savePush(device, topic, relay)
            // The page re-registers quietly on every open; only switching on asks.
            if (o.optBoolean("ask", true)) askForPush()
            PushService.start(this@MainActivity)
        }

        @JavascriptInterface fun pushOff() = mainHandler.post {
            if (!isHaAppHost(webView.url.orEmpty())) return@post
            PushService.stop(this@MainActivity)
            secureStore.forgetPush()
        }

        /** The thread the chat page shows right now, or "" when it shows none. */
        @JavascriptInterface fun chatShown(thread: String) {
            Notifier.onScreen = thread.take(32)
        }

        /** A file from the house into Downloads, under the name the house gave it. */
        @JavascriptInterface fun download(url: String, name: String, mime: String) =
            mainHandler.post { saveFile(url, name, mime) }

        // ── CV Chat voice notes (VoiceRecorder) ──────────────────────────────
        // Only the house's own pages may switch the microphone on. The first
        // time, Android asks the person: the answer reaches the page as a
        // 'cvapp-mic' event ("granted", "denied"); so does "stopped", when the
        // app leaves the screen in the middle of a recording.

        /** "" once it records, "asking" while Android asks, else why not. */
        @JavascriptInterface fun micStart(): String {
            if (!onHaPage) return "denied"
            if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                mainHandler.post { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
                return "asking"
            }
            return voice.start()
        }

        @JavascriptInterface fun micLevel(): Int = voice.level()

        /** The recording as {b64, mime}, or "" when `send` is false or nothing was recorded. */
        @JavascriptInterface fun micStop(send: Boolean): String = voice.stop(send)

        /** A button pressed in a notification, for the chat page to press as the person. Once. */
        @JavascriptInterface fun takeTap(): String {
            val tap = pendingTap ?: ""
            pendingTap = null
            return tap
        }
    }

    // A notification's button, waiting for the chat page to press it.
    @Volatile private var pendingTap: String? = null
    private lateinit var notifyPermission: ActivityResultLauncher<String>
    private lateinit var micPermission: ActivityResultLauncher<String>
    private val voice by lazy { VoiceRecorder(applicationContext) }
    // Whether the page on screen is the house's own (the bridge's calls arrive
    // off the main thread, where the WebView cannot be asked).
    @Volatile private var onHaPage = false

    private fun micNews(what: String) {
        if (::webView.isInitialized)
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('cvapp-mic',{detail:'$what'}))", null)
    }

    private fun takeTapFrom(intent: Intent?): Boolean {
        val tap = intent?.getStringExtra(Notifier.EXTRA_TAP) ?: return false
        intent.removeExtra(Notifier.EXTRA_TAP)
        pendingTap = tap
        runCatching { JSONObject(tap).optString("tag") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { Notifier.cancel(this, it) }
        return true
    }

    /**
     * Notifications need a yes on Android 13 and later, and a stream that lasts
     * the night needs the app out of battery optimisation: the price of push
     * without Google, as for ntfy or Signal without Play services.
     */
    private fun askForPush() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askBatteryExemption()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The person's look before anything is drawn: the last one a house gave.
        Look.load(this)
        // Edge-to-edge on every Android, not only where 15 insists on it: the
        // bars then show the look's own background, not a strip of the system's.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The window may run past the camera, as it does on 15 anyway: with the
        // status bar hidden the page gets the camera's strip too (see the insets
        // listener).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)

        // Must be registered before onStart, same as any ActivityResultLauncher —
        // this is the one-time "CVapp wants to set up a VPN connection" system
        // consent dialog, resumed via ensureVpnPermission()'s continuation.
        vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            vpnPermissionContinuation?.resume(result.resultCode == RESULT_OK)
            vpnPermissionContinuation = null
        }
        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onFilesChosen(it) }
        notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryExemption() }
        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            micNews(if (ok) "granted" else "denied")
        }

        // First thing, before a second WebView gets built on top of the one that
        // is already out there. This is the instance Android keeps.
        removeOtherAppTasks()

        // Edge-to-edge is mandatory from targetSdk 35, which means the window is
        // no longer resized when the keyboard opens — the IME arrives as an inset
        // and padding for it is this listener's job. Reading only systemBars()
        // here left the WebView at full height with the keyboard drawn over it,
        // hiding whatever field the user had just tapped.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars   = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime    = insets.getInsets(WindowInsetsCompat.Type.ime())
            // A hidden status bar gives the page its whole height, up to the top
            // edge — under the camera, on a phone that has one in the screen. The
            // app's own screens step down by the camera's height instead: a gear
            // or a title behind the lens helps nobody.
            val camera = maxOf(0, cutout.top - bars.top)
            loadingView.setPadding(0, camera, 0, 0)
            dialogScrim.setPadding(0, camera, 0, 0)
            // At the bottom whichever is taller, not their sum: the keyboard is
            // drawn over the navigation bar, so adding them leaves a navbar-sized
            // dead strip.
            v.setPadding(
                maxOf(bars.left, cutout.left), bars.top,
                maxOf(bars.right, cutout.right), maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        rootLayout         = findViewById(R.id.rootLayout)
        webView            = findViewById(R.id.webView)
        loadingView        = findViewById(R.id.loadingView)
        loadingSky         = findViewById(R.id.loadingSky)
        loadingSettings    = findViewById(R.id.loadingSettings)
        loadingLogo        = findViewById(R.id.loadingLogo)
        loadingTitle       = findViewById(R.id.loadingTitle)
        loadingGreeting    = findViewById(R.id.loadingGreeting)
        routeRow           = findViewById(R.id.routeRow)
        routeLocal         = findViewById(R.id.routeLocal)
        routeRemote        = findViewById(R.id.routeRemote)
        loadingLog         = findViewById(R.id.loadingLog)
        statusCard         = findViewById(R.id.statusCard)
        statusIcon         = findViewById(R.id.statusIcon)
        statusName         = findViewById(R.id.statusName)
        statusEndpoint     = findViewById(R.id.statusEndpoint)
        loadingBrand       = findViewById(R.id.loadingBrand)
        loadingVersion     = findViewById(R.id.loadingVersion)
        loadingRetryHint   = findViewById(R.id.loadingRetryHint)
        dialogScrim        = findViewById(R.id.dialogScrim)
        dialogCard         = findViewById(R.id.dialogCard)
        (dialogCard.layoutParams as FrameLayout.LayoutParams).let {
            cardMarginStart = it.marginStart
            cardMarginEnd   = it.marginEnd
        }
        cardElevation  = dialogCard.elevation

        applyLook(Look.current)
        applyBars()
        capWidth(findViewById(R.id.loadingColumn), LOADING_COLUMN_DP)
        loadingGreeting.text = greeting()
        statusEndpoint.text = endpointLabel()

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
        loadingSettings.setOnClickListener { showAppSettings() }

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
        takeTapFrom(intent)
        // Listening again if Android stopped it, as when the app was swiped away.
        PushService.start(this)
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
        // A page already open may never have left the screen (the shade only
        // covered it), so it is told outright that a tap is waiting.
        if (takeTapFrom(intent) && ::webView.isInitialized) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('cvapp-tap'))", null)
        }
        deepLinkPathOrWarn(intent.data)?.let { applyDeepLink(it) }
    }

    override fun onResume() {
        super.onResume()
        // Home-app and link approvals are changed in Settings, i.e. in another
        // activity, with our dialog still open behind it. Re-read on the way back
        // so it never shows a state that stopped being true.
        refreshSettings?.invoke()
        resumeUpdateAfterPermission()
        applyBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A system dialog (VPN consent, the home picker) can bring the bars back
        // with it; put them away again when the window is ours once more.
        if (hasFocus) applyBars()
    }

    override fun onStart() {
        super.onStart()
        Notifier.appVisible = true
        networkMonitor.start()
        mainHandler.postDelayed(recheckRunnable, RECHECK_INTERVAL_MS)
        checkForUpdate(force = false)
        // The charger, for "Keep the screen on while charging". The broadcast is
        // sticky, so the state it is in now arrives at once.
        ContextCompat.registerReceiver(this, powerReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Again on every foreground, not only at creation: a stray task can also
        // appear after the home instance exists, and coming to the front is the
        // natural moment to be sure this is the only one.
        removeOtherAppTasks()
        if (vpnDownOnStop) {
            vpnDownOnStop = false
            // Target hasn't changed (still REMOTE) so the normal resolve path
            // would see nothing to do and leave the dead tunnel sitting there
            // until the next periodic recheck — force it instead.
            checkAvailabilityAndActOn(forceReload = true)
        }
    }

    override fun onStop() {
        super.onStop()
        Notifier.appVisible = false
        // A voice note is recorded on screen only: off it, the recording is dropped.
        if (voice.recording) { voice.stop(false); micNews("stopped") }
        networkMonitor.stop()
        mainHandler.removeCallbacks(recheckRunnable)
        runCatching { unregisterReceiver(powerReceiver) }
        // Whatever the person changed in the panel meanwhile, their colour above
        // all, is what the next loading screen should wear.
        readPage()
        // Off to Android's file picker for the page: back in a moment, with the
        // page waiting for its answer. Dropping the tunnel now would reload the
        // page under it and lose the upload.
        if (pickingFiles) return
        // The VPN key stays in the status bar for as long as the tunnel is up,
        // regardless of whether this app is on screen — that's Android's own
        // system indicator, not ours to hide. Tearing the tunnel down here (as
        // opposed to only on a LOCAL⇄REMOTE switch, see switchTo()) makes it
        // disappear when the app actually closes, at the cost of a short
        // reconnect on the way back in — cheap, since the WG key and refresh
        // token are already on disk and neither VPN consent nor a fresh OTP is
        // needed again, only ensureVpnPermission()'s null fast-path and a
        // handshake.
        if (currentTarget == HaTarget.REMOTE) {
            vpnDownOnStop = true
            activityScope.launch { vpnManager.down() }
        }
    }

    /**
     * Removes every other task belonging to this app.
     *
     * Android runs the home app in a task of its own, and a task that is already
     * running cannot be merged into it — `singleTask` is powerless here, because
     * the two are different kinds of task. Switching home mode on while the app
     * is open therefore produces two live activities, two WebViews and two Home
     * Assistant sessions.
     *
     * The clean-up has to reach across tasks, and it has to be the home instance
     * that performs it. Letting the leftover retire itself in onStop does not
     * work, and that was measured rather than reasoned: the home-app picker opens
     * *inside* the running task, so the old activity stops while CVapp is still
     * not the home app. Choose CVapp there, press Home without returning to the
     * app first, and onStop never runs again — it fires on the way into the
     * stopped state, not on anything that happens afterwards. The task then sits
     * there indefinitely.
     *
     * The survivor is the instance Android intends to keep, it is created at
     * exactly the moment the duplicate appears, and it restores the user's page
     * from KEY_LAST_PATH — so nothing is lost by cutting the other one loose.
     */
    private fun removeOtherAppTasks() {
        if (!LauncherMode.isHomeInstance(this)) return
        val manager = getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            manager.appTasks.forEach { task ->
                val id = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task.taskInfo.taskId
                    else @Suppress("DEPRECATION") task.taskInfo.id
                }.getOrNull()
                // Removing rather than finishing also clears the stale card out of
                // Recents, where two CVapps side by side is its own kind of wrong.
                if (id != null && id != taskId) task.finishAndRemoveTask()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        // Dialogs are plain child views now, not separate windows — they die
        // with the activity's own view tree, nothing to leak or dismiss here.
        refreshSettings = null
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        activityScope.cancel()

        // Duplicate instances are now destroyed deliberately, so their WebView has
        // to actually go with them — otherwise "removed the second session" would
        // mean a dead activity still holding a live renderer. Detach first: destroy
        // on an attached WebView is documented to misbehave.
        runCatching {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Debug builds only: lets a desktop's DevTools look into the page, for testing.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
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

            // A page's element full screen (the chat's viewer, a clip's own
            // button): WebView refuses the request unless the app puts it up.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = showFull(view, callback)
            override fun onHideCustomView() = hideFull(fromPage = true)

            // <input type="file">: answered by Android's own picker, see chooseFiles().
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean = chooseFiles(callback, params)

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
                val allowed = isServiceHost(host) || host == BuildConfig.LOCAL_HOST || host == secureStore.tunnelHost
                return if (!allowed) { openExternal(url); true } else false
            }

            override fun onPageStarted(view: WebView, url: String, fav: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, fav)
                onHaPage = isHaAppHost(url)
                step(R.drawable.ic_mdi_web, "Loading…")
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

                awaitFrontend(url)
                readPageSoon()
            }

            // Fires on both full navigations and SPA route changes (pushState,
            // location.hash) — this is how we track where in HA the user actually is.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onHaPage = isHaAppHost(url)
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

        // The house's own files go to Downloads; anything else to the browser.
        webView.setDownloadListener { url, _, disposition, mime, _ ->
            if (isHaAppHost(url)) saveFile(url, URLUtil.guessFileName(url, disposition, mime), mime.orEmpty())
            else runCatching { openExternal(url) }
        }
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
        // mDNS doesn't cross the WireGuard tunnel, so REMOTE loads the LAN IP
        // cv-auth handed back (tunnel_host) instead of BuildConfig.LOCAL_HOST —
        // both count as "home", same as LOCAL_HOST does on real Wi-Fi.
        return host == BuildConfig.LOCAL_HOST || host == secureStore.tunnelHost || isInstanceHost(host)
    }

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun pathOf(url: String): String =
        url.removePrefix(originOf(url)).ifBlank { "/" }

    // ── Availability resolution & switching ──────────────────────────────────

    private fun resolveInitialTarget() {
        showLoading()
        showSwitch(pending = true)
        resolveJob = activityScope.launch {
            val target = resolveTarget()
            currentTarget = target
            revealSwitch(target)
            loadTarget(target)
        }
    }

    private suspend fun resolveTarget(): HaTarget =
        if (networkMonitor.isOnWifi() && HaProbe.isReachable(BuildConfig.LOCAL_URL)) HaTarget.LOCAL else HaTarget.REMOTE

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
        showSwitch(pending = false)
        revealSwitch(target)
        // Real Wi-Fi makes the tunnel pointless overhead — drop it in the
        // background, the LOCAL page load below doesn't wait on this.
        if (target == HaTarget.LOCAL) activityScope.launch { vpnManager.down() }
        loadTarget(target)
    }

    private fun retryCurrentTarget() {
        val target = currentTarget ?: return
        loadTarget(target)
    }

    private fun loadTarget(target: HaTarget) {
        if (!loadingVisible) showLoading()
        statusEndpoint.text = endpointLabel()
        refreshSettings?.invoke()
        restoreArmed = true
        when (target) {
            HaTarget.LOCAL -> {
                // A network blip could otherwise leave connectRemote() (and its
                // OTP dialog) running in the background while LOCAL loads.
                connectJob?.cancel()
                step(R.drawable.ic_mdi_home_outline, "Connecting locally…")
                // Being home is the safest moment to renew the way out of it.
                keepSessionWarm()
                // Same URL as REMOTE (see haUrl()) — a second origin here is what
                // used to make HA ask to sign in again after every Wi-Fi⇄tunnel
                // switch, since hassTokens lives in localStorage, which is scoped
                // per-origin.
                webView.loadUrl(haUrl())
            }
            HaTarget.REMOTE -> {
                // Already connecting — most commonly the OTP dialog sitting open
                // waiting on the user. Don't restart the flow out from under it.
                if (connectJob?.isActive == true) return
                connectJob = activityScope.launch {
                    if (connectRemote()) webView.loadUrl(haUrl())
                    else enterTrouble(ERROR_VPN, keepStatus = true)
                }
            }
        }
    }

    /**
     * The page both targets load. `ha.local` (mDNS) only resolves on the real
     * LAN — it cannot cross a routed WireGuard tunnel, multicast doesn't route —
     * so the moment cv-auth has handed back this instance's LAN IP
     * (tunnel_host, from a VPN bootstrap/refresh) it becomes the one true
     * origin for BOTH targets, not just REMOTE. That's what keeps the HA
     * session in localStorage alive across a Wi-Fi⇄tunnel switch: same origin
     * either way. Before the first ever bootstrap, tunnelHost is unset and
     * this just falls back to the mDNS name, exactly like LOCAL always did.
     */
    private fun haUrl(): String =
        secureStore.tunnelHost?.let { "http://$it:${BuildConfig.LOCAL_PORT}/" } ?: BuildConfig.LOCAL_URL

    /** host[:port] this app actually loads, on either side. */
    private fun endpointLabel(): String {
        val uri = Uri.parse(haUrl())
        val host = uri.host ?: return ""
        return if (uri.port > 0) "$host:${uri.port}" else host
    }

    // ── In-app dialog host ────────────────────────────────────────────────────
    // Every dialog in this app — OTP sign-in, settings, the link-permission
    // hint, update prompts — is a card animated into dialogScrim/dialogCard
    // (see activity_main.xml) instead of a system AlertDialog window. That
    // keeps the same dark/branded look on every OEM skin rather than whatever
    // theme Android ships, and it means a dialog is just a view in this
    // activity's own hierarchy: nothing to leak, nothing to re-theme, and
    // Back has to be handled by hand (see onBackPressed) since there's no
    // Window intercepting it for us anymore. One slot — at most one dialog is
    // ever on screen, matching how this app has only ever shown one at a time.

    private class ActiveDialog(val cancelable: Boolean, val fullScreen: Boolean, val onDismiss: (() -> Unit)?)

    /**
     * Inflates [layoutRes] into the shared card and animates it on screen.
     * Returns the inflated view so the caller can bind its own fields and
     * button clicks — closing always goes through dismissDialog(), whichever
     * button or gesture triggers it.
     */
    private fun showDialog(
        layoutRes: Int,
        cancelable: Boolean = true,
        fullScreen: Boolean = false,
        onDismiss: (() -> Unit)? = null,
    ): View {
        // Cancel first: a ViewPropertyAnimator's withEndAction still fires on
        // cancel(), synchronously, so this clears out any dialog mid-dismiss
        // before we touch the card ourselves — see dismissDialog().
        dialogScrim.animate().cancel()
        dialogCard.animate().cancel()
        dialogCard.removeAllViews()
        activeDialog = null

        shapeCard(fullScreen)

        val view = layoutInflater.inflate(layoutRes, dialogCard, false)
        Bubble.paint(view, Look.current)
        dialogCard.addView(view)
        activeDialog = ActiveDialog(cancelable, fullScreen, onDismiss)
        // A page covers the loading screen whole, and its sky can rest meanwhile.
        // Behind a card, what is under it goes soft, as behind Bubble's pop-ups.
        loadingSky.paused = fullScreen
        blurBehind(!fullScreen)

        // Behind a card, a scrim; behind a page, the page's own colour, which is
        // also what shows in the strip by the camera when the status bar is hidden.
        dialogScrim.setBackgroundColor(if (fullScreen) Look.current.bg else SCRIM)
        dialogScrim.visibility = View.VISIBLE
        dialogScrim.alpha = 0f
        dialogScrim.animate().alpha(1f).setDuration(200).start()
        dialogCard.alpha = 0f
        // A card can afford to spring in. A whole screen doing the same reads as
        // the app lurching, so it only rises.
        val from = if (fullScreen) 0.97f else 0.88f
        dialogCard.scaleX = from
        dialogCard.scaleY = from
        dialogCard.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(if (fullScreen) 300 else 260)
            .setInterpolator(if (fullScreen) DecelerateInterpolator() else OvershootInterpolator(1.6f))
            .start()

        // Tapping the card itself must not fall through to the scrim below it.
        dialogCard.setOnClickListener { }
        dialogScrim.setOnClickListener { if (cancelable) dismissDialog() }

        return view
    }

    /**
     * Lends the shared host the whole window, or takes it back.
     *
     * Full screen means no card at all: no margins, no rounded plate, no lift off
     * the surface — the inflated layout paints edge to edge and brings its own
     * background. Everything else keeps the plate, so the values it is given back
     * are the ones the layout started with rather than numbers repeated here.
     */
    private fun shapeCard(fullScreen: Boolean) {
        val params = dialogCard.layoutParams as FrameLayout.LayoutParams
        params.height = if (fullScreen) FrameLayout.LayoutParams.MATCH_PARENT
        else FrameLayout.LayoutParams.WRAP_CONTENT
        // A pop-up stays pop-up sized on a tablet, as Bubble's do.
        val max = (CARD_MAX_DP * resources.displayMetrics.density).toInt()
        params.width = if (!fullScreen && rootLayout.width - cardMarginStart - cardMarginEnd > max) max
        else FrameLayout.LayoutParams.MATCH_PARENT
        params.marginStart = if (fullScreen) 0 else cardMarginStart
        params.marginEnd   = if (fullScreen) 0 else cardMarginEnd
        dialogCard.layoutParams = params
        dialogCard.background    = if (fullScreen) null else Bubble.popup(this, Look.current)
        dialogCard.elevation     = if (fullScreen) 0f else cardElevation
        dialogCard.clipToOutline = !fullScreen
    }

    /**
     * Softens what is behind a card, the way the panel's pop-ups blur the page.
     * Android draws that from 12 on; before, the scrim alone has to do.
     */
    private fun blurBehind(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = 4f * resources.displayMetrics.density
        val effect = if (on) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
        webView.setRenderEffect(effect)
        loadingView.setRenderEffect(effect)
    }

    private fun dismissDialog() {
        val prev = activeDialog ?: return
        activeDialog = null
        loadingSky.paused = false
        blurBehind(false)
        dialogScrim.animate().alpha(0f).setDuration(180).withEndAction {
            dialogScrim.visibility = View.GONE
            dialogCard.removeAllViews()
        }.start()
        prev.onDismiss?.invoke()
    }

    private fun isDialogShowing(): Boolean = activeDialog != null

    // ── VPN connect flow ──────────────────────────────────────────────────────
    // REMOTE means exactly this, start to finish: reuse or renew a session
    // (silently, at most once a cold start needs to touch cv-auth at all),
    // bring the tunnel up, then the caller loads LOCAL_URL through it — same
    // page, same origin, same auth as standing on the home Wi-Fi.

    private suspend fun connectRemote(): Boolean {
        if (vpnManager.currentState() == Tunnel.State.UP) return true

        setSwitchStage(SwitchStage.KEY)
        step(R.drawable.ic_mdi_key_variant, "Checking access…")

        // OFFLINE is not one of the answers here: with nothing forcing it, an
        // unreachable cv-auth leaves us holding what we already had, and what we
        // already had is enough to try the tunnel with.
        var access = ensureSession()
        if (access == Access.NEEDS_CODE && !signInWithCode()) return false

        setSwitchStage(SwitchStage.LOCK_CLOSED)
        step(R.drawable.ic_mdi_shield_key_outline, "Requesting VPN permission…")
        if (!ensureVpnPermission()) {
            step(R.drawable.ic_mdi_cancel, "VPN permission denied")
            return false
        }

        if (!raiseTunnel()) {
            // The config we hold no longer carries traffic. Nearly always that
            // means it has moved on without us — a hub that forgot the peer, a
            // house on a new address — and a renewal is exactly what fetches the
            // replacement. Worth one forced try before anybody is sent to the bot
            // for a code they probably do not need.
            access = ensureSession(force = true)
            if (access == Access.NEEDS_CODE && !signInWithCode()) return false
            if (access == Access.OFFLINE || !raiseTunnel()) {
                step(R.drawable.ic_mdi_lan_disconnect, "Home is offline, retrying…")
                return false
            }
        }

        setSwitchStage(SwitchStage.LOCK_OPEN)
        step(R.drawable.ic_mdi_check_circle_outline, "Tunnel up, loading dashboard…")
        return true
    }

    /** What standing we have with cv-auth right now. */
    private enum class Access { READY, NEEDS_CODE, OFFLINE }

    /**
     * The session, renewed if it is old enough to be worth renewing.
     *
     * The tunnel does not need cv-auth to come up — the config it handed over
     * last time is all WireGuard uses, and cv-auth plays no part in a handshake.
     * Asking it on every single open therefore staked a perfectly good session on
     * a round trip made at the flakiest moment of the day: the one where you have
     * just walked out of the house and the phone is still arguing about which
     * network it is on. So a young session is simply used, and only [force] — the
     * tunnel having actually failed — insists on an answer.
     */
    private suspend fun ensureSession(force: Boolean = false): Access {
        if (!secureStore.hasPeer()) return Access.NEEDS_CODE
        if (!force && secureStore.sessionAgeMs() < RENEW_AFTER_MS) return Access.READY

        return when (val result = renewSession()) {
            is CvAuthApi.Result.Ok -> { applyVpnConfig(result.config); Access.READY }
            // cv-auth says this token is dead. It is the only thing that can say so.
            is CvAuthApi.Result.Rejected -> {
                step(R.drawable.ic_mdi_timer_sand_complete, "Session expired — sign in again")
                secureStore.clearSession()
                Access.NEEDS_CODE
            }
            // Silence says nothing about the session, so nothing is thrown away.
            // What we already hold is still the best thing we have.
            is CvAuthApi.Result.Unreachable -> if (force) Access.OFFLINE else Access.READY
        }
    }

    /** One renewal, retried past the blips a handover throws at it. */
    private suspend fun renewSession(): CvAuthApi.Result {
        val token = secureStore.refreshToken
            ?: return CvAuthApi.Result.Rejected(0, "No session")
        step(R.drawable.ic_mdi_refresh, "Renewing access…")
        var last: CvAuthApi.Result = CvAuthApi.Result.Unreachable("Network error")
        repeat(RENEW_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RENEW_RETRY_DELAY_MS)
            last = CvAuthApi.refresh(token)
            if (last !is CvAuthApi.Result.Unreachable) return last
        }
        return last
    }

    /**
     * Six digits from the bot, exchanged for a peer. Only ever reached when there
     * is nothing left to renew — the whole flow above exists to keep it that way.
     */
    private suspend fun signInWithCode(): Boolean {
        val code = awaitOtpCode()
        if (code == null) {
            step(R.drawable.ic_mdi_close_circle_outline, "Sign-in cancelled")
            return false
        }
        step(R.drawable.ic_mdi_dialpad, "Verifying code…")
        val pubkey = vpnManager.ensureKeyPair(secureStore)
        return when (val result = CvAuthApi.bootstrap(code, pubkey)) {
            is CvAuthApi.Result.Ok -> { applyVpnConfig(result.config); true }
            is CvAuthApi.Result.Failed -> {
                step(R.drawable.ic_mdi_cancel, "Sign-in failed: ${result.message}")
                toast(result.message)
                false
            }
        }
    }

    /**
     * Brings the tunnel up and proves something travels through it.
     *
     * setState(UP) only means the interface exists. A peer the hub has forgotten
     * looks exactly like a working tunnel from this side until something is asked
     * to cross it, and the app would sit there reloading a page that could never
     * arrive.
     */
    private suspend fun raiseTunnel(): Boolean {
        step(R.drawable.ic_mdi_lock_outline, "Establishing tunnel…")
        if (vpnManager.up(secureStore).isFailure) return false
        repeat(TUNNEL_PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(TUNNEL_PROBE_DELAY_MS)
            if (HaProbe.isReachable(haUrl(), TUNNEL_PROBE_TIMEOUT_MS)) return true
        }
        vpnManager.down()
        return false
    }

    /**
     * Keeps the remote session warm from inside the house.
     *
     * A session dies of silence, and the only thing that ever spoke to cv-auth
     * was the remote path — so a month of evenings at home was enough to be sent
     * to the bot on the first evening out. Nothing waits on this and nothing is
     * discarded if it fails: at worst the session stays exactly as old as it was.
     */
    private fun keepSessionWarm() {
        if (!secureStore.hasPeer()) return
        if (secureStore.sessionAgeMs() < RENEW_AFTER_MS) return
        val token = secureStore.refreshToken ?: return
        activityScope.launch {
            (CvAuthApi.refresh(token) as? CvAuthApi.Result.Ok)?.let { applyVpnConfig(it.config) }
        }
    }

    private fun applyVpnConfig(config: CvAuthApi.VpnConfig) = secureStore.save(config)

    private suspend fun ensureVpnPermission(): Boolean {
        val intent = vpnManager.permissionIntent() ?: return true
        return suspendCancellableCoroutine { cont ->
            vpnPermissionContinuation = cont
            vpnPermissionLauncher.launch(intent)
            cont.invokeOnCancellation { vpnPermissionContinuation = null }
        }
    }

    /**
     * The 6-digit Telegram OTP — null if the user cancels. The view carries the
     * whole presentation (brand, instructions, bot link); mirrors what
     * login.coyotes.vision's own page did before REMOTE stopped using a
     * WebView for this.
     */
    private suspend fun awaitOtpCode(): String? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            if (isFinishing || isDestroyed) { cont.resume(null); return@post }

            var resumed = false
            val view = showDialog(R.layout.dialog_otp, cancelable = true, fullScreen = true) {
                if (!resumed) { resumed = true; cont.resume(null) }
            }
            capWidth(view.findViewById(R.id.otpColumn), OTP_COLUMN_DP)
            val input = view.findViewById<EditText>(R.id.otpCodeInput)
            val status = view.findViewById<TextView>(R.id.otpStatus)
            view.findViewById<TextView>(R.id.otpOpenBot).setOnClickListener {
                openExternal(BuildConfig.OTP_BOT_URL)
            }
            view.findViewById<TextView>(R.id.otpBrand).setOnClickListener { openExternal(siteUrl()) }

            fun submit() {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.length != 6) {
                    status.text = getString(R.string.otp_incomplete)
                    status.visibility = View.VISIBLE
                    return
                }
                resumed = true
                cont.resume(code)
                dismissDialog()
            }

            view.findViewById<View>(R.id.otpSubmit).setOnClickListener { submit() }
            view.findViewById<View>(R.id.otpCancel).setOnClickListener {
                resumed = true
                cont.resume(null)
                dismissDialog()
            }
            input.setOnEditorActionListener { _, _, _ -> submit(); true }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    status.visibility = View.GONE
                    if (s?.length == 6) submit()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            })

            cont.invokeOnCancellation { mainHandler.post { dismissDialog() } }
        }
    }

    private fun openExternal(url: String) =
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun siteUrl(): String = "https://${BuildConfig.INSTANCE_DOMAIN}/"

    // ── First-run link hint ───────────────────────────────────────────────────
    // Android 12 stopped offering a chooser for unverified web links: they go
    // straight to the browser, silently, and nothing tells the user that this app
    // could have handled them. Until assetlinks.json is in place the only fix is a
    // manual tick in Settings, which nobody discovers on their own.
    //
    // The hint is asked for once per launch but answers itself first: if the links
    // already work it never appears, and the checkbox silences it for good.

    private fun maybeShowLinkHint() {
        if (isFinishing || isDestroyed) return
        if (prefs.getBoolean(KEY_LINK_HINT_DISMISSED, false)) return
        if (isDialogShowing()) return   // don't stack over the OTP/settings/update cards
        if (webLinksAlreadyHandled()) return

        val host = "*.${BuildConfig.INSTANCE_DOMAIN}"
        lateinit var dontShow: CheckBox
        val view = showDialog(R.layout.dialog_link_hint, cancelable = true) {
            // Read on dismiss, not per-button: the choice should stick whether
            // they opened settings, tapped Not now, or dismissed the card.
            if (dontShow.isChecked) {
                prefs.edit().putBoolean(KEY_LINK_HINT_DISMISSED, true).apply()
            }
        }
        dontShow = view.findViewById(R.id.linkHintDontShow)
        view.findViewById<TextView>(R.id.linkHintBody).text = getString(R.string.link_hint_body, host)
        view.findViewById<View>(R.id.linkHintOpenSettings).setOnClickListener {
            openLinkSettings()
            dismissDialog()
        }
        view.findViewById<View>(R.id.linkHintLater).setOnClickListener { dismissDialog() }
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

    private var refreshSettings: (() -> Unit)? = null

    private fun showAppSettings() {
        if (isFinishing || isDestroyed) return
        if (isDialogShowing()) return

        val view = showDialog(R.layout.dialog_app_settings, cancelable = true, fullScreen = true) { refreshSettings = null }
        capWidth(view.findViewById(R.id.settingsColumn), SETTINGS_COLUMN_DP)
        fun text(id: Int) = view.findViewById<TextView>(id)
        val statusSwitch = view.findViewById<BubbleSwitch>(R.id.settingsStatusBarSwitch)
        val navSwitch    = view.findViewById<BubbleSwitch>(R.id.settingsNavBarSwitch)
        val awakeSwitch  = view.findViewById<BubbleSwitch>(R.id.settingsAwakeSwitch)
        val homeSwitch   = view.findViewById<BubbleSwitch>(R.id.settingsHomeSwitch)

        text(R.id.settingsUpdateSubtitle).text = getString(R.string.settings_update_subtitle, BuildConfig.VERSION_NAME)

        // Forgetting takes two taps: the first says what it will cost, and only
        // for a few seconds.
        val forget = text(R.id.settingsRemoteForget)
        var forgetArmed = false

        // Everything here can change while the page is open — a look read off the
        // page, the charger, the home-app role changed in Settings — so one
        // function says it all again, and setChecked() never reports back.
        val refresh = {
            val look = Look.current
            text(R.id.settingsLookName).text = getString(R.string.settings_look_name, look.familyName, look.shadeName)
            text(R.id.settingsLookState).text =
                if (look.animate) getString(R.string.settings_look_state, look.styleName, look.fps)
                else getString(R.string.settings_look_state_off)
            text(R.id.settingsLookNote).setText(
                if (look.fromHouse) R.string.settings_look_note else R.string.settings_look_note_default)

            val hidden = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
            statusSwitch.setChecked(hidden)
            text(R.id.settingsStatusBarState).setText(
                if (hidden) R.string.settings_status_bar_on else R.string.settings_status_bar_off)

            val navHidden = prefs.getBoolean(KEY_HIDE_NAV_BAR, false)
            navSwitch.setChecked(navHidden)
            text(R.id.settingsNavBarState).setText(
                if (navHidden) R.string.settings_nav_bar_on else R.string.settings_nav_bar_off)

            val awake = prefs.getBoolean(KEY_AWAKE_CHARGING, false)
            awakeSwitch.setChecked(awake)
            text(R.id.settingsAwakeState).setText(when {
                awake && charging -> R.string.settings_awake_now
                awake             -> R.string.settings_awake_on
                else              -> R.string.settings_awake_off
            })

            val offered = LauncherMode.isOffered(this)
            val active  = LauncherMode.isCurrentHome(this)
            text(R.id.settingsHomeSubtitle).setText(when {
                active  -> R.string.settings_home_state_active
                offered -> R.string.settings_home_state_offered
                else    -> R.string.settings_home_state_off
            })
            homeSwitch.setChecked(offered)

            val target = currentTarget
            view.findViewById<ImageView>(R.id.settingsRouteIcon).setImageResource(
                if (target == HaTarget.REMOTE) R.drawable.ic_mdi_vpn else R.drawable.ic_mdi_home_outline)
            text(R.id.settingsRouteName).setText(when (target) {
                HaTarget.LOCAL  -> R.string.settings_route_local
                HaTarget.REMOTE -> R.string.settings_route_remote
                null            -> R.string.settings_route_none
            })
            text(R.id.settingsRouteState).text = endpointLabel()
            text(R.id.settingsRemoteState).text =
                if (forgetArmed) getString(R.string.settings_remote_forget_warning) else remoteAccessState()
            forget.visibility = if (secureStore.hasPeer()) View.VISIBLE else View.GONE
            forget.setText(if (forgetArmed) R.string.settings_remote_forget_confirm else R.string.settings_remote_forget)
            forget.tag = if (forgetArmed) "danger-on" else "danger"
            Bubble.paint(forget, Look.current)
        }
        refresh()
        refreshSettings = refresh

        statusSwitch.setOnCheckedChangeListener { hide ->
            prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR, hide).apply()
            applyBars()
            refresh()
        }
        navSwitch.setOnCheckedChangeListener { hide ->
            prefs.edit().putBoolean(KEY_HIDE_NAV_BAR, hide).apply()
            applyBars()
            refresh()
        }
        awakeSwitch.setOnCheckedChangeListener { on ->
            prefs.edit().putBoolean(KEY_AWAKE_CHARGING, on).apply()
            applyKeepAwake()
            refresh()
        }
        homeSwitch.setOnCheckedChangeListener { setHomeAppOffered(it) }

        val disarm = Runnable { forgetArmed = false; refresh() }
        forget.setOnClickListener {
            mainHandler.removeCallbacks(disarm)
            if (forgetArmed) {
                forgetArmed = false
                forgetRemoteAccess()
            } else {
                forgetArmed = true
                refresh()
                mainHandler.postDelayed(disarm, FORGET_ARMED_MS)
            }
        }

        // A row is as good as its switch: the whole card is the target.
        view.findViewById<View>(R.id.settingsStatusBarRow).setOnClickListener { statusSwitch.toggle() }
        view.findViewById<View>(R.id.settingsNavBarRow).setOnClickListener { navSwitch.toggle() }
        view.findViewById<View>(R.id.settingsAwakeRow).setOnClickListener { awakeSwitch.toggle() }
        // Except here: tapping the row once CVapp is offered reopens the picker.
        // That is the step between "CVapp can be a home app" and "CVapp is the
        // home app" — only the user makes that choice, and Android will not ask
        // on our behalf.
        view.findViewById<View>(R.id.settingsHomeRow).setOnClickListener {
            if (LauncherMode.isOffered(this)) openHomePicker() else homeSwitch.toggle()
        }
        // The look is chosen where the house keeps it: the CV Panel.
        view.findViewById<View>(R.id.settingsLookRow).setOnClickListener {
            dismissDialog()
            applyDeepLink(PANEL_APPEARANCE)
        }
        view.findViewById<View>(R.id.settingsLinksRow).setOnClickListener { openLinkSettings() }
        view.findViewById<View>(R.id.settingsUpdateRow).setOnClickListener {
            dismissDialog()      // the update prompt takes it from here
            checkForUpdate(force = true)
        }
        view.findViewById<View>(R.id.settingsClose).setOnClickListener { dismissDialog() }
        view.findViewById<View>(R.id.settingsSiteLink).setOnClickListener { openExternal(siteUrl()) }
        view.findViewById<View>(R.id.settingsGithubLink).setOnClickListener {
            openExternal("https://github.com/${BuildConfig.UPDATE_REPO}")
        }

        // The newest look and name, painted in when the page answers.
        readPage()
    }

    /**
     * Forgets this phone's remote access, from App settings. cv-auth cannot be
     * told; the session it holds lapses after a month unused, and the key that
     * could have used it is gone from here. Away from home that was the way in,
     * so the app goes straight back to asking for a code.
     */
    private fun forgetRemoteAccess() {
        activityScope.launch {
            vpnManager.down()
            secureStore.forget()
            toast(getString(R.string.settings_remote_forgotten))
            refreshSettings?.invoke()
            if (currentTarget == HaTarget.REMOTE) checkAvailabilityAndActOn(forceReload = true)
        }
    }

    /** How the remote session stands, for the Connection card. */
    private fun remoteAccessState(): String {
        if (!secureStore.hasPeer()) return getString(R.string.settings_remote_none)
        val age = secureStore.sessionAgeMs()
        if (age == Long.MAX_VALUE) return getString(R.string.settings_remote_unknown)
        return when (val days = (age / (24L * 60 * 60 * 1000)).toInt()) {
            0    -> getString(R.string.settings_remote_today)
            1    -> getString(R.string.settings_remote_yesterday)
            else -> getString(R.string.settings_remote_days, days)
        }
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
        if (isDialogShowing()) dismissDialog()
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
        if (isDialogShowing()) return

        val body = buildString {
            append(getString(R.string.update_available_body, BuildConfig.VERSION_NAME))
            if (release.sizeBytes > 0) {
                append(String.format(" %.1f MB.", release.sizeBytes / 1_048_576.0))
            }
            if (release.notes.isNotBlank()) append("\n\n").append(unwrap(release.notes))
        }

        val view = showDialog(R.layout.dialog_update_available, cancelable = true)
        view.findViewById<TextView>(R.id.updateTitle).text =
            getString(R.string.update_available_title, release.versionName)
        view.findViewById<TextView>(R.id.updateBody).text = body
        view.findViewById<View>(R.id.updateInstall).setOnClickListener { beginUpdate(release) }
        view.findViewById<View>(R.id.updateLater).setOnClickListener { dismissDialog() }
    }

    /**
     * A tag message is wrapped at 72 columns for git, and in a dialog those
     * breaks land in the middle of sentences. Paragraphs and list items keep
     * their own lines; the rest runs on as text.
     */
    private fun unwrap(notes: String): String =
        notes.trim().split(Regex("\n\\s*\n")).joinToString("\n\n") { paragraph ->
            paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }.fold("") { text, line ->
                when {
                    text.isEmpty() -> line
                    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> "$text\n$line"
                    else -> "$text $line"
                }
            }
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

        val view     = showDialog(R.layout.dialog_update_progress, cancelable = false)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgressBar)
        val label    = view.findViewById<TextView>(R.id.updateProgressLabel)
        view.findViewById<View>(R.id.updateProgressCancel).setOnClickListener {
            updateJob?.cancel()
            dismissDialog()
        }

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
            dismissDialog()
            if (apk == null) toast(getString(R.string.update_download_failed))
            else launchInstaller(apk)
        }
    }

    private fun promptInstallPermission() {
        val view = showDialog(R.layout.dialog_install_permission, cancelable = true) {
            // Backing out here (any way — button, outside tap, back) ends this
            // attempt, so a grant made later for some unrelated reason doesn't
            // start a download nobody asked for. The update itself stays
            // remembered and is offered again next launch.
            awaitingInstallPermission = false
        }
        view.findViewById<View>(R.id.installPermOpenSettings).setOnClickListener {
            runCatching { startActivity(Updater.unknownSourcesSettingsIntent(this)) }
            dismissDialog()
        }
        view.findViewById<View>(R.id.installPermLater).setOnClickListener { dismissDialog() }
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
        // A fresh attempt starts a fresh log; a switch or a retry while the
        // screen is already up is the same attempt continuing.
        if (!loadingVisible) {
            clearLog()
            loadingGreeting.text = greeting()
        }
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
        stopRoutePulse()
        hideRetryHint()
        loadingView.animate().alpha(0f).setDuration(350).withEndAction {
            loadingView.visibility = View.GONE
            loadingView.alpha = 1f
        }.start()
        webView.animate().alpha(1f).setDuration(350).start()
    }

    // ── The connect log ───────────────────────────────────────────────────────
    // What is happening now sits in the status card: the step's icon and words,
    // and under them the address being dialled. Each step before it moves up
    // into the log above the card, the newest nearest and the older ones
    // stepping back — on a slow connection the card alone would be the only
    // thing moving, and it would never say what had already worked.

    private var stepIcon = 0
    private var stepText: String? = null

    private fun step(icon: Int, text: String) {
        // The retry loop says "Reconnecting…" every few seconds, and a log that
        // repeats itself is a slower way of saying nothing.
        if (text == stepText) { repeatStep(); return }
        // A step coming round again (Home Assistant loads twice when it returns
        // you to the page you were on) moves down into the card rather than
        // appearing in the log a second time.
        for (i in loadingLog.childCount - 1 downTo 0) {
            if ((loadingLog.getChildAt(i) as? TextView)?.text?.toString() == text) loadingLog.removeViewAt(i)
        }
        stepText?.let { logLine(stepIcon, it) }
        stepIcon = icon
        stepText = text

        val dp = resources.displayMetrics.density
        statusIcon.setImageResource(icon)
        statusIcon.scaleX = 0.7f
        statusIcon.scaleY = 0.7f
        statusIcon.animate().scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
        statusName.text = text
        statusName.alpha = 0f
        statusName.translationY = 6f * dp
        statusName.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** A step that is over: one line in the log, with its icon. */
    private fun logLine(icon: Int, text: String) {
        val dp = resources.displayMetrics.density
        val line = TextView(this).apply {
            this.text = text
            tag = "muted"
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            compoundDrawablePadding = (6 * dp).toInt()
            ContextCompat.getDrawable(context, icon)?.mutate()?.let {
                it.setBounds(0, 0, (14 * dp).toInt(), (14 * dp).toInt())
                setCompoundDrawablesRelative(it, null, null, null)
            }
            alpha = 0f
            translationY = 10f * dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (3 * dp).toInt(); gravity = Gravity.CENTER_HORIZONTAL }
        }
        Bubble.paint(line, Look.current)
        loadingLog.addView(line)
        while (loadingLog.childCount > LOG_LINES) loadingLog.removeViewAt(0)
        // The newest reads clearest; everything above it recedes.
        for (index in 0 until loadingLog.childCount) {
            val fromEnd = loadingLog.childCount - 1 - index
            loadingLog.getChildAt(index).animate()
                .alpha(LOG_FADES.getOrElse(fromEnd) { 0.15f }).translationY(0f).setDuration(240).start()
        }
    }

    /** Said again: worth a flicker, not a new line. */
    private fun repeatStep() {
        statusName.animate().cancel()
        statusName.alpha = 0.5f
        statusName.translationY = 0f
        statusName.animate().alpha(1f).setDuration(280).start()
    }

    private fun clearLog() {
        loadingLog.removeAllViews()
        stepText = null
    }

    // ── Loading animation ─────────────────────────────────────────────────────
    // Things arrive in turn rather than all at once: the coyote, its name and
    // the greeting, the way in, then the log of getting there.

    private fun animateLoadingIn() {
        val dp = resources.displayMetrics.density
        loadingLogo.alpha = 0f;     loadingLogo.scaleX = 0.82f; loadingLogo.scaleY = 0.82f
        loadingTitle.alpha = 0f;    loadingTitle.translationY = 18f * dp
        loadingGreeting.alpha = 0f; loadingGreeting.translationY = 10f * dp
        routeRow.alpha = 0f;        routeRow.translationY = 10f * dp
        loadingLog.alpha = 0f
        statusCard.alpha = 0f;      statusCard.translationY = 14f * dp
        loadingSettings.alpha = 0f; loadingBrand.alpha = 0f; loadingVersion.alpha = 0f

        loadingLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(450).setStartDelay(80).withEndAction { startBreathing() }.start()
        loadingTitle.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(260).start()
        loadingGreeting.animate().alpha(1f).translationY(0f).setDuration(320).setStartDelay(380).start()
        routeRow.animate().alpha(1f).translationY(0f).setDuration(300).setStartDelay(480).start()
        loadingLog.animate().alpha(1f).setDuration(280).setStartDelay(560).start()
        statusCard.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(560)
            .setInterpolator(DecelerateInterpolator()).start()
        loadingSettings.animate().alpha(1f).setDuration(300).setStartDelay(720).start()
        loadingBrand.animate().alpha(1f).setDuration(300).setStartDelay(720).start()
        loadingVersion.animate().alpha(1f).setDuration(300).setStartDelay(720).start()
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

    // ── Which way in: Local or Remote ─────────────────────────────────────────
    // Two of Bubble's sub-buttons under the greeting. While the app is still
    // finding its way they breathe together; the side that wins fills with the
    // person's colour and pops, and the other steps back. The remote one also
    // shows the tunnel coming up: key, closed lock, open lock.

    private var routeChosen: HaTarget? = null
    private var routeRemoteIcon = R.drawable.ic_mdi_cloud_outline
    private var routePulse: ObjectAnimator? = null

    private fun showSwitch(pending: Boolean) {
        routeRemoteIcon = R.drawable.ic_mdi_cloud_outline
        drawRoute(null)
        if (pending) step(R.drawable.ic_mdi_compass_outline, "Finding connection…")
        else step(R.drawable.ic_mdi_swap_horizontal, "Switching…")
    }

    /** Key → closed lock → open lock, driven by connectRemote()'s own stages — see there. */
    private enum class SwitchStage(val icon: Int) {
        KEY(R.drawable.ic_mdi_key_variant),
        LOCK_CLOSED(R.drawable.ic_mdi_lock_outline),
        LOCK_OPEN(R.drawable.ic_mdi_lock_open_variant_outline),
    }

    private fun setSwitchStage(stage: SwitchStage) {
        routeRemoteIcon = stage.icon
        drawRoute(routeChosen)
        pop(routeRemote)
    }

    private fun revealSwitch(to: HaTarget) {
        drawRoute(to)
        pop(if (to == HaTarget.LOCAL) routeLocal else routeRemote)
        if (to == HaTarget.LOCAL) step(R.drawable.ic_mdi_home_outline, "Local network")
        else step(R.drawable.ic_mdi_cloud_outline, "Remote connection")
    }

    private fun drawRoute(chosen: HaTarget?) {
        routeChosen = chosen
        val look = Look.current
        fun pill(view: TextView, on: Boolean, icon: Int) {
            view.tag = if (on) "sub-on" else "sub"
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            Bubble.paint(view, look)
            view.animate().alpha(if (chosen == null || on) 1f else 0.4f).setDuration(240).start()
        }
        pill(routeLocal, chosen == HaTarget.LOCAL, R.drawable.ic_mdi_home_outline)
        pill(routeRemote, chosen == HaTarget.REMOTE, routeRemoteIcon)
        if (chosen == null) startRoutePulse() else stopRoutePulse()
    }

    private fun startRoutePulse() {
        if (routePulse?.isRunning == true) return
        routePulse = ObjectAnimator.ofPropertyValuesHolder(
            routeRow,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 0.94f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 0.94f)
        ).apply {
            duration = 650; repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopRoutePulse() {
        routePulse?.cancel()
        routePulse = null
        routeRow.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun pop(view: View) {
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate().scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(OvershootInterpolator(3f)).start()
    }

    // ── Connection trouble (replaces any browser-shown error) ────────────────
    // A couple of blips are retried silently (e.g. a DNS hiccup right after a
    // network handoff). Only once failures persist do we surface the retry hint —
    // the loading screen itself never changes into a scary "error screen".

    private fun enterTrouble(code: Int, keepStatus: Boolean = false) {
        consecutiveFailures++
        if (!loadingVisible) showLoading()
        // connectRemote() already leaves a specific status behind (which step
        // failed, or that the user cancelled) — the generic fallback would
        // only make VPN failures less informative than every other error path.
        if (!keepStatus) step(troubleIcon(code), troubleMessage(code))

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

    private fun troubleIcon(code: Int): Int = when (code) {
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, ERROR_SSL -> R.drawable.ic_mdi_alert_outline
        WebViewClient.ERROR_AUTHENTICATION                  -> R.drawable.ic_mdi_account_lock_outline
        else                                                -> R.drawable.ic_mdi_refresh
    }

    private fun troubleMessage(code: Int): String = when (code) {
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, ERROR_SSL -> "Security warning — check your connection"
        WebViewClient.ERROR_AUTHENTICATION                  -> "Sign-in required"
        else                                                -> "Reconnecting…"
    }

    private fun showRetryHint() {
        loadingRetryHint.isClickable = true
        loadingRetryHint.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideRetryHint() {
        loadingRetryHint.isClickable = false
        loadingRetryHint.animate().alpha(0f).setDuration(200).start()
    }

    // ── Handing over to Home Assistant ───────────────────────────────────────
    // A loaded page is not a drawn dashboard: Home Assistant's frontend boots
    // after it, behind a launch screen of its own (its logo, "Loading…"). Lifting
    // ours at onPageFinished showed that one in between, a second blue screen on
    // the way in. So ours stays until Home Assistant takes its launch screen
    // down, and the app goes from its own screen straight to the dashboard.

    private var frontendChecks = 0

    private val frontendCheck = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(FRONTEND_READY_JS) { ready ->
                if (!loadingVisible) return@evaluateJavascript
                if (ready == "true" || ++frontendChecks >= FRONTEND_CHECKS) hideLoading()
                else mainHandler.postDelayed(this, FRONTEND_CHECK_MS)
            }
        }
    }

    private fun awaitFrontend(url: String) {
        mainHandler.removeCallbacks(frontendCheck)
        frontendChecks = 0
        if (loadingVisible && isHaAppHost(url)) step(R.drawable.ic_mdi_home_assistant, "Starting Home Assistant…")
        mainHandler.postDelayed(frontendCheck, FRONTEND_CHECK_MS)
    }

    // ── The person's look and name ────────────────────────────────────────────
    // The app's own screens wear the look Home Assistant shows this person (see
    // Look.kt), and greet them by the name it knows them by. Both are read off
    // the page the app has loaded: a moment after it lands, once Home Assistant
    // has answered the page's own questions, and again whenever the app goes to
    // the background, which catches a colour changed in the panel meanwhile.

    private val readPageRunnable = Runnable { readPage() }

    private fun readPageSoon() {
        mainHandler.removeCallbacks(readPageRunnable)
        READ_PAGE_AFTER_MS.forEach { mainHandler.postDelayed(readPageRunnable, it) }
    }

    private fun readPage() {
        val url = webView.url ?: return
        if (!isHaAppHost(url)) return
        webView.evaluateJavascript(PAGE_JS) { result -> onPageRead(result) }
    }

    private fun onPageRead(result: String?) {
        if (isFinishing || isDestroyed) return
        val answer = runCatching { JSONObject(result ?: return) }.getOrNull() ?: return
        when {
            answer.optBoolean("login") -> prefs.edit().remove(KEY_USER_NAME).apply()
            !answer.isNull("user") -> answer.optString("user").trim().takeIf { it.isNotEmpty() }
                ?.let { prefs.edit().putString(KEY_USER_NAME, it).apply() }
        }
        if (loadingVisible) loadingGreeting.text = greeting()
        val look = Look.fromPage(this, answer.optJSONObject("look")) ?: return
        Log.i(TAG, "look: ${look.familyName} ${look.shadeName}, ${look.styleName} ${look.fps} fps, " +
            "${if (look.animate) "animated" else "still"}, ${look.scheme}")
        applyLook(look)
    }

    /** Puts [look] on everything the app draws itself. */
    private fun applyLook(look: Look) {
        window.setBackgroundDrawable(ColorDrawable(look.bg))
        Bubble.paint(rootLayout, look)
        when (activeDialog?.fullScreen) {
            false -> dialogCard.background = Bubble.popup(this, look)
            true  -> dialogScrim.setBackgroundColor(look.bg)
            null  -> Unit
        }
        applySystemBars(look)
        refreshSettings?.invoke()
    }

    /** Good evening, and the person's name once a page has said it. */
    private fun greeting(): String {
        val part = getString(when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11  -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..22 -> R.string.greeting_evening
            else      -> R.string.greeting_night
        })
        val name = prefs.getString(KEY_USER_NAME, null)
        return if (name.isNullOrBlank()) part else getString(R.string.greeting_named, part, name)
    }

    /** Keeps a column to [maxDp] on a wide screen, centred, as the panel keeps its sections. */
    private fun capWidth(view: View, maxDp: Int) {
        val max = (maxDp * resources.displayMetrics.density).toInt()
        val parent = view.parent as? View ?: return
        parent.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val want = if (right - left > max) max else ViewGroup.LayoutParams.MATCH_PARENT
            if (view.layoutParams.width != want) view.post {
                view.layoutParams = view.layoutParams.apply { width = want }
            }
        }
    }

    // ── Screen: the status bar, and staying awake on the charger ─────────────

    /**
     * The status bar and the navigation bar, hidden or shown as App settings say;
     * a swipe in from that edge shows a hidden one for a moment. Either one
     * hidden gives its height to the page (see the insets listener).
     */
    private fun applyBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fun show(type: Int, hidden: Boolean) = if (hidden) controller.hide(type) else controller.show(type)
        show(WindowInsetsCompat.Type.statusBars(), prefs.getBoolean(KEY_HIDE_STATUS_BAR, false))
        show(WindowInsetsCompat.Type.navigationBars(), prefs.getBoolean(KEY_HIDE_NAV_BAR, false))
    }

    /** The bars' icons light over a dark look and dark over a light one. */
    private fun applySystemBars(look: Look) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !look.dark
        controller.isAppearanceLightNavigationBars = !look.dark
        // Three-button navigation would get a translucent plate of the system's
        // choosing; the look's own background is behind it already.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }

    private var charging = false

    /** The charger, while the app is in front (registered in onStart, dropped in onStop). */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            applyKeepAwake()
            refreshSettings?.invoke()
        }
    }

    /** On a charger, with the setting on, the screen does not go dark by itself. */
    private fun applyKeepAwake() {
        if (prefs.getBoolean(KEY_AWAKE_CHARGING, false) && charging)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── File uploads ──────────────────────────────────────────────────────────
    // A page's "choose a file" does nothing in a WebView until the app answers
    // it, and the CV Panel asks for several: voice clips for the pools, car trips
    // from a CSV, pictures in Home Assistant itself. Android's picker answers.

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var filePicker: ActivityResultLauncher<Intent>

    /** Set while the picker is up, so onStop leaves the tunnel and the page alone. */
    private var pickingFiles = false

    private fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // One at a time: an earlier chooser nobody answered is answered "nothing".
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return runCatching {
            pickingFiles = true
            filePicker.launch(fileIntent(params))
            true
        }.getOrElse {
            pickingFiles = false
            fileCallback = null
            false
        }
    }

    private fun onFilesChosen(result: ActivityResult) {
        pickingFiles = false
        val callback = fileCallback ?: return
        fileCallback = null
        val data = result.data
        val clip = data?.clipData
        callback.onReceiveValue(when {
            result.resultCode != RESULT_OK || data == null -> null
            clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        })
    }

    /**
     * Android's picker for what the page accepts. MIME types filter it. A file
     * extension (".csv") has no type Android can be trusted with — the same file
     * is text/csv in one app and something else in the next — so a page that
     * names extensions is offered every file, and checks the one chosen itself,
     * as it does in a browser.
     */
    private fun fileIntent(params: WebChromeClient.FileChooserParams): Intent {
        val accepted = params.acceptTypes.flatMap { it.split(',') }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val types = accepted.filter { '/' in it }.distinct()
        val anyFile = accepted.isEmpty() || accepted.any { it.startsWith(".") } || "*/*" in types
        return Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            when {
                anyFile         -> type = "*/*"
                types.size == 1 -> type = types[0]
                else            -> { type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray()) }
            }
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    // ── Files and full screen for the chat ────────────────────────────────────

    /**
     * A file from the house into Downloads, through Android's own downloader:
     * a clip of a few hundred megabytes gets the system's progress and its
     * "done" notification. Only the house's own addresses; the link is signed
     * by the house, so it needs no session.
     */
    private fun saveFile(url: String, name: String, mime: String) {
        val scheme = Uri.parse(url).scheme.orEmpty()
        if (!isHaAppHost(url) || (scheme != "https" && scheme != "http")) return
        // Before Android 10 a public folder needs a storage permission the app does not ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { openExternal(url); return }
        val file = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
            .ifBlank { URLUtil.guessFileName(url, null, mime) }
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(file)
            if (mime.isNotBlank()) setMimeType(mime)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file)
        }
        runCatching { (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req) }
            .onFailure { Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show() }
    }

    private var fullView: View? = null
    private var fullDone: WebChromeClient.CustomViewCallback? = null

    private fun showFull(view: View, done: WebChromeClient.CustomViewCallback) {
        if (fullView != null) { done.onCustomViewHidden(); return }
        fullView = view
        fullDone = done
        (window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Out of full screen: the page left it, or Back did (then the page is told). */
    private fun hideFull(fromPage: Boolean = false) {
        val view = fullView ?: return
        fullView = null
        (window.decorView as ViewGroup).removeView(view)
        applyBars()
        val done = fullDone
        fullDone = null
        if (!fromPage) done?.onCustomViewHidden()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        // A system AlertDialog used to swallow Back on its own; a plain view
        // in our own hierarchy doesn't, so this card needs its own handling —
        // dismiss if the card allows it, otherwise swallow the press entirely
        // (matches setCancelable(false) on the dialogs that used to set it).
        activeDialog?.let { if (it.cancelable) dismissDialog(); return }
        // Full screen first, as a video player leaves it.
        if (fullView != null) { hideFull(); return }
        // Home Assistant's side menu, slid over the page on a phone, keeps no
        // history of its own: Back would leave the page under it instead of
        // closing it, as Android's own drawers do. The page is asked first.
        webView.evaluateJavascript(CLOSE_DRAWER_JS) { closed ->
            if (closed == "true") return@evaluateJavascript
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
}
