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

## Outlook Mail setup (Microsoft Graph)

`OUTLOOK_MAIL` tasks read mail via the **Microsoft Graph API**, not IMAP. This is
deliberate: Microsoft has disabled Basic Authentication for IMAP tenant-wide, and
many Microsoft 365 tenants additionally block legacy protocols (IMAP/POP/SMTP AUTH)
entirely via Conditional Access / authentication policies — a restriction that
OAuth2-over-IMAP does not bypass, because it applies at the protocol layer before
authentication is even evaluated. Graph is a plain HTTPS REST API — the same path
the Outlook web app and mobile apps use — so it is unaffected by that policy.

### One-time setup (per organization, ~5 minutes, no admin required in most tenants)

1. Go to [portal.azure.com](https://portal.azure.com) → **Microsoft Entra ID** →
   **App registrations** → **New registration**.
2. Give it any name, leave the redirect URI blank, click **Register**.
3. Open **Authentication** → **Advanced settings** → set **"Allow public client
   flows"** to **Yes**. (This lets the app use the OAuth2 device-code flow with no
   client secret.)
4. Open **API permissions** → **Add a permission** → **Microsoft Graph** →
   **Delegated permissions** → search for and check **Mail.ReadWrite**. Click **Add
   permissions**. (Note: **not** Application permissions — device-code sign-in only
   works with delegated permissions, since it authenticates as a specific signed-in
   person, not an app-only/client-credentials identity. `Mail.ReadWrite` is needed
   rather than the narrower `Mail.Read` because this app also marks messages as
   read and moves them between folders, both of which are write operations.)
5. From the **Overview** page, copy the **Application (client) ID** and the
   **Directory (tenant) ID**.

If step 4 is blocked with "admin approval required," your tenant has locked down
user consent entirely — at that point Graph mail access needs a tenant admin to
either grant consent for the app or pre-approve `Mail.ReadWrite`, same as any other
integration would.

**If you authorized a mailbox before this permission was `Mail.ReadWrite`:** its
cached refresh token was issued for the narrower `Mail.Read` scope, and Microsoft
won't silently upgrade it on a background refresh — you'll need to click
**"Authorize Mailbox…"** again (one more interactive sign-in) for mark-as-read /
move-to-folder to start working on that task.

### Per-mailbox authorization (one-time, per person/mailbox)

1. Create or edit an `OUTLOOK_MAIL` task in the app.
2. On the **Mail/IMAP** tab, fill in:
   - **Mailbox Address** — the email address to read mail from
   - **Azure AD Tenant ID** — the Directory (tenant) ID from setup, or `common`
   - **Azure AD Client ID** — the Application (client) ID from setup
3. Click **Authorize Mailbox…**. A dialog shows a URL and a short code — open the
   URL in any browser, sign in normally (MFA included), and enter the code.
4. Once authorized, the status next to the button shows **✓ Authorized**, and the
   task can be saved and scheduled. Every scheduled run after this silently
   refreshes its access token — no further sign-in is required.

The refresh token is cached at `~/.opstool/oauth/<mailbox>.oauth`, encrypted at
rest with the same machine-local key already used for stored credential passwords
(`util.CryptoUtil`).

### Fetch Scope — latest only vs. everything matching

Mail tasks have a **Fetch Scope** setting, same idea as file transfers' Transfer Mode:

| Fetch Scope     | Behavior |
|------------------|----------|
| `LATEST_ONLY`     | Returns only the single newest message matching the search criteria. |
| `ALL_MATCHING`    | Returns every message matching the search criteria, paginated via Graph's `@odata.nextLink`, up to the **Max Messages** cap you set (a plain numeric field, default 50, 1–5000). This cap exists on purpose — "entire mailbox" with no limit could mean fetching thousands of messages in one run, which is rarely what's actually wanted and is expensive against the Graph API. |

Note this is a different axis from the search criteria: `UNSEEN` + `ALL_MATCHING`
means "every unread message" (paginated), while `UNSEEN` + `LATEST_ONLY` means "just
the newest unread message." See the **Watcher** section below for how to avoid
re-fetching the same messages on every run.

### Watcher — only fetch what's new since the last run

Mail tasks can use the same watcher concept as file-transfer tasks: check
**"Enable watcher"** and every scheduled run fetches only messages received after
the newest message processed by the *previous successful run* — instead of
re-matching your search criteria from scratch each time.

- While the watcher is on, it **overrides** Fetch Scope: every run always means
  "everything new since last time," capped by **Max Messages** as a safety limit.
- The baseline (a timestamp) is stored on the task and shown under **Watcher
  baseline** in the editor, with a **Reset Baseline** button if you ever want the
  next run to start fresh (e.g. after changing search criteria or folders).
- Same limitation as the file-transfer watcher: if two messages share the *exact*
  same `receivedDateTime` down to the second and only one was seen at the old
  baseline, the tie can't be perfectly disambiguated (this is a documented,
  low-probability edge case in Graph's timestamp precision — not something client
  code can fully solve).

### Mark as read / move to another folder

Two independent post-processing options, applied to each message right after it's
successfully fetched:

- **Mark fetched messages as read** — sends a Graph `PATCH` setting `isRead: true`.
  This is the simplest way to make `UNSEEN` search criteria behave like "give me
  what I haven't already processed" *without* needing the watcher.
- **Move processed messages to another folder** — sends a Graph `POST .../move`
  after marking as read (order matters: moving a message changes its ID, so any
  other per-message action must happen first). You're prompted for the
  **destination folder** name when you check this box — any custom folder name
  (e.g. "Processed") or a well-known name (`SENT`, `DRAFTS`, `DELETED`, `JUNK`,
  `ARCHIVE`).

Both are best-effort per message: if marking-as-read or moving fails for one
message (e.g. a transient Graph error), that failure is logged as a warning and
the run continues with the remaining messages rather than aborting the whole task.

You can combine these however makes sense for your workflow:
- **Watcher only** — never touches read/folder state, relies purely on the
  timestamp baseline for dedup.
- **Mark as read only** (no watcher) — good with `UNSEEN` criteria and
  `ALL_MATCHING`/`LATEST_ONLY` scope; each run only sees what's still unread.
- **Move only** (no watcher, no mark-as-read) — moving a message out of the
  source folder is itself a form of dedup, since it won't match the folder's
  search again.
- **All three together** — belt-and-suspenders: watcher skips already-seen
  timestamps, mark-as-read updates state immediately, and move archives
  processed mail out of the way.

### Search criteria

The **Selected Criteria** builder still uses IMAP-style tokens (`UNSEEN`,
`FROM "..."`, `SINCE dd-MMM-yyyy`, etc.) for continuity with the existing UI, but
these are now translated into a Graph OData `$filter` on a best-effort basis:

| IMAP-style criterion         | Graph translation                                  |
|-------------------------------|-----------------------------------------------------|
| `UNSEEN` / `SEEN`              | `isRead eq false` / `isRead eq true`                |
| `SUBJECT "text"`               | `contains(subject,'text')`                          |
| `FROM "address"`               | `from/emailAddress/address eq 'address'`             |
| `SINCE` / `BEFORE` / `ON dd-MMM-yyyy` | `receivedDateTime` comparisons               |
| `ALL`                          | no filter (all messages)                            |

Anything else (e.g. `TO`, `CC`, `HEADER`, `KEYWORD`) has no direct Graph
equivalent through this endpoint and is logged as a warning at run time rather
than silently ignored or guessed at.

The panel's old free-text **"Custom/Advanced (Raw IMAP)"** box was removed — typing
arbitrary IMAP RFC 3501 syntax there was misleading now that Graph, not IMAP, is
what actually executes the search, and Graph doesn't support that grammar. Use the
**Advanced Criteria Builder** (Type + Value + Add Criteria) above the Selected
Criteria tags instead; anything it can produce is exactly what `GraphMailService`
knows how to translate. Existing tasks saved before this change still load
correctly — their stored criteria string is best-effort re-parsed back into tags
when the task is reopened.

### Key classes
- `service/OAuth2TokenService.java` — device-code enrollment + silent token refresh
- `service/GraphMailService.java` — Graph REST calls (fetch/mark-as-read/move) + search-criteria translation
- `ui/OAuthAuthorizeDialog.java` — the one-time sign-in dialog
- `util/MiniJson.java` — minimal dependency-free JSON parser (used instead of adding
  a JSON library dependency)
- Mail watcher baseline fields live on `ScheduledTask` (`mailLastKnownEpoch` etc.) and
  are persisted the same way as file-transfer watcher baselines, via `XmlStorageService`