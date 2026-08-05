# Regenerates app/src/main/res/drawable/logo.xml from cv_logo_yote.svg.
#
# That one vector is the whole logo story: the loading screen uses it directly,
# and the launcher icon is ic_launcher_foreground.xml, a five-line <inset> that
# wraps it with the adaptive-icon safe-zone padding. Nothing else to regenerate.
#
# Safe for this artwork specifically: only m/l/c/z commands, flat fills, no
# arcs, no gradients, and a single outer uniform-scale matrix that maps 1:1 onto
# a VectorDrawable <group> (scale then translate, the order VGroup applies).
#
# Usage:  pwsh design/svg2vd.ps1

$src = Join-Path $PSScriptRoot 'cv_logo_yote.svg'
$dst = Join-Path $PSScriptRoot '..\app\src\main\res\drawable\logo.xml'

$inv = [Globalization.CultureInfo]::InvariantCulture
[xml]$x = Get-Content $src -Raw
$ns = New-Object System.Xml.XmlNamespaceManager($x.NameTable)
$ns.AddNamespace('s', 'http://www.w3.org/2000/svg')

$vb  = ($x.svg.viewBox -split '[\s,]+')
$vbW = [double]::Parse($vb[2], $inv)
$vbH = [double]::Parse($vb[3], $inv)

$outer = $x.SelectSingleNode('//s:g[@transform]', $ns)
$m = [regex]::Match($outer.transform, 'matrix\(([^)]*)\)')
$p = $m.Groups[1].Value -split '[\s,]+' | ForEach-Object { [double]::Parse($_, $inv) }
if ($p[1] -ne 0 -or $p[2] -ne 0) { throw "matrix has rotation/skew - group mapping is not valid" }

$num = [regex]'-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?'
function Fmt([double]$v) { ([Math]::Round($v, 3)).ToString('0.###', $inv) }

function Clean([string]$d) {
    # Re-emit every number in plain decimal: exponent notation ("1e-5") has a
    # rough history with Android's PathParser, and rounding to 3 decimals is
    # invisible at 1248 viewport units.
    $out = $num.Replace($d, {
        param($mm)
        ' ' + (Fmt ([double]::Parse($mm.Value, [Globalization.NumberStyles]::Float, $inv)))
    })
    return ([regex]::Replace($out, '[\s,]+', ' ')).Trim()
}

$sb = [Text.StringBuilder]::new()
[void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
[void]$sb.AppendLine('<!-- Generated from design/cv_logo_yote.svg by design/svg2vd.ps1. -->')
[void]$sb.AppendLine('<!-- Do not hand-edit: regenerate from the SVG instead.           -->')
[void]$sb.AppendLine('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
[void]$sb.AppendLine('    android:width="110dp"')
[void]$sb.AppendLine('    android:height="110dp"')
[void]$sb.AppendLine("    android:viewportWidth=""$(Fmt $vbW)""")
[void]$sb.AppendLine("    android:viewportHeight=""$(Fmt $vbH)"">")
[void]$sb.AppendLine("    <group")
[void]$sb.AppendLine("        android:scaleX=""$(Fmt $p[0])""")
[void]$sb.AppendLine("        android:scaleY=""$(Fmt $p[0])""")
[void]$sb.AppendLine("        android:translateX=""$(Fmt $p[4])""")
[void]$sb.AppendLine("        android:translateY=""$(Fmt $p[5])"">")

$count = 0
foreach ($path in $outer.SelectNodes('.//s:path', $ns)) {
    $fill = $path.fill; if (-not $fill) { $fill = '#000000' }
    $op = $path.opacity
    if ($op -and ([double]::Parse($op, $inv)) -eq 0) { continue }
    [void]$sb.AppendLine("        <path")
    [void]$sb.AppendLine("            android:fillColor=""$fill""")
    if ($op -and ([double]::Parse($op, $inv)) -lt 1) {
        [void]$sb.AppendLine("            android:fillAlpha=""$op""")
    }
    [void]$sb.AppendLine("            android:pathData=""$(Clean $path.d)"" />")
    $count++
}

[void]$sb.AppendLine('    </group>')
[void]$sb.AppendLine('</vector>')
[IO.File]::WriteAllText($dst, $sb.ToString(), (New-Object Text.UTF8Encoding $false))
"wrote $count paths -> $dst"
