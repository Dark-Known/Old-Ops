# ============================================================
#  setup.ps1  --  One-click installer for Monitoring tool
#  Run as Administrator (right-click -> Run with PowerShell)
#  Tested on Windows 10 / Windows 11 / Windows Server 2019+
#
#  Prerequisites (place in the same folder as this script):
#    Monitoring-Tool.jar         -- Prebuilt application JAR
#    OpenJDK21-jdk.msi           -- Temurin JDK 21 offline installer
#    WinSCP-Setup.exe            -- WinSCP offline installer
#    app-config.xml              -- Install + runtime configuration
#    Icon.ico                    -- App logo, already included in this repo at
#                                    the root - used for shortcuts, the GUI's
#                                    window/taskbar icon, and (with rcedit.exe
#                                    below) both the GUI's and the daemon's
#                                    Task Manager entries.
#    rcedit.exe                  -- OPTIONAL: bakes this app's icon/name into
#                                    a renamed copy of javaw.exe for both the
#                                    GUI and daemon launchers, so Task Manager
#                                    shows this app instead of "OpenJDK
#                                    Platform Binary". Get it from
#                                    https://github.com/electron/rcedit/releases
#
#  ARCHITECTURE: this script is a thin orchestrator - it just runs three
#  standalone scripts in order and stops on the first failure. Every one
#  of them also works fine run on its own with NO arguments at all when
#  run from this same folder (they self-detect the jar, config, icon,
#  etc. - see each script's own -Full help for details):
#
#    1. packaging\steps\Install-Prerequisites.ps1  - check/install JDK + WinSCP
#    2. packaging\steps\Deploy-Application.ps1      - copy files + create shortcuts
#    3. packaging\install-service.ps1               - register the daemon
#
#  Common example - just redeploying a rebuilt jar, nothing else:
#      .\packaging\steps\Deploy-Application.ps1
# ============================================================

#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

$ScriptRoot = if ($PSCommandPath) {
    Split-Path -Parent $PSCommandPath
} elseif ($PSScriptRoot) {
    $PSScriptRoot
} elseif ($MyInvocation.MyCommand.Path) {
    Split-Path -Parent $MyInvocation.MyCommand.Path
} else {
    Get-Location
}
$StepsDir = Join-Path $ScriptRoot "packaging\steps"

function Log($msg) {
    $line = "$(Get-Date -Format 'HH:mm:ss')  $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

function Step($msg) {
    Write-Host ""
    Write-Host "------------------------------------------" -ForegroundColor DarkGray
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "------------------------------------------" -ForegroundColor DarkGray
}

function OK($msg)   { Write-Host "  v  $msg" -ForegroundColor Green }
function SKIP($msg) { Write-Host "  -  $msg" -ForegroundColor Yellow }

function FAIL($msg) {
    Write-Host ""
    Write-Host "  x  ERROR: $msg" -ForegroundColor Red
    Write-Host "     See log: $LogFile"
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# Runs one of the step scripts, forwarding -LogFile automatically, and
# turns any thrown error into the same FAIL() stop-and-explain behavior
# the rest of this script uses - so a failure partway through a step
# reads the same as a failure in this file itself. $StepArgs only needs
# to contain overrides; every step script fills in everything else itself
# from app-config.xml or its own project defaults.
function Invoke-Step {
    param([string]$ScriptPath, [hashtable]$StepArgs = @{})
    if (-not (Test-Path $ScriptPath)) {
        FAIL "$ScriptPath not found - re-download the packaging folder alongside setup.ps1."
    }
    try {
        return & $ScriptPath @StepArgs -LogFile $LogFile
    } catch {
        $errorMessage = if ($_.Exception) { $_.Exception.Message } else { $_.ToString() }
        FAIL "$(Split-Path $ScriptPath -Leaf) failed: $errorMessage"
    }
}

$LogDir  = Join-Path $ScriptRoot "logs"
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$LogFile = Join-Path $LogDir "opstool-setup_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

# Only read here for the banner text and the final summary/daemon-skip
# decision below - every step script reads app-config.xml itself too, so
# nothing computed here actually needs to be passed down to them.
$xmlConfig = $null
$configFile = Join-Path $ScriptRoot "app-config.xml"
if (Test-Path $configFile) {
    try { [xml]$xmlConfig = Get-Content $configFile; Log "Loaded configuration from: $configFile" }
    catch { Log "WARNING: Failed to parse app-config.xml: $_" }
} else {
    Log "WARNING: app-config.xml not found next to setup.ps1. Every step will use its own built-in defaults."
}
function Get-ConfigValue([string]$xpath, [string]$defaultValue) {
    if ($null -eq $xmlConfig) { return $defaultValue }
    try {
        $node = $xmlConfig.SelectSingleNode($xpath)
        if ($node -and -not [string]::IsNullOrEmpty($node.InnerText)) { return $node.InnerText }
    } catch {}
    return $defaultValue
}

$InstallDir    = Get-ConfigValue "/application/installation/installDir" "C:\OpsTools"
$JarName       = Get-ConfigValue "/application/installation/jarName" "Monitoring-Tool.jar"
$dataDir       = Get-ConfigValue "/application/installation/dataDir" "C:\OpsTools\Data"
$daemonEnabled = Get-ConfigValue "/application/daemon/enabled" "true"
$taskName      = Get-ConfigValue "/application/daemon/taskName" "Monitoring-Tool-Daemon"

Clear-Host
Write-Host ""
Write-Host "  +======================================================+" -ForegroundColor Cyan
Write-Host "  |       Monitoring tool  --  One-Click Setup           |" -ForegroundColor Cyan
Write-Host "  +======================================================+" -ForegroundColor Cyan
Write-Host ""
Write-Host "  This script will:"
Write-Host "    1. Check / install Java JDK 21 and WinSCP"
Write-Host "    2. Deploy $JarName to $InstallDir, and create shortcuts"
Write-Host "    3. Register background daemon (Windows Service)"
Write-Host ""
Write-Host "  Log: $LogFile"
Write-Host ""
$confirm = Read-Host "Continue? [Y/n]"
if ($confirm -match "^[Nn]") { exit 0 }

# ── Step 1: Prerequisites (Java + WinSCP) ───────────────────────────────
Step "Step 1/3: Prerequisites (Java + WinSCP)"
$prereqResult = Invoke-Step (Join-Path $StepsDir "Install-Prerequisites.ps1") @{ ScriptRoot = $ScriptRoot }

# ── Step 2: Deploy application + shortcuts ──────────────────────────────
Step "Step 2/3: Deploy application + shortcuts"
$deployResult = Invoke-Step (Join-Path $StepsDir "Deploy-Application.ps1") @{ ScriptRoot = $ScriptRoot }
$destJar = $deployResult.DestJar

# ── Step 3: Daemon registration ─────────────────────────────────────────
Step "Step 3/3: Daemon registration"
if ($daemonEnabled -ne "true") {
    SKIP "Daemon registration disabled in configuration"
} else {
    # Delegates entirely to packaging\install-service.ps1, which registers
    # the daemon as a real Windows Service (via WinSW) instead of a
    # Scheduled Task, and removes any existing Scheduled Task registration
    # from a previous setup.ps1 run before installing the Service - so
    # re-running this script on an older install migrates it cleanly
    # rather than leaving two things trying to run the same daemon.
    # See packaging/README.md for details and manual rollback steps.
    $installServiceScript = Join-Path $ScriptRoot "packaging\install-service.ps1"
    $winSwExe = Join-Path $InstallDir "daemon-service.exe"

    if (-not (Test-Path $installServiceScript)) {
        Log "WARNING: packaging\install-service.ps1 not found next to this script - skipping daemon registration."
    } elseif (-not (Test-Path $winSwExe)) {
        # Same "optional local prerequisite" convention as rcedit.exe above:
        # this isn't fatal, since offline installs may not have fetched
        # WinSW yet - just tell the operator exactly what to do next.
        Log "NOTE: daemon-service.exe (WinSW) not found in $InstallDir - skipping daemon registration."
        Log "Download WinSW-x64.exe from https://github.com/winsw/winsw/releases,"
        Log "rename it to 'daemon-service.exe', place it in $InstallDir, and re-run setup"
        Log "(or run packaging\install-service.ps1 directly) to register the daemon."
    } else {
        try {
            Log "Registering daemon as a Windows Service via install-service.ps1..."
            & $installServiceScript -InstallDir $InstallDir -DataDir $dataDir `
                -WinSwPath $winSwExe -JarPath $destJar -ExistingScheduledTaskName $taskName
            if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
                Log "WARNING: install-service.ps1 exited with code $LASTEXITCODE - check the output above."
            } else {
                OK "Daemon registered as Windows Service (see output above for status)."
            }
        } catch {
            $errorMessage = if ($_.Exception) { $_.Exception.Message } else { $_.ToString() }
            Log "WARNING: Could not register daemon service: $errorMessage"
            Log "You can register manually later via: packaging\install-service.ps1 -InstallDir `"$InstallDir`""
        }
    }
}

Write-Host ""
Write-Host "  v Java JDK installed/verified: $($prereqResult.JavaBin)"
Write-Host "  v WinSCP installed/verified: $($prereqResult.WinScpComPath)"
Write-Host "  v JAR deployed to: $destJar"
Write-Host "  v Desktop shortcut -> $($deployResult.DesktopLink)"
Write-Host "  v Start Menu entry -> $($deployResult.StartMenuLink)"
Write-Host "  [OK] Daemon registered (if available)"
Write-Host ""
Write-Host "  Launch the application:"
Write-Host "    * Start Menu -> Ops Tools -> the app's shortcut"
Write-Host "    * Or double-click Desktop shortcut"
Write-Host "    * Opens directly - no UAC prompt, no wrapper script."
Write-Host ""
Write-Host "  Data location: $dataDir"
Write-Host "    * tasks.xml (your scheduled tasks)"
Write-Host "    * creds_*.xml (server credentials)"
Write-Host "    * daemon.log (background execution log)"
Write-Host ""
Write-Host "  Setup log: $LogFile"
Write-Host ""
Write-Host "  +======================================================+" -ForegroundColor Green
Write-Host "  |  Setup Complete - Ready to use!                     |" -ForegroundColor Green
Write-Host "  +======================================================+" -ForegroundColor Green
Write-Host ""

Read-Host "Press Enter to exit"
