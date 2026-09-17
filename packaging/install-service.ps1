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
    Full path to the daemon jar. Defaults to "$InstallDir\OpsTransferTool.jar" -
    override this if your build's jar has a different name (setup.ps1 passes
    its own $destJar value here automatically, since that name is
    configurable via /application/installation/jarName in the install XML
    config and isn't always "OpsTransferTool.jar").

.PARAMETER DataDir
    Passed through to the daemon as its data directory. Defaults to
    C:\OpsTools\Data, matching setup.ps1's own default.

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
    [string]$ExistingScheduledTaskName = "Monitoring-Tool-Daemon"
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) { Write-Host "[install-service] $msg" }

# ── Pre-flight checks ────────────────────────────────────────────────────
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "This script must be run as Administrator (right-click PowerShell -> Run as Administrator)."
}

if (-not $JarPath) { $JarPath = Join-Path $InstallDir "OpsTransferTool.jar" }
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
    # Service doesn't start alongside a leftover instance.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*Daemon*" } |
        ForEach-Object {
            Log "Stopping leftover daemon process (PID $($_.ProcessId))"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
} else {
    Log "No existing '$ExistingScheduledTaskName' Scheduled Task found - nothing to remove."
}

# ── Ensure the data directory exists ───────────────────────────────────────
if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir | Out-Null
    Log "Created data directory: $DataDir"
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
