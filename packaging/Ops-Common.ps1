<#
.SYNOPSIS
    Shared helpers for redeploy.ps1 and rollback.ps1. Dot-source this file;
    it does nothing on its own.

.DESCRIPTION
    Backup layout (created under <InstallDir>\backups\):

        redeploy_20260107_101500\        <- snapshot taken before a redeploy
            Monitoring-Tool.jar
            app-config.xml
            Icon.ico
            manifest.json                (reason, timestamps, jar SHA-256)
            data\                        (full copy of the data folder, always)
        pre-rollback_20260107_103000\    <- snapshot taken before a rollback,
                                            so a rollback can itself be undone
#>

$script:ServiceName = "OpsTransferToolDaemon"   # <id> in packaging\daemon-service.xml
$script:OpsLogFile  = $null

function Write-OpsLog {
    param([string]$Message, [ValidateSet("INFO","WARN","ERROR","OK")][string]$Level = "INFO")
    $color = switch ($Level) { "OK" {"Green"} "WARN" {"Yellow"} "ERROR" {"Red"} default {"Gray"} }
    $line  = "{0}  [{1}] {2}" -f (Get-Date -Format "HH:mm:ss"), $Level, $Message
    Write-Host $line -ForegroundColor $color
    if ($script:OpsLogFile) { Add-Content -Path $script:OpsLogFile -Value $line }
}

function Assert-OpsAdmin {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Run this script as Administrator (it stops/starts a Windows Service and writes to the install directory)."
    }
}

# Same app-config.xml lookup used by setup.ps1 and the step scripts.
function Get-OpsConfig {
    param([string]$ConfigRoot)
    $cfg = @{
        InstallDir = "C:\OpsTools"
        JarName    = "Monitoring-Tool.jar"
        IconName   = "Icon.ico"
        DataDir    = "C:\OpsTools\Data"
    }
    $file = if ($ConfigRoot) { Join-Path $ConfigRoot "app-config.xml" } else { $null }
    if ($file -and (Test-Path $file)) {
        try {
            [xml]$xml = Get-Content $file
            $map = @{
                InstallDir = "/application/installation/installDir"
                JarName    = "/application/installation/jarName"
                IconName   = "/application/installation/iconName"
                DataDir    = "/application/installation/dataDir"
            }
            foreach ($k in $map.Keys) {
                $n = $xml.SelectSingleNode($map[$k])
                if ($n -and -not [string]::IsNullOrEmpty($n.InnerText)) { $cfg[$k] = $n.InnerText }
            }
        } catch { Write-OpsLog "Could not parse ${file}: $_" "WARN" }
    }
    return $cfg
}

# ── Service / process control ───────────────────────────────────────────────
function Get-OpsService { Get-Service -Name $script:ServiceName -ErrorAction SilentlyContinue }

function Get-OpsJarProcesses {
    # Anything (GUI launcher, daemon JVM) whose command line references this jar.
    param([Parameter(Mandatory)][string]$JarPath)
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -in @("java.exe","javaw.exe","MonitoringTool.exe","MonitoringToolDaemon.exe") -and
            $_.CommandLine -and
            $_.CommandLine.IndexOf($JarPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
        }
}

function Stop-OpsService {
    param([int]$TimeoutSec = 60)
    $svc = Get-OpsService
    if (-not $svc) { Write-OpsLog "Service '$script:ServiceName' is not registered - nothing to stop." "WARN"; return $false }
    $wasRunning = ($svc.Status -ne "Stopped")
    if ($wasRunning) {
        Write-OpsLog "Stopping service '$script:ServiceName'..."
        Stop-Service -Name $script:ServiceName -Force -ErrorAction Stop
        try { $svc.WaitForStatus("Stopped", [TimeSpan]::FromSeconds($TimeoutSec)) }
        catch { throw "Service did not stop within $TimeoutSec s." }
        Write-OpsLog "Service stopped." "OK"
    } else {
        Write-OpsLog "Service already stopped."
    }
    return $wasRunning
}

function Start-OpsService {
    param([int]$TimeoutSec = 60)
    $svc = Get-OpsService
    if (-not $svc) { return }
    if ($svc.Status -ne "Running") {
        Write-OpsLog "Starting service '$script:ServiceName'..."
        Start-Service -Name $script:ServiceName -ErrorAction Stop
        $svc.WaitForStatus("Running", [TimeSpan]::FromSeconds($TimeoutSec))
    }
}

# Running, and still the same process after N seconds (catches crash/restart loops).
function Test-OpsHealth {
    param([int]$Seconds = 20)
    $svc = Get-OpsService
    if (-not $svc) { Write-OpsLog "Service not registered - skipping health check." "WARN"; return $true }
    $pidAtStart = (Get-CimInstance Win32_Service -Filter "Name='$script:ServiceName'").ProcessId
    Write-OpsLog "Health check: watching service for $Seconds s (PID $pidAtStart)..."
    $elapsed = 0
    while ($elapsed -lt $Seconds) {
        Start-Sleep -Seconds 2; $elapsed += 2
        $svc.Refresh()
        $pidNow = (Get-CimInstance Win32_Service -Filter "Name='$script:ServiceName'").ProcessId
        if ($svc.Status -ne "Running") { Write-OpsLog "Service status is '$($svc.Status)'." "ERROR"; return $false }
        if ($pidNow -ne $pidAtStart)   { Write-OpsLog "Service PID changed ($pidAtStart -> $pidNow): it is restarting." "ERROR"; return $false }
    }
    Write-OpsLog "Service healthy." "OK"
    return $true
}

# ── Backups ─────────────────────────────────────────────────────────────────
function Get-OpsBackupRoot([string]$InstallDir) { Join-Path $InstallDir "backups" }

function Get-OpsFileHash([string]$Path) {
    if (Test-Path $Path) { (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
}

function Copy-OpsDataDir {
    param([string]$From, [string]$To)
    # /E copies everything incl. sub-folders and logs; never deletes anything at the destination.
    & robocopy $From $To /E /R:2 /W:2 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed copying '$From' -> '$To' (exit $LASTEXITCODE)." }
    $global:LASTEXITCODE = 0
}

function New-OpsBackup {
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [ValidateSet("redeploy","pre-rollback")][string]$Reason = "redeploy"
    )
    $installDir = $Config.InstallDir
    $jar = Join-Path $installDir $Config.JarName
    if (-not (Test-Path $jar)) { Write-OpsLog "No existing $($Config.JarName) in $installDir - nothing to back up (first install?)." "WARN"; return $null }

    $name = "{0}_{1}" -f $Reason, (Get-Date -Format "yyyyMMdd_HHmmss")
    $dir  = Join-Path (Get-OpsBackupRoot $installDir) $name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null

    foreach ($f in @($Config.JarName, "app-config.xml", $Config.IconName)) {
        $src = Join-Path $installDir $f
        if (Test-Path $src) { Copy-Item $src -Destination $dir -Force }
    }
    if (Test-Path $Config.DataDir) {
        Copy-OpsDataDir -From $Config.DataDir -To (Join-Path $dir "data")
        Write-OpsLog "Full data folder backed up: $($Config.DataDir)"
    } else {
        Write-OpsLog "Data folder $($Config.DataDir) not found - backing up application files only." "WARN"
    }

    [pscustomobject]@{
        Reason      = $Reason
        CreatedAt   = (Get-Date).ToString("o")
        Host        = $env:COMPUTERNAME
        JarName     = $Config.JarName
        JarSha256   = Get-OpsFileHash $jar
        JarBytes    = (Get-Item $jar).Length
        JarModified = (Get-Item $jar).LastWriteTime.ToString("o")
        DataDir     = $Config.DataDir
        HasData     = (Test-Path (Join-Path $dir "data"))
    } | ConvertTo-Json | Set-Content -Path (Join-Path $dir "manifest.json") -Encoding UTF8

    Write-OpsLog "Backup created: $dir" "OK"
    return $name
}

function Get-OpsBackups {
    param([Parameter(Mandatory)][string]$InstallDir)
    $root = Get-OpsBackupRoot $InstallDir
    if (-not (Test-Path $root)) { return @() }
    Get-ChildItem -Path $root -Directory |
        Where-Object { $_.Name -match '^(redeploy|pre-rollback)_\d{8}_\d{6}$' } |
        ForEach-Object {
            $m = $null
            $mf = Join-Path $_.FullName "manifest.json"
            if (Test-Path $mf) { try { $m = Get-Content $mf -Raw | ConvertFrom-Json } catch {} }
            [pscustomobject]@{
                Name      = $_.Name
                Reason    = ($_.Name -split '_')[0]
                Stamp     = $_.Name.Substring($_.Name.Length - 15)
                Path      = $_.FullName
                JarSha256 = if ($m) { $m.JarSha256 } else { $null }
                JarBytes  = if ($m) { $m.JarBytes }  else { $null }
                HasData   = if ($m) { [bool]$m.HasData } else { $false }
            }
        } | Sort-Object Stamp -Descending
}

function Remove-OldOpsBackups {
    param([Parameter(Mandatory)][string]$InstallDir, [int]$Keep = 5)
    if ($Keep -lt 1) { return }
    foreach ($reason in @("redeploy","pre-rollback")) {
        Get-OpsBackups -InstallDir $InstallDir | Where-Object { $_.Reason -eq $reason } |
            Select-Object -Skip $Keep | ForEach-Object {
                Write-OpsLog "Pruning old backup: $($_.Name)"
                Remove-Item $_.Path -Recurse -Force -ErrorAction SilentlyContinue
            }
    }
}

# Copies a backup's files back over the install dir. Service/GUI must already be stopped.
function Restore-OpsBackupFiles {
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [Parameter(Mandatory)]$Backup,
        [switch]$SkipDataRestore
    )
    $installDir = $Config.InstallDir
    $backupJar  = Join-Path $Backup.Path $Config.JarName
    if (-not (Test-Path $backupJar)) { throw "Backup '$($Backup.Name)' does not contain $($Config.JarName)." }

    foreach ($f in @($Config.JarName, "app-config.xml", $Config.IconName)) {
        $src = Join-Path $Backup.Path $f
        if (Test-Path $src) { Copy-Item $src -Destination (Join-Path $installDir $f) -Force }
    }
    $expected = Get-OpsFileHash $backupJar
    $actual   = Get-OpsFileHash (Join-Path $installDir $Config.JarName)
    if ($expected -ne $actual) { throw "Restored jar hash mismatch (expected $expected, got $actual)." }
    Write-OpsLog "Restored $($Config.JarName), app-config.xml and icon from '$($Backup.Name)' (SHA-256 verified)." "OK"

    if (-not $SkipDataRestore) {
        $dataBackup = Join-Path $Backup.Path "data"
        if (Test-Path $dataBackup) {
            Copy-OpsDataDir -From $dataBackup -To $Config.DataDir
            Write-OpsLog "Data folder restored from backup (existing files overwritten, nothing deleted)." "OK"
        } else {
            Write-OpsLog "Backup '$($Backup.Name)' has no data snapshot - data folder left as is." "WARN"
        }
    }
}

# Stop -> snapshot current state -> restore -> start -> health check.
function Invoke-OpsRollback {
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [Parameter(Mandatory)]$Backup,
        [switch]$SkipDataRestore,
        [switch]$NoRestart,
        [int]$HealthCheckSeconds = 20
    )
    Stop-OpsService | Out-Null
    $jarPath = Join-Path $Config.InstallDir $Config.JarName
    Get-OpsJarProcesses -JarPath $jarPath | ForEach-Object {
        Write-OpsLog "Stopping leftover process $($_.Name) (PID $($_.ProcessId))" "WARN"
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2

    # Make the rollback itself reversible.
    New-OpsBackup -Config $Config -Reason "pre-rollback" | Out-Null

    Restore-OpsBackupFiles -Config $Config -Backup $Backup -SkipDataRestore:$SkipDataRestore

    if ($NoRestart) { Write-OpsLog "-NoRestart set - leaving service stopped." "WARN"; return $true }
    if (-not (Get-OpsService)) { return $true }
    Start-OpsService
    return (Test-OpsHealth -Seconds $HealthCheckSeconds)
}
