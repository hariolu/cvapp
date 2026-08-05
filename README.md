<div align="center">

<img src="design/logo.png" alt="" width="180">

# CVapp

**The Android client for [Coyotes Vision](https://coyotes.vision)**

</div>

## Why this code is public

So you can check it.

CVapp asks to be installed from outside any app store, and it asks for permission
to install its own updates. Those are reasonable things to be suspicious of. The
source is here so that suspicion has somewhere to go: read it, build it yourself,
compare it against the release you were given.

There is no analytics, no crash reporting, no advertising identifier, and no
telemetry of any kind. The app has exactly three dependencies and none of them
belong to Google:

```groovy
implementation 'androidx.appcompat:appcompat:1.7.0'
implementation 'androidx.core:core-ktx:1.13.1'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
```

It talks to your Home Assistant instance and to this repository's releases page.
Nowhere else. Two permissions, both listed in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml): network access, and
installing updates.

For what Coyotes Vision *is*, and everything that is not about this APK, see
**[coyotes.vision](https://coyotes.vision)**.

## What the app does

It is not a bookmark to a web page. It knows about two addresses for the same
home — the one on your local network and the one behind the remote login — and
keeps choosing the right one for you.

**Finds your home by itself.** On every network change it probes the local
address with a 700 ms timeout. On Wi-Fi with the instance answering it connects
directly; otherwise it goes through the remote portal. Walk out of the door
mid-session and the connection follows you.

**Opens links where they belong.** A link from a notification, a bot message, a
QR code or a home-screen shortcut opens the app on that exact page.

The rule that makes it work: **a link carries a path, never an address.** Whoever
sent it cannot know which side of your front door you are standing on, so they do
not get a vote — the app resolves that itself, and anything address-shaped in the
link is discarded. Links pointing outside Coyotes Vision are refused.

**Stays calm on a bad connection.** Network errors, TLS failures and browser
crashes are absorbed into the loading screen, which keeps retrying quietly
instead of dropping you on an error page. The page you were reading is
remembered and restored.

## Install

Download the latest `.apk` from [Releases](../../releases) and open it.

Requires Android 8.0 or newer.

Android will warn you that the app comes from an unrecognised source, and Play
Protect may add a warning of its own. That is what it looks like when software is
not published through Google — which is the point, and why the source is here.

**After that it updates itself.** It checks this repository on start, at most once
every six hours, and offers the new version with its release notes.

Every release is signed with the same key, and Android installs an update only
when the signature matches. An APK from anywhere but this Releases page will
refuse to install over an existing CVapp.

## Build it yourself

```bash
git clone https://github.com/hariolu/cvapp.git
cd cvapp
./gradlew assembleDebug
```

The APK appears in `app/build/outputs/apk/debug/`. JDK 17 is required; the runtime
bundled with Android Studio works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
```

Everything configurable sits at the top of
[`app/build.gradle`](app/build.gradle). No home is named anywhere in this
repository, and the app does not need one — each home is its own subdomain, and
the app matches them by suffix.

The logo is one vector, [`design/cv_logo_yote.svg`](design/cv_logo_yote.svg).
`design/svg2vd.ps1` turns it into the `VectorDrawable` the app draws, and
`design/render-brand.ps1` renders the PNGs used here. There is one copy of the
artwork; everything else is generated from it.

## Notes for anyone maintaining this

- [docs/RELEASING.md](docs/RELEASING.md) — cutting a release, and the two mistakes
  that break updates for everyone.
- [docs/NO-GOOGLE.md](docs/NO-GOOGLE.md) — why this stays off the Play Store and
  off developer verification, and what that costs.

---

By **Rio Fenris** for [coyotes.vision](https://coyotes.vision).
