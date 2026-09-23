<#
.SYNOPSIS
    Checks for (and, if missing, installs/deploys) all required
    third-party prerequisites: a Java 11+ JDK, WinSCP, and the WinSW
    service-wrapper executable used by install-service.ps1. Detection
    order for each is: already installed/deployed on the machine ->
    local offline copy next to this script -> download from the internet
    (with confirmation). One script because these are all the same shape
    of work, rather than several nearly identical scripts. One of the
    individual deployment steps setup.ps1 calls in sequence - see
    packaging/README.md for the full pipeline.

.DESCRIPTION
    Designed to be runnable with NO parameters at all when run from the
    folder that has the offline installers sitting next to it (the same
    layout setup.ps1 expects): it reads file names / URLs from
    app-config.xml if present, otherwise falls back to the project's
    default file names and download URLs. Pass -ScriptRoot only if you're
    running this from somewhere other than that folder, or the individual
    -JavaMsiPath/-WinScpExePath/-WinSwExePath overrides for anything else
    non-standard.

    Fallback order for Java and WinSCP:
      1. Already installed / on PATH / registered -> use it, no elevation
         needed.
      2. Offline installer found next to the script (or at the
         -JavaMsiPath/-WinScpExePath override) -> install silently from
         it. Requires elevation.
      3. Neither found -> ask for confirmation (unless -AutoDownload is
         passed), download the installer from the internet, verify its
         checksum, then install silently. Requires elevation and network
         access.

    WinSW follows the same three-step fallback, but with one difference:
    it has no separate "installer" - there's nothing to run. The
    downloaded/offline WinSW-x64.exe IS the artifact install-service.ps1
    uses directly (renamed to "daemon-service.exe"), so this step's job
    is just to get a clean copy of it into $InstallDir. This also makes
    it self-healing: if a previous run corrupted daemon-service.exe (for
    example via rcedit branding a single-file .NET bundle, which is not
    done by default - see install-service.ps1's -BrandWinSwWrapper), a
    fresh copy under $InstallDir\daemon-service.exe is missing/invalid
    and gets re-fetched automatically rather than requiring a manual
    download-and-copy.

    Requires an elevated (Administrator) PowerShell session only if an
    actual install/deploy (from an offline file or a download) is
    needed - pure detection doesn't need elevation.

.PARAMETER ScriptRoot
    Folder to look for the offline installers and app-config.xml in.
    Defaults to the current directory, then falls back to the repo root
    (two levels above this script) if nothing is found there - covering
    both "run directly from the release folder" and "run in place inside
    the cloned repo" without needing this parameter either way.

.PARAMETER JavaMsiPath
    Explicit override for the JDK installer path (used both as the
    detection path for an existing offline file and as the save path if
    it needs to be downloaded). Normally auto-resolved from
    app-config.xml's /application/java/installerFile, defaulting to
    "OpenJDK21-jdk.msi" in -ScriptRoot.

.PARAMETER WinScpExePath
    Explicit override for the WinSCP installer path (used both as the
    detection path for an existing offline file and as the save path if
    it needs to be downloaded). Normally auto-resolved from
    app-config.xml's /application/tools/winSCP/installerFile, defaulting
    to "WinSCP-Setup.exe" in -ScriptRoot.

.PARAMETER InstallDir
    App install directory WinSW gets deployed into, as
    "$InstallDir\daemon-service.exe" (the same default install-service.ps1
    itself uses for -WinSwPath). Auto-resolved from app-config.xml's
    /application/installation/installDir, defaulting to "C:\OpsTools".
    Not used for Java or WinSCP, which install machine-wide.

.PARAMETER WinSwExePath
    Explicit override for where to look for / save the raw WinSW-x64.exe
    (before it's copied into -InstallDir as daemon-service.exe). Normally
    auto-resolved from app-config.xml's /application/daemon/winsw/installerFile,
    defaulting to "WinSW-x64.exe" in -ScriptRoot.

.PARAMETER ForceWinSw
    Re-fetch and redeploy WinSW even if $InstallDir\daemon-service.exe
    already exists. Use this if you suspect the deployed copy is corrupt
    but its mere presence would otherwise short-circuit detection.

.PARAMETER LogFile
    Optional path to append timestamped log lines to. Safe to omit.

.PARAMETER AutoDownload
    Skip the "download now?" confirmation prompt and proceed straight to
    downloading whenever a prerequisite is missing locally and no offline
    installer is found. Intended for unattended/CI runs where nobody is
    present to answer the prompt. Without this switch, a missing
    interactive session (no console to prompt on) causes the script to
    fail with guidance to pass -AutoDownload, rather than silently
    downloading or silently hanging.

.OUTPUTS
    [pscustomobject] with JavaBin, JavawExe, WinScpComPath, and
    WinSwExePath (the deployed $InstallDir\daemon-service.exe path).

.EXAMPLE
    .\Install-Prerequisites.ps1
    Run with no arguments from the release folder - detects/installs all
    three, using whatever app-config.xml sitting next to it (or the
    project defaults) says the installer file names/URLs are. Prompts
    before any download.

.EXAMPLE
    .\Install-Prerequisites.ps1 -AutoDownload
    Same, but for unattended/CI runs: downloads automatically instead of
    prompting when an installer isn't found locally.

.EXAMPLE
    .\Install-Prerequisites.ps1 -JavaMsiPath "D:\installers\jdk21.msi"

.EXAMPLE
    .\Install-Prerequisites.ps1 -ForceWinSw
    Re-fetch WinSW even though C:\OpsTools\daemon-service.exe already
    exists - useful after a corrupted deploy.
#>
[CmdletBinding()]
param(
    [string]$ScriptRoot    = $null,
    [string]$JavaMsiPath   = $null,
    [string]$WinScpExePath = $null,
    [string]$InstallDir    = $null,
    [string]$WinSwExePath  = $null,
    [switch]$ForceWinSw,
    [string]$LogFile       = $null,
    [switch]$AutoDownload
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    $line = "[install-prerequisites] $msg"
    Write-Host "  $line"
    if ($LogFile) { Add-Content -Path $LogFile -Value "$(Get-Date -Format 'HH:mm:ss')  $line" }
}
function OK([string]$msg) { Write-Host "  v  $msg" -ForegroundColor Green; Log $msg }
function Warn([string]$msg) { Write-Host "  !  WARNING: $msg" -ForegroundColor Yellow; Log "WARNING: $msg" }
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
if (-not $InstallDir) {
    $InstallDir = Get-ConfigValue "/application/installation/installDir" "C:\OpsTools"
}
if (-not $WinSwExePath) {
    $WinSwExePath = Join-Path $ScriptRoot (Get-ConfigValue "/application/daemon/winsw/installerFile" "WinSW-x64.exe")
}
$WinSwDeployedName = Get-ConfigValue "/application/daemon/winsw/deployedFileName" "daemon-service.exe"
$WinSwDeployedPath = Join-Path $InstallDir $WinSwDeployedName

# Download settings. JDK uses Adoptium's stable "latest" API, which
# redirects to the current build for the given major version and also
# publishes a matching <file>.sha256.txt next to every binary - so the
# checksum is fetched dynamically rather than hardcoded (hardcoding it
# would go stale the moment Adoptium ships a new 21.x.y build).
# WinSCP is pinned to an explicit version instead (see
# /application/tools/winSCP/version in app-config.xml, default 6.5.6),
# since it has no "latest" API of its own - the download URL is built from
# that version against SourceForge, and the checksum is fetched from
# WinSCP's own official "ReadMe.txt" for that same version, which
# publishes a SHA-256 per release. Bumping <version> moves both the
# download and the checksum check together; nothing here silently tracks
# upstream "latest". WinSW is pinned the same way as WinSCP, against its
# GitHub release asset - see /application/daemon/winsw/version, default
# below. Unlike Java/WinSCP, WinSW doesn't reliably publish a per-asset
# checksum file, so /application/daemon/winsw/sha256 is an optional
# explicit pin only; without it, verification is skipped with a warning
# (the same fallback behavior already used elsewhere in this script when
# no checksum is available).
$JavaMajorVersion   = Get-ConfigValue "/application/java/majorVersion" "21"
$JavaDownloadUrl    = Get-ConfigValue "/application/java/downloadUrl" `
    "https://api.adoptium.net/v3/binary/latest/$JavaMajorVersion/ga/windows/x64/jdk/hotspot/normal/eclipse"
$JavaExpectedSha256 = Get-ConfigValue "/application/java/sha256" $null   # optional pin; auto-fetched from Adoptium if omitted

$WinScpVersion        = Get-ConfigValue "/application/tools/winSCP/version" "6.5.6"
$WinScpDownloadUrl    = Get-ConfigValue "/application/tools/winSCP/downloadUrl" `
    "https://sourceforge.net/projects/winscp/files/WinSCP/$WinScpVersion/WinSCP-$WinScpVersion-Setup.exe/download"
# WinSCP publishes a SHA-256 for every release in an official "ReadMe.txt"
# at a predictable URL - fetched dynamically per the pinned version below,
# the same way the JDK's checksum is fetched from Adoptium, rather than
# hardcoding a hash here that would go stale (or be wrong) the moment
# <version> is bumped.
$WinScpReadmeUrl      = Get-ConfigValue "/application/tools/winSCP/readmeUrl" `
    "https://winscp.net/download/WinSCP-$WinScpVersion-ReadMe.txt"
$WinScpExpectedSha256 = Get-ConfigValue "/application/tools/winSCP/sha256" $null   # optional explicit pin, takes priority over the readme fetch

$WinSwVersion        = Get-ConfigValue "/application/daemon/winsw/version" "3.0.0-alpha.11"
$WinSwDownloadUrl     = Get-ConfigValue "/application/daemon/winsw/downloadUrl" `
    "https://github.com/winsw/winsw/releases/download/v$WinSwVersion/WinSW-x64.exe"
$WinSwExpectedSha256  = Get-ConfigValue "/application/daemon/winsw/sha256" $null   # optional explicit pin; no reliable published checksum to auto-fetch

function Test-Admin {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ── Shared download/verify helper ───────────────────────────────────────
function Confirm-Download([string]$what, [string]$url) {
    if ($AutoDownload) {
        Log "$what not found locally or offline; -AutoDownload was passed, downloading without prompting."
        return $true
    }
    $isInteractive = [Environment]::UserInteractive -and -not ([Console]::IsInputRedirected)
    if (-not $isInteractive) {
        FailStep "$what not found locally or offline, and this session isn't interactive to ask for confirmation. Re-run with -AutoDownload to allow downloading it from $url, or provide the offline installer/path."
    }
    Write-Host ""
    $answer = Read-Host "  ?  $what wasn't found locally or as an offline installer. Download it now from $url ? (Y/N)"
    if ($answer -notmatch '^[Yy]') {
        FailStep "$what not found and download was declined. Provide an offline installer, pass the matching -...Path override, or re-run and answer Y."
    }
    return $true
}

function Get-DownloadFileHash([string]$path) {
    return (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-IsValidExe([string]$path) {
    # Cheap first gate: rejects the case where the file isn't a Windows
    # executable at all (URL typo, HTML error page, captive portal,
    # truncated-to-nothing download).
    try {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        if ($bytes.Length -lt 2) { return $false }
        return ($bytes[0] -eq 0x4D -and $bytes[1] -eq 0x5A)   # 'M' 'Z'
    } catch {
        return $false
    }
}

function Test-WinSwExecutable([string]$path) {
    # A real functional check, not just a header check. WinSW v3's
    # daemon-service.exe is typically a self-contained single-file .NET
    # "apphost bundle" - a normal PE followed by a manifest whose byte
    # offsets are computed for that exact file's layout/size. Rcedit
    # branding it (install-service.ps1's -BrandWinSwWrapper, off by
    # default - see its own comments) corrupts those offsets WITHOUT
    # touching the leading MZ bytes Test-IsValidExe checks, so a
    # header-only check passes a corrupted copy as fine. The only
    # reliable test is actually running it: a corrupted bundle fails on
    # invocation with "Failure processing application bundle... Arithmetic
    # overflow while reading bundle" - exactly the symptom this guards
    # against - while a healthy copy just prints its usage/help text.
    if (-not (Test-IsValidExe $path)) { return $false }
    try {
        $output = & $path 2>&1 | Out-String
    } catch {
        return $false
    }
    if ($output -match 'bundle' -or $output -match 'Arithmetic overflow' -or $output -match 'file corruption') {
        return $false
    }
    return $true
}

function Save-FileWithRetry([string]$url, [string]$destPath, [int]$maxAttempts = 3) {
    $destDir = Split-Path $destPath -Parent
    if ($destDir -and -not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
    $attempt = 0
    $lastError = $null
    while ($attempt -lt $maxAttempts) {
        $attempt++
        try {
            Log "Downloading (attempt $attempt/$maxAttempts): $url"
            Invoke-WebRequest -Uri $url -OutFile $destPath -UseBasicParsing -MaximumRedirection 10
            if ((Test-Path $destPath) -and (Get-Item $destPath).Length -gt 0) { return }
            throw "Downloaded file is empty."
        } catch {
            $lastError = $_
            Log "Download attempt $attempt failed: $_"
            if (Test-Path $destPath) { Remove-Item $destPath -Force -ErrorAction SilentlyContinue }
            if ($attempt -lt $maxAttempts) { Start-Sleep -Seconds (5 * $attempt) }
        }
    }
    FailStep "Failed to download from $url after $maxAttempts attempts. Last error: $lastError"
}

# ── Java ─────────────────────────────────────────────────────────────────
function Get-JdkProducts {
    # Installed-programs entries that are an ACTUAL JDK, verified by the
    # presence of javac.exe under their InstallLocation - not by name
    # pattern (see note below Find-JavaBin) and NOT via Win32_Product.
    # Win32_Product enumerates by validating/repairing every MSI package on
    # the machine as a side effect of being queried - it's slow, and worse,
    # it can give inconsistent results across repeated calls in the same
    # session (one call finding an install, the very next call missing it),
    # which is exactly what caused a genuinely-installed JDK to be reported
    # as "installed but can't be located" here. The Windows Uninstall
    # registry keys - what Programs and Features itself actually reads -
    # carry the same InstallLocation data without any of that, so they're
    # used instead. Cached in $script:CachedJdkProducts so this only runs
    # once per script invocation rather than being re-queried on every
    # Find-JavaBin call.
    if ($null -ne $script:CachedJdkProducts) { return $script:CachedJdkProducts }
    $uninstallRoots = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    $found = @()
    foreach ($root in $uninstallRoots) {
        try {
            $found += Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
                Where-Object { $_.InstallLocation -and (Test-Path (Join-Path $_.InstallLocation "bin\javac.exe")) } |
                ForEach-Object {
                    [pscustomobject]@{
                        Name            = $_.DisplayName
                        Version         = $_.DisplayVersion
                        InstallLocation = $_.InstallLocation
                    }
                }
        } catch {}
    }
    $script:CachedJdkProducts = @($found)
    return $script:CachedJdkProducts
}

function Find-JavaBin {
    # Searches for javac.exe specifically, not javaw.exe/java.exe - those
    # two ship with a JRE as well as a JDK, so checking for them alone
    # would happily "find" a JRE-only install and report it as usable, even
    # though the app needs a real JDK (it uses javac). Every branch below
    # mirrors the previous javaw.exe-based search, just pointed at the file
    # that's actually diagnostic of "this is a JDK".
    if ($env:JAVA_HOME) {
        if (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe")) {
            return (Join-Path $env:JAVA_HOME "bin")
        }
    }
    $jc = Get-Command javac -ErrorAction SilentlyContinue
    if ($jc) { return (Split-Path $jc.Source) }
    $roots = @(
        "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Eclipse Foundation",
        "C:\Program Files\Temurin", "C:\Program Files\Microsoft",
        "C:\Program Files\Java", "C:\Program Files (x86)\Java",
        "C:\Program Files\Zulu", "C:\Program Files\Amazon Corretto",
        "C:\Program Files\BellSoft", "C:\Program Files\Oracle\Java",
        "C:\Program Files\RedHat"
    )
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $found = Get-ChildItem "$root" -Recurse -Filter "javac.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
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
                    if ($jHome -and (Test-Path (Join-Path $jHome "bin\javac.exe"))) { return (Join-Path $jHome "bin") }
                }
            } catch {}
        }
    }
    $prod = Get-JdkProducts | Select-Object -First 1
    if ($prod) { return (Join-Path $prod.InstallLocation "bin") }
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

function Get-JavaInstaller {
    # 1. Offline installer already present (explicit path or default location).
    if (Test-Path $JavaMsiPath) {
        OK "Found offline JDK installer: $JavaMsiPath"
        return
    }

    # 2. Nothing offline -> confirm, then download from Adoptium.
    Confirm-Download "JDK $JavaMajorVersion" $JavaDownloadUrl | Out-Null

    # Resolve the API's redirect ourselves so we know the exact final URL -
    # needed both for the save path's real filename and for building the
    # matching ".sha256.txt" checksum URL.
    Log "Resolving latest Temurin $JavaMajorVersion download URL..."
    $resolvedUrl = $null
    try {
        $resp = Invoke-WebRequest -Uri $JavaDownloadUrl -MaximumRedirection 0 -UseBasicParsing -ErrorAction SilentlyContinue
        $resolvedUrl = $resp.Headers.Location
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.Headers -and $_.Exception.Response.Headers.Location) {
            $resolvedUrl = $_.Exception.Response.Headers.Location.ToString()
        }
    }
    if (-not $resolvedUrl) { FailStep "Could not resolve the Adoptium download redirect at $JavaDownloadUrl. Check network/proxy settings, or pass -JavaMsiPath to an offline installer." }

    Save-FileWithRetry $resolvedUrl $JavaMsiPath
    OK "Downloaded JDK installer to: $JavaMsiPath"

    # Checksum: prefer an explicit pin from app-config.xml; otherwise fetch
    # Adoptium's own published .sha256.txt for this exact build.
    $expected = $JavaExpectedSha256
    if (-not $expected) {
        try {
            $checksumText = (Invoke-WebRequest -Uri "$resolvedUrl.sha256.txt" -UseBasicParsing).Content
            $expected = ($checksumText -split '\s+')[0].Trim().ToLowerInvariant()
        } catch {
            Warn "Could not fetch the published checksum from ${resolvedUrl}.sha256.txt: $_"
        }
    }
    if ($expected) {
        $actual = Get-DownloadFileHash $JavaMsiPath
        if ($actual -ne $expected.ToLowerInvariant()) {
            Remove-Item $JavaMsiPath -Force -ErrorAction SilentlyContinue
            FailStep "JDK installer checksum mismatch (expected $expected, got $actual). Downloaded file removed - re-run, or supply a trusted offline installer."
        }
        OK "JDK installer checksum verified (SHA256: $actual)"
    } else {
        Warn "Proceeding without checksum verification for the JDK installer - no expected hash available. To pin one, add /application/java/sha256 to app-config.xml."
    }
}

$javaBin = Find-JavaBin
if ($javaBin -and (Get-JavaMajorVersion $javaBin) -ge 11) {
    OK "JDK $(Get-JavaMajorVersion $javaBin) found: $javaBin"
} else {
    # A JDK can be genuinely installed but still invisible to Find-JavaBin if
    # this process inherited its PATH before the JDK's installer updated the
    # machine/user PATH (e.g. this script is run from a PowerShell session
    # that was already open when Java was installed, or in a fresh RDP/CI
    # session that hasn't picked up a recent install yet). Rather than
    # concluding "no JDK" and offering to download one - which is exactly
    # the wrong message when a JDK is actually present - check the
    # installed-programs list first and, if something shows up there, force
    # a PATH refresh and look again. Mirrors setup.ps1's equivalent
    # fallback.
    $existingJdk = Get-JdkProducts | Where-Object { try { [version]$_.Version -ge [version]"11.0" } catch { $false } } | Select-Object -First 1

    if ($existingJdk) {
        Log "WARNING: $($existingJdk.Name) v$($existingJdk.Version) is installed (InstallLocation: $($existingJdk.InstallLocation)) but was not auto-detected. Attempting forced PATH refresh to locate it..."
        $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH","User")
        $javaBin = Find-JavaBin
        Log "After PATH refresh, Find-JavaBin resolved to: $(if ($javaBin) { $javaBin } else { '(nothing)' })"
    }

    if ($javaBin -and (Get-JavaMajorVersion $javaBin) -ge 11) {
        OK "JDK $(Get-JavaMajorVersion $javaBin) located after PATH refresh: $javaBin"
    } elseif ($existingJdk) {
        # The installed-programs list already confirmed this JDK's
        # InstallLocation has a real javac.exe (verified moments ago inside
        # Get-JdkProducts) - use that path directly instead of depending on
        # Find-JavaBin to re-derive the same answer a second time. Asking
        # twice was exactly the seam where the two calls could disagree.
        $javaBin = Join-Path $existingJdk.InstallLocation "bin"
        if (-not (Test-Path (Join-Path $javaBin "javac.exe"))) {
            FailStep "$($existingJdk.Name) v$($existingJdk.Version) was listed as installed at $($existingJdk.InstallLocation) moments ago, but javac.exe is no longer there now. Try re-running; if this persists, set JAVA_HOME to its install folder."
        }
        OK "JDK $(Get-JavaMajorVersion $javaBin) found via installed-programs list: $javaBin"
    } else {
        if (-not (Test-Admin)) { FailStep "No suitable JDK found and installing one requires an elevated (Administrator) PowerShell session." }
        Get-JavaInstaller
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
        if (-not $javaBin) { FailStep "JDK installed but javac.exe not found. Restart this machine and re-run." }
        OK "JDK installed: $javaBin"
    }
}
$javawExe = Join-Path $javaBin "javaw.exe"
if (-not (Test-Path $javawExe)) { $javawExe = Join-Path $javaBin "java.exe" }

# ── WinSCP ───────────────────────────────────────────────────────────────
function Get-WinScpInstaller {
    # 1. Offline installer already present.
    if (Test-Path $WinScpExePath) {
        OK "Found offline WinSCP installer: $WinScpExePath"
        return
    }

    # 2. Nothing offline -> confirm, then download.
    Confirm-Download "WinSCP" $WinScpDownloadUrl | Out-Null
    Save-FileWithRetry $WinScpDownloadUrl $WinScpExePath
    OK "Downloaded WinSCP installer to: $WinScpExePath"

    # Checksum: prefer an explicit pin from app-config.xml; otherwise fetch
    # WinSCP's own published SHA-256 for this exact pinned version from its
    # official ReadMe.txt (same trust model as the JDK's Adoptium check).
    $expected = $WinScpExpectedSha256
    if (-not $expected) {
        try {
            $readmeText  = (Invoke-WebRequest -Uri $WinScpReadmeUrl -UseBasicParsing).Content
            $setupName   = [System.IO.Path]::GetFileName(([Uri]$WinScpDownloadUrl).LocalPath)
            if ([string]::IsNullOrEmpty($setupName) -or $setupName -notlike "*.exe") { $setupName = "WinSCP-$WinScpVersion-Setup.exe" }
            $pattern     = [regex]::Escape($setupName) + '.*?SHA-256:\s*([0-9a-fA-F]{64})'
            $match       = [regex]::Match($readmeText, $pattern, 'Singleline')
            if ($match.Success) { $expected = $match.Groups[1].Value }
            else { Warn "Could not find a SHA-256 line for $setupName in $WinScpReadmeUrl." }
        } catch {
            Warn "Could not fetch WinSCP's published checksum from ${WinScpReadmeUrl}: $_"
        }
    }
    if ($expected) {
        $actual = Get-DownloadFileHash $WinScpExePath
        if ($actual -ne $expected.ToLowerInvariant()) {
            Remove-Item $WinScpExePath -Force -ErrorAction SilentlyContinue
            FailStep "WinSCP installer checksum mismatch (expected $expected, got $actual). Downloaded file removed - re-run, or supply a trusted offline installer."
        }
        OK "WinSCP installer checksum verified (SHA256: $actual)"
    } else {
        $actual = Get-DownloadFileHash $WinScpExePath
        Warn "Proceeding without checksum verification for the WinSCP installer - no expected hash available. Downloaded file SHA256 is $actual. To pin one explicitly, add /application/tools/winSCP/sha256 to app-config.xml."
    }
}

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
    Get-WinScpInstaller
    Log "Installing WinSCP silently..."
    $proc = Start-Process $WinScpExePath -ArgumentList "/VERYSILENT /NORESTART /ALLUSERS" -Wait -PassThru
    if ($proc.ExitCode -notin @(0, 3010)) { FailStep "WinSCP installer exited with code $($proc.ExitCode)." }
    if      (Test-Path $WinScpCom)   { $resolvedWinScp = $WinScpCom }
    elseif  (Test-Path $WinScpCom64) { $resolvedWinScp = $WinScpCom64 }
    else    { FailStep "WinSCP installed but WinSCP.com not found. Check C:\Program Files." }
    OK "WinSCP installed: $resolvedWinScp"
}

# ── WinSW (daemon service-wrapper executable) ───────────────────────────
function Get-WinSwExecutable {
    # Unlike Java/WinSCP, WinSW has no separate "installer" to run - the
    # downloaded/offline exe itself IS the final artifact, just renamed
    # and placed at $InstallDir\daemon-service.exe (install-service.ps1's
    # own default -WinSwPath). So "installing" WinSW here just means
    # getting a clean copy into place, which also makes this self-healing:
    # if a previously deployed daemon-service.exe was corrupted (e.g. by
    # rcedit branding a single-file .NET bundle - not done by default, see
    # install-service.ps1's -BrandWinSwWrapper), it's still "present" but
    # broken; -ForceWinSw re-fetches over it.
    if ((Test-Path $WinSwDeployedPath) -and (Test-WinSwExecutable $WinSwDeployedPath) -and -not $ForceWinSw) {
        OK "WinSW already deployed: $WinSwDeployedPath"
        return
    }

    # 1. Offline copy already present (explicit -WinSwExePath, or the
    #    default "WinSW-x64.exe" next to this script). Validated, not just
    #    detected - a stale copy from an earlier failed/corrupted attempt
    #    sitting at this same path would otherwise pass silently and get
    #    redeployed as-is on every -ForceWinSw re-run.
    if ((Test-Path $WinSwExePath) -and (Test-WinSwExecutable $WinSwExePath)) {
        OK "Found offline WinSW executable: $WinSwExePath"
    } else {
        if (Test-Path $WinSwExePath) {
            Warn "Offline file at $WinSwExePath doesn't look like a valid executable (bad/stale copy?) - re-downloading instead."
            Remove-Item $WinSwExePath -Force -ErrorAction SilentlyContinue
        }
        # 2. Nothing usable offline -> confirm, then download from GitHub releases.
        Confirm-Download "WinSW $WinSwVersion" $WinSwDownloadUrl | Out-Null
        Save-FileWithRetry $WinSwDownloadUrl $WinSwExePath
        if (-not (Test-WinSwExecutable $WinSwExePath)) {
            Remove-Item $WinSwExePath -Force -ErrorAction SilentlyContinue
            FailStep "Downloaded file from $WinSwDownloadUrl doesn't look like a valid Windows executable (wrong URL/version, a redirected error page, or a network intercept). Removed - check /application/daemon/winsw/version in app-config.xml, verify $WinSwDownloadUrl opens a real .exe in a browser, or supply a trusted offline copy at $WinSwExePath."
        }
        OK "Downloaded WinSW to: $WinSwExePath"

        # Checksum: WinSW doesn't reliably publish a per-asset checksum
        # file the way Adoptium/WinSCP do, so this only verifies against
        # an explicit pin in app-config.xml (if the person has set one);
        # otherwise it proceeds with a warning, same fallback used
        # elsewhere in this script when no expected hash is available.
        $expected = $WinSwExpectedSha256
        if ($expected) {
            $actual = Get-DownloadFileHash $WinSwExePath
            if ($actual -ne $expected.ToLowerInvariant()) {
                Remove-Item $WinSwExePath -Force -ErrorAction SilentlyContinue
                FailStep "WinSW checksum mismatch (expected $expected, got $actual). Downloaded file removed - re-run, or supply a trusted offline copy."
            }
            OK "WinSW checksum verified (SHA256: $actual)"
        } else {
            $actual = Get-DownloadFileHash $WinSwExePath
            Warn "Proceeding without checksum verification for WinSW - no expected hash pinned. Downloaded file SHA256 is $actual. To pin one, add /application/daemon/winsw/sha256 to app-config.xml."
        }
    }

    if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null }
    Copy-Item -Path $WinSwExePath -Destination $WinSwDeployedPath -Force
    OK "Deployed WinSW as: $WinSwDeployedPath"
}

$winSwDeployedLooksValid = (Test-Path $WinSwDeployedPath) -and (Test-WinSwExecutable $WinSwDeployedPath)
if ($winSwDeployedLooksValid -and -not $ForceWinSw) {
    OK "WinSW already deployed: $WinSwDeployedPath"
} else {
    if (-not (Test-Admin)) {
        if ($ForceWinSw) { FailStep "WinSW is already deployed but -ForceWinSw was passed and re-deploying requires an elevated (Administrator) PowerShell session." }
        else { FailStep "WinSW isn't deployed (or the deployed copy at $WinSwDeployedPath doesn't look like a valid executable) and deploying/replacing it requires an elevated (Administrator) PowerShell session." }
    }
    if ((Test-Path $WinSwDeployedPath) -and -not $winSwDeployedLooksValid) {
        Warn "Deployed WinSW at $WinSwDeployedPath doesn't look like a valid executable (previously corrupted?) - replacing it."
    }
    Get-WinSwExecutable
}

[pscustomobject]@{
    JavaBin       = $javaBin
    JavawExe      = $javawExe
    WinScpComPath = $resolvedWinScp
    WinSwExePath  = $WinSwDeployedPath
}