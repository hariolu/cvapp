# Artwork

There is one copy of the logo: [`cv_logo_yote.svg`](cv_logo_yote.svg). Everything
the app and this repository display is derived from it, and all of it is
committed — nothing here has to be regenerated to build or run CVapp. These notes
exist for the day the drawing changes.

## What comes from it

| File | What it is |
|---|---|
| `app/src/main/res/drawable/logo.xml` | the `VectorDrawable` the loading screen draws |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | five-line `<inset>` wrapping `logo.xml` with the adaptive-icon safe-zone padding |
| `design/logo.png` | 512×512, transparent — the README header |
| `design/social-preview.png` | 1280×640 — GitHub's social preview card |

The launcher icon needs no separate artwork: it is the same vector with padding,
so changing the SVG changes both the splash and the icon.

## Regenerating the VectorDrawable

Android Studio: **File → New → Vector Asset → Local file**, pick
`cv_logo_yote.svg`, set the size to 110 dp, and let it overwrite `logo.xml`.

The import is clean for this artwork specifically, and it is worth knowing why:
the drawing uses only `m`/`l`/`c`/`z` path commands and flat fills — no arcs, no
gradients, no filters — and a single outer uniform-scale matrix that maps 1:1
onto a `<group>`. Artwork with rotation or skew in that matrix, or with gradient
fills, will not survive the trip intact and needs checking by eye afterwards.

Two things to watch in the result:

- **Exponent notation.** If any `pathData` number comes out as `1e-5`, replace it
  with plain decimal. Android's `PathParser` has a rough history with it.
  Rounding to three decimals is invisible at this viewport size.
- **Zero-opacity paths.** The SVG carries a few; they are pure weight in the APK
  and can be deleted.

## Regenerating the PNGs

Both are screenshots of a page holding the SVG, taken with headless Chrome or
Edge. That is a faithful renderer here for the same reason the import is clean:
flat fills and plain path data, nothing that needs a real SVG engine.

Put `cv_logo_yote.svg` and a small HTML file in a scratch directory, then:

```bash
# logo.png — the transparent flag is what keeps the cut-out transparent
chrome --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 --default-background-color=00000000 \
  --window-size=512,512 --screenshot=logo.png file:///.../logo.html

# social-preview.png — paints its own #111111 background, so no transparency
chrome --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 \
  --window-size=1280,640 --screenshot=social-preview.png file:///.../social.html
```

`logo.html` is a 512×512 body centring a 496 px image on a transparent
background. `social.html` is a 1280×640 body on `#111111` — the same colour as
the app's loading screen — with the mark at 330 px on the left and, to its right,
"CVapp" at 92 px bold, "The Android client for Coyotes Vision" at 33 px in
`#9a9a9a`, and "coyotes.vision" at 25 px in `#4f4f4f`.

The social preview can only be uploaded through the web interface
(**Settings → Social preview**); GitHub has no API for it.
