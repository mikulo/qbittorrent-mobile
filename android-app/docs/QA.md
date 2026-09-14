# Build and runtime verification

## Version 0.3.9 public download directory (2026-09-14)

- Default new-task location is Android public Downloads/qbittorrent (normally `/sdcard/Download/qbittorrent`). Custom defaults and existing task locations are preserved during upgrade; explicit reset offers migration confirmation.
- Added shared storage-permission flow, refusal gating at UI/service/engine startup, Android 10 legacy storage declaration, per-source savePath persistence and native storage-move success/failure handling. Settings path rendering no longer creates/probes directories.
- `assembleDebug`: BUILD SUCCESSFUL. Per user instruction no tests, emulator execution or lint. Runtime permission acceptance/denial, upgrade, migration, file access from another app and restart behavior remain untested in this version. Future emulator regressions need explicit storage permission setup.

## Version 0.3.8 one-second refresh (2026-09-13)

- User accepted 0.3.7 and explicitly requested 1-second refresh, APK generation without testing, and a GitHub update.
- List/detail/notification refresh, asynchronous state/statistics request gate, and detail polling are now 1000ms. The 0.3.7 asynchronous pipeline is retained.
- Only `assembleDebug` is requested for this version; no unit tests, instrumentation, emulator run or lint. Cadence-dependent test expectations were updated but not executed. Earlier passing results below belong to 0.3.7, not this build.
- `assembleDebug`: BUILD SUCCESSFUL. Local artifact: `artifacts/qBittorrent-Mobile-0.3.8-universal-debug.apk`, versionCode 13, 32,250,053 bytes. SHA-256: `76b0a86d755b91c1220c8376d64fb02b3871d29bee52e011e23dab48930930f6`.

## Version 0.3.7 native transfer freshness (2026-09-13)

- User-provided logs: 12 active-download health samples showed the old serial query taking 1,816–10,396ms (mean 5,753.8ms); empty-session samples took 0–4ms. The UI's 500ms timer did not bound data age. Raw logs, private torrent names, tracker credentials and test input links are not included here.
- Replaced high-frequency synchronous per-handle status/name/flags/path reads with native asynchronous state/statistics alerts, requested at 500ms cadence. The library's own requests share the same one-in-flight-per-type gates. Delta responses retain unchanged tasks; removed tasks cannot reappear from late state callbacks. Existing/committed handles explicitly subscribe to updates.
- Session/task rates use counter differences on native alert timestamps, without the additional Java SessionStats smoothing. Accurate native completion counters replace the old payload-addition estimate (which could count retransmitted bytes). File-detail reads and checkpoint writes no longer block publication of the fast status cache.
- New health fields separate state/statistics age, reply/pending time, conversion time, detail-query time and frame counts. These measure the pipeline rather than merely counting UI timer callbacks.
- Four JVM tests passed: half-second counter deltas, delayed/duplicate samples, counter/clock reset, and bounded native requests during a 10-second stall.
- Android 14/API 34 x86_64, dedicated `QBMobile_API34`, `emulator-5554` only. A local trackerless 128 MiB fixture transfers over loopback; no private torrent, public tracker or user's magnet/URL was used. The test intentionally blocks the details executor for 10 seconds and checks cache progress, real task-card counter changes, statistics callbacks, file details, upload to a second local peer, pause-to-zero and removal. Generated successful-run fixture files are removed by the test.
- Earlier download-only full suite passed all 6 instrumentation methods. In a subsequent UI-observation run, 10 seconds produced 20 statistics frames, 11 completion changes, 13 visible counter changes and a maximum completion-change gap of 2,037ms. These values distinguish fresh samples from actual traffic: half-second sampling does not guarantee a different byte count on every frame, especially under rate limiting/bursty transfers or rounded GiB display.
- Rejected experiment: changing the bundled disk backend from POSIX to default mmap caused MediaProvider/FUSE aborts on both API 37 preview and a fresh API 34 emulator, followed by system termination of the app. This change was reverted in production and test seeders. The delivered code retains the original POSIX disk backend; no claim of a new asynchronous disk backend is made.
- Test corrections: an initial 32 MiB fixture had a 3,437ms completion-change gap and could finish during observation; it was enlarged and the test now requires it to remain downloading throughout. A later 128 MiB run still had a 3,448ms byte-change gap despite 20 statistics responses, so early completion alone did not explain the gaps. The final assertions bound sample age (<1,500ms), require real progress/card changes and retain byte-change gaps as diagnostics, rather than treating a rate-limited peer's traffic cadence as the sampler cadence. Additional upload phases initially failed to connect a receiver to the app (including after disconnecting the same-IP seed); the test now lets the app initiate the connection to the receiver's dedicated loopback listener. No app connection policy is changed for this test. Final expanded-suite result is recorded below after execution.
- Scope: emulator results do not establish long-running stability or latency on the user's vivo device, slow/removable storage, many torrents, or a large real-world file list. Private Tracker protocol behavior and HTTPS certificate validation were not changed by this refresh fix.
- Final expanded suite: **OK (6 tests), 30.272 seconds**, including actual upload and visible UI checks. In the 10-second download observation: 20 statistics responses, 11 completion changes, 13 visible task-card counter changes; maximum state sample age 497ms, statistics sample age 496ms, byte-change gap 2,031ms. Upload phase observed 11,534,336 new payload bytes and positive task/aggregate upload rates. Pause reached zero task rates, file details populated, and the generated task/payload were removed successfully.
- Final build: `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` passed (lint: 0 errors, 13 warnings). APK: `artifacts/qBittorrent-Mobile-0.3.7-universal-debug.apk`, versionCode 12, 32,318,866 bytes, four ABIs, signature scheme v2. Signing certificate SHA-256 matches the 0.3.6 artifact, so this local build can upgrade it without uninstalling.
- Artifact SHA-256: `450ad51a9c12dad45deaf7bbc19526b66ecf8b861b0e2599580f66e21117f92d`. Final process exit was instrumentation's expected force-stop, not an unexpected crash.

## Version 0.3.6 refresh cadence and file logging (2026-09-12)

- Task list, details, notification refresh: 500ms; shared native-state sampler: fixed delay 500ms (plus query time). Native statistics can be averaged internally; the UI does not fabricate intermediate speed values.
- Instrumentation verified five refresh callbacks with intervals in the 450–800ms tolerance, bounded event bursts/stopped consumers and the existing 1,000-file options dialog.
- File logging regression passed: selected directory persisted, UTF-8 Chinese text written, exception type retained, synthetic tracker passkey and Authorization value removed. Rotation retained four historical files without touching an unrelated file. Tests used app cache directories and removed their temporary files.
- Settings UI: log-directory button and current path visible. On-device text log verified startup/lifecycle and health entries at ~10-second intervals. No private torrent was used.
- Build and lint passed. The first unfiltered instrumentation launch during emulator boot was terminated by LOW_MEMORY; explicit test-class runs after boot completed successfully.

## Version 0.3.5 foreground stability regression (2026-09-12)

- Reported device: vivo X200 Pro mini, Android 14; one torrent, main list, slowdown then process restart within 30 seconds of foregrounding. No exit record from that device was available, so its exact termination cause is unconfirmed.
- Confirmed source defects: Activity/Service renders performed synchronous native torrent/status/tracker calls on the main thread; tracker events queued an additional full render/notification with no bound; lifecycle stop removed the periodic callback but not the event lambdas. Detail renders also reconstructed file text repeatedly.
- Fixed: shared immutable cached snapshots from a single fixed-delay background sampler; native status/vector resources released after conversion; detail reads only while observed; one bounded lifecycle refresh per consumer; repeated card change animations disabled. Service checkpoints run off the main thread.
- Added local-only system exit diagnostics in Settings (reason, exit status, timestamp, RSS, device and current PID). No magnet URLs, tracker credentials or filenames are included.
- Build, Android test APK and lint passed. Instrumentation verified a 10,000-request burst, repeated starts, stopped-page suppression, cached readers, and the existing 1,000-file add-dialog regression (3 tests).
- Public magnet from line 3 of `magnet.txt`, only `emulator-5554`. Final APK observed through two foreground returns, each over 30 seconds: PID stayed 8665, progress advanced 12.9 to 21.5 MiB; 20 seeds/112 peers then 34 seeds/157 peers; PSS 81,360 to 84,159 KiB. No crash-buffer entry or new unexpected exit record. These measurements do not establish behavior on the user's vivo device.
- Detail stats populated after switching to the details screen. QA torrent and its downloaded files removed via the app after testing.

## Version 0.3.4 add-dialog regression (2026-09-12)

- Compared the desktop `addnewtorrentdialog.ui` layout, which keeps the button box separate from scrollable content. Replaced the Android AlertDialog custom-view/multi-choice combination with an independently scrolling RecyclerView and fixed action footer.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed.
- Emulator `emulator-5554`: `TorrentOptionsDialogTest` passed with 1,000 synthetic file entries; no torrent payload or tracker is used by the test.
- Both footer buttons remain fully visible before and after scrolling to the last file, and with the window reduced to 300dp height (a reduced-viewport check, not an actual keyboard test).
- All/none selection updates confirmation availability. Deselecting the last file survives scrolling back to the header; submitting delivers the exact selection and 128 KiB/s download limit.
- Screenshot visually reviewed: metadata, limits, file checkboxes, selection summary and fixed confirmation buttons are visible.

Verification date: 2026-08-30

## Build

- `:app:assembleDebug`: passed
- `:app:lintDebug`: passed (warnings only)
- APK signature: Android APK Signature Scheme v2, debug certificate
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`

## Emulator smoke test

- Device: Android Emulator `Pixel_10_Pro`, x86_64
- Cold launch: passed
- Foreground service: started
- Native library: `lib/x86_64/libtorrent4j.so` loaded successfully
- Main screen, add dialog, settings screen and torrent card rendered
- No `FATAL EXCEPTION`, `AndroidRuntime` crash or `UnsatisfiedLinkError` in logcat

## Version 0.2.0 regressions

- Public magnet pause: after pausing, the action changed to “继续” and transfer stayed at zero during a 12-second observation.
- Process restart: the persisted source contained `paused: true`; after force-stop and relaunch, the task still showed “继续” and did not enter checking/downloading.
- Direct torrent URL: the URL in `dl-link.txt` downloaded a valid metainfo file and created a task; the URL and any private credentials were not printed in QA output. The task was paused immediately after validation.
- Custom directory: Android's system tree picker selected `/storage/emulated/0/Download/qBMobileQA`; the engine persisted the resolved native path and verified it was writable. The temporary QA directory and imported test data were removed afterward.

## Version 0.3.0 regressions

- A locally generated public, trackerless two-file torrent opened the pre-download options dialog with both files checked by default.
- Unchecking the second file persisted one `IGNORE` file priority; setting 128 KiB/s persisted a native 131072 B/s per-torrent limit.
- The pending torrent stayed hidden from the main list while metadata/options were pending.
- A left swipe revealed the task's right-side delete button and clicking it opened the deletion confirmation dialog.
- The detail screen loaded the existing 128 KiB/s task limit; changing it to 64 KiB/s persisted a native 65536 B/s limit.

## Version 0.3.1 fast-resume regression

- Adding a public, trackerless two-file QA torrent immediately produced one non-empty `.fastresume` checkpoint.
- After force-stopping the Android process, startup logged that the torrent was restored from the fast-resume checkpoint; the task appeared without entering the checking state.
- Pausing produced another checkpoint immediately. A second force-stop/restart retained the paused state and again avoided checking.
- QA used no private torrent or private Tracker, and the temporary torrent and payload directory were removed afterward.

## Version 0.3.2 magnet metadata regression

- The metadata-only handle clears `PAUSED` and `AUTO_MANAGED`, enables `UPLOAD_MODE` and `STOP_WHEN_READY`, and is explicitly resumed, matching qBittorrent/libtorrent's metadata-fetch flow.
- DHT bootstrap nodes are explicitly configured and DHT is started when enabled. During a public-magnet cold-start test the session progressed from 0 to 49 DHT nodes and reached 38 peers.
- A public magnet from `magnet.txt` opened the file-selection and per-task speed-limit dialog in roughly 10–20 seconds on the emulator.
- Tapping outside the loading dialog no longer dismisses it. Canceling a pending magnet removes its native handle immediately; re-adding the same magnet did not produce a duplicate-task error.
- libtorrent session state is saved atomically and restored on startup so the DHT routing table does not cold-start after every process restart.
- No private torrent or private Tracker was used for this regression.

## Version 0.3.3 torrent-card regression

- Swipe handling and its revealed-row state were removed; the delete button is permanently present on every torrent card.
- Diff updates now include completed/total bytes, uploaded bytes, ETA and state, so the live byte counter is rebound even before a whole piece changes the torrent percentage.
- Live completion combines verified `totalWantedDone` with incremental payload bytes reported between refreshes, bounded by the wanted size. This keeps restart-safe verified progress while allowing sub-piece visual updates.
- Progress bars use 100,000 steps instead of 1,000. Downloaded counters render KiB as integers, MiB with one decimal place, and GiB/TiB with two decimal places.

## Tracker identity capture

The emulator added a QA magnet whose tracker pointed to a one-shot TCP listener on the host. The actual announce request contained:

```http
GET /announce?...&peer_id=-qB5230-kPOfxQG7QPLF&port=6881&uploaded=0&downloaded=0&left=16384&event=started&compact=1&supportcrypto=1... HTTP/1.1
User-Agent: qBittorrent/5.2.3
```

This verifies that the live libtorrent session, not just the settings UI, uses the stable qBittorrent prefix and HTTP User-Agent. The random suffix after `-qB5230-` is expected BitTorrent peer-id behavior.

## Private torrent metainfo regression

- Imported a private `.torrent` while emulator bandwidth was forced to zero, so the test did not contact its Tracker.
- Display name and all three files were loaded from metainfo.
- The private HTTPS Tracker was present in `TorrentHandle.trackers()`; its passkey was redacted from QA output.
- This covers the full-metainfo load path used by version 0.1.1. Loading only `TorrentInfo` is intentionally avoided because it drops top-level Tracker fields with this libtorrent binding.

## Private HTTPS Tracker end-to-end regression

- Direct failure details are now surfaced from libtorrent Tracker alerts instead of being hidden behind zero Seed/Peer counts.
- The Android OpenSSL build reproduced `BIO routines` initialization failure when `validate_https_trackers` was enabled.
- With the documented Android compatibility setting and SOCKS5 enabled, the private Tracker replied with 307 peers.
- The session connected to 3 seeds / 33 peers and transferred payload at approximately 3.6 MiB/s before the QA task was paused.
- Tracker URL and passkey are excluded from this report.
