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

# -- XML Configuration Loader ------------------------------------------------

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
        # Fall through to return default
    }
    return $defaultValue
}

# -- Config -------------------------------------------------------------------

# Load XML configuration
$xmlConfig = Load-XmlConfig

# Installation paths
$InstallDir   = Get-ConfigValue $xmlConfig "/application/installation/installDir" "C:\OpsTools"
$JarName      = Get-ConfigValue $xmlConfig "/application/installation/jarName" "Monitoring-Tool.jar"

# *** OFFLINE: all files must sit next to setup.ps1 ***
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

# Logging
$logDir = Get-ConfigValue $xmlConfig "/application/logging/logDir" "logs"
$LogDir  = Join-Path $ScriptRoot $logDir
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$LogFile = Join-Path $LogDir "opstool-setup_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

# -- Helpers ------------------------------------------------------------------

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

# -- Java JDK discovery -------------------------------------------------------

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

# -- Banner -------------------------------------------------------------------

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

# -- 1. Locate prebuilt JAR ---------------------------------------------------

Step "Locating prebuilt JAR"

if (-not (Test-Path $SourceJar)) {
    FAIL "$JarName not found next to setup.ps1.`n     Place $JarName in the same folder as setup.ps1 and re-run."
}

$jarSize = (Get-Item $SourceJar).Length
Log "Found prebuilt JAR: $SourceJar ($jarSize bytes)"
OK "JAR ready: $SourceJar"

# -- 1b. Verify test-elevation.bat is present ---------------------------------

Step "Verifying test-elevation.bat"

if (-not (Test-Path $ElevationBat)) {
    FAIL "test-elevation.bat not found in: $ScriptRoot`n     Place it in the same folder as setup.ps1 and re-run."
}
OK "Found: $ElevationBat"

# -- 2. Java JDK --------------------------------------------------------------

Step "Checking Java JDK (required to run the application)"

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

# -- 3. WinSCP ----------------------------------------------------------------

Step "Checking WinSCP"

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

# -- 4. Deploy to C:\OpsTools -------------------------------------------------

Step "Deploying to $InstallDir"

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

# -- 5. Create shortcuts ------------------------------------------------------

Step "Creating shortcuts"

$psArgs = "-WindowStyle Hidden -ExecutionPolicy Bypass -Command " +
          "`"Start-Process -FilePath '$destBat' -Verb RunAs -WindowStyle Hidden`""

$WshShell = New-Object -ComObject WScript.Shell

# -- Desktop shortcut (all users) --
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

# -- Start Menu shortcut (all users) --
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

# -- 6. Register daemon -------------------------------------------------------

Step "Registering background daemon"

# Load daemon configuration from XML
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
        # Unregister old task if it exists
        $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($existingTask) {
            Log "Removing old scheduled task: $taskName"
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 500
        }

        Log "Registering background daemon task: $taskName"
        $javaExe = Join-Path $javaBin "java.exe"
        Log "Daemon command: $javaExe -cp `"$destJar`" Daemon `"$dataDir`""
        if (-not (Test-Path $javaExe)) {
            Log "WARNING: java.exe not found at expected path: $javaExe"
            Log "Falling back to PATH search for java.exe..."
            $javaExe = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
            if (-not $javaExe) {
                FAIL "java.exe not found in system PATH. Please ensure Java JDK is properly installed."
            }
            Log "Found java.exe at: $javaExe"
        }

        # Create task action with working directory set to installation directory
        $taskAction = New-ScheduledTaskAction `
            -Execute $javaExe `
            -Argument "-cp `"$destJar`" Daemon `"$dataDir`"" `
            -WorkingDirectory $InstallDir

        # Startup trigger (configurable delay to allow system to stabilize)
        $trigStart = New-ScheduledTaskTrigger -AtStartup
        $trigStart.Delay = "PT${startupDelayMinutes}M"

        # Hourly trigger for redundancy (configurable interval)
        $trigHourly = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes $periodicIntervalMinutes)

        # Task settings with restart recovery
        $settings = New-ScheduledTaskSettingsSet `
            -ExecutionTimeLimit ([TimeSpan]::Zero) `
            -RestartCount $restartCount `
            -RestartInterval (New-TimeSpan -Minutes $restartIntervalMinutes) `
            -MultipleInstances IgnoreNew `
            -AllowStartIfOnBatteries `
            -DontStopIfGoingOnBatteries `
            -StartWhenAvailable

        # Register the scheduled task
        Register-ScheduledTask -TaskName $taskName `
                              -Action $taskAction `
                              -Trigger $trigStart, $trigHourly `
                              -Settings $settings `
                              -RunLevel Highest `
                              -User "SYSTEM" `
                              -Force | Out-Null

        OK "Daemon registered as Windows Scheduled Task: $taskName"
        Log "Trigger 1: At startup (${startupDelayMinutes} minute delay)"
        Log "Trigger 2: Every ${periodicIntervalMinutes} minutes (redundancy)"
        Log "Account: SYSTEM (no user login required)"
        Log "Working directory: $InstallDir"

        # Verify task was registered
        Start-Sleep -Milliseconds 1000
        $registeredTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($registeredTask) {
            OK "Verified scheduled task exists: $taskName"
            Log "Task state: $($registeredTask.State)"
        } else {
            Log "WARNING: Scheduled task was not found immediately after registration"
            Log "This might be normal; task should appear shortly"
        }

        # Start daemon now (with delay to allow task to fully register)
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

# -- 7. Summary ---------------------------------------------------------------

Step "Installation Complete!"

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