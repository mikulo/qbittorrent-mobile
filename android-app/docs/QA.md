# Build and runtime verification

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
