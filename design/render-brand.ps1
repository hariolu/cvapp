# Regenerates the raster brand assets from cv_logo_yote.svg.
#
#   logo.png            512x512, transparent — the README header
#   social-preview.png  1280x640 — GitHub's social preview card
#
# The social preview can only be uploaded through the web interface
# (Settings -> Social preview); GitHub has no API for it.
#
# Rendering goes through headless Edge because the SVG uses only flat fills and
# plain path data — no filters, no external fonts — so a browser is a faithful
# and dependency-free renderer here.
#
# Usage:  pwsh design/render-brand.ps1

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$work = Join-Path ([IO.Path]::GetTempPath()) "cvapp-brand"

$edge = @(
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw "No Chromium-based browser found to render with." }

if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
Copy-Item (Join-Path $here 'cv_logo_yote.svg') (Join-Path $work 'logo.svg')

@'
<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;padding:0;background:transparent}
  body{width:512px;height:512px;display:flex;align-items:center;justify-content:center}
  img{width:496px;height:496px}
</style></head><body><img src="logo.svg"></body></html>
'@ | Set-Content -Path (Join-Path $work 'logo.html') -Encoding utf8

@'
<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;padding:0}
  body{
    width:1280px;height:640px;background:#111111;
    font-family:"Segoe UI",system-ui,sans-serif;color:#fff;
    display:flex;align-items:center;justify-content:center;gap:56px;
    padding:0 80px;box-sizing:border-box;
  }
  .mark{width:330px;height:330px;flex:none}
  h1{font-size:92px;margin:0 0 12px;letter-spacing:2px;font-weight:700;line-height:1}
  .sub{font-size:33px;color:#9a9a9a;margin:0 0 30px;line-height:1.3}
  .foot{font-size:25px;color:#4f4f4f;letter-spacing:1.5px}
</style></head><body>
  <img class="mark" src="logo.svg">
  <div>
    <h1>CVapp</h1>
    <p class="sub">The Android client for Coyotes&nbsp;Vision</p>
    <div class="foot">coyotes.vision</div>
  </div>
</body></html>
'@ | Set-Content -Path (Join-Path $work 'social.html') -Encoding utf8

$base = "file:///" + ($work -replace '\\', '/')

# --default-background-color=00000000 keeps the logo cut-out transparent;
# the social card paints its own background and does not want it.
& $edge --headless --disable-gpu --no-sandbox --hide-scrollbars `
    --force-device-scale-factor=1 --default-background-color=00000000 `
    --window-size=512,512 --screenshot="$here\logo.png" "$base/logo.html" | Out-Null

& $edge --headless --disable-gpu --no-sandbox --hide-scrollbars `
    --force-device-scale-factor=1 `
    --window-size=1280,640 --screenshot="$here\social-preview.png" "$base/social.html" | Out-Null

Remove-Item $work -Recurse -Force
Get-ChildItem $here -Filter *.png | ForEach-Object { "wrote {0} ({1:N0} bytes)" -f $_.Name, $_.Length }
