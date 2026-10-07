<#
.SYNOPSIS
    Rolls the Monitoring tool back to a backup taken by redeploy.ps1.

.DESCRIPTION
    stop service -> snapshot current state (so the rollback can be undone) ->
    restore jar/config/icon from the chosen backup -> start service -> health check.

    By default restores the most recent "redeploy_*" backup, i.e. the version that was
    live before your last redeploy. "pre-rollback_*" snapshots are skipped by default so
    running rollback twice doesn't flip-flop; name one explicitly to roll *forward* again.

    The full data folder saved in the backup is restored too (tasks.xml, creds_*.xml,
    app.db, logs...), overwriting current files and deleting nothing. Anything changed
    in the data folder since that backup is replaced, but a pre-rollback snapshot keeps it.
    Pass -SkipDataRestore to roll back only the application files.

.PARAMETER Backup
    Backup folder name (see -List), or "latest" (default).

.PARAMETER List
    Show available backups and exit.

.PARAMETER SkipDataRestore
    Restore only jar/config/icon; leave the current data folder untouched.

.PARAMETER NoRestart
    Restore files but leave the service stopped.

.PARAMETER Yes
    Skip the confirmation prompt (for automation).

.EXAMPLE
    .\packaging\rollback.ps1 -List
.EXAMPLE
    .\packaging\rollback.ps1
.EXAMPLE
    .\packaging\rollback.ps1 -Backup redeploy_20260107_101500 -Yes
#>
[CmdletBinding()]
param(
    [string]$Backup = "latest",
    [string]$InstallDir,
    [string]$ScriptRoot,
    [switch]$List,
    [switch]$SkipDataRestore,
    [switch]$NoRestart,
    [switch]$ForceCloseGui,
    [switch]$Yes,
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

try {
    $backups = @(Get-OpsBackups -InstallDir $cfg.InstallDir)

    if ($List) {
        if ($backups.Count -eq 0) { Write-Host "No backups found under $(Get-OpsBackupRoot $cfg.InstallDir)"; exit 0 }
        $liveHash = Get-OpsFileHash (Join-Path $cfg.InstallDir $cfg.JarName)
        $backups | ForEach-Object {
            [pscustomobject]@{
                Name    = $_.Name
                Kind    = $_.Reason
                JarKB   = if ($_.JarBytes) { [math]::Round($_.JarBytes / 1KB) } else { "?" }
                Data    = if ($_.HasData) { "yes" } else { "-" }
                Sha256  = if ($_.JarSha256) { $_.JarSha256.Substring(0,12) } else { "?" }
                IsLive  = if ($_.JarSha256 -and $_.JarSha256 -eq $liveHash) { "<- matches deployed jar" } else { "" }
            }
        } | Format-Table -AutoSize
        exit 0
    }

    Assert-OpsAdmin
    $logDir = Join-Path $ScriptRoot "logs"
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    $script:OpsLogFile = Join-Path $logDir ("opstool-rollback_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))

    if ($backups.Count -eq 0) { throw "No backups found under $(Get-OpsBackupRoot $cfg.InstallDir). Nothing to roll back to." }

    $target = if ($Backup -eq "latest") {
        $backups | Where-Object { $_.Reason -eq "redeploy" } | Select-Object -First 1
    } else {
        $backups | Where-Object { $_.Name -eq $Backup } | Select-Object -First 1
    }
    if (-not $target) { throw "Backup '$Backup' not found. Run with -List to see what's available." }

    $jarPath  = Join-Path $cfg.InstallDir $cfg.JarName
    $liveHash = Get-OpsFileHash $jarPath
    Write-OpsLog "Rolling back $jarPath to backup '$($target.Name)'."
    if ($liveHash -and $target.JarSha256 -and $liveHash -eq $target.JarSha256) {
        Write-OpsLog "The deployed jar already matches this backup." "WARN"
    }
    if (-not $SkipDataRestore -and $target.HasData) { Write-OpsLog "Data folder $($cfg.DataDir) will be restored from the backup (current state is snapshotted first)." "WARN" }

    if (-not $Yes) {
        $a = Read-Host "Proceed? [y/N]"
        if ($a -notmatch '^[Yy]') { Write-OpsLog "Cancelled."; exit 0 }
    }

    $gui = @(Get-OpsJarProcesses -JarPath $jarPath | Where-Object { $_.CommandLine -notmatch "\sDaemon(\s|$)" })
    if ($gui.Count -gt 0) {
        if (-not $ForceCloseGui) { throw "The application is running (PID $($gui.ProcessId -join ', ')). Close it, or re-run with -ForceCloseGui." }
        $gui | ForEach-Object { Write-OpsLog "Closing GUI process PID $($_.ProcessId)" "WARN"; Stop-Process -Id $_.ProcessId -Force }
    }

    $ok = Invoke-OpsRollback -Config $cfg -Backup $target -SkipDataRestore:$SkipDataRestore `
                             -NoRestart:$NoRestart -HealthCheckSeconds $HealthCheckSeconds
    if ($ok) {
        Write-OpsLog "Rollback complete." "OK"
        Write-OpsLog "To undo this rollback: .\packaging\rollback.ps1 -Backup <pre-rollback_...>  (see -List)"
        Write-OpsLog "Log: $script:OpsLogFile"
        exit 0
    }
    Write-OpsLog "Files restored but the service is not healthy. Check daemon.log and Event Viewer." "ERROR"
    exit 2
}
catch {
    Write-OpsLog $_.Exception.Message "ERROR"
    exit 1
}
