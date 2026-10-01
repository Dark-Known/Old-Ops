<#
.SYNOPSIS
    Installs (or updates) the Ops Transfer Tool daemon as a real Windows
    Service using WinSW, as the recommended replacement for the Scheduled
    Task registration in setup.ps1.

.DESCRIPTION
    Run this INSTEAD OF the daemon-registration section of setup.ps1, not
    in addition to it - having both a Scheduled Task and a Service trying
    to run Daemon.jar would double-run the daemon. If a Scheduled Task
    from a previous setup.ps1 run already exists, this script removes it
    first.

    Requires an elevated (Administrator) PowerShell session, and WinSW's
    executable already downloaded - see https://github.com/winsw/winsw
    (grab WinSW-x64.exe from the latest release) - placed or referenced
    via -WinSwPath below.

.PARAMETER InstallDir
    Folder containing OpsTransferTool.jar and where the service wrapper
    executable + config will be placed. Defaults to the current directory.

.PARAMETER WinSwPath
    Path to the downloaded WinSW executable. Defaults to
    "$InstallDir\winsw.exe" - rename the downloaded WinSW-x64.exe to this
    before running, or pass -WinSwPath explicitly.

.PARAMETER JarPath
    Full path to the daemon jar. Auto-resolved if omitted: tries
    "$InstallDir\Monitoring-Tool.jar" (this project's default jar name)
    first, then any single *.jar found in $InstallDir, then falls back to
    the legacy "$InstallDir\OpsTransferTool.jar" name. Pass this
    explicitly if none of those match your build's actual jar name.

.PARAMETER DataDir
    Passed through to the daemon as its data directory. If not passed
    explicitly, auto-read from "$InstallDir\app-config.xml"'s
    /application/installation/dataDir if that file is present (it's
    deployed there by Deploy-Application.ps1); otherwise defaults to
    C:\OpsTools\Data.

.PARAMETER RceditPath
    Path to rcedit.exe, used to rebrand the daemon's actual JVM process so
    it shows this app's name/icon instead of "OpenJDK Platform Binary" in
    Task Manager. Entirely optional - auto-detected in $InstallDir, next
    to setup.ps1, or on PATH; if it can't be found anywhere the daemon
    still installs and runs fine, just under the plain Java process
    identity. Get it from https://github.com/electron/rcedit/releases.

.PARAMETER IconPath
    Icon to bake into the rebranded daemon executable via rcedit.
    Defaults to "$InstallDir\Icon.ico" (deployed there by
    Deploy-Application.ps1). Only relevant if rcedit is available.

.PARAMETER DaemonExeName
    File name for the rebranded daemon executable (a renamed copy of the
    JRE's own javaw.exe, not a different program). Defaults to
    "MonitoringToolDaemon.exe".

.PARAMETER DaemonDisplayName
    Product/description name baked into the rebranded executable's
    version resources - this is the text Task Manager's "Name"/"Description"
    columns will show. Defaults to "Monitoring tool".

.PARAMETER BrandWinSwWrapper
    Also rebrand the WinSW wrapper executable itself (daemon-service.exe),
    not just the child JVM process it launches. OFF by default. WinSW v3's
    daemon-service.exe is typically published as a self-contained
    single-file .NET "apphost bundle": a normal PE header followed by a
    bundle manifest whose byte offsets are computed for that exact file's
    layout/size. Running rcedit against it resizes the PE resource section
    to inject the icon/version info, but rcedit has no awareness of the
    .NET bundle footer appended after it, so those offsets go stale.
    rcedit itself reports success - the icon/name do visibly change - but
    the .NET host then fails the FIRST time the branded copy actually
    runs, with "Failure processing application bundle; possible file
    corruption. Arithmetic overflow while reading bundle.", and WinSW
    install/start fails with exit code -2147450721. Only pass this switch
    if you've confirmed your WinSW build is NOT a single-file publish
    (e.g. a framework-dependent build); otherwise leave it off and accept
    the default WinSW icon/description on the wrapper's own Task Manager
    entry - the child JVM process above is still rebranded either way.

.EXAMPLE
    .\install-service.ps1 -InstallDir "C:\Program Files\OpsTransferTool"

.EXAMPLE
    .\install-service.ps1 -InstallDir "C:\Program Files\OpsTransferTool" -JarPath "C:\Program Files\OpsTransferTool\Monitoring-Tool.jar"
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Get-Location).Path,
    [string]$WinSwPath  = $null,
    [string]$JarPath    = $null,
    [string]$DataDir    = "C:\OpsTools\Data",
    [string]$ExistingScheduledTaskName = "Monitoring-Tool-Daemon",
    [string]$RceditPath = $null,
    [string]$IconPath   = $null,
    [string]$DaemonExeName     = "MonitoringToolDaemon.exe",
    [string]$DaemonDisplayName = "Monitoring tool",
    [switch]$BrandWinSwWrapper
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) { Write-Host "[install-service] $msg" }

# ── Pre-flight checks ────────────────────────────────────────────────────
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "This script must be run as Administrator (right-click PowerShell -> Run as Administrator)."
}

# If -DataDir wasn't explicitly passed, try reading it from app-config.xml
# in $InstallDir (deployed there by Deploy-Application.ps1) before falling
# back to the hardcoded default above - so a plain
# ".\install-service.ps1 -InstallDir X" picks up a customized dataDir
# without needing to also repeat -DataDir.
if (-not $PSBoundParameters.ContainsKey('DataDir')) {
    $deployedConfig = Join-Path $InstallDir "app-config.xml"
    if (Test-Path $deployedConfig) {
        try {
            [xml]$cfg = Get-Content $deployedConfig
            $node = $cfg.SelectSingleNode("/application/installation/dataDir")
            if ($node -and -not [string]::IsNullOrEmpty($node.InnerText)) {
                $DataDir = $node.InnerText
                Log "Using DataDir from app-config.xml: $DataDir"
            }
        } catch { Log "WARNING: Could not read dataDir from ${deployedConfig}: $_" }
    }
}

if (-not $JarPath) {
    # Prefer this project's actual default jar name; fall back to any
    # single *.jar in InstallDir, then to the old default name (which
    # will then fail with a clear message below if genuinely not there).
    $preferredJar = Join-Path $InstallDir "Monitoring-Tool.jar"
    if (Test-Path $preferredJar) {
        $JarPath = $preferredJar
    } else {
        $anyJar = Get-ChildItem -Path $InstallDir -Filter "*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        $JarPath = if ($anyJar) { $anyJar.FullName } else { Join-Path $InstallDir "OpsTransferTool.jar" }
    }
}
$jarPath = $JarPath
if (-not (Test-Path $jarPath)) {
    throw "Daemon jar not found at '$jarPath'. Pass -JarPath pointing at the actual jar " +
          "(its name is configurable via /application/installation/jarName and isn't always OpsTransferTool.jar)."
}

if (-not $WinSwPath) { $WinSwPath = Join-Path $InstallDir "daemon-service.exe" }
if (-not (Test-Path $WinSwPath)) {
    throw "WinSW executable not found at '$WinSwPath'. Download it from " +
          "https://github.com/winsw/winsw/releases (WinSW-x64.exe), rename it to " +
          "'daemon-service.exe', and place it in $InstallDir - or pass -WinSwPath."
}

$configSource = Join-Path $PSScriptRoot "daemon-service.xml"
if (-not (Test-Path $configSource)) {
    throw "daemon-service.xml not found next to this script ($PSScriptRoot). It ships alongside install-service.ps1."
}

# ── Remove a prior Scheduled Task registration, if present ─────────────────
$existingTask = Get-ScheduledTask -TaskName $ExistingScheduledTaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Log "Found existing Scheduled Task '$ExistingScheduledTaskName' - removing it so it can't double-run the daemon alongside the new Service."
    Stop-ScheduledTask -TaskName $ExistingScheduledTaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $ExistingScheduledTaskName -Confirm:$false
    # The Scheduled Task's own process isn't stopped by Unregister-ScheduledTask -
    # kill any daemon jar still running under the old registration so the new
    # Service doesn't start alongside a leftover instance. Matched by full
    # command line (this specific jar path + "Daemon" argument), not just
    # process name, so this never touches an unrelated java.exe running on
    # the machine for some other reason. Checks both "java.exe" (an
    # unbranded old install) and the renamed "MonitoringToolDaemon.exe"
    # (see the rebrand step below), since either could be what an older
    # registration was actually running.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = '$DaemonExeName'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$jarPath*" -and $_.CommandLine -like "*Daemon*" } |
        ForEach-Object {
            Log "Stopping leftover daemon process (PID $($_.ProcessId))"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
} else {
    Log "No existing '$ExistingScheduledTaskName' Scheduled Task found - nothing to remove."
}

# ── Ensure the data directory exists, and is writable without elevation ────
# The GUI no longer launches elevated (see Deploy-Application.ps1 - the
# old test-elevation.bat/UAC-prompt chain existed only to query the
# now-retired Scheduled Task's status, and is gone entirely), so it needs
# to be able to read/write tasks.xml, creds_*.xml, and app-settings here
# as a plain standard user. Granting the built-in "Users" group Modify
# here is what makes that actually true, not just true in theory.
if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir | Out-Null
    Log "Created data directory: $DataDir"
}
try {
    & icacls $DataDir /grant "*S-1-5-32-545:(OI)(CI)M" /T /Q | Out-Null
    Log "Granted standard users write access to: $DataDir"
} catch {
    Log "WARNING: Could not set permissions on ${DataDir}: $_ - the GUI may need to be run elevated if it can't write here."
}

# ── Stage the WinSW config next to the wrapper executable ──────────────────
# WinSW expects its config XML named to match the executable, e.g.
# daemon-service.exe -> daemon-service.xml, both in the same folder.
$configTarget = Join-Path $InstallDir "daemon-service.xml"
Copy-Item -Path $configSource -Destination $configTarget -Force
Log "Staged service config at $configTarget"

# Point the config at this install's actual jar path and data dir, in case
# they differ from the shipped defaults.
[xml]$cfg = Get-Content $configTarget
$cfg.service.arguments = "-cp `"$jarPath`" Daemon `"$DataDir`""
$cfg.Save($configTarget)
Log "Configured service arguments: $($cfg.service.arguments)"

# ── Stop/uninstall a previous instance of this Service, if present ─────────
$existingService = Get-Service -Name "OpsTransferToolDaemon" -ErrorAction SilentlyContinue
if ($existingService) {
    Log "Service already registered - stopping and reinstalling with the current config."
    & $WinSwPath stop
    & $WinSwPath uninstall
    Start-Sleep -Seconds 2
}

# ── Rebrand the daemon's actual JVM process for Task Manager ───────────────
# Without this, the SCM-managed process WinSW spawns runs plain
# "java.exe"/"javaw.exe", which Windows labels "OpenJDK Platform Binary"
# with the stock Java coffee-cup icon in Task Manager - correct, but
# indistinguishable from any other Java app on the machine. This branded
# copy needs to live inside its OWN private runtime (built via
# New-PrivateRuntime.ps1), not the system JDK's bin\ folder — see that
# script's own docs for why a bare renamed copy of the system javaw.exe
# can't just be dropped into -InstallDir directly. Entirely optional -
# if rcedit isn't available, Brand-Executable.ps1 logs why and hands back
# the plain javaw.exe path unchanged, and the daemon installs and runs
# exactly the same either way; only its Task Manager identity differs.
$iconCandidate = if ($IconPath -and (Test-Path $IconPath)) { $IconPath } else { Join-Path $InstallDir "Icon.ico" }
$brandScript = Join-Path $PSScriptRoot "steps\Brand-Executable.ps1"
# setup.ps1 and rcedit.exe (per its own header's prerequisite list) live one
# level up from this script (packaging\install-service.ps1 -> the staging
# root) — NOT in -InstallDir, which is the deployed destination and never
# has rcedit.exe placed in it. Same staging-root concept as
# Deploy-Application.ps1's self-detected -ScriptRoot, just one directory
# level shallower since this script sits directly in packaging\, not
# packaging\steps\.
$stagingRoot = Split-Path $PSScriptRoot -Parent

$runtimeScript = Join-Path $PSScriptRoot "steps\New-PrivateRuntime.ps1"
$javaSource = $null
if (Test-Path $runtimeScript) {
    try {
        $runtimeResult = & $runtimeScript -InstallDir $InstallDir
        $javaSource = $runtimeResult.Javaw
    } catch {
        Log "WARNING: Could not build private runtime: $_"
    }
}
if (-not $javaSource) {
    # Fallback only — see Deploy-Application.ps1's identical fallback for why.
    $javaSource = (Get-Command "javaw.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    if (-not $javaSource) {
        try { $javaSource = Join-Path (Split-Path (Get-Command java.exe).Source) "javaw.exe" } catch { $javaSource = $null }
    }
}

if (-not $javaSource -or -not (Test-Path $javaSource)) {
    Log "NOTE: Couldn't locate javaw.exe to rebrand (is Java on PATH?) - daemon will run as plain java.exe in Task Manager."
} elseif (-not (Test-Path $brandScript)) {
    Log "NOTE: packaging\steps\Brand-Executable.ps1 not found - skipping Task Manager rebrand."
} else {
    $brandArgs = @{
        SourceExe   = $javaSource
        DestExe     = $DaemonExeName
        IconPath    = $iconCandidate
        Description = "$DaemonDisplayName - background daemon"
        ProductName = $DaemonDisplayName
        SearchDir   = $stagingRoot
    }
    if ($RceditPath) { $brandArgs.RceditPath = $RceditPath }
    $brandResult = & $brandScript @brandArgs
    if ($brandResult.Branded) {
        [xml]$cfg = Get-Content $configTarget
        $cfg.service.executable = $brandResult.ExePath
        $cfg.Save($configTarget)
        Log "Daemon JVM process rebranded for Task Manager: $($brandResult.ExePath)"
    }
}

# ── Rebrand the WinSW WRAPPER itself (daemon-service.exe) ──────────────────
# OFF BY DEFAULT - pass -BrandWinSwWrapper to enable. See the parameter's
# own help comment above for the full explanation: WinSW v3's own exe is
# typically a self-contained single-file .NET "apphost bundle", and
# running rcedit against it corrupts the bundle's offset table, which
# fails at daemon-service.exe install/start time with "Arithmetic
# overflow while reading bundle." (WinSW install exit code -2147450721) -
# exactly the failure this setup previously hit. The child JVM process
# above is always rebranded regardless of this switch; only the WinSW
# wrapper's own Task Manager entry is affected.
if ($BrandWinSwWrapper) {
    if ((Test-Path $WinSwPath) -and $brandScript -and (Test-Path $brandScript) -and (Test-Path $iconCandidate)) {
        $wrapperBrandArgs = @{
            SourceExe   = $WinSwPath
            DestExe     = (Split-Path $WinSwPath -Leaf)
            IconPath    = $iconCandidate
            Description = "$DaemonDisplayName - background daemon"
            ProductName = $DaemonDisplayName
            SearchDir   = $stagingRoot
        }
        if ($RceditPath) { $wrapperBrandArgs.RceditPath = $RceditPath }
        # DestExe intentionally has the SAME name as SourceExe here: this brands
        # daemon-service.exe in place (it's already a per-app renamed copy of
        # WinSW the operator created during setup, not a shared system binary),
        # rather than producing a second differently-named copy alongside it.
        try {
            Copy-Item -Path $WinSwPath -Destination "$WinSwPath.tmp" -Force
            $wrapperBrandArgs.SourceExe = "$WinSwPath.tmp"
            $wrapperResult = & $brandScript @wrapperBrandArgs
            if ($wrapperResult.Branded) {
                Log "WinSW wrapper rebranded for Task Manager: $WinSwPath"
            }
            Remove-Item "$WinSwPath.tmp" -ErrorAction SilentlyContinue
        } catch {
            Log "WARNING: Could not rebrand WinSW wrapper: $_"
        }
    }
} else {
    Log "Skipping WinSW wrapper rebrand (default - pass -BrandWinSwWrapper only if your daemon-service.exe is confirmed NOT a single-file .NET publish)."
}

# ── Install and start ───────────────────────────────────────────────────────
Push-Location $InstallDir
try {
    & $WinSwPath install
    if ($LASTEXITCODE -ne 0) { throw "WinSW install failed with exit code $LASTEXITCODE" }
    & $WinSwPath start
    if ($LASTEXITCODE -ne 0) { throw "WinSW start failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

# ── Configure Service Recovery (WinSW's own onfailure handles most cases,
#    but sc.exe's Recovery settings are the belt-and-suspenders fallback
#    Windows admins expect to see in services.msc) ──────────────────────────
sc.exe failure "OpsTransferToolDaemon" reset= 3600 actions= restart/10000/restart/30000/restart/60000 | Out-Null
Log "Configured Service Recovery (restart on 1st/2nd/subsequent failure)."

$status = Get-Service -Name "OpsTransferToolDaemon"
Log "Service '$($status.Name)' is now: $($status.Status)"
Log "Done. Check Event Viewer / $InstallDir logs if it did not reach 'Running'."