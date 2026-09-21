<#
.SYNOPSIS
    Checks for (and, if missing, silently installs) both required
    third-party prerequisites: a Java 11+ JDK and WinSCP. One script
    because both are the same shape of work - detect, else install
    silently from a local offline installer - rather than two nearly
    identical scripts. One of the individual deployment steps setup.ps1
    calls in sequence - see packaging/README.md for the full pipeline.

.DESCRIPTION
    Designed to be runnable with NO parameters at all when run from the
    folder that has the offline installers sitting next to it (the same
    layout setup.ps1 expects): it reads file names from app-config.xml if
    present, otherwise falls back to the project's default file names
    (OpenJDK21-jdk.msi, WinSCP-Setup.exe). Pass -ScriptRoot only if
    you're running this from somewhere other than that folder, or the
    individual -JavaMsiPath/-WinScpExePath overrides for anything else
    non-standard.

    Requires an elevated (Administrator) PowerShell session only if an
    actual install is needed - pure detection doesn't need elevation.

.PARAMETER ScriptRoot
    Folder to look for the offline installers and app-config.xml in.
    Defaults to the current directory, then falls back to the repo root
    (two levels above this script) if nothing is found there - covering
    both "run directly from the release folder" and "run in place inside
    the cloned repo" without needing this parameter either way.

.PARAMETER JavaMsiPath
    Explicit override for the JDK installer path. Normally auto-resolved
    from app-config.xml's /application/java/installerFile, defaulting to
    "OpenJDK21-jdk.msi" in -ScriptRoot.

.PARAMETER WinScpExePath
    Explicit override for the WinSCP installer path. Normally
    auto-resolved from app-config.xml's
    /application/tools/winSCP/installerFile, defaulting to
    "WinSCP-Setup.exe" in -ScriptRoot.

.PARAMETER LogFile
    Optional path to append timestamped log lines to, so this step's
    output lands in the same log file as the rest of setup.ps1's run.
    Safe to omit when running this script standalone.

.OUTPUTS
    [pscustomobject] with JavaBin, JavawExe, and WinScpComPath.

.EXAMPLE
    .\Install-Prerequisites.ps1
    Run with no arguments from the release folder - detects/installs both,
    using whatever app-config.xml sitting next to it (or the project
    defaults) says the installer file names are.

.EXAMPLE
    .\Install-Prerequisites.ps1 -JavaMsiPath "D:\installers\jdk21.msi"
#>
[CmdletBinding()]
param(
    [string]$ScriptRoot    = $null,
    [string]$JavaMsiPath   = $null,
    [string]$WinScpExePath = $null,
    [string]$LogFile       = $null
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    $line = "[install-prerequisites] $msg"
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

# ── Resolve ScriptRoot + app-config.xml (best-effort; nothing here is
#    required to exist - every value just falls back to a hardcoded
#    project default if config can't be found or parsed) ──────────────────
if (-not $ScriptRoot) {
    $candidate1 = (Get-Location).Path
    $candidate2 = if ($PSScriptRoot) { Split-Path (Split-Path $PSScriptRoot -Parent) -Parent } else { $null }
    if (Test-Path (Join-Path $candidate1 "app-config.xml")) { $ScriptRoot = $candidate1 }
    elseif ($candidate2 -and (Test-Path (Join-Path $candidate2 "app-config.xml"))) { $ScriptRoot = $candidate2 }
    else { $ScriptRoot = $candidate1 }
}

$xmlConfig = $null
$configFile = Join-Path $ScriptRoot "app-config.xml"
if (Test-Path $configFile) {
    try { [xml]$xmlConfig = Get-Content $configFile } catch { Log "WARNING: Failed to parse app-config.xml: $_" }
}
function Get-ConfigValue([string]$xpath, [string]$defaultValue) {
    if ($null -eq $xmlConfig) { return $defaultValue }
    try {
        $node = $xmlConfig.SelectSingleNode($xpath)
        if ($node -and -not [string]::IsNullOrEmpty($node.InnerText)) { return $node.InnerText }
    } catch {}
    return $defaultValue
}

if (-not $JavaMsiPath) {
    $JavaMsiPath = Join-Path $ScriptRoot (Get-ConfigValue "/application/java/installerFile" "OpenJDK21-jdk.msi")
}
if (-not $WinScpExePath) {
    $WinScpExePath = Join-Path $ScriptRoot (Get-ConfigValue "/application/tools/winSCP/installerFile" "WinSCP-Setup.exe")
}

function Test-Admin {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ── Java ─────────────────────────────────────────────────────────────────
function Find-JavaBin {
    if ($env:JAVA_HOME) {
        if (Test-Path (Join-Path $env:JAVA_HOME "bin\javaw.exe")) {
            return (Join-Path $env:JAVA_HOME "bin")
        }
    }
    $jc = Get-Command javaw -ErrorAction SilentlyContinue
    if ($jc) { return (Split-Path $jc.Source) }
    $roots = @(
        "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Eclipse Foundation",
        "C:\Program Files\Temurin", "C:\Program Files\Microsoft",
        "C:\Program Files\Java", "C:\Program Files (x86)\Java"
    )
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $found = Get-ChildItem "$root" -Recurse -Filter "javaw.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) { return $found.DirectoryName }
        }
    }
    foreach ($rp in @("HKLM:\SOFTWARE\Eclipse Adoptium\JDK", "HKLM:\SOFTWARE\JavaSoft\JDK", "HKLM:\SOFTWARE\JavaSoft\Java Development Kit")) {
        if (Test-Path $rp) {
            try {
                foreach ($key in (Get-ChildItem $rp -ErrorAction SilentlyContinue)) {
                    $subkeys = Get-ChildItem $key.PSPath -ErrorAction SilentlyContinue
                    $target  = if ($subkeys) { $subkeys | Select-Object -Last 1 } else { $key }
                    $jHome   = (Get-ItemProperty $target.PSPath -ErrorAction SilentlyContinue).JavaHome
                    if ($jHome -and (Test-Path (Join-Path $jHome "bin\javaw.exe"))) { return (Join-Path $jHome "bin") }
                }
            } catch {}
        }
    }
    try {
        $prod = Get-WmiObject -Class Win32_Product -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like "*JDK*" -or $_.Name -like "*Temurin*" } | Select-Object -First 1
        if ($prod -and $prod.InstallLocation -and (Test-Path (Join-Path $prod.InstallLocation "bin\javaw.exe"))) {
            return (Join-Path $prod.InstallLocation "bin")
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

$javaBin = Find-JavaBin
if ($javaBin -and (Get-JavaMajorVersion $javaBin) -ge 11) {
    OK "JDK $(Get-JavaMajorVersion $javaBin) found: $javaBin"
} else {
    if (-not (Test-Admin)) { FailStep "No suitable JDK found and installing one requires an elevated (Administrator) PowerShell session." }
    if (-not (Test-Path $JavaMsiPath)) { FailStep "No JDK found and no offline installer at: $JavaMsiPath (pass -JavaMsiPath, or place OpenJDK21-jdk.msi next to this script)." }
    OK "Found offline JDK installer: $JavaMsiPath"
    Log "Installing JDK silently (this may take a minute)..."
    $jdkInstallLog = "C:\Windows\Temp\jdk-install.log"
    $attempt = 0
    do {
        $attempt++
        if ($attempt -gt 1) { Log "Another installer is running. Waiting 30s (attempt $attempt/5)..."; Start-Sleep -Seconds 30 }
        $msiArgs = @("/i", $JavaMsiPath, "/quiet", "/norestart",
                     "ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome", "/L*V", $jdkInstallLog)
        $proc = Start-Process "msiexec.exe" -ArgumentList $msiArgs -Wait -PassThru
    } while ($proc.ExitCode -eq 1618 -and $attempt -lt 5)
    if ($proc.ExitCode -notin @(0, 3010)) { FailStep "JDK installer exited with code $($proc.ExitCode). See $jdkInstallLog" }
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH","User")
    $javaBin = Find-JavaBin
    if (-not $javaBin) { FailStep "JDK installed but javaw.exe not found. Restart this machine and re-run." }
    OK "JDK installed: $javaBin"
}
$javawExe = Join-Path $javaBin "javaw.exe"
if (-not (Test-Path $javawExe)) { $javawExe = Join-Path $javaBin "java.exe" }

# ── WinSCP ───────────────────────────────────────────────────────────────
$WinScpCom   = "C:\Program Files (x86)\WinSCP\WinSCP.com"
$WinScpCom64 = "C:\Program Files\WinSCP\WinSCP.com"
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
    if (-not (Test-Admin)) { FailStep "WinSCP isn't installed and installing it requires an elevated (Administrator) PowerShell session." }
    if (-not (Test-Path $WinScpExePath)) { FailStep "WinSCP isn't installed and no offline installer at: $WinScpExePath (pass -WinScpExePath, or place WinSCP-Setup.exe next to this script)." }
    OK "Found offline WinSCP installer: $WinScpExePath"
    Log "Installing WinSCP silently..."
    $proc = Start-Process $WinScpExePath -ArgumentList "/VERYSILENT /NORESTART /ALLUSERS" -Wait -PassThru
    if ($proc.ExitCode -notin @(0, 3010)) { FailStep "WinSCP installer exited with code $($proc.ExitCode)." }
    if      (Test-Path $WinScpCom)   { $resolvedWinScp = $WinScpCom }
    elseif  (Test-Path $WinScpCom64) { $resolvedWinScp = $WinScpCom64 }
    else    { FailStep "WinSCP installed but WinSCP.com not found. Check C:\Program Files." }
    OK "WinSCP installed: $resolvedWinScp"
}

[pscustomobject]@{
    JavaBin       = $javaBin
    JavawExe      = $javawExe
    WinScpComPath = $resolvedWinScp
}
