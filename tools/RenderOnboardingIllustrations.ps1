param(
    [string]$Reference = (Join-Path $PSScriptRoot "..\pixa-onboarding-refined-7 (2).html"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\app\src\main\res\drawable-nodpi"),
    [string]$EdgePath = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path -LiteralPath $Reference)) { throw "Reference not found: $Reference" }
if (!(Test-Path -LiteralPath $EdgePath)) { throw "Microsoft Edge not found: $EdgePath" }

$names = "welcome", "search", "people", "smart_albums", "smart_cleanup", "editor", "safe"
$html = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Reference))
$svgs = [regex]::Matches($html, '<svg class="(?:mark|feature-icon)"[\s\S]*?</svg>')

if ($svgs.Count -ne $names.Count) {
    throw "Expected $($names.Count) feature illustrations, found $($svgs.Count)."
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$temporaryHtml = Join-Path $env:TEMP "pixa-onboarding-illustration.html"

for ($index = 0; $index -lt $names.Count; $index++) {
    $svg = $svgs[$index].Value -replace '<svg ', '<svg xmlns="http://www.w3.org/2000/svg" '
    $document = @"
<!doctype html><html><head><style>
html,body { width:900px; height:660px; margin:0; overflow:hidden; background:transparent; }
svg { display:block; width:900px; height:660px; }
</style></head><body>$svg</body></html>
"@
    [IO.File]::WriteAllText($temporaryHtml, $document, [Text.UTF8Encoding]::new($false))
    $target = Join-Path $OutputDirectory "onboarding_$($names[$index]).png"
    & $EdgePath --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
        "--screenshot=$target" --window-size=900,660 "file:///$($temporaryHtml -replace '\\', '/')" | Out-Null
    if (!(Test-Path -LiteralPath $target)) { throw "Edge did not render $($names[$index])." }
}

Remove-Item -LiteralPath $temporaryHtml -ErrorAction SilentlyContinue
