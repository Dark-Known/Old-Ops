# packaging/ — deployment scripts

`setup.ps1` (in the repo root) is a thin orchestrator: it runs three
standalone scripts in order, stopping on the first failure. Each one
**also runs on its own with no arguments at all** when run from the
folder that has the jar/config/icon sitting next to it — every path is
auto-detected from `app-config.xml`, falling back to this project's
default file names if there's no config to read.

## Files
- `steps/Install-Prerequisites.ps1` — checks for (and silently installs
  if missing) both a JDK 11+ and WinSCP. One script, not two, since both
  are the same shape of work: detect, else install from a local offline
  installer.
- `steps/New-PrivateRuntime.ps1` — builds (or reuses) a private,
  self-contained Java runtime inside the install directory via `jlink`.
  This is what makes it valid for the branded launchers below to live
  inside the app's own install directory instead of the shared system
  JDK's `bin\` folder — see its own doc comment for why that distinction
  matters (it's not cosmetic; a bare renamed copy of `javaw.exe` placed
  outside its own JDK structure fails to start at all).
- `steps/Deploy-Application.ps1` — copies the JAR, `app-config.xml`, and
  icon into the install directory, brands a direct GUI launcher inside
  the private runtime above, and creates the Desktop + Start Menu
  shortcuts.
- `steps/Brand-Executable.ps1` — shared helper: copies a source `.exe`
  to a new name, in the same directory as the source, and bakes in an
  icon + description via `rcedit`. Used by `Deploy-Application.ps1` (the
  GUI launcher), `install-service.ps1` (the daemon launcher, and the
  WinSW wrapper itself), instead of duplicating that logic in each.
- `install-service.ps1` — registers the daemon as a Windows Service via
  [WinSW](https://github.com/winsw/winsw); see its own section below.
- `daemon-service.xml` — WinSW service definition (id, start command,
  logging, failure/recovery policy). Its `<arguments>` (and, if `rcedit`
  is available, `<executable>` too) are overwritten at install time.

## The zero-argument case
If you're standing in the folder with the jar, `app-config.xml`, and the
icon all sitting together (the same layout `setup.ps1` expects), you can
run either step script with **no parameters at all**:
```powershell
.\packaging\steps\Install-Prerequisites.ps1
.\packaging\steps\Deploy-Application.ps1
```
This is the common redeploy case — rebuilt the jar, need it live on a
machine that already has Java/WinSCP/shortcuts set up:
```powershell
.\packaging\steps\Deploy-Application.ps1
```
That single line re-copies the jar, config, and icon, and refreshes both
shortcuts. Nothing else to pass.

## Overriding something specific
Every auto-detected value has a named override, for the cases that
aren't "everything sits in one folder": different install location,
jar built with an unusual name, running the script from somewhere other
than the release folder, etc. Full docs for every parameter are in each
file's comment-based help:
```powershell
Get-Help .\packaging\steps\Deploy-Application.ps1 -Full
```
Example — deploying to a non-default location:
```powershell
.\packaging\steps\Deploy-Application.ps1 -InstallDir "D:\OpsTools"
```

Pass `-LogFile <path>` to either step to have it append into an existing
setup log instead of only writing to the console — this is how
`setup.ps1` keeps every step's output in one file.

## install-service.ps1 — Windows Service registration
Replaces the Scheduled-Task-plus-hourly-restart daemon registration this
project used before with a real Windows Service.

**`setup.ps1` calls `install-service.ps1` itself** as its last step — you
don't normally run it separately. It's also mostly zero-argument-capable
already: `-InstallDir` defaults to the current directory, `-JarPath`
auto-detects `Monitoring-Tool.jar` (or any single `*.jar`) in that
folder, and `-DataDir` auto-reads `/application/installation/dataDir`
from `app-config.xml` if one was deployed there — so a plain
```powershell
.\packaging\install-service.ps1
```
run from the install directory usually needs no parameters either.

- Removes any prior Scheduled Task registration first (so the daemon
  can't double-run under both mechanisms at once).
- Stages the WinSW config, installs, starts, and sets `sc.exe` Recovery
  settings as a belt-and-suspenders fallback alongside WinSW's own
  `onfailure` retries.

### How it fits into setup.ps1
`setup.ps1`'s daemon-registration step does this automatically:
1. Looks for `packaging\install-service.ps1` next to itself.
2. Looks for `daemon-service.exe` (the renamed WinSW executable — see
   below) in the install directory.
3. If both are present, calls `install-service.ps1` — so any prior
   Scheduled Task from an older run of `setup.ps1` gets cleaned up
   automatically as part of the same run.
4. If either is missing, it logs exactly what's missing and skips
   registration (the same "optional local prerequisite" pattern this
   installer already uses for `rcedit.exe`) — it does **not** fail the
   whole install.

So for a normal install, you only ever run `setup.ps1`. The one manual
step is getting WinSW in place first:
1. Download `WinSW-x64.exe` from the
   [WinSW releases page](https://github.com/winsw/winsw/releases),
   rename it to `daemon-service.exe`, and place it in your install
   directory (next to the daemon jar) *before* running `setup.ps1`.
2. Run `setup.ps1` as Administrator, as normal.

## Rollback
```powershell
.\daemon-service.exe stop
.\daemon-service.exe uninstall
```
Then re-run the previous Scheduled-Task-based `setup.ps1` version, or
register one manually with `Register-ScheduledTask`.

## Why this is safe alongside the app-level changes
The daemon itself already reads its settings (WinSCP path, poll interval)
from `AppSettings`/`app.db` — a shared file in the data directory, not
per-user Windows Preferences — specifically so it behaves the same
whether it's launched by a logged-in user, a Scheduled Task, or a Service
running under `LocalSystem` or a dedicated service account. No additional
app-level change is needed to make the daemon "service-safe" — this
directory only changes *how the process is launched and kept alive*.

## Branding: one logo, everywhere
`Icon.ico` at the repo root is the app's logo — same source art as
`src/main/resources/icon.png`, which the GUI (`MainWindow`) loads for its
window and taskbar icon. `Deploy-Application.ps1` copies `Icon.ico` into
the install directory for the shortcuts to reference.

Task Manager is a separate problem from all of the above: Windows shows
whatever name/icon is baked into the resources of the actual `.exe`
that's running — for a Java app, that's normally `java.exe`/`javaw.exe`
itself ("OpenJDK Platform Binary"), regardless of what icon the *window*
uses. Both launchers now avoid that entirely:

- **The daemon**: `install-service.ps1` copies `javaw.exe` to
  `MonitoringToolDaemon.exe` and points WinSW at that copy.
- **The GUI**: `Deploy-Application.ps1` copies `javaw.exe` to
  `MonitoringTool.exe` and points the Desktop/Start Menu shortcuts
  directly at that copy — there's no more `test-elevation.bat` batch
  file or PowerShell wrapper in between; the shortcut launches the app
  directly with `-jar "<deployed jar>"`, and needs no elevation, since
  the GUI only reads the daemon's status via shared files
  (`CommandQueueService`) rather than querying it directly.

Both go through the shared `steps/Brand-Executable.ps1`, which uses
`rcedit.exe` to bake this app's icon and description into each renamed
copy. This is optional and auto-skips (falling back to the plain,
unbranded `javaw.exe`) with a log note if `rcedit.exe` isn't available —
either launcher works identically either way; only the Task Manager
identity differs. `rcedit.exe` itself is a completely standalone tool
with no relationship to the JDK — `Brand-Executable.ps1` looks for it via
an explicit `-RceditPath`, a `-SearchDir` the caller points at the
install directory, or PATH; it deliberately does NOT search next to the
exe being branded, since there's no reason it would need to live there.

**Important constraint `Brand-Executable.ps1` enforces, not just
documents:** the renamed copy has to live in the *same directory* as its
source exe — the Java launcher locates `jvm.dll` and the rest of the JDK
via a path relative to its own location, so a copy placed anywhere else
fails to launch at all, silently, until you actually try it.
`Brand-Executable.ps1` ignores whatever directory a caller's `-DestExe`
implies and always places the branded copy next to `-SourceExe`.

That's also why `-SourceExe` for both launchers is never the *system*
JDK's `javaw.exe` — it's `steps/New-PrivateRuntime.ps1`'s own private,
self-contained runtime, built once via `jlink` into
`<InstallDir>\runtime`. That private runtime is what makes it valid for
`MonitoringTool.exe`/`MonitoringToolDaemon.exe` to end up inside the
app's own install directory (`<InstallDir>\runtime\bin\...`) rather than
reaching into the shared system JDK's own folder.

The WinSW wrapper (`daemon-service.exe`) is branded too, separately —
it's a second, distinct entry in Task Manager/Services from the daemon's
JVM child process, and has no "must live next to its DLLs" constraint of
its own (it's already a single self-contained native binary), so it's
branded in place directly rather than via the private runtime.

One knock-on effect worth knowing: since the GUI shortcut no longer
requests elevation, `install-service.ps1` also grants the data directory
(`DataDir`) write access to the built-in Users group when it creates it,
so the GUI can read/write there as a normal user. If it still can't (a
network share with unusual ACLs, say), the GUI relaunches itself
elevated once automatically via a UAC prompt — see `App.java`'s startup
check — rather than silently starting with an inaccessible database.
