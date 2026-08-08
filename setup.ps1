# ============================================================
#  setup.ps1  --  One-click installer for Monitoring tool
#  Run as Administrator (right-click -> Run with PowerShell)
#  Tested on Windows 10 / Windows 11 / Windows Server 2019+
#
#  Prerequisites (place in the same folder as this script):
#    OpsTransferTool.jar         -- Prebuilt application JAR
#    OpenJDK21-jdk.msi           -- Temurin JDK 21 offline installer
#    WinSCP-Setup.exe            -- WinSCP offline installer
#    test-elevation.bat          -- Elevation test/launcher batch file
#    rcedit.exe                  -- OPTIONAL: renames the daemon process as
#                                    it appears in Task Manager (otherwise it
#                                    shows as "OpenJDK Platform Binary"). Get
#                                    it from https://github.com/electron/rcedit/releases
#
#  This script will:
#    1. Check / install Temurin JDK 21 (includes javac)
#    2. Check / install WinSCP
#    3. Deploy OpsTransferTool.jar to C:\OpsTools
#    4. Create Desktop shortcut + Start Menu entry (all users)
#       - Desktop shortcut launches test-elevation.bat with admin
#         privileges silently (no visible window)
#    5. Register background daemon (Windows Task Scheduler)
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

# Bootstrap log file so Log/FAIL already work while app-config.xml is being
# loaded below (Load-XmlConfig calls Log on a missing/bad config file, and
# at that point the config-driven log path further down hasn't been read
# yet). Once the config is loaded, $LogDir/$LogFile are recomputed from it
# and everything after that point respects the configured logDir; only the
# handful of bootstrap-phase lines above stay in this default-location file.
$LogDir  = Join-Path $ScriptRoot "logs"
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$LogFile = Join-Path $LogDir "opstool-setup_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

function Load-XmlConfig {
    $configFile = Join-Path $ScriptRoot "app-config.xml"
    if (-not (Test-Path $configFile)) {
        Log "WARNING: app-config.xml not found. Using default configuration."
        return $null
    }
    try {
        [xml]$config = Get-Content $configFile
        Log "Loaded configuration from: $configFile"
        return $config
    } catch {
        Log "WARNING: Failed to parse app-config.xml: $_"
        return $null
    }
}

function Get-ConfigValue {
    param(
        [xml]$config,
        [string]$xpath,
        [string]$defaultValue
    )
    if ($null -eq $config) {
        return $defaultValue
    }
    try {
        $node = $config.SelectSingleNode($xpath)
        if ($null -ne $node) {
            $value = $node.InnerText
            if (-not [string]::IsNullOrEmpty($value)) {
                return $value
            }
        }
    } catch {
    }
    return $defaultValue
}

$xmlConfig = Load-XmlConfig

$InstallDir   = Get-ConfigValue $xmlConfig "/application/installation/installDir" "C:\OpsTools"
$JarName      = Get-ConfigValue $xmlConfig "/application/installation/jarName" "Monitoring-Tool.jar"

$JavaMsi      = Join-Path $ScriptRoot (Get-ConfigValue $xmlConfig "/application/java/installerFile" "OpenJDK21-jdk.msi")
$WinScpExe    = Join-Path $ScriptRoot (Get-ConfigValue $xmlConfig "/application/tools/winSCP/installerFile" "WinSCP-Setup.exe")
$SourceJar    = Join-Path $ScriptRoot $JarName

$ElevationBat = Join-Path $ScriptRoot "test-elevation.bat"

$WinScpCom    = "C:\Program Files (x86)\WinSCP\WinSCP.com"
$WinScpCom64  = "C:\Program Files\WinSCP\WinSCP.com"

$ShortcutName = Get-ConfigValue $xmlConfig "/application/metadata/appName" "Monitoring tool"
$IconName     = Get-ConfigValue $xmlConfig "/application/installation/iconName" "Icon.ico"
$IconSource   = Join-Path $ScriptRoot $IconName
$InstalledIcon = Join-Path $InstallDir $IconName
$IconLocation = "$InstalledIcon,0"
if (-not (Test-Path $IconSource)) {
    $foundIcon = Get-ChildItem -Path $ScriptRoot -Filter "*.ico" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($foundIcon) {
        $IconSource = $foundIcon.FullName
        Log "Found fallback icon source: $IconSource"
    }
}
if (-not (Test-Path $IconSource)) {
    $fallbackIcon = Get-ConfigValue $xmlConfig "/application/installation/fallbackIcon" "C:\Windows\System32\imageres.dll,19"
    $IconLocation = $fallbackIcon
}

# Re-point logging at the configured logDir (falls back to the same "logs"
# folder used for bootstrap above if app-config.xml didn't specify one).
$logDir = Get-ConfigValue $xmlConfig "/application/logging/logDir" "logs"
$LogDir  = Join-Path $ScriptRoot $logDir
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$LogFile = Join-Path $LogDir "opstool-setup_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

function Find-JavaBin {
    if ($env:JAVA_HOME) {
        if (Test-Path (Join-Path $env:JAVA_HOME "bin\javaw.exe")) {
            return (Join-Path $env:JAVA_HOME "bin")
        }
    }
    $jc = Get-Command javaw -ErrorAction SilentlyContinue
    if ($jc) { return (Split-Path $jc.Source) }
    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Eclipse Foundation",
        "C:\Program Files\Temurin",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Java",
        "C:\Program Files (x86)\Java"
    )
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $found = Get-ChildItem "$root" -Recurse -Filter "javaw.exe" -ErrorAction SilentlyContinue |
                     Select-Object -First 1
            if ($found) { return $found.DirectoryName }
        }
    }
    foreach ($rp in @("HKLM:\SOFTWARE\Eclipse Adoptium\JDK",
                       "HKLM:\SOFTWARE\JavaSoft\JDK",
                       "HKLM:\SOFTWARE\JavaSoft\Java Development Kit")) {
        if (Test-Path $rp) {
            try {
                Get-ChildItem $rp -ErrorAction SilentlyContinue | ForEach-Object {
                    $subkeys = Get-ChildItem $_.PSPath -ErrorAction SilentlyContinue
                    $target  = if ($subkeys) { $subkeys | Select-Object -Last 1 } else { $_ }
                    $jHome   = (Get-ItemProperty $target.PSPath -ErrorAction SilentlyContinue).JavaHome
                    if ($jHome -and (Test-Path (Join-Path $jHome "bin\javaw.exe"))) {
                        return (Join-Path $jHome "bin")
                    }
                }
            } catch {}
        }
    }
    try {
        $prod = Get-WmiObject -Class Win32_Product -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like "*JDK*" -or $_.Name -like "*Temurin*" } |
                Select-Object -First 1
        if ($prod) {
            $loc = $prod.InstallLocation
            if ($loc -and (Test-Path (Join-Path $loc "bin\javaw.exe"))) {
                return (Join-Path $loc "bin")
            }
        }
    } catch {}
    return $null
}

function Get-JavaMajorVersion($binDir) {
    try {
        $out = & "$binDir\java.exe" -version 2>&1
        $ver = ($out | Select-String "version").ToString()
        if ($ver -match '"1\.(\d+)') { return [int]$Matches[1] }
        if ($ver -match '"(\d+)')    { return [int]$Matches[1] }
    } catch {}
    return 0
}

Clear-Host
Write-Host ""
Write-Host "  +======================================================+" -ForegroundColor Cyan
Write-Host "  |       Monitoring tool  --  One-Click Setup           |" -ForegroundColor Cyan
Write-Host "  +======================================================+" -ForegroundColor Cyan
Write-Host ""
Write-Host "  This script will:"
Write-Host "    1. Check / install Java JDK 21 (Temurin LTS)"
Write-Host "    2. Check / install WinSCP"
Write-Host "    3. Deploy Monitoring-Tool.jar to $InstallDir"
Write-Host "    4. Create Desktop shortcut + Start Menu entry (all users)"
Write-Host "    5. Register background daemon (Windows Task Scheduler)"
Write-Host ""
Write-Host "  Log: $LogFile"
Write-Host ""
$confirm = Read-Host "Continue? [Y/n]"
if ($confirm -match "^[Nn]") { exit 0 }

if (-not (Test-Path $SourceJar)) {
    FAIL "$JarName not found next to setup.ps1.`n     Place $JarName in the same folder as setup.ps1 and re-run."
}

$jarSize = (Get-Item $SourceJar).Length
Log "Found prebuilt JAR: $SourceJar ($jarSize bytes)"
OK "JAR ready: $SourceJar"

if (-not (Test-Path $ElevationBat)) {
    FAIL "test-elevation.bat not found in: $ScriptRoot`n     Place it in the same folder as setup.ps1 and re-run."
}
OK "Found: $ElevationBat"

$javaBin = Find-JavaBin

if ($javaBin) {
    $ver = Get-JavaMajorVersion $javaBin
    if ($ver -ge 11) {
        OK "JDK $ver found: $javaBin"
    } else {
        Log "JDK $ver is too old (need 11+). Installing Temurin JDK 21 from local file..."
        $javaBin = $null
    }
}

if (-not $javaBin) {
    try {
        $existingJdk = Get-WmiObject -Class Win32_Product -ErrorAction SilentlyContinue |
                       Where-Object { ($_.Name -like "*JDK*" -or $_.Name -like "*Temurin*") -and
                                      [version]$_.Version -ge [version]"11.0" } |
                       Select-Object -First 1
        if ($existingJdk) {
            Log "WARNING: $($existingJdk.Name) v$($existingJdk.Version) is installed but was not auto-detected."
            Log "Attempting forced PATH refresh to locate it..."
            $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" +
                        [System.Environment]::GetEnvironmentVariable("PATH","User")
            $javaBin = Find-JavaBin
            if ($javaBin) {
                OK "JDK located after PATH refresh: $javaBin"
            } else {
                FAIL "JDK is installed ($($existingJdk.Name)) but javaw.exe cannot be found.`n     Try restarting this machine and re-running setup.ps1."
            }
        }
    } catch {}
}

if (-not $javaBin) {
    if (-not (Test-Path $JavaMsi)) {
        FAIL "OpenJDK21-jdk.msi not found next to setup.ps1.`n     Copy the Temurin JDK 21 MSI installer into the same folder and re-run."
    }
    OK "Found offline JDK installer: $JavaMsi"
    Log "Installing JDK silently (this may take a minute)..."

    $jdkInstallLog = "C:\Windows\Temp\jdk-install.log"
    $attempt = 0
    do {
        $attempt++
        if ($attempt -gt 1) {
            Log "Another installer is running. Waiting 30s (attempt $attempt/5)..."
            Start-Sleep -Seconds 30
        }
        $msiArgs = @("/i", $JavaMsi, "/quiet", "/norestart",
                     "ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome",
                     "/L*V", $jdkInstallLog)
        $proc = Start-Process "msiexec.exe" -ArgumentList $msiArgs -Wait -PassThru
    } while ($proc.ExitCode -eq 1618 -and $attempt -lt 5)

    if ($proc.ExitCode -notin @(0, 3010)) {
        FAIL "JDK installer exited with code $($proc.ExitCode). See $jdkInstallLog"
    }

    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("PATH","User")

    $javaBin = Find-JavaBin
    if (-not $javaBin) {
        FAIL "JDK installed but javaw.exe not found. Restart this machine and re-run setup."
    }
    OK "JDK installed: $javaBin"
}

$JavawExe = Join-Path $javaBin "javaw.exe"
if (-not (Test-Path $JavawExe)) { $JavawExe = Join-Path $javaBin "java.exe" }

$resolvedWinScp = $null
if (Test-Path $WinScpCom)   { $resolvedWinScp = $WinScpCom }
if (Test-Path $WinScpCom64) { $resolvedWinScp = $WinScpCom64 }
if (-not $resolvedWinScp) {
    $ws = Get-Command "WinSCP.com" -ErrorAction SilentlyContinue
    if ($ws) { $resolvedWinScp = $ws.Source }
}

if ($resolvedWinScp) {
    OK "WinSCP already installed: $resolvedWinScp"
} else {
    if (-not (Test-Path $WinScpExe)) {
        FAIL "WinSCP-Setup.exe not found next to setup.ps1.`n     Copy the WinSCP installer into the same folder and re-run."
    }
    OK "Found offline WinSCP installer: $WinScpExe"
    Log "Installing WinSCP silently..."
    $proc = Start-Process $WinScpExe -ArgumentList "/VERYSILENT /NORESTART /ALLUSERS" -Wait -PassThru
    if ($proc.ExitCode -notin @(0, 3010)) {
        FAIL "WinSCP installer exited with code $($proc.ExitCode)."
    }

    if      (Test-Path $WinScpCom)   { $resolvedWinScp = $WinScpCom }
    elseif  (Test-Path $WinScpCom64) { $resolvedWinScp = $WinScpCom64 }
    else    { FAIL "WinSCP installed but WinSCP.com not found. Check C:\Program Files." }

    OK "WinSCP installed: $resolvedWinScp"
}

if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir | Out-Null
    Log "Created directory: $InstallDir"
}

$destJar = Join-Path $InstallDir $JarName
Log "Copying JAR to: $destJar"

try {
    Copy-Item -Path $SourceJar -Destination $destJar -Force
    OK "Deployed: $destJar"
} catch {
    FAIL "Failed to deploy JAR: $_"
}

$destBat = Join-Path $InstallDir "test-elevation.bat"
try {
    Copy-Item -Path $ElevationBat -Destination $destBat -Force
    Log "Copied test-elevation.bat to: $destBat"
    OK "Deployed: $destBat"
} catch {
    FAIL "Failed to copy test-elevation.bat to $InstallDir : $_"
}

# app-config.xml was only ever read from $ScriptRoot (this installer
# package's own folder) - it was never actually deployed to $InstallDir.
# That's harmless for settings baked into the scheduled task's command line
# at install time (dataDir), but every setting the running app reads live
# from app-config.xml at runtime (SITA station-code lookup, default station
# address, LDM/PTM/Others folder names, etc.) was silently unreadable in
# production: neither the GUI nor the Daemon had a copy of this file
# anywhere they'd look for it, so those always fell back to hardcoded
# defaults regardless of what was actually configured here.
$destConfigFile = Join-Path $InstallDir "app-config.xml"
$sourceConfigFile = Join-Path $ScriptRoot "app-config.xml"
if (Test-Path $sourceConfigFile) {
    try {
        Copy-Item -Path $sourceConfigFile -Destination $destConfigFile -Force
        Log "Copied app-config.xml to: $destConfigFile"
        OK "Deployed: $destConfigFile"
    } catch {
        Log "WARNING: Failed to copy app-config.xml to $InstallDir : $_"
    }
} else {
    Log "WARNING: app-config.xml not found at $sourceConfigFile - runtime settings (station codes, default address, LDM/PTM folder names) will use hardcoded defaults."
}

if (Test-Path $IconSource) {
    try {
        Copy-Item -Path $IconSource -Destination $InstalledIcon -Force
        Log "Copied icon to: $InstalledIcon"
        OK "Deployed icon: $InstalledIcon"
        $IconLocation = "$InstalledIcon,0"
    } catch {
        Log "WARNING: Could not copy custom icon: $_"
        $IconLocation = "C:\Windows\System32\imageres.dll,19"
    }
} else {
    Log "Icon source not found: $IconSource. Falling back to default system icon."
    $IconLocation = "C:\Windows\System32\imageres.dll,19"
}

$psArgs = "-WindowStyle Hidden -ExecutionPolicy Bypass -Command " +
          "`"Start-Process -FilePath '$destBat' -Verb RunAs -WindowStyle Hidden`""

$WshShell = New-Object -ComObject WScript.Shell

$desktopPath = [Environment]::GetFolderPath("CommonDesktopDirectory")
$desktopLink = Join-Path $desktopPath "$ShortcutName.lnk"

$shortcut = $WshShell.CreateShortcut($desktopLink)
$shortcut.TargetPath       = "powershell.exe"
$shortcut.Arguments        = $psArgs
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Description      = "Monitoring tool"
$shortcut.WindowStyle      = 7
$shortcut.IconLocation     = $IconLocation
$shortcut.Save()

try {
    $lnkBytes = [System.IO.File]::ReadAllBytes($desktopLink)
    $lnkBytes[0x15] = $lnkBytes[0x15] -bor 0x20
    [System.IO.File]::WriteAllBytes($desktopLink, $lnkBytes)
    OK "Desktop shortcut created (elevated, hidden window): $desktopLink"
} catch {
    Log "WARNING: Could not patch elevation flag on desktop shortcut: $_"
    OK "Desktop shortcut created: $desktopLink"
}

$startMenuDir = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "Ops Tools"
if (-not (Test-Path $startMenuDir)) {
    New-Item -ItemType Directory -Path $startMenuDir | Out-Null
}
$startMenuLink = Join-Path $startMenuDir "$ShortcutName.lnk"

$shortcut = $WshShell.CreateShortcut($startMenuLink)
$shortcut.TargetPath       = "powershell.exe"
$shortcut.Arguments        = $psArgs
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Description      = "Monitoring tool"
$shortcut.WindowStyle      = 7
$shortcut.IconLocation     = $IconLocation
$shortcut.Save()

try {
    $lnkBytes = [System.IO.File]::ReadAllBytes($startMenuLink)
    $lnkBytes[0x15] = $lnkBytes[0x15] -bor 0x20
    [System.IO.File]::WriteAllBytes($startMenuLink, $lnkBytes)
    OK "Start Menu shortcut created (elevated, hidden window): $startMenuLink"
} catch {
    Log "WARNING: Could not patch elevation flag on Start Menu shortcut: $_"
    OK "Start Menu shortcut created: $startMenuLink"
}

$daemonEnabled = Get-ConfigValue $xmlConfig "/application/daemon/enabled" "true"
if ($daemonEnabled -ne "true") {
    SKIP "Daemon registration disabled in configuration"
} else {
    $dataDir = Get-ConfigValue $xmlConfig "/application/installation/dataDir" "C:\OpsTools\Data"
    if (-not (Test-Path $dataDir)) {
        New-Item -ItemType Directory -Path $dataDir | Out-Null
        Log "Created data directory: $dataDir"
    }

    $taskName = Get-ConfigValue $xmlConfig "/application/daemon/taskName" "Monitoring-Tool-Daemon"
    $startupDelayMinutes = Get-ConfigValue $xmlConfig "/application/daemon/startupTrigger/delayMinutes" "1"
    $periodicIntervalMinutes = Get-ConfigValue $xmlConfig "/application/daemon/periodicTrigger/intervalMinutes" "60"
    $restartCount = Get-ConfigValue $xmlConfig "/application/daemon/recovery/restartCount" "3"
    $restartIntervalMinutes = Get-ConfigValue $xmlConfig "/application/daemon/recovery/restartIntervalMinutes" "5"

    try {
        # Stop any already-running daemon process before touching the task
        # registration. Unregister-ScheduledTask below only removes the task
        # definition - it does NOT terminate a process the task already
        # spawned, so without this, every redeploy orphans the previous
        # daemon process while starting a new one, and they accumulate
        # across redeploys. Matched by full command line (jar path + "Daemon"
        # argument) rather than process name alone, so this never touches
        # unrelated java.exe processes that might be running on the machine
        # for other reasons. Checks both "java.exe" (older installs, or if
        # the renamed-copy step below ever falls back to it) and
        # "MonitoringToolDaemon.exe" (the renamed copy used going forward -
        # see the daemon launcher section below).
        try {
            $daemonProcs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'MonitoringToolDaemon.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -and $_.CommandLine -like "*$destJar*" -and $_.CommandLine -like "*Daemon*" }
            foreach ($proc in $daemonProcs) {
                Log "Stopping existing daemon process (PID $($proc.ProcessId))"
                Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
            }
            if ($daemonProcs) { Start-Sleep -Milliseconds 500 }
        } catch {
            Log "WARNING: could not enumerate/stop existing daemon processes: $($_.Exception.Message)"
        }

        $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($existingTask) {
            Log "Removing old scheduled task: $taskName"
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 500
        }

        Log "Registering background daemon task: $taskName"
        $javaExe = Join-Path $javaBin "java.exe"
        if (-not (Test-Path $javaExe)) {
            Log "WARNING: java.exe not found at expected path: $javaExe"
            Log "Falling back to PATH search for java.exe..."
            $javaExe = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
            if (-not $javaExe) {
                FAIL "java.exe not found in system PATH. Please ensure Java JDK is properly installed."
            }
            Log "Found java.exe at: $javaExe"
        }

        # Task Manager's "Name" column for java.exe is read from the
        # FileDescription field in the exe's own embedded version resource
        # ("OpenJDK Platform Binary" for every stock OpenJDK build) - it is
        # NOT derived from the command line or filename, so simply renaming
        # a copy of java.exe would still show "OpenJDK Platform Binary"
        # unless that embedded metadata is actually patched.
        #
        # The copy must live in the SAME bin directory as the real java.exe:
        # the launcher locates jvm.dll and the rest of the JDK via a path
        # relative to its own location, so copying it elsewhere (e.g. into
        # $InstallDir) would break it at startup.
        $daemonExeName = "MonitoringToolDaemon.exe"
        $daemonExe = Join-Path $javaBin $daemonExeName
        try {
            Copy-Item -Path $javaExe -Destination $daemonExe -Force
            Log "Created daemon launcher copy: $daemonExe"

            # rcedit (https://github.com/electron/rcedit) is the standard
            # tool for patching a Windows exe's version resource. It's an
            # OPTIONAL local prerequisite (same offline-only convention as
            # the rest of this installer) - if it isn't present, the copy is
            # still used (so the kill-before-redeploy logic above has a
            # single, precisely-matchable target), it just keeps showing
            # "OpenJDK Platform Binary" in Task Manager until rcedit.exe is
            # dropped alongside this script and setup is re-run.
            $rceditExe = Join-Path $ScriptRoot "rcedit.exe"
            if (Test-Path $rceditExe) {
                & $rceditExe $daemonExe --set-version-string "FileDescription" "Monitoring Tool Daemon"
                & $rceditExe $daemonExe --set-version-string "ProductName" "Monitoring Tool Daemon"
                & $rceditExe $daemonExe --set-version-string "OriginalFilename" $daemonExeName
                & $rceditExe $daemonExe --set-version-string "InternalName" "MonitoringToolDaemon"
                Log "Patched daemon launcher version info via rcedit - Task Manager will show 'Monitoring Tool Daemon'"
            } else {
                Log "NOTE: rcedit.exe not found next to this script - daemon process will still show as 'OpenJDK Platform Binary' in Task Manager. Download it from https://github.com/electron/rcedit/releases, place rcedit.exe alongside setup.ps1, and re-run setup to fix this."
            }
        } catch {
            Log "WARNING: could not create/patch daemon launcher copy ($($_.Exception.Message)) - falling back to java.exe directly."
            $daemonExe = $javaExe
        }

        Log "Daemon command: $daemonExe -cp `"$destJar`" Daemon `"$dataDir`""

        $daemonArgument = '-cp "' + $destJar + '" Daemon "' + $dataDir + '"'
        $taskActionParams = @{
            Execute          = $daemonExe
            Argument         = $daemonArgument
            WorkingDirectory = $InstallDir
        }
        $taskAction = New-ScheduledTaskAction @taskActionParams

        $trigStart = New-ScheduledTaskTrigger -AtStartup
        $trigStart.Delay = "PT${startupDelayMinutes}M"

        $trigHourly = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes $periodicIntervalMinutes)

        $settingsParams = @{
            ExecutionTimeLimit        = [TimeSpan]::Zero
            RestartCount               = $restartCount
            RestartInterval             = (New-TimeSpan -Minutes $restartIntervalMinutes)
            MultipleInstances          = "IgnoreNew"
            AllowStartIfOnBatteries    = $true
            DontStopIfGoingOnBatteries = $true
            StartWhenAvailable         = $true
        }
        $settings = New-ScheduledTaskSettingsSet @settingsParams

        $registerParams = @{
            TaskName = $taskName
            Action   = $taskAction
            Trigger  = @($trigStart, $trigHourly)
            Settings = $settings
            RunLevel = "Highest"
            User     = "SYSTEM"
            Force    = $true
        }
        Register-ScheduledTask @registerParams | Out-Null

        OK "Daemon registered as Windows Scheduled Task: $taskName"
        Log "Trigger 1: At startup ($startupDelayMinutes minute delay)"
        Log "Trigger 2: Every $periodicIntervalMinutes minutes (redundancy)"
        Log "Account: SYSTEM (no user login required)"
        Log "Working directory: $InstallDir"

        Start-Sleep -Milliseconds 1000
        $registeredTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($registeredTask) {
            OK "Verified scheduled task exists: $taskName"
            Log "Task state: $($registeredTask.State)"
        } else {
            Log "WARNING: Scheduled task was not found immediately after registration"
            Log "This might be normal; task should appear shortly"
        }

        Log "Starting daemon task now..."
        Start-Sleep -Milliseconds 1000
        try {
            Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
            OK "Daemon task started from Task Scheduler"
            Log "Daemon should be running now. Check daemon.log in data directory for details."
        } catch {
            Log "WARNING: Could not start daemon immediately (it will run at next trigger)"
        }
    } catch {
        $errorMessage = if ($_.Exception) { $_.Exception.Message } else { $_.ToString() }
        $errorStack   = if ($_.Exception) { $_.Exception.StackTrace } else { $_ | Out-String }
        Log "WARNING: Could not register daemon: $errorMessage"
        Log "WARNING: Registration exception details: $errorStack"
        Log "You can register manually later via Settings tab or run:"
        Log "  powershell -Command `".\setup.ps1`" (re-run this script)"
    }
}

Write-Host ""
Write-Host "  v Java JDK installed/verified"
Write-Host "  v WinSCP installed/verified"
Write-Host "  v JAR deployed to: $destJar"
Write-Host "  v test-elevation.bat deployed to: $destBat"
Write-Host "  v Desktop shortcut -> runs test-elevation.bat (elevated, no window)"
Write-Host "  v Start Menu entry -> same"
Write-Host "  [OK] Daemon registered (if available)"
Write-Host ""
Write-Host "  Launch the application:"
Write-Host "    * Start Menu -> Ops Tools -> Monitoring tool"
Write-Host "    * Or double-click Desktop shortcut"
Write-Host "    * A UAC prompt will appear once; accept it."
Write-Host "      test-elevation.bat then runs silently in the background."
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