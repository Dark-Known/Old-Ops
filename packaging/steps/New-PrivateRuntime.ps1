[CmdletBinding()]
param(
    [string]$JavaHome   = $null,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [switch]$Force,
    [string]$LogFile    = $null
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    $line = "[new-private-runtime] $msg"
    Write-Host "  $line"
    if ($LogFile) { Add-Content -Path $LogFile -Value "$(Get-Date -Format 'HH:mm:ss')  $line" }
}
function OK([string]$msg) { Write-Host "  v  $msg" -ForegroundColor Green; Log $msg }
function FailStep([string]$msg) {
    Write-Host ""
    Write-Host "  x  ERROR: $msg" -ForegroundColor Red
    Log "ERROR: $msg"
    throw $msg
}

$runtimeDir = Join-Path $InstallDir "runtime"
$runtimeJavaw = Join-Path $runtimeDir "bin\javaw.exe"

if ((Test-Path $runtimeJavaw) -and -not $Force) {
    OK "Private runtime already exists: $runtimeJavaw"
    return [pscustomobject]@{ RuntimeDir = $runtimeDir; Javaw = $runtimeJavaw }
}

if (-not $JavaHome) {
    if ($env:JAVA_HOME) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $jc = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($jc) { $JavaHome = Split-Path (Split-Path $jc.Source) }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin\jlink.exe"))) {
    FailStep "Could not locate a full JDK with jlink.exe (need JAVA_HOME or javac.exe on PATH pointing at a JDK, not just a JRE). Run Install-Prerequisites.ps1 first, or pass -JavaHome explicitly."
}
$jlinkExe = Join-Path $JavaHome "bin\jlink.exe"
$jmodsDir = Join-Path $JavaHome "jmods"
if (-not (Test-Path $jmodsDir)) {
    FailStep "No jmods\ folder under $JavaHome - this looks like a JRE, not a full JDK. jlink needs the full JDK's jmods to build a runtime image."
}

if (Test-Path $runtimeDir) {
    Remove-Item $runtimeDir -Recurse -Force
}

Log "Building private runtime at $runtimeDir via jlink (this can take up to a minute)..."
$jlinkArgs = @(
    "--module-path", $jmodsDir,
    "--add-modules", "ALL-MODULE-PATH",
    "--output", $runtimeDir,
    "--no-header-files",
    "--no-man-pages",
    "--strip-debug",
    "--compress=2"
)
& $jlinkExe @jlinkArgs
if ($LASTEXITCODE -ne 0) {
    FailStep "jlink exited with code $LASTEXITCODE."
}
if (-not (Test-Path $runtimeJavaw)) {
    FailStep "jlink completed but $runtimeJavaw wasn't produced - something is wrong with the generated image."
}

OK "Private runtime built: $runtimeDir"
[pscustomobject]@{ RuntimeDir = $runtimeDir; Javaw = $runtimeJavaw }