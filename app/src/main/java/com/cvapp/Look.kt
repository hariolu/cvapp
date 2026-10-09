package com.cvapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.json.JSONObject

/**
 * The person's look: the colour and the background they picked in the CV Panel
 * (Settings → Appearance), as Home Assistant draws it for them right now.
 *
 * The app works none of it out itself. It asks the page it has loaded ([READ_JS])
 * for the colours the engine has put on that page, and keeps the answer, so its
 * own screens — loading, settings, sign-in — open in the person's colours before
 * the house has even answered, and follow whatever the engine decides to draw:
 * another colour, another background, or a light mode. A house whose engine has
 * no look (older than 1.4.0) never answers, and the app stays on the engine's
 * default: Amber, Classic, Constellation.
 *
 * Home Assistant can draw one person differently by day and by night (its theme
 * mode "Auto" follows the phone). So a look is kept per mode, and the app opens
 * with the one for the mode the phone is in when the person's Home Assistant
 * follows it.
 */
class Look private constructor(private val payload: JSONObject, val fromHouse: Boolean) {

    val familyName: String
    val shadeName: String
    val styleName: String
    /** One of BackdropView's styles: constellation, bubbles, lines, squares, stars, grid. */
    val style: String
    val fps: Int
    /** False: the person switched the background off. */
    val animate: Boolean
    /** The background's colours, as {r, g, b}. */
    val dots: Array<IntArray>
    /** "dark" or "light": the mode Home Assistant was drawing in when it told us. */
    val scheme: String

    // The engine's --cv-* tokens this app draws with (cv-look.js, tokens()).
    val bg: Int
    val text: Int
    val text2: Int
    val text3: Int
    val accent: Int
    val accentSoft: Int
    val accent2Line: Int
    /** A filled button's colour: the accent, lifted where Bubble Card would put dark icons on it. */
    val bubbleAccent: Int
    val onBubbleAccent: Int
    val card: Int
    val well: Int
    val line: Int
    val popup: Int
    val scrim: Int

    /** A dark background: light status bar icons over it, and the other way round. */
    val dark: Boolean

    init {
        val look = payload.getJSONObject("look")
        val names = payload.optJSONObject("names") ?: JSONObject()
        val tokens = payload.optJSONObject("tokens") ?: JSONObject()
        // A token the page did not give, or gave in a form this app cannot read,
        // is the default's: one odd value must not cost the whole look.
        fun token(name: String, fallback: String) =
            runCatching { parseColour(tokens.getString("--cv-$name")) }.getOrElse { parseColour(fallback) }

        style = look.optString("style", "constellation")
        fps = if (look.optInt("fps", 30) == 60) 60 else 30
        animate = look.optBoolean("animate", true)
        val family = look.optString("family", "amber")
        familyName = names.optString("family", family.replaceFirstChar { it.uppercase() })
        shadeName = names.optString("shade", look.optString("shade", "classic").replaceFirstChar { it.uppercase() })
        styleName = names.optString("style", style.replaceFirstChar { it.uppercase() })
        scheme = if (payload.optString("scheme") == "light") "light" else "dark"

        val list = payload.optJSONArray("dots")
        dots = runCatching {
            Array(list!!.length()) { i ->
                val c = list.getJSONArray(i)
                intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2))
            }.also { require(it.isNotEmpty()) }
        }.getOrElse { DEFAULT_DOTS }

        bg = token("bg", "#100e0e")
        text = token("text", "#e4e0e1")
        text2 = token("text-2", "#a29d9e")
        text3 = token("text-3", "#666263")
        accent = token("accent", "#b87848")
        accentSoft = token("accent-soft", "rgba(184, 120, 72, 0.14)")
        accent2Line = token("accent-2-line", "rgba(164, 137, 204, 0.45)")
        bubbleAccent = token("bubble-accent", "#b87848")
        onBubbleAccent = token("on-bubble-accent", "#ffffff")
        card = token("card", "rgba(27, 25, 25, 0.86)")
        well = token("well", "rgba(231, 227, 228, 0.06)")
        line = token("line", "rgba(231, 227, 228, 0.085)")
        popup = token("popup", "rgba(21, 19, 20, 0.94)")
        scrim = token("scrim", "rgba(2, 2, 2, 0.72)")
        dark = luminance(bg) < 0.4f
    }

    /** The page's answer as it came: two looks are the same when the page said the same. */
    private val signature: String = payload.toString()

    companion object {
        private const val PREF = "cv_look"
        private const val PREF_FOLLOWS = "cv_look_follows_system"
        private const val PREF_SCHEME = "cv_look_scheme"

        private val TOKENS = listOf("bg", "text", "text-2", "text-3", "accent", "accent-soft", "accent-2-line",
            "bubble-accent", "on-bubble-accent", "card", "well", "line", "popup", "scrim")

        /**
         * A JavaScript expression: what the person's Home Assistant page looks like.
         *
         * The colours are the ones the engine has *applied* to the page (cv-look-ha.js
         * sets the --cv-* tokens on the document), not worked out again here: what
         * the engine decides for a mode or a theme, the app takes as it is. The
         * choice itself (style, frame rate, background on or off) is the one the
         * engine keeps in localStorage once Home Assistant has told it. A page
         * without the engine's look answers null, and the app keeps what it had.
         * Nothing here changes the page.
         */
        val READ_JS = """(function () {
  var L = window.CVLook, saved = null;
  if (!L || !L.normalize || !L.paletteColours) return null;
  try { saved = JSON.parse(localStorage.getItem('cv-look')); } catch (e) { return null; }
  if (!saved) return null;
  var look = L.normalize(saved);
  var name = function (list, id) { var x = (list || []).find(function (i) { return i.id === id; }); return x ? x.name : id; };
  var css = getComputedStyle(document.documentElement);
  var made = L.tokens ? L.tokens(look.family, look.shade) : {};
  var tokens = {};
  ${TOKENS.joinToString(", ", "[", "]") { "'$it'" }}.forEach(function (k) {
    tokens['--cv-' + k] = css.getPropertyValue('--cv-' + k).trim() || made['--cv-' + k];
  });
  var ha = document.querySelector('home-assistant'), hass = ha && ha.hass;
  var themes = hass && hass.themes, chosen = hass && hass.selectedTheme;
  return {
    look: look,
    names: { family: name(L.FAMILIES, look.family), shade: name(L.SHADES, look.shade),
             style: (L.STYLES[look.style] || {}).name || look.style },
    tokens: tokens,
    dots: L.paletteColours(look.palette, look.family, look.shade),
    scheme: themes && themes.darkMode === false ? 'light' : 'dark',
    follows_system: !chosen || chosen.dark === undefined || chosen.dark === null
  };
})()"""

        private val DEFAULT_DOTS = arrayOf(
            intArrayOf(214, 147, 99), intArrayOf(151, 125, 191), intArrayOf(218, 175, 125),
            intArrayOf(180, 106, 77), intArrayOf(185, 167, 215), intArrayOf(196, 137, 95))

        /** cv-look.js's DEFAULT (Amber, Classic, Constellation); its colours are the token fallbacks above. */
        val DEFAULT: Look = Look(JSONObject(
            """{"look":{"family":"amber","shade":"classic","style":"constellation","fps":30,"animate":true},
               "names":{"family":"Amber","shade":"Classic","style":"Constellation"}}"""), fromHouse = false)

        /** The look the app's screens use right now. */
        @Volatile var current: Look = DEFAULT
            private set

        /**
         * The look to open with: the last one a house gave this phone, for the
         * mode the phone is in if that person's Home Assistant follows it.
         */
        fun load(context: Context): Look {
            val prefs = prefs(context)
            val follows = prefs.getBoolean(PREF_FOLLOWS, true)
            val scheme = if (follows) systemScheme(context) else prefs.getString(PREF_SCHEME, "dark") ?: "dark"
            val other = if (scheme == "dark") "light" else "dark"
            val saved = prefs.getString("$PREF.$scheme", null) ?: prefs.getString("$PREF.$other", null)
            current = saved?.let { runCatching { Look(JSONObject(it), fromHouse = true) }.getOrNull() } ?: DEFAULT
            return current
        }

        /**
         * What [READ_JS] found on the page, parsed. A new look is kept for its mode
         * and becomes [current]; the result is null when nothing changed, so the
         * caller repaints only when it must.
         */
        fun fromPage(context: Context, answer: JSONObject?): Look? {
            if (answer == null) return null
            val look = runCatching { Look(answer, fromHouse = true) }.getOrNull() ?: return null
            prefs(context).edit()
                .putString("$PREF.${look.scheme}", look.payload.toString())
                .putString(PREF_SCHEME, look.scheme)
                .putBoolean(PREF_FOLLOWS, answer.optBoolean("follows_system", true))
                .apply()
            if (current.fromHouse && look.signature == current.signature) return null
            current = look
            return look
        }

        private fun systemScheme(context: Context): String =
            if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_NO) "light" else "dark"

        private fun prefs(context: Context) =
            context.getSharedPreferences("cvapp_prefs", Context.MODE_PRIVATE)

        /**
         * A CSS colour as the engine writes them: "#rgb", "#rrggbb", "#rrggbbaa",
         * or "rgb()"/"rgba()" with commas or spaces and an alpha as a fraction or
         * a percentage. Throws on anything else.
         */
        fun parseColour(value: String): Int {
            val v = value.trim().lowercase()
            if (v.startsWith("#")) {
                val hex = v.drop(1)
                val full = when (hex.length) {
                    3, 4 -> hex.map { "$it$it" }.joinToString("")
                    6, 8 -> hex
                    else -> throw IllegalArgumentException(value)
                }
                val rgb = full.substring(0, 6).toLong(16).toInt()
                val alpha = if (full.length == 8) full.substring(6, 8).toInt(16) else 255
                return (alpha shl 24) or rgb
            }
            require(v.startsWith("rgb")) { value }
            val parts = v.substringAfter('(').substringBefore(')')
                .split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
            fun channel(s: String) = if (s.endsWith("%")) s.dropLast(1).toFloat() * 2.55f else s.toFloat()
            val alpha = parts.getOrNull(3)?.let { if (it.endsWith("%")) it.dropLast(1).toFloat() / 100 else it.toFloat() } ?: 1f
            return Color.argb((alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt(),
                channel(parts[0]).toInt().coerceIn(0, 255), channel(parts[1]).toInt().coerceIn(0, 255),
                channel(parts[2]).toInt().coerceIn(0, 255))
        }

        /** [colour] with its alpha replaced: [alpha] 0..1. */
        fun withAlpha(colour: Int, alpha: Float): Int =
            (colour and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 24)

        /** Relative luminance, 0 (black) to 1 (white). */
        fun luminance(colour: Int): Float {
            fun lin(c: Int): Float {
                val s = c / 255f
                return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * lin(Color.red(colour)) + 0.7152f * lin(Color.green(colour)) + 0.0722f * lin(Color.blue(colour))
        }
    }
}
