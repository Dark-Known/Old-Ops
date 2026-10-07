<#
.SYNOPSIS
    Redeploys a rebuilt Monitoring-Tool.jar with a safety net:
    stop service -> back up current install -> deploy -> restart -> health check.

.DESCRIPTION
    Reuses the existing scripts instead of duplicating them:
      * packaging\steps\Deploy-Application.ps1  copies jar/config/icon + refreshes shortcuts
      * packaging\install-service.ps1           only when -ReinstallService is passed

    Every backup contains the ENTIRE data folder (tasks.xml, creds_*.xml, app.db, logs,
    everything), taken while the service is stopped so the files are consistent. A
    redeploy itself never modifies the data folder.

    If the deploy step throws, the previous version is restored automatically.
    If the service is unhealthy after restart, -AutoRollback restores it too.

.PARAMETER SourceJar
    Jar to deploy. Default: <jarName> next to app-config.xml, else newest
    non-"original-" jar in .\target.

.PARAMETER InstallDir
    Override install dir (default: /application/installation/installDir from app-config.xml).

.PARAMETER AutoRollback
    Restore the previous version if the service is not healthy after redeploy.

.PARAMETER ReinstallService
    Re-run install-service.ps1 (re-registers the WinSW service, refreshes its config).
    Not needed for a plain jar update - the service points at the same jar path.

.PARAMETER ForceCloseGui
    Kill running GUI instances that hold the jar open. Without it the script stops
    and asks you to close them (an open jar can't be overwritten on Windows).

.EXAMPLE
    .\packaging\redeploy.ps1
.EXAMPLE
    .\packaging\redeploy.ps1 -SourceJar .\target\Monitoring-Tool.jar -AutoRollback
#>
[CmdletBinding()]
param(
    [string]$SourceJar,
    [string]$InstallDir,
    [string]$ScriptRoot,
    [switch]$AutoRollback,
    [switch]$ReinstallService,
    [switch]$ForceCloseGui,
    [switch]$SkipBackup,
    [int]$KeepBackups = 5,
    [int]$HealthCheckSeconds = 20
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Ops-Common.ps1")

$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $ScriptRoot) {
    $ScriptRoot = if (Test-Path (Join-Path (Get-Location).Path "app-config.xml")) { (Get-Location).Path } else { $repoRoot }
}

$cfg = Get-OpsConfig -ConfigRoot $ScriptRoot
if ($InstallDir) { $cfg.InstallDir = $InstallDir }
$jarPath = Join-Path $cfg.InstallDir $cfg.JarName

$logDir = Join-Path $ScriptRoot "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$script:OpsLogFile = Join-Path $logDir ("opstool-redeploy_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))

$backupName = $null
$exitCode   = 0

try {
    Assert-OpsAdmin
    Write-OpsLog "Redeploy starting. InstallDir=$($cfg.InstallDir)  Log=$script:OpsLogFile"

    # ── 1. Resolve and sanity-check the new jar ─────────────────────────────
    if (-not $SourceJar) {
        $candidates = @(
            (Join-Path $ScriptRoot $cfg.JarName),
            (Join-Path $repoRoot   $cfg.JarName)
        )
        $SourceJar = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $SourceJar) {
            $built = Get-ChildItem (Join-Path $ScriptRoot "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike "original-*" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($built) { $SourceJar = $built.FullName }
        }
    }
    if (-not $SourceJar -or -not (Test-Path $SourceJar)) {
        throw "No jar to deploy. Build one (mvn -DskipTests package) or pass -SourceJar."
    }
    $SourceJar = (Resolve-Path $SourceJar).Path

    $bytes = [System.IO.File]::ReadAllBytes($SourceJar)
    if ($bytes.Length -lt 1024 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) {
        throw "'$SourceJar' does not look like a valid jar (missing zip header or too small)."
    }
    $newHash = Get-OpsFileHash $SourceJar
    $curHash = Get-OpsFileHash $jarPath
    Write-OpsLog "New jar:     $SourceJar  (sha256 $($newHash.Substring(0,12))...)"
    if ($curHash) {
        Write-OpsLog "Current jar: $jarPath  (sha256 $($curHash.Substring(0,12))...)"
        if ($curHash -eq $newHash) { Write-OpsLog "New jar is identical to the deployed one - redeploying anyway (config/shortcuts still refreshed)." "WARN" }
    }

    # ── 2. GUI instances hold the jar open ──────────────────────────────────
    $gui = @(Get-OpsJarProcesses -JarPath $jarPath | Where-Object { $_.CommandLine -notmatch "\sDaemon(\s|$)" })
    if ($gui.Count -gt 0) {
        if (-not $ForceCloseGui) {
            throw "The application is running (PID $($gui.ProcessId -join ', ')). Close it, or re-run with -ForceCloseGui."
        }
        $gui | ForEach-Object { Write-OpsLog "Closing GUI process PID $($_.ProcessId)" "WARN"; Stop-Process -Id $_.ProcessId -Force }
    }

    # ── 3. Stop daemon, snapshot current state ──────────────────────────────
    $svcWasRunning = Stop-OpsService
    Get-OpsJarProcesses -JarPath $jarPath | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2

    if (-not $SkipBackup) {
        $backupName = New-OpsBackup -Config $cfg -Reason "redeploy"
    } else {
        Write-OpsLog "-SkipBackup set: rollback will not be possible for this deploy." "WARN"
    }

    # ── 4. Deploy (existing script does the real work) ──────────────────────
    $deployScript = Join-Path $PSScriptRoot "steps\Deploy-Application.ps1"
    if (-not (Test-Path $deployScript)) { throw "Missing $deployScript" }
    try {
        Write-OpsLog "Running Deploy-Application.ps1..."
        $deployResult = & $deployScript -ScriptRoot $ScriptRoot -InstallDir $cfg.InstallDir `
                                        -SourceJar $SourceJar -LogFile $script:OpsLogFile |
                        Select-Object -Last 1
        $deployedHash = Get-OpsFileHash $jarPath
        if ($deployedHash -ne $newHash) { throw "Deployed jar hash does not match the source jar." }
        Write-OpsLog "Deployed $jarPath (hash verified)." "OK"
    } catch {
        Write-OpsLog "Deploy failed: $($_.Exception.Message)" "ERROR"
        if ($backupName) {
            Write-OpsLog "Restoring previous version from backup '$backupName'..." "WARN"
            $b = Get-OpsBackups -InstallDir $cfg.InstallDir | Where-Object { $_.Name -eq $backupName }
            Restore-OpsBackupFiles -Config $cfg -Backup $b
            if ($svcWasRunning) { Start-OpsService }
        }
        throw
    }

    # ── 5. Bring the daemon back ────────────────────────────────────────────
    $healthy = $true
    if ($ReinstallService) {
        $svcScript = Join-Path $PSScriptRoot "install-service.ps1"
        Write-OpsLog "Re-registering service via install-service.ps1..."
        & $svcScript -InstallDir $cfg.InstallDir -DataDir $cfg.DataDir -JarPath $jarPath
        $healthy = Test-OpsHealth -Seconds $HealthCheckSeconds
    } elseif (Get-OpsService) {
        if ($svcWasRunning) {
            Start-OpsService
            $healthy = Test-OpsHealth -Seconds $HealthCheckSeconds
        } else {
            Write-OpsLog "Service was stopped before the redeploy - leaving it stopped." "WARN"
        }
    } else {
        Write-OpsLog "Daemon service is not registered. Run packaging\install-service.ps1 (or setup.ps1) once to register it." "WARN"
    }

    # ── 6. Health failure handling ──────────────────────────────────────────
    if (-not $healthy) {
        $exitCode = 2
        if ($AutoRollback -and $backupName) {
            Write-OpsLog "Service unhealthy after redeploy - rolling back to '$backupName'." "ERROR"
            $b  = Get-OpsBackups -InstallDir $cfg.InstallDir | Where-Object { $_.Name -eq $backupName }
            $ok = Invoke-OpsRollback -Config $cfg -Backup $b -HealthCheckSeconds $HealthCheckSeconds
            if ($ok) { Write-OpsLog "Rollback complete - previous version is running." "OK" }
            else     { Write-OpsLog "Rollback finished but service is still unhealthy. Check daemon.log / Event Viewer." "ERROR" }
        } else {
            Write-OpsLog "Service unhealthy. Roll back with:  .\packaging\rollback.ps1" "ERROR"
        }
    } else {
        Remove-OldOpsBackups -InstallDir $cfg.InstallDir -Keep $KeepBackups
        Write-OpsLog "Redeploy finished successfully." "OK"
        if ($backupName) { Write-OpsLog "Rollback available:  .\packaging\rollback.ps1   (backup: $backupName)" }
    }
}
catch {
    Write-OpsLog $_.Exception.Message "ERROR"
    $exitCode = 1
}

Write-OpsLog "Log: $script:OpsLogFile"
exit $exitCode
