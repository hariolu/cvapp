# No Google

CVapp does not go through Google — not the Play Store, and not developer
verification. Decided 2026-08-04. This document exists so the decision does not
get re-litigated every year by someone who has forgotten what it costs.

## What was turned down

**Google Play.** A personal account needs 12 testers opted in for 14 continuous
days before production. An organization account needs a D-U-N-S number, meaning a
legal entity and roughly a month. Policy 4.3 actively rejects WebView wrappers. A
login-gated app has to hand working credentials to reviewers.

**Android developer verification.** $25 for unlimited installs, or free with a
hard cap of 20 devices. Identity verified against a government ID, with the
verified developer details published by Google. No review and no testers — but
still registering this app, and the person behind it, with Google.

## What that costs

None of this is speculative.

**Play Protect warns on every first install.** "Harmful app blocked — this app
can put your device or data at risk", with `Install anyway` hidden under *More
details*. No threat family is named; it is the heuristic for a certificate Google
has not seen. `adb install` does not trigger it. The normal user path does.

**Installs get harder from late 2026.** On *certified* devices — the ones
shipping Play Protect — apps from unverified developers stop installing normally:
Brazil, Indonesia, Singapore and Thailand from **30 September 2026**, the rest of
the world during **2027**. Not an outright ban: enable developer mode, reboot,
wait 24 hours, re-authenticate. Or ADB.

**This includes the maintainer's own phone.** A stock Pixel is a certified
device. The logical end of this position is a de-Googled OS — GrapheneOS,
CalyxOS, LineageOS, /e/OS — where none of the above applies: no Play Protect, no
verification requirement. That is a decision about phones rather than about this
repo, but the two are linked, and better known now than in 2027.

## What this changes in the code

Nothing. The app has no Google dependency of any kind:

```groovy
implementation 'androidx.appcompat:appcompat:1.7.0'
implementation 'androidx.core:core-ktx:1.13.1'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
```

No Play Services, no Firebase, no GMS, no analytics, no crash reporting. Nothing
here talks to Google and nothing needs removing. Keep it that way — one
`play-services-*` dependency would quietly undo the whole position.

## App Links verification is off the table, and that is fine

`assetlinks.json` plus `android:autoVerify` would remove the manual "Open by
default" tick that the first-run hint explains. It was investigated and dropped,
because the component that fetches `assetlinks.json` and marks a domain verified
**is Google Play Services**. Measured on a Pixel 9 running Android 16:

```
$ adb shell dumpsys package com.google.android.gms | grep DOMAIN_VERIFICATION_AGENT
      android.permission.DOMAIN_VERIFICATION_AGENT: granted=true
```

No other package on the device holds that role, and the framework only declares
the permission — it does not implement the agent. So on a phone without GMS there
is no verifier at all, and `autoVerify` can never succeed no matter what is
served from the domain.

The consequence is worth stating plainly: **the manual tick is not a temporary
workaround, it is the permanent mechanism** for the devices this project is aimed
at. The first-run hint that explains it is load-bearing, not a stopgap, and
should be maintained as a real feature.

Serving `assetlinks.json` anyway would only help users who have Google Play
Services — the exact users this approach is not optimising for.

## The distribution stack

| Layer | What | Status |
|---|---|---|
| Source | GitHub Releases, signed with our own key | Working |
| Updates | In-app updater reading `releases/latest` | Working |
| Link handling | Manual approval + first-run hint | Working |
| Preferred channel | Custom F-Droid repository | Not built |

A custom F-Droid repo is what turns this from "possible" into "comfortable":
automatic background updates instead of a prompt, one trust decision for the
whole repo instead of per-app "unknown sources", and the normal way to install
software on a de-Googled phone. The artifacts this repo already publishes are
exactly what such a repo consumes, so nothing about the release process has to
change to add it.
