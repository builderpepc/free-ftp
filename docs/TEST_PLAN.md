# FreeFTP — Test Plan

Written **before** implementation (TDD). Every case below maps to a named test that
runs deterministically against a **real** server: an embedded Apache FtpServer
(FTP + FTPS) or an embedded Apache MINA SSHD (SFTP), booted on an ephemeral
localhost port per test class. No mocks of the protocol layer.

Prior art surveyed for case selection, in order of influence:

- **Cyberduck** (`iterate-ch/cyberduck`) — the closest analogue: a mature,
  Java-based FTP/FTPS/SFTP client with the most thorough open-source suite of
  the lot. Its `ftp/` module devotes an entire `parser/` package to
  server-dialect LIST parsing (`UnixFTPEntryParserTest`, `NTFTPEntryParserTest`,
  `vsFTPdEntryParserTest`, `NetwareFTPEntryParserTest`, `HPTru64ParserTest`,
  `EPLFEntryParserTest`, `MicrosoftFTPEntryParserTest`, `WebstarFTPEntryParserTest`,
  `RumpusFTPEntryParserTest`, `TrellixFTPEntryParserTest`, `StingrayFTPEntryParserTest`,
  `AXSPortFTPEntryParserTest`, `OpensolarisFTPEntryParserTest`,
  `FreeboxFTPEntryParserTest`, `FilezillaFTPEntryParserTest`) plus a `list/`
  package covering MLSD vs LIST vs STAT listing strategies. Its per-feature
  classes (`FTPMDTMTimestampFeatureTest`, `FTPMFMTTimestampFeatureTest`,
  `FTPUTIMETimestampFeatureTest`, `FTPUnixPermissionFeatureTest`,
  `FTPExceptionMappingServiceTest`, `SFTPSymlinkFeatureTest`,
  `SFTPTouchFeatureTest`, `SFTPHomeDirectoryServiceTest`,
  `SSHFingerprintGeneratorTest`, `PreferencesHostKeyVerifierTest`,
  `OpenSSHHostKeyVerifierTest`, `PuTTYKeyTest`, and the `auth/` package of
  password / publickey / keyboard-interactive / none authentication tests)
  directly shaped sections 2, 3, 6b and 10 below.
- **FileZilla** — its `dirparser` tests and documented server-quirk matrix
  (MLSD vs LIST, UTF-8 negotiation, passive/active fallback, TLS resumption)
  motivated 5.14, 5b and 4.x.
- **curl** — its `tests/data` FTP cases explicitly cover zero-byte transfers
  (`sendzero`), resume offsets, and server responses that break naive clients;
  motivated 7.1, 7.9–7.11 and 10.x.
- **lftp** — resume/restart and listing regression cases.
- **Apache Commons Net** and **SSHJ** upstream suites, since those are the two
  protocol libraries in use.

Legend: **U** = pure unit (no server), **S** = integration vs. real server,
**D** = on-device: run on any emulator or phone. The results recorded below were
taken on an Android 16 (API 36) arm64 device.

---

## 1. Remote path handling (U) — `RemotePathTest`

Path bugs are the number-one source of "works on my server" failures, so these
are exhaustive and pure.

| # | Case | Expectation |
|---|---|---|
| 1.1 | Normalize `/a//b/` | `/a/b` |
| 1.2 | Normalize `` and `.` | `/` |
| 1.3 | Resolve `..` past root | clamps at `/` |
| 1.4 | `parent("/a/b.txt")` | `/a` |
| 1.5 | `parent("/a")` and `parent("/")` | `/` and `/` |
| 1.6 | `name("/a/b.txt")`, `name("/")` | `b.txt`, `""` |
| 1.7 | `join("/a", "b c.txt")` | `/a/b c.txt` (no escaping applied) |
| 1.8 | `join("/a/", "/b")` | `/a/b` |
| 1.9 | Names with `..` embedded (`/a/..b`) | preserved, not treated as parent |
| 1.10 | Unicode + emoji segment round-trip | preserved byte-for-byte |
| 1.11 | `isAncestorOf` for `/a` vs `/ab` | false (prefix must be a path boundary) |
| 1.12 | `relativize("/a/b", "/a/b/c/d")` | `c/d` |
| 1.13 | Windows-style backslash input from a server | not treated as separator |
| 1.14 | `segments("/a/b")` | `["a","b"]` |
| 1.15 | Extremely long path (4096 chars) | no truncation/overflow |

## 2. Connection & authentication (S) — `FtpConnectionTest`, `SftpConnectionTest`

| # | Case | Expectation |
|---|---|---|
| 2.1 | FTP connect + login with valid credentials | connected, `isConnected()` true |
| 2.2 | FTP login with wrong password | `AuthenticationException`, not a generic IO error |
| 2.3 | FTP connect to a closed port | `ConnectException`-backed `TransportException`, fast fail (< connect timeout + slack) |
| 2.4 | FTP anonymous login | succeeds where server allows it |
| 2.5 | FTP passive mode transfer | list + download succeed |
| 2.6 | FTP active mode transfer | list + download succeed (server-side data connect) |
| 2.7 | FTP disconnect then reconnect on same client object | works, no leaked socket |
| 2.8 | FTP `disconnect()` when never connected | no-op, no throw |
| 2.9 | SFTP connect + password auth | connected |
| 2.10 | SFTP wrong password | `AuthenticationException` |
| 2.11 | SFTP public-key auth (OpenSSH ed25519 key) | connected |
| 2.12 | SFTP public-key auth (RSA PEM key) | connected |
| 2.13 | SFTP encrypted private key, correct passphrase | connected |
| 2.14 | SFTP encrypted private key, wrong passphrase | `AuthenticationException` |
| 2.15 | Connect timeout is honoured (unroutable host) | throws within the configured budget |
| 2.16 | Operation before `connect()` | `IllegalStateException`/`NotConnectedException` |
| 2.17 | SFTP keyboard-interactive auth | connected (Cyberduck `SFTPChallengeResponseAuthenticationTest`) |
| 2.19 | Auth method fallback: publickey offered, rejected, password succeeds | connected, no spurious failure |
| 2.20 | FTP UTF-8 negotiation via `FEAT` | control encoding switched to UTF-8 automatically |

## 3. Host-key verification, SFTP (S) — `HostKeyVerificationTest`

| # | Case | Expectation |
|---|---|---|
| 3.1 | First connect to unknown host, TOFU accepted | key persisted with correct fingerprint |
| 3.2 | Reconnect with the stored, matching key | connects silently |
| 3.3 | Reconnect after the server key changed | `HostKeyChangedException`; connection refused |
| 3.4 | Strict mode with no stored key | `UnknownHostKeyException`, no silent trust |
| 3.5 | Fingerprint format | `SHA256:` base64, matches `ssh-keygen -lf` |
| 3.6 | Store keyed by host **and** port | `:22` and `:2222` are separate entries |
| 3.7 | A file-backed store survives a restart | the fingerprint is still trusted |

> **Dropped:** importing an OpenSSH `known_hosts` file (Cyberduck's
> `OpenSSHHostKeyVerifierTest`). That test earns its place in a desktop client that
> shares a home directory with `ssh`; on Android there is no `~/.ssh` to read, and
> adding an import path purely to satisfy a test would be a feature nobody asked for.

## 3b. Not covered by automated tests
- Importing PuTTY `.ppk` keys (planned case 2.18). SSHJ can parse them, but the
  suite generates its keys with `ssh-keygen`, which cannot emit `.ppk`; asserting
  on a checked-in key blob would test the fixture, not the client.

## 4. FTPS / TLS (S) — `FtpsConnectionTest`

| # | Case | Expectation |
|---|---|---|
| 4.1 | Explicit FTPS (`AUTH TLS`) against a TLS-enabled server | connects, lists, transfers |
| 4.2 | Implicit FTPS | connects, lists, transfers |
| 4.3 | Self-signed cert with verification **on** | `SSLHandshakeException` surfaced as `TlsException` |
| 4.4 | Self-signed cert with "trust this certificate" enabled | connects |
| 4.5 | Data channel is also protected (`PROT P`) | transfer succeeds and content is intact |
| 4.6 | Plain FTP client against a TLS-only server | fails cleanly with a protocol error |

## 5. Directory listing (S) — `FtpListingTest`, `SftpListingTest`

| # | Case | Expectation |
|---|---|---|
| 5.1 | List an empty directory | empty list, not null, no error |
| 5.2 | List mixed files and directories | `isDirectory` correct for each |
| 5.3 | Sizes reported match the bytes written | exact |
| 5.4 | Modified timestamps parsed and within 5 min of truth | non-null, plausible |
| 5.5 | Names with spaces, `#`, `&`, `'`, `+`, `%` | round-trip intact |
| 5.6 | Non-ASCII names (UTF-8, CJK, emoji) with UTF-8 control encoding | round-trip intact |
| 5.7 | Hidden dotfiles are returned | present in listing |
| 5.8 | `.` and `..` entries are filtered out | absent |
| 5.9 | List a nonexistent directory | `FileNotFoundException`-flavoured `RemoteException` |
| 5.10 | List a path that is a file, not a directory | `NotADirectoryException` |
| 5.11 | Large directory (500 entries) | all 500 returned, no truncation |
| 5.12 | Symlink entry (SFTP) | flagged `isSymlink`, target resolvable |
| 5.13 | Listing sort is stable (dirs first, then case-insensitive name) | deterministic order |
| 5.14 | FTP server that only supports `LIST` (no `MLSD`) | falls back and still parses |
| 5.15 | Server advertises `MLSD`; facts (`type`, `size`, `modify`) are used | parsed from facts, not the LIST heuristics |

## 5b. LIST dialect parsing (U) — `ListingParserTest`

Following Cyberduck's `ftp/parser/` package: raw response lines captured from
real servers are fed to the parser directly. No server needed, fully
deterministic, and this is where real clients accumulate the most regressions.

| # | Dialect / case | Expectation |
|---|---|---|
| 5b.1 | vsftpd Unix long listing | name/size/dir flag/date correct |
| 5b.2 | ProFTPD Unix listing with `total 12` header | header line ignored |
| 5b.3 | Unix listing, filename containing spaces | full name captured, not truncated at the first space |
| 5b.4 | Unix listing, symlink `lrwxrwxrwx ... link -> target` | `isSymlink`, name is `link`, target `target` |
| 5b.5 | Unix listing, recent date `Mar  3 09:14` (no year) | year inferred, not in the future |
| 5b.6 | Unix listing, old date `Mar  3  2019` (year, no time) | year 2019, midnight |
| 5b.7 | Unix listing with no group column | still parses |
| 5b.8 | Windows/IIS `MS-DOS` listing `02-14-24 09:11AM <DIR> name` | dir flag + date correct |
| 5b.9 | IIS listing of a file with size | size correct |
| 5b.10 | Netware listing | parses |
| 5b.11 | EPLF listing (`+i8388621.48594,m825718503,r,s280,\tfile`) | size/mtime/type from facts |
| 5b.12 | MLSD facts line `type=dir;sizd=4096;modify=20240214091100; name` | dir, mtime UTC-correct |
| 5b.13 | MLSD file line with `type=file;size=1234;` | size correct |
| 5b.14 | MLSD `type=cdir` / `type=pdir` entries | filtered out |
| 5b.15 | Unicode filename in a LIST line with UTF-8 control encoding | intact |
| 5b.16 | Garbage / unparseable line | skipped, does not abort the whole listing |

## 6. File operations (S) — `FtpFileOpsTest`, `SftpFileOpsTest`

| # | Case | Expectation |
|---|---|---|
| 6.1 | `mkdir` then list | directory appears |
| 6.2 | `mkdir` nested (`mkdirs`) | full chain created |
| 6.3 | `mkdir` over an existing name | `FileAlreadyExistsException` |
| 6.4 | `delete` a file | gone from listing |
| 6.5 | `delete` a nonexistent file | `RemoteFileNotFoundException` |
| 6.6 | `rmdir` on an empty directory | removed |
| 6.7 | `rmdir` on a non-empty directory (non-recursive) | fails, contents untouched |
| 6.8 | `deleteRecursively` on a 3-level tree | whole tree removed |
| 6.9 | `rename` within a directory | old name gone, new name present, content intact |
| 6.10 | `rename` across directories (move) | moved, content intact |
| 6.11 | `rename` onto an existing target | documented behaviour, no silent data loss |
| 6.12 | `exists()` for present and absent paths | true / false, no throw |
| 6.13 | `stat()` on a file returns size + mtime | matches source |
| 6.14 | Operations on names needing quoting (spaces, unicode) | all succeed |

## 6b. Metadata features (S) — `FtpMetadataTest`, `SftpMetadataTest`

Mirrors Cyberduck's timestamp / permission / touch / symlink feature tests.

| # | Case | Expectation |
|---|---|---|
| 6b.1 | `touch` creates an empty file | exists, size 0 |
| 6b.2 | Set modification time, FTP (`MFMT`, falling back to `MDTM`) | `stat()` reports the new mtime (± 1 s) |
| 6b.3 | Set modification time, SFTP (`SETSTAT`) | `stat()` reports the new mtime |
| 6b.4 | Server without `MFMT`/`MDTM` support | reported as unsupported, not a silent no-op |
| 6b.5 | Set Unix permissions, SFTP (`chmod 0640`) | `stat()` reports `rw-r-----` |
| 6b.6 | Set Unix permissions, FTP (`SITE CHMOD`) | applied where supported, unsupported reported otherwise |
| 6b.7 | Create a symlink, SFTP | listing shows it as a symlink to the target |
| 6b.8 | Read through a symlink to a file, SFTP | content matches the target |
| 6b.9 | Home directory resolution, SFTP (`realpath .`) | absolute path, not `.` |
| 6b.10 | Working directory, FTP (`PWD`) | absolute path after `CWD` |
| 6b.11 | Preserve mtime on upload when enabled | remote mtime == local mtime |

## 7. Transfers (S) — `FtpTransferTest`, `SftpTransferTest`, `TransferIntegrityTest`

| # | Case | Expectation |
|---|---|---|
| 7.1 | Upload then download a 0-byte file | 0 bytes, no error |
| 7.2 | Upload then download 1 KiB text | SHA-256 matches |
| 7.3 | Upload then download 5 MiB random binary | SHA-256 matches (binary mode, no CRLF mangling) |
| 7.4 | Binary containing `0x0D 0x0A` sequences | byte-identical (regression: ASCII-mode corruption) |
| 7.5 | Progress callback is monotonic and ends at total | strictly non-decreasing, final == size |
| 7.6 | Progress fires more than once for a 5 MiB transfer | > 1 callback |
| 7.7 | Cancel mid-download | throws `TransferCancelledException`, partial file left at a known length |
| 7.8 | Cancel mid-upload | cancelled promptly (< 2 s) |
| 7.9 | Resume download from a byte offset | resulting file's SHA-256 matches the whole original |
| 7.10 | Resume upload (`APPE`/offset write) | remote SHA-256 matches the whole original |
| 7.11 | Resume offset beyond file size | rejected with a clear error |
| 7.12 | Download to a path whose parent does not exist | creates parents or fails clearly (documented) |
| 7.13 | Upload overwrites an existing remote file | final content is the new content, full length |
| 7.14 | Two sequential transfers on one connection | both succeed (control channel left clean) |
| 7.15 | Server closes the connection mid-transfer | `TransferException`, client recovers on reconnect |

## 8. Transfer queue / manager (U + S) — `TransferQueueTest`

| # | Case | Expectation |
|---|---|---|
| 8.1 | Enqueue 3 transfers, run sequentially | all complete, order preserved |
| 8.2 | Queue state transitions | `Queued → Running → Completed` observed exactly once each |
| 8.3 | A failing transfer does not stall the queue | subsequent items still run, failure recorded |
| 8.4 | Cancel a queued (not yet started) item | never starts, marked `Cancelled` |
| 8.5 | Cancel the running item | stops, next item starts |
| 8.6 | Aggregate progress across the queue | reflects per-item bytes |
| 8.7 | Retry a failed item | re-enqueued and completes |
| 8.8 | Clear completed items | only completed removed |

## 9. Connection profile persistence (U) — `ServerProfileRepositoryTest`

| # | Case | Expectation |
|---|---|---|
| 9.1 | Save then load a profile | all fields round-trip |
| 9.2 | Update an existing profile | no duplicate created |
| 9.3 | Delete a profile | gone; others intact |
| 9.4 | Passwords are not stored in plaintext in the backing file | ciphertext on disk, plaintext via the API |
| 9.5 | Corrupt store file | load yields an empty list, no crash |
| 9.6 | Default port per protocol | FTP 21, FTPS-implicit 990, SFTP 22 |
| 9.7 | Profile validation | empty host / bad port rejected with a field-level error |

## 10. Error mapping (U + S) — `ErrorMappingTest`

| # | Case | Expectation |
|---|---|---|
| 10.1 | Unknown host | `TransportException` with a user-readable message |
| 10.2 | Connection refused | `TransportException`, message names host:port |
| 10.3 | Permission denied on write (read-only dir) | `PermissionDeniedException` |
| 10.4 | Disk-quota / write failure surfaces | not swallowed; queue marks the item failed |
| 10.5 | No exception type leaks a password into its message | assert on message contents |
| 10.6 | FTP reply 550 → not found, 553 → invalid name, 530 → auth, 552 → quota | mapped distinctly (Cyberduck `FTPExceptionMappingServiceTest`) |
| 10.7 | SFTP status codes `NO_SUCH_FILE`, `PERMISSION_DENIED`, `FAILURE` | mapped distinctly (`SFTPExceptionMappingServiceTest`) |
| 10.8 | Every mapped exception has a non-empty, human-readable message | asserted for all types |

## 11. On-device (D) — driven over ADB

Run against the same real servers, started by `./gradlew :core:devServers` on the
development machine and reached from the device through `adb reverse` (the control
ports and the FTP passive data-port range). Any emulator or physical device works;
the results below were recorded on an Android 16 (API 36) arm64 device.

| # | Case | Result |
|---|---|---|
| 11.1 | App installs and launches on Android 16 (arm64) | **pass** — server list renders |
| 11.2 | Add an FTP profile against the host test server | **pass** — saved and listed |
| 11.3 | Connect and browse | **pass** — 5 entries, sizes and dates correct |
| 11.4 | Download a file to device storage | **pass** — `readme.txt`, 67 B, byte-identical |
| 11.5 | Upload a file picked through the storage picker | **pass** — appears on the server, 59 B, content matches |
| 11.6 | Create a remote folder (name containing a space) | **pass** — `from phone` created on disk |
| 11.7 | Add an SFTP profile, review the host key, browse | **pass** — prompt showed the server's exact `SHA256:` fingerprint |
| 11.8 | Download 1 MiB over SFTP | **pass** — MD5 on device matches the server |
| 11.9 | Rotation mid-browse | **pass** — listing intact in both orientations |
| 11.10 | Process death while backgrounded, then resume | **pass** — returns to the server list, no crash |
| 11.11 | Secrets at rest | **pass** — `servers.dat` holds ciphertext; host key stored as `127.0.0.1:2222 SHA256:...` |
| 11.12 | The toolbar's ⋮ opens a menu rather than acting | **pass** — regression: it used to disconnect on the first tap |
| 11.13 | "Open here next time" sets the start folder | **pass** — survives reconnect *and* an app restart; `initialPath=/documents` on disk |
| 11.14 | The menu reflects which folder is the start folder | **pass** — "Opens here already" (disabled, ticked) only in that folder |
| 11.15 | System back walks up the remote tree | **pass** — `/documents/reports/2026` → `reports` → `documents` → `/` |
| 11.16 | System back at the root leaves the server | **pass** — returns to the server list, server logs the connection CLOSED, no socket left established |
| 11.17 | Tapping a text file opens it in the app | **pass** — UTF-8 config with CJK and emoji rendered correctly |
| 11.18 | A Latin-1 file that is invalid UTF-8 | **pass** — shown as ISO-8859-1: "Grüße aus München", no mojibake |
| 11.19 | A CRLF file | **pass** — 3 lines, no stray glyph at the line ends |
| 11.20 | A 2.2 MB log | **pass** — first 1 MB shown with a banner; 18,304 lines scroll smoothly (8 flings in 0.9 s) |
| 11.21 | A binary file | **pass** — "Not a text file", with a download button instead |
| 11.22 | An empty file | **pass** — "This file is empty" |
| 11.23 | Nothing is written to disk by viewing | **pass** — the Downloads folder and app storage stay empty |
| 11.24 | A truncated preview over FTP leaves the session usable | **pass** — listing and a second preview both work straight after |
| 11.25 | Download a folder from its row menu | **pass** — `project/` arrived with `src/main/App.kt`, `docs/README.md` etc., structure intact |
| 11.26 | Long-press starts a selection; tapping adds to it | **pass** — checkboxes on every row, count in the bar, selected row highlighted |
| 11.27 | Bulk download of a mixed selection | **pass** — a folder and a loose file, each landing at the right relative path |
| 11.28 | "Download all in this folder" | **pass** — 51 items, 117.9 MB, all transferred |
| 11.29 | Confirmation above the file-count threshold | **pass** — "Download 35 items?"; cancelling queued nothing |
| 11.30 | Confirmation above the size threshold | **pass** — "Download 51 items? … 117.9 MB" |
| 11.31 | Pause a running transfer | **pass** — over a 50 KB/s link: "Paused · 63.0 KB of 2.2 MB", queue held |
| 11.32 | Resume | **pass** — continued from 63 KB; final MD5 identical to the file on the server |
| 11.33 | Tapping a completed download opens it | **pass** — the system chooser appears for the file's type |
| 11.34 | Downloads land somewhere the user can browse | **pass** — `Downloads/FreeFTP/…`, visible in the Files app |
| 11.35 | Settings shows and can change the download folder | **pass** — folder picker grants access to one directory; no storage permission requested |

Three defects were found here that no JVM test could have caught, because all
three are Android platform behaviours:

1. **Android key store rejects a caller-supplied GCM nonce.** Saving the first
   profile crashed with `InvalidAlgorithmParameterException: Caller-provided IV
   not permitted`. Fixed by letting the provider generate the nonce.
2. **SSHJ silently got Android's cut-down `BC` provider**, so SFTP failed with
   `no such algorithm: X25519 for provider BC`. Fixed by replacing the platform
   provider with the bundled BouncyCastle at startup.
3. **A restored browser screen after process death had no connection to browse.**
   Fixed by returning to the server list instead of showing an error.

A fourth was reported by the first person to use the app: the toolbar's ⋮ button
disconnected the session on the first tap. It carried the universal "overflow
menu" icon while performing an immediate, destructive-feeling action — a case of
the icon promising one thing and the handler doing another, which no assertion
was ever going to catch. It is now a real menu (11.12-11.14).

Two more surfaced while testing the bulk work: the row menu offered no **Download**
for folders at all (the reported gap), and the floating action button sat on top of
the last row's menu, making that entry unusable. Both fixed (11.25).

The same report turned up a second one: the system back button left the server
outright on the first press, rather than walking up the directory tree — and left
the connection open behind it. Back and the toolbar arrow now do the same thing:
go up a level, and only leave (closing the connection) once at the root (11.15,
11.16).

## 12. In-app text preview (U + S) — `TextPreviewTest`, `RemoteTextPreviewTest`

Streams a file into memory and shows it, without writing anything to disk. The
hard part is not the fetch, it is deciding what is text and in which encoding —
guess wrong and the user sees mojibake or a screen of control characters.

| # | Case | Expectation |
|---|---|---|
| 12.1 | Plain ASCII | shown as text, charset UTF-8 |
| 12.2 | UTF-8 with CJK and emoji | round-trips exactly |
| 12.3 | UTF-8 with a BOM | BOM stripped, not shown as a stray glyph |
| 12.4 | UTF-16LE and UTF-16BE with BOM | decoded, despite being full of `NUL` bytes |
| 12.5 | Latin-1 bytes that are invalid UTF-8 | falls back to ISO-8859-1, no replacement characters |
| 12.6 | A file containing a `NUL` byte | reported binary, not rendered |
| 12.7 | PNG and ZIP headers | reported binary |
| 12.8 | Mostly control characters | reported binary |
| 12.9 | High-bit Latin-1 prose (accented text) | reported text, not binary |
| 12.10 | Empty file | text, empty content, no error |
| 12.11 | CRLF line endings | preserved; line count correct |
| 12.12 | Line count for content with and without a trailing newline | no off-by-one |
| 12.13 | Fetch a text file over FTP and over SFTP | content matches the file on the server |
| 12.14 | A file larger than the limit | first N bytes shown, flagged truncated |
| 12.15 | Truncated fetch does not leave the session unusable | the next listing still works |
| 12.16 | A binary file is not fully transferred to find out it is binary | reads no more than the limit |
| 12.17 | Preview of a missing file | `RemoteFileNotFoundException`, no crash |
| 12.18 | A minified one-megabyte single line | hard-wrapped for display, content preserved; `\r` stripped |

## 13. Recursive directory scanning (S) — part of `RemoteClientContractTest`

Downloading a folder means walking it first. The walk is where a file manager can
hang forever or blow up memory, so it is bounded in three ways and every bound is
tested.

| # | Case | Expectation |
|---|---|---|
| 13.1 | Scan a flat directory | every file found, sizes summed |
| 13.2 | Scan a nested tree | relative paths keep the structure, e.g. `docs/reports/2026/q3.txt` |
| 13.3 | Relative paths are taken from the browsing directory | selecting `a.txt` and `docs/` yields `a.txt` and `docs/...` |
| 13.4 | Empty directories are counted but contribute no files | directory count correct |
| 13.5 | Scan a mixed selection of files and directories | both included, in one result |
| 13.6 | A directory symlink is not followed | skipped and counted; a symlink loop terminates |
| 13.7 | `maxFiles` reached | stops, flags `truncated`, does not run away |
| 13.8 | `maxDepth` reached | stops descending, flags `truncated` |
| 13.9 | Total byte count matches the files on the server | exact |
| 13.10 | Scanning a missing path | `RemoteFileNotFoundException` |

## 14. Pausing and resuming the queue (S) — `TransferQueueTest`

| # | Case | Expectation |
|---|---|---|
| 14.1 | Pause while a transfer is running | it stops, marked `Paused`, partial bytes retained |
| 14.2 | Resume | it continues **from the partial file** and the final SHA-256 matches the whole original |
| 14.3 | Pause holds the rest of the queue | no queued item starts while paused |
| 14.4 | Resume releases the whole queue | every item completes, order preserved |
| 14.5 | Pause with nothing running | no error; later items still held |
| 14.6 | Resume when not paused | no-op, nothing duplicated |
| 14.7 | Cancel an item while paused | it is cancelled, not silently resumed later |
| 14.8 | Pause then resume an upload | remote SHA-256 matches the whole original |
| 14.9 | `isPaused` reflects the state | observable for the UI |

## 15. Bulk-action guard rails (U) — `BulkTransferPolicyTest`

Downloading the wrong folder — a home directory, or `/` — should take a
deliberate second tap, not happen because a long-press landed badly.

| # | Case | Expectation |
|---|---|---|
| 15.1 | A handful of small files | no confirmation |
| 15.2 | More files than the count threshold | confirmation required |
| 15.3 | Fewer files but more bytes than the size threshold | confirmation required |
| 15.4 | Exactly at each threshold | boundary behaviour is defined and tested |
| 15.5 | A truncated scan | always requires confirmation, since the real total is unknown |

---

## Out of scope for automated tests
- Real-world server quirk matrix beyond what the embedded servers emulate
  (IIS/vsftpd/ProFTPD listing dialects) — covered by the `LIST` fallback parser
  test (5.14) and manual checks.
- Play Store packaging / signing.
