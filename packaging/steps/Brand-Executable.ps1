<#
.SYNOPSIS
    Copies a source executable (in practice, always the JRE's own
    javaw.exe) to a new name, in the SAME directory, and bakes in an icon
    + description via rcedit, so Task Manager shows this app's name/icon
    instead of the JRE's own ("OpenJDK Platform Binary"). Shared by both
    Deploy-Application.ps1 (the GUI launcher) and install-service.ps1
    (the daemon launcher) - same operation, two different callers,
    rather than duplicating this logic in each.

.DESCRIPTION
    IMPORTANT constraint this script enforces, not just documents: the
    renamed copy MUST live in the same directory as -SourceExe. The Java
    launcher (java.exe/javaw.exe) locates jvm.dll and the rest of the JDK
    via a path relative to its own location - copying it anywhere else
    (e.g. into an app's install directory) produces an exe that fails to
    start at all, not just an unbranded one. Because that failure mode is
    silent-until-launch and easy to get wrong, this script ignores any
    directory component in -DestExe and always places the copy next to
    -SourceExe, using only the file name from -DestExe. Callers should
    treat the returned ExePath as the actual, authoritative location -
    don't assume it's wherever -DestExe's path implied.

    Entirely optional and safe to call even when rcedit isn't available:
    if -RceditPath can't be resolved (explicit, next to -SourceExe, or on
    PATH) or -IconPath doesn't exist, this returns Branded = $false and
    ExePath = the original -SourceExe unchanged - the caller should use
    that directly. Nothing about this ever fails the caller's own run;
    branding is a nice-to-have, not a requirement.

.PARAMETER SourceExe
    Path to the executable to copy and rebrand - almost always
    javaw.exe from the installed JDK.

.PARAMETER DestExe
    Desired name for the renamed, rebranded copy. Only the file name is
    used - see the directory-placement note above.

.PARAMETER IconPath
    .ico file to bake in via rcedit --set-icon.

.PARAMETER Description
    Text baked into the FileDescription version resource - what Task
    Manager's "Description"/"Name" columns will actually show.

.PARAMETER ProductName
    Text baked into the ProductName version resource. Defaults to
    -Description if not given.

.PARAMETER RceditPath
    Path to rcedit.exe. Auto-detected next to -SourceExe, then on PATH,
    if not given explicitly.

.PARAMETER LogFile
    Optional path to append timestamped log lines to. Safe to omit.

.OUTPUTS
    [pscustomobject] with Branded (bool) and ExePath (the branded copy,
    next to -SourceExe, on success; the original -SourceExe unchanged if
    branding was skipped).

.EXAMPLE
    .\Brand-Executable.ps1 -SourceExe "C:\...\jdk\bin\javaw.exe" `
        -DestExe "MonitoringTool.exe" -IconPath "C:\OpsTools\Icon.ico" `
        -Description "Monitoring tool"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceExe,
    [Parameter(Mandatory = $true)][string]$DestExe,
    [Parameter(Mandatory = $true)][string]$IconPath,
    [Parameter(Mandatory = $true)][string]$Description,
    [string]$ProductName = $null,
    [string]$RceditPath  = $null,
    [string]$LogFile     = $null
)

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    $line = "[brand-executable] $msg"
    Write-Host "  $line"
    if ($LogFile) { Add-Content -Path $LogFile -Value "$(Get-Date -Format 'HH:mm:ss')  $line" }
}
function OK([string]$msg) { Write-Host "  v  $msg" -ForegroundColor Green; Log $msg }

if (-not $ProductName) { $ProductName = $Description }

$fallback = [pscustomobject]@{ Branded = $false; ExePath = $SourceExe }

if (-not (Test-Path $SourceExe)) {
    Log "NOTE: Source executable not found at $SourceExe - nothing to brand."
    return $fallback
}

# Always co-located with SourceExe - see .DESCRIPTION. Silently correcting
# this (rather than trusting a caller-supplied directory) is deliberate:
# a wrong directory here doesn't fail loudly, it produces an exe that
# won't launch, which is a much worse failure to debug later.
$sourceDir  = Split-Path $SourceExe -Parent
$destExeName = Split-Path $DestExe -Leaf
$DestExe    = Join-Path $sourceDir $destExeName

$rcEdit = @(
    $RceditPath,
    (Join-Path $sourceDir "rcedit.exe"),
    (Get-Command "rcedit.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if (-not $rcEdit) {
    Log "NOTE: rcedit.exe not found - '$(Split-Path $SourceExe -Leaf)' will keep its own identity in Task Manager."
    Log "Download rcedit.exe from https://github.com/electron/rcedit/releases and place it in $sourceDir, or pass -RceditPath, to brand it."
    return $fallback
}
if (-not (Test-Path $IconPath)) {
    Log "NOTE: No icon at $IconPath - skipping rebrand (rcedit needs an icon to bake in)."
    return $fallback
}

try {
    Copy-Item -Path $SourceExe -Destination $DestExe -Force
    $internalName = [System.IO.Path]::GetFileNameWithoutExtension($DestExe)

    # Separate rcedit invocations per field, rather than one call with
    # several --set-version-string flags chained together - matches the
    # pattern this project has actually tested working, rather than
    # relying on rcedit's multi-flag argument parsing behaving as hoped.
    $edits = @(
        @("--set-icon", $IconPath),
        @("--set-version-string", "FileDescription", $Description),
        @("--set-version-string", "ProductName", $ProductName),
        @("--set-version-string", "OriginalFilename", $destExeName),
        @("--set-version-string", "InternalName", $internalName)
    )
    $allOk = $true
    foreach ($edit in $edits) {
        & $rcEdit $DestExe @edit
        if ($LASTEXITCODE -ne 0) {
            Log "WARNING: rcedit $($edit -join ' ') exited with code $LASTEXITCODE"
            $allOk = $false
            break
        }
    }
    if (-not $allOk) {
        Log "Falling back to unbranded $SourceExe."
        Remove-Item $DestExe -ErrorAction SilentlyContinue
        return $fallback
    }
    OK "Branded: $DestExe ($Description)"
    return [pscustomobject]@{ Branded = $true; ExePath = $DestExe }
} catch {
    Log "WARNING: Could not brand executable: $_ - falling back to unbranded $SourceExe."
    Remove-Item $DestExe -ErrorAction SilentlyContinue
    return $fallback
}
