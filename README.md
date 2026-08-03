# OpsTransferTool — README

## Overview
OpsTransferTool executes file transfers between local and remote systems using WinSCP scripting and internal metadata services. It supports watcher-based "LATEST_ONLY" transfers and full-folder or specific-file transfers. This README explains how files are selected, what classes are responsible, configuration fields, and troubleshooting guidance for integrations that need the "latest only" files.

## Features
- Watcher-based transfers (LATEST_ONLY) for inbound and outbound directions
- Non-watcher modes: ENTIRE_FOLDER and SPECIFIC_FILE
- Local→local copies, SFTP transfers via WinSCP scripting
- Baseline persistence using epoch (milliseconds) + size to avoid duplicate transfers
- Cancellable transfers and masked logs for credentials

## How file selection works (LATEST_ONLY)
Primary logic lives in `src/main/java/service/TransferService.java`.

Watcher flow (used when `task.isWatcherEnabled()` and `task.getTransferMode() == TransferMode.LATEST_ONLY`):
1. Read baseline epoch and size from the task model:
   - `task.getLastKnownRemoteFileEpoch()` (long, epoch millis)
   - `task.getLastKnownRemoteFileSize()` (long, bytes)
2. Build `modifiedAfter` Instant:
   - If epoch > 0: `Instant.ofEpochMilli(epoch)`
   - Else: `Instant.EPOCH` (initial run)
3. Query metadata service for files modified after `modifiedAfter`:
   - Outbound (local): `LocalFileMetadataService.getFilesModifiedAfter(watchDir, modifiedAfter)`
   - Inbound (remote): created via `RemoteFileMetadataServiceFactory` → `ManagedMetadataService.service().getFilesModifiedAfter(watchDir, modifiedAfter)`
4. Filter rule applied (if baseline present): include files where
   - `file.lastModified() > baselineEpoch` OR
   - `file.lastModified() == baselineEpoch && file.size() != baselineSize`
   This preserves multiple files that share the same timestamp but differ in size.
5. Initial-run special case (no baseline): the implementation reduces candidates to only the newest timestamp file(s) (ties preserved) to avoid transferring an entire directory on first run.
6. Transfer the chosen files:
   - OUTBOUND watcher: builds WinSCP `put` script (see `executeWinScpWatcherOutbound`).
   - INBOUND watcher: builds WinSCP `get` script (see `executeWinScpWatcherInbound`) or uses `Files.copy` for local→local.
7. Baseline persistence: after successful transfer the service selects the file with the maximum `lastModified` and persists:
   - `task.setLastKnownRemoteFileEpoch(newest.lastModified().toEpochMilli())`
   - `task.setLastKnownRemoteFileSize(newest.size())`
   - `storage.saveTask(task)` — persisted via `XmlStorageService`.

Non-watcher LATEST_ONLY behavior:
- INBOUND non-watcher uses `resolveLatestRemoteFilesViaWinScp(...)` which runs a WinSCP `ls` command, parses the listing, converts timestamps to epoch millis (UTC), and returns the file(s) with the maximum epoch.
- OUTBOUND non-watcher scans the local directory and chooses file(s) with max `lastModified()`.

Edge cases & tie handling:
- Multiple files sharing the same max timestamp are all included (tie-preserving).
- Equal-timestamp-but-different-size files are included if the baseline size differs, preventing missed files created with identical timestamps.
- Timezone/granularity: WinSCP `ls` parser converts to epoch with `ZoneOffset.UTC`; server timezone differences or coarse timestamp resolutions can affect behavior — see Troubleshooting.

## Key classes & where to look
- `TransferService` — `src/main/java/service/TransferService.java` (core logic, watcher, script building, baseline persistence)
- `LocalFileMetadataService` — local file listing used for outbound watcher and local→local
- `RemoteFileMetadataServiceFactory` / `SftpRemoteFileMetadataService` — remote metadata access for inbound watcher
- `ScheduledTask` model — contains baseline fields, transfer mode, direction, paths, and credential references
- `XmlStorageService` — persistence for tasks and credentials

## Configuration (task-level fields)
Important `ScheduledTask` fields used by the transfer code:
- `sourcePath` — local path (source for outbound, destination for inbound watcher)
- `targetPath` — remote path (destination for outbound, source for inbound watcher)
- `transferMode` — LATEST_ONLY | ENTIRE_FOLDER | SPECIFIC_FILE
- `transferDirection` — INBOUND | OUTBOUND
- `watcherEnabled` — boolean to enable watcher path
- `targetUsername` or `targetCredentialId` — credential lookup
- `lastKnownRemoteFileEpoch`, `lastKnownRemoteFileSize` — baseline used by watcher

## Running & prerequisites
- Install WinSCP (winscp.com) and ensure it's on PATH or in standard install locations (detected automatically). The code searches:
  - `C:\Program Files (x86)\WinSCP\WinSCP.com`
  - `C:\Program Files\WinSCP\WinSCP.com`
  - `winscp.com`
- Build with Maven (project has `pom.xml`). Example:

```bash
mvn -U -DskipTests package
# Run the jar (adjust path/version)
java -jar target/ops-transfer-tool.jar
```

- If running from IDE, configure `XmlStorageService` data dir and ensure credentials exist.

## Logs to collect when debugging LATEST_ONLY behavior
If you see "pulling everything" after a run, collect these log lines from a watcher run (they appear in the app log/stdout):
- "[INFO] Watcher enabled (LATEST_ONLY, ...). Querying files modified after: <instant>"
- "[INFO] Metadata service: LOCAL | watch directory: ..." or "[INFO] Metadata service: SFTP | watch directory: ..."
- The found-files listing — lines like:
  - "[INFO]   filename | lastModified=... | size=..."
- If inbound non-watcher used: any "[LS]" lines and the raw WinSCP `ls` output
- The baseline update line:
  - "[INFO] Watcher baseline updated → epoch=..., size=... (newest file: ...) | task saved, ID=..."

Paste these sections in a bug report and we can inspect timestamp/size values and ordering.

## Troubleshooting tips
- If the baseline epoch saved is older than expected:
  - Confirm metadata service returns correct timestamps (UTC vs server local time). The WinSCP ls parser uses UTC conversion — verify server listing format and timezone.
- If first run still pulls many files and you prefer a different policy:
  - Option A: skip initial transfer — set baseline to newest but do not transfer (we can add this behavior).
  - Option B: transfer newest but do NOT update baseline (we can add a flag to control baseline update).
- If multiple files have identical timestamps and you want deterministic single-file behavior, consider adding filename-based tie-breaker ordering.

## Integration points (for other components needing "latest only")
If an external integration needs the list of latest-only files (without performing transfer), you can reuse or adapt these components:
- `RemoteFileMetadataService` implementations provide `getFilesModifiedAfter(String dir, Instant after)` which returns `RemoteFileMetadata` objects containing `fileName()`, `lastModified()`, and `size()`.
- For WinSCP-based remote scanning, `resolveLatestRemoteFilesViaWinScp(...)` returns the list of latest remote paths and a max epoch.

Suggested integration approach:
1. Call the metadata service (local or SFTP) with the baseline epoch you have.
2. Apply the same filter rules used here:
   - `lastModified > baseline` OR (`lastModified == baseline && size != baselineSize`).
3. If you want initial-run behavior consistent with this tool, only keep files with the maximum lastModified when baseline is missing.

## Testing suggestions
- Unit tests that stub `RemoteFileMetadataService` responses and verify `executeWatcherTransfer` chooses and persists the expected baseline. Test cases:
  - No baseline: multiple files → only newest(s) chosen
  - Existing baseline: equal timestamp, different size → included
  - Ordering: metadata returns out-of-order list → newest selection still correct

## Extensibility / optional flags to add
- `skipInitialTransfer` boolean: on first run record baseline without transferring.
- `preserveBaselineOnTransfer` boolean: transfer but do not update baseline.
- Deterministic tie-breaker (filename or configured comparator).

## Contributing
- Fork, implement tests under `src/test/java`, run `mvn test`, open PR.
- Keep changes focused; follow existing coding conventions in `src/main/java`.

## Where to look in the codebase
- `src/main/java/service/TransferService.java` — core transfer and watcher logic
- `src/main/java/service/LocalFileMetadataService.java` — local metadata
- `src/main/java/service/RemoteFileMetadataServiceFactory.java` — remote service factory
- `src/main/java/model/ScheduledTask.java` — task model and baseline fields
- `src/main/java/service/SftpRemoteFileMetadataService.java` — SFTP metadata implementation

---
If you want, I can add a CLI example, unit tests for watcher behavior, or implement the `skipInitialTransfer` option — which would you like next?