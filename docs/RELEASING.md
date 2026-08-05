# Releasing CVapp

How a new build reaches phones that already have the app.

CVapp is not in any store. It ships from GitHub Releases, and the app checks
that same place for updates on its own. So a release is one thing: **a signed
APK attached to a GitHub release whose tag matches the version in the code.**
Everything below exists to make that one thing hard to get wrong.

---

## The short version

```bash
# 1. Bump both numbers in app/build.gradle
#      versionCode 9        <- must increase
#      versionName "1.8"    <- what users see, what the updater compares

git add app/build.gradle
git commit -m "Bump to 1.8"

# 2. Tag it. Annotated, and the message becomes the release notes users read
#    in the update dialog.
git tag -a v1.8 -m "Adds the in-app updater.
Fixes the Household privacy card on hash navigation."

# 3. Push both
git push origin main
git push origin v1.8
```

That's it. The `Release` workflow builds, signs, verifies and publishes. Within
about two minutes the release is live and phones will offer the update on their
next check.

**Tag messages are user-facing.** The annotated tag's message becomes the release
notes, which is the exact text shown in the in-app update dialog. Write it for
whoever taps "Update", not for the commit log.

> A lightweight tag (`git tag v1.11` with no `-m`) has no message, and the
> workflow falls back to generated notes rather than publishing the commit
> message. It has to check for this: `actions/checkout` fetches tags and then
> clobbers the one it is building — its second fetch maps the *commit SHA* onto
> `refs/tags/<tag>`, turning an annotated tag into a lightweight one, after which
> `%(contents)` quietly returns the commit message instead. v1.10 shipped raw
> commit text into the update dialog because of this. The workflow now re-fetches
> the tag ref and checks `git cat-file -t` before trusting the message.

Watch it run:

```bash
gh run watch
gh release view v1.8
```

---

## The rule you cannot break

**`versionCode` must increase on every release.**

`versionName` is a label. `versionCode` is the integer Android actually compares,
and it refuses to install an APK whose code is not higher than what's installed.
Forget the bump and the updater will cheerfully download 12 MB, hand it to the
installer, and the install will fail — for every user, with no obvious cause.

CI checks this against the previous tag and fails the release rather than
publishing a build nobody can install. It also checks that the tag matches
`versionName`, because the updater compares `versionName`: tag `v1.8` on a build
that still says `1.7` produces an update prompt that reappears forever, since
installing it never changes what the app reports.

---

## One-time setup

### The key

The release key lives at `~/.android-keys/cvapp-release.jks` — deliberately
outside the repo, so no ignore-rule mistake can commit it. Local builds read it
through `keystore.properties` in the project root, which is gitignored.

> **Back up the keystore and its password somewhere you will still have in five
> years.** There is no Play App Signing here and no recovery path. Lose the key
> and you can never update any installed copy of CVapp again — every user would
> have to uninstall (losing their Home Assistant session) and reinstall. Treat
> it like the only copy of a house key, because that is what it is.

Current certificate, for reference. The DN carries the account name the key was
generated under and cannot be changed without a new key, which is impossible now
that releases are published:

```
DN      CN=hariolu, OU=CVapp, O=Coyotes Vision
SHA-256 64:DB:CB:CD:5C:23:9C:B5:EA:A1:D4:A0:C7:A1:B7:55:B0:48:41:92:7B:68:7F:EB:2F:71:DC:E6:5F:D5:81:93
SHA-1   7E:9C:DA:8A:BD:14:64:4F:55:5A:F3:E0:F4:CF:74:49:EA:8C:FC:BF
Expires 2053-12-20
```

The SHA-256 is also what an `assetlinks.json` needs if App Links verification is
set up later, so that manual "Open by default" approvals stop being necessary.

### Repository secrets

CI needs the key. Encode it and set four secrets:

```bash
# base64 of the keystore, single line
base64 -w0 ~/.android-keys/cvapp-release.jks > /tmp/ks.b64

gh secret set KEYSTORE_BASE64  < /tmp/ks.b64
gh secret set KEYSTORE_PASSWORD          # paste the password
gh secret set KEY_ALIAS                  # cvapp
gh secret set KEY_PASSWORD               # same as KEYSTORE_PASSWORD

rm /tmp/ks.b64
```

On Windows PowerShell, `base64 -w0` does not exist:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android-keys\cvapp-release.jks")) |
    Set-Content -NoNewline "$env:TEMP\ks.b64"
gh secret set KEYSTORE_BASE64 < "$env:TEMP\ks.b64"
Remove-Item "$env:TEMP\ks.b64"
```

Optionally pin the expected signer so a wrong key fails loudly instead of
shipping an uninstallable APK — lowercase hex, no colons:

```bash
gh variable set SIGNING_CERT_SHA256 \
  --body 64dbcbcd5c239cb5eaa1d4a0c7a1b755b04841927b687feb2f71dce65fd58193
```

Verify what's configured:

```bash
gh secret list
gh variable list
```

---

## How the app finds an update

`Updater.kt` asks `https://api.github.com/repos/<owner>/<repo>/releases/latest`,
takes the first `.apk` asset, strips the leading `v` off `tag_name` and compares
it to `BuildConfig.VERSION_NAME` component by component — numerically, so 1.10
correctly beats 1.9.

Three things trigger a check:

| Trigger | Throttled? |
|---|---|
| App start (`onStart`) | Yes — at most once per 6 hours |
| Tapping the version number on the loading screen | No |
| `window.CVAppBridge.checkForUpdate()` from the panel | No |

Unauthenticated GitHub API allows 60 requests per hour per IP; the 6-hour
throttle keeps this nowhere near it. The timestamp is written even when a check
fails, so a phone with no route to GitHub doesn't retry on every single start.

The download goes to app-private storage and is handed to the system installer
through a `FileProvider`. Android then enforces the real security boundary: an
APK signed with a different key **cannot** install over the existing app. This
holds no matter what the updater does, which is why a compromised release URL
still can't push a malicious build onto anyone.

Note that the release must have an APK **attached**. A release with notes and no
asset is treated as not-an-update and silently ignored.

---

## Publishing a draft first

To look at a release before phones see it:

```bash
git push origin v1.8            # CI builds and publishes
gh release edit v1.8 --draft    # hide it again
# ... check the APK ...
gh release edit v1.8 --draft=false
```

`releases/latest` skips drafts and prereleases, so a draft is invisible to the
updater. Same for `--prerelease`, which is the better switch if you want a build
that testers can grab by URL but that nobody gets offered automatically.

---

## When something goes wrong

**Published a broken build.** Delete the release; the updater immediately falls
back to the previous one. Do not delete the tag if anyone already installed it —
just ship a higher version.

```bash
gh release delete v1.8 --yes
```

**CI failed on a version guard.** Fix `app/build.gradle`, then move the tag:

```bash
git tag -d v1.8
git push origin :refs/tags/v1.8
# commit the fix, then re-tag and push
```

**Need a signed APK without a release.** Locally:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleRelease
# app/build/outputs/apk/release/cvapp-<version>.apk
```

This uses `keystore.properties`, so it is signed with the real key and installs
over an existing CVapp — handy for testing an update by hand.

**Testing the updater end to end.** Install the *older* version on the phone,
publish the newer release, then force a check by tapping the version number on
the loading screen rather than waiting out the 6-hour throttle:

```bash
adb install -r app/build/outputs/apk/release/cvapp-1.7.apk
adb shell am start -a android.intent.action.VIEW -d "cvha://cv-panel"
```

The first update on a given phone also needs "install unknown apps" granted to
CVapp; the app detects this and offers to open the right settings page.

---

## Configuration

Everything configurable is at the top of `app/build.gradle`:

```groovy
def instanceDomain = 'coyotes.vision'
def localHost      = 'ha.local'
def localPort      = '8123'
def linkScheme     = 'cvha'
def updateRepo     = 'hariolu/cvapp'
```

**No instance hostname appears here, and none should.** Each home is a subdomain
— `<slug>.coyotes.vision` — which names a real address with real people behind
it. This repository is public, so a subdomain committed here is published
permanently, and rewriting git history does not un-publish anything already
cloned, forked or cached.

The app does not need one anyway. It matches instances by domain suffix, and
remote entry goes through the login portal, which redirects each user to their
own instance after OTP. If you ever find yourself wanting to hardcode a house
here, that is the signal that something else is wrong.

The one value that genuinely varies between homes is `localHost`, the mDNS name
of the Home Assistant box on that LAN. If a second home needs a different one,
change it — but note that two builds sharing `applicationId "com.cvapp"` will
overwrite each other on the same phone.

---

## Play Protect and developer verification

Installing a release for the first time shows **"Harmful app blocked — this app
can put your device or data at risk"**, with `Install anyway` hidden behind
*More details*. Nothing is wrong with the build. Play Protect names no threat
family; this is the generic heuristic for an APK signed by a certificate Google
has never seen, and every self-distributed app hits it.

Note that `adb install` does *not* show this. It appears on the normal user
install path — which is the one that matters.

This gets stricter. From **30 September 2026**, certified Android devices in
Brazil, Indonesia, Singapore and Thailand will only install and update apps
registered to a *verified developer*, regardless of where the APK came from;
the rest of the world follows in 2027. Unverified apps are not blocked outright,
but installing one requires an advanced sideloading flow — developer mode, a
reboot, a 24-hour wait, re-authentication — or ADB.

Google's own answer to this is developer verification. **This project declines
it** — see [NO-GOOGLE.md](NO-GOOGLE.md) for what that means and what it costs.
Do not add it back without reading that first.

## Later: F-Droid

A custom F-Droid repository is the natural next step — it gives users automatic
background updates instead of a prompt, and F-Droid handles the "unknown
sources" grant once for the whole repo rather than per app.

It needs an `fdroid`-generated index (signed with its own repo key, separate
from the app key) served over HTTPS, plus the APKs. The release artifacts here
are already exactly what such a repo consumes, so nothing about this workflow
has to change to add it.
