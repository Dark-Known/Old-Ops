<#
.SYNOPSIS
    Deploys the application (JAR, config, icon) AND creates the Desktop +
    Start Menu shortcuts for it, launching the GUI directly via a
    rebranded copy of javaw.exe (see Brand-Executable.ps1) rather than
    through an elevation-detection batch file. One of the individual
    deployment steps setup.ps1 calls in sequence - see
    packaging/README.md for the full pipeline.

.DESCRIPTION
    There is deliberately no more test-elevation.bat / UAC-prompt / hidden
    PowerShell wrapper in the shortcut chain: that mechanism existed to
    detect the daemon's status when the daemon ran as a Scheduled Task,
    which required an elevated query. Now that the daemon runs as a
    Windows Service and the GUI only reads its status via
    CommandQueueService / shared files in the data directory (see
    MainWindow's own "thin client" comment), the GUI itself needs no
    elevation at all - the shortcut launches the app directly.

    Designed to be runnable with NO parameters at all when run from the
    folder that has the jar/app-config.xml/icon sitting next to it (the
    same layout setup.ps1 expects): every path is auto-resolved from
    app-config.xml if present, otherwise from this project's default
    file/folder names.

    Requires an elevated (Administrator) PowerShell session to RUN this
    script, since -InstallDir and the all-users shortcut locations are
    typically admin-protected - that's about deploying the files, not
    about how the deployed app launches afterward.

.PARAMETER ScriptRoot
    Folder to look for the jar, app-config.xml, and icon in. Defaults to
    the current directory, then falls back to the repo root (two levels
    above this script) if nothing is found there.

.PARAMETER InstallDir
    Destination folder. Auto-resolved from app-config.xml's
    /application/installation/installDir, defaulting to "C:\OpsTools".

.PARAMETER SourceJar
    Path to the prebuilt application JAR. Auto-resolved as
    "<jarName from app-config.xml, default Monitoring-Tool.jar>" in
    -ScriptRoot, falling back to the first *.jar found there.

.PARAMETER ConfigXmlSource
    Path to app-config.xml to deploy alongside the JAR. Defaults to
    "app-config.xml" in -ScriptRoot; skipped with a warning if not found.

.PARAMETER IconSource
    Path to an .ico file. Auto-resolved from app-config.xml's
    /application/installation/iconName (default "Icon.ico") in
    -ScriptRoot, falling back to the first *.ico found there, then to a
    system icon if none exists at all.

.PARAMETER GuiExeName
    File name for the branded GUI launcher (a renamed copy of the JRE's
    own javaw.exe). Defaults to "MonitoringTool.exe".

.PARAMETER ShortcutName
    Display name for the shortcuts, and the FileDescription/ProductName
    baked into the branded exe. Auto-resolved from app-config.xml's
    /application/metadata/appName, defaulting to "Monitoring tool".

.PARAMETER StartMenuFolderName
    Start Menu subfolder name. Defaults to "Ops Tools".

.PARAMETER RceditPath
    Path to rcedit.exe, used to brand the GUI launcher's icon/description
    so it shows correctly in Task Manager instead of "OpenJDK Platform
    Binary". Entirely optional - auto-detected in -InstallDir or on PATH;
    if it can't be found, the shortcut launches plain javaw.exe instead -
    the app runs identically either way, only its Task Manager identity
    differs.

.PARAMETER LogFile
    Optional path to append timestamped log lines to. Safe to omit.

.OUTPUTS
    [pscustomobject] with DestJar, DestConfigXml, InstalledIcon,
    IconLocation, GuiExePath, GuiExeBranded (bool), DesktopLink, and
    StartMenuLink.

.EXAMPLE
    .\Deploy-Application.ps1
    Run with no arguments from the folder containing the freshly-built
    jar - deploys everything, brands and points shortcuts at a direct
    launcher, no arguments needed.

.EXAMPLE
    .\Deploy-Application.ps1 -InstallDir "D:\OpsTools"
#>
[CmdletBinding()]
param(
    [string]$ScriptRoot          = $null,
    [string]$InstallDir          = $null,
    [string]$SourceJar           = $null,
    [string]$ConfigXmlSource     = $null,
    [string]$IconSource          = $null,
    [string]$GuiExeName          = "MonitoringTool.exe",
    [string]$ShortcutName        = $null,
    [string]$StartMenuFolderName = "Ops Tools",
    [string]$RceditPath          = $null,
    [string]$LogFile             = $null
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    $line = "[deploy-application] $msg"
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

$currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    FailStep "This script must be run as Administrator (deploying files + writing all-users shortcuts)."
}

# ── Resolve ScriptRoot + app-config.xml (best-effort) ──────────────────────
if (-not $ScriptRoot) {
    $candidate1 = (Get-Location).Path
    $candidate2 = if ($PSScriptRoot) { Split-Path (Split-Path $PSScriptRoot -Parent) -Parent } else { $null }
    if (Test-Path (Join-Path $candidate1 "app-config.xml")) { $ScriptRoot = $candidate1 }
    elseif ($candidate2 -and (Test-Path (Join-Path $candidate2 "app-config.xml"))) { $ScriptRoot = $candidate2 }
    else { $ScriptRoot = $candidate1 }
}

$xmlConfig = $null
$defaultConfigFile = Join-Path $ScriptRoot "app-config.xml"
if (Test-Path $defaultConfigFile) {
    try { [xml]$xmlConfig = Get-Content $defaultConfigFile } catch { Log "WARNING: Failed to parse app-config.xml: $_" }
}
function Get-ConfigValue([string]$xpath, [string]$defaultValue) {
    if ($null -eq $xmlConfig) { return $defaultValue }
    try {
        $node = $xmlConfig.SelectSingleNode($xpath)
        if ($node -and -not [string]::IsNullOrEmpty($node.InnerText)) { return $node.InnerText }
    } catch {}
    return $defaultValue
}

if (-not $InstallDir)   { $InstallDir   = Get-ConfigValue "/application/installation/installDir" "C:\OpsTools" }
if (-not $ShortcutName) { $ShortcutName = Get-ConfigValue "/application/metadata/appName" "Monitoring tool" }
$jarName = Get-ConfigValue "/application/installation/jarName" "Monitoring-Tool.jar"

if (-not $SourceJar) {
    $SourceJar = Join-Path $ScriptRoot $jarName
    if (-not (Test-Path $SourceJar)) {
        $foundJar = Get-ChildItem -Path $ScriptRoot -Filter "*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($foundJar) { $SourceJar = $foundJar.FullName }
    }
}
if (-not $ConfigXmlSource) { $ConfigXmlSource = $defaultConfigFile }

$iconName = Get-ConfigValue "/application/installation/iconName" "Icon.ico"
if (-not $IconSource) {
    $candidateIcon = Join-Path $ScriptRoot $iconName
    if (Test-Path $candidateIcon) {
        $IconSource = $candidateIcon
    } else {
        $foundIcon = Get-ChildItem -Path $ScriptRoot -Filter "*.ico" -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($foundIcon) { $IconSource = $foundIcon.FullName }
    }
}

if (-not (Test-Path $SourceJar)) {
    FailStep "No JAR found. Looked for '$jarName' (and any *.jar) in: $ScriptRoot`n     Pass -SourceJar explicitly, or place the built jar next to this script."
}

# ── Deploy files ─────────────────────────────────────────────────────────
if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    Log "Created directory: $InstallDir"
}

$destJar = Join-Path $InstallDir $jarName
try {
    Copy-Item -Path $SourceJar -Destination $destJar -Force
    $jarSize = (Get-Item $destJar).Length
    OK "Deployed: $destJar ($jarSize bytes)"
} catch {
    FailStep "Failed to deploy JAR: $_"
}

$destConfigXml = $null
if ($ConfigXmlSource -and (Test-Path $ConfigXmlSource)) {
    $destConfigXml = Join-Path $InstallDir "app-config.xml"
    try {
        Copy-Item -Path $ConfigXmlSource -Destination $destConfigXml -Force
        OK "Deployed: $destConfigXml"
    } catch {
        Log "WARNING: Failed to copy app-config.xml to ${InstallDir}: $_"
        $destConfigXml = $null
    }
} else {
    Log "WARNING: app-config.xml not found - runtime settings will use hardcoded defaults."
}

$installedIcon = $null
$iconLocation  = "C:\Windows\System32\imageres.dll,19"
if ($IconSource -and (Test-Path $IconSource)) {
    $installedIcon = Join-Path $InstallDir $iconName
    try {
        Copy-Item -Path $IconSource -Destination $installedIcon -Force
        OK "Deployed icon: $installedIcon"
        $iconLocation = "$installedIcon,0"
    } catch {
        Log "WARNING: Could not copy custom icon: $_. Falling back to default system icon."
        $installedIcon = $null
    }
} else {
    Log "No icon source found. Falling back to default system icon."
}

# ── Build/reuse this app's own private Java runtime, then brand a direct
#    GUI launcher inside it (no more test-elevation.bat). The private
#    runtime — not the system JDK's own bin\ — is what makes it valid for
#    the branded copy to live inside -InstallDir: see
#    New-PrivateRuntime.ps1's own docs for why a bare copy of the system
#    javaw.exe can't just be dropped into an arbitrary folder.
$runtimeScript = Join-Path $PSScriptRoot "New-PrivateRuntime.ps1"
$javawExe = $null
if (Test-Path $runtimeScript) {
    try {
        $runtimeArgs = @{ InstallDir = $InstallDir }
        if ($LogFile) { $runtimeArgs.LogFile = $LogFile }
        $runtimeResult = & $runtimeScript @runtimeArgs
        $javawExe = $runtimeResult.Javaw
    } catch {
        Log "WARNING: Could not build private runtime: $_ - falling back to the system javaw.exe (shortcuts will still work, just without a private, self-contained runtime)."
    }
}
if (-not $javawExe) {
    # Fallback only: same detection Install-Prerequisites.ps1 uses. A
    # shortcut built from this still launches fine, it just points at the
    # shared system JDK instead of a private runtime, and (per
    # Brand-Executable.ps1's own same-directory rule) any branding ends up
    # placed inside the system JDK's bin\ rather than -InstallDir - not
    # what was asked for, but better than no shortcut at all.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javaw.exe"))) {
        $javawExe = Join-Path $env:JAVA_HOME "bin\javaw.exe"
    } else {
        $jc = Get-Command javaw.exe -ErrorAction SilentlyContinue
        if ($jc) { $javawExe = $jc.Source }
    }
}

$guiExePath    = $null
$guiExeBranded = $false
if (-not $javawExe) {
    Log "WARNING: javaw.exe not found - shortcuts will not be created. Run Install-Prerequisites.ps1 first, or ensure Java is on PATH."
} else {
    $brandScript = Join-Path $PSScriptRoot "Brand-Executable.ps1"
    if ($installedIcon -and (Test-Path $brandScript)) {
        $brandArgs = @{
            SourceExe   = $javawExe
            DestExe     = $GuiExeName
            IconPath    = $installedIcon
            Description = $ShortcutName
            SearchDir   = $ScriptRoot
        }
        if ($RceditPath) { $brandArgs.RceditPath = $RceditPath }
        if ($LogFile)     { $brandArgs.LogFile     = $LogFile }
        $brandResult = & $brandScript @brandArgs
        $guiExePath    = $brandResult.ExePath
        $guiExeBranded = $brandResult.Branded
    } else {
        Log "No icon deployed - launching via plain javaw.exe (shortcut will show the default Java icon)."
        $guiExePath = $javawExe
    }
}

# ── Shortcuts: launch the (possibly branded) exe directly, no elevation,
#    no wrapper batch file or PowerShell hop ────────────────────────────────
$desktopLink = $null
$startMenuLink = $null
if ($guiExePath) {
    $shortcutArgs = "-jar `"$destJar`""
    $WshShell = New-Object -ComObject WScript.Shell

    $desktopPath = [Environment]::GetFolderPath("CommonDesktopDirectory")
    $desktopLink = Join-Path $desktopPath "$ShortcutName.lnk"
    $shortcut = $WshShell.CreateShortcut($desktopLink)
    $shortcut.TargetPath       = $guiExePath
    $shortcut.Arguments        = $shortcutArgs
    $shortcut.WorkingDirectory = $InstallDir
    $shortcut.Description      = $ShortcutName
    $shortcut.IconLocation     = $iconLocation
    $shortcut.Save()
    OK "Desktop shortcut created: $desktopLink -> $guiExePath"

    $startMenuDir = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) $StartMenuFolderName
    if (-not (Test-Path $startMenuDir)) { New-Item -ItemType Directory -Path $startMenuDir | Out-Null }
    $startMenuLink = Join-Path $startMenuDir "$ShortcutName.lnk"
    $shortcut = $WshShell.CreateShortcut($startMenuLink)
    $shortcut.TargetPath       = $guiExePath
    $shortcut.Arguments        = $shortcutArgs
    $shortcut.WorkingDirectory = $InstallDir
    $shortcut.Description      = $ShortcutName
    $shortcut.IconLocation     = $iconLocation
    $shortcut.Save()
    OK "Start Menu shortcut created: $startMenuLink -> $guiExePath"
}

[pscustomobject]@{
    DestJar       = $destJar
    DestConfigXml = $destConfigXml
    InstalledIcon = $installedIcon
    IconLocation  = $iconLocation
    GuiExePath    = $guiExePath
    GuiExeBranded = $guiExeBranded
    DesktopLink   = $desktopLink
    StartMenuLink = $startMenuLink
}
