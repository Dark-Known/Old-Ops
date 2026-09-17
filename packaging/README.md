# packaging/ — Windows Service migration

Replaces the Scheduled-Task-plus-hourly-restart daemon registration in
`setup.ps1` with a real Windows Service, using [WinSW](https://github.com/winsw/winsw).

**As of this update, `setup.ps1` calls `install-service.ps1` itself** for
daemon registration — you don't run them separately. See "How it fits
into setup.ps1" below.

## Files
- `daemon-service.xml` — WinSW service definition (id, start command,
  logging, failure/recovery policy). Its `<arguments>` are overwritten at
  install time with the real jar path and data directory, so the
  defaults in the file itself only matter for a fully standalone run.
- `install-service.ps1` — installs/updates the service: removes any prior
  Scheduled Task registration first (so the daemon can't double-run under
  both mechanisms at once), stages the config, installs, starts, and sets
  `sc.exe` Recovery settings as a belt-and-suspenders fallback alongside
  WinSW's own `onfailure` retries.

## How it fits into setup.ps1
`setup.ps1`'s daemon-registration section now does this automatically:
1. Looks for `packaging\install-service.ps1` next to itself.
2. Looks for `daemon-service.exe` (the renamed WinSW executable — see
   step 1 below) in the install directory.
3. If both are present, calls `install-service.ps1` with the install's
   real jar path, data directory, and configured task name — so any
   prior Scheduled Task from an older run of `setup.ps1` gets cleaned up
   automatically as part of the same run.
4. If either is missing, it logs exactly what's missing and skips
   registration (the same "optional local prerequisite" pattern this
   installer already uses for `rcedit.exe`) — it does **not** fail the
   whole install.

So for a normal install, you only ever run `setup.ps1`. The one manual
step is getting WinSW in place first:

1. Download `WinSW-x64.exe` from the [WinSW releases page](https://github.com/winsw/winsw/releases),
   rename it to `daemon-service.exe`, and place it in your install
   directory (next to the daemon jar) *before* running `setup.ps1`.
2. Run `setup.ps1` as Administrator, as normal.

## Running install-service.ps1 directly (manual/advanced use)
You can still invoke it standalone — e.g. to re-register after changing
`-DataDir`, or on a machine where you're not re-running all of
`setup.ps1`:
```powershell
.\packaging\install-service.ps1 -InstallDir "C:\Path\To\Install" -JarPath "C:\Path\To\Install\Monitoring-Tool.jar"
```
`-JarPath` matters here specifically because the jar's name is
configurable (`/application/installation/jarName` in the install XML
config, defaulting to `Monitoring-Tool.jar`) — `setup.ps1` already knows
this and passes it through for you; a standalone run needs to be told.

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
