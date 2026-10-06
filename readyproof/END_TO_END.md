# ReadyProof end-to-end

Capture and local storage run first. Google Drive is an optional background sidecar, never a prerequisite for capture, History, reports or manual sharing. No hidden Grab API is used.

## Shop setup

Bind each dedicated phone once from **ร้าน / Google Drive / คิวส่งไฟล์** to the shop open in Grab. Binding survives config reset. The app cannot independently verify the Grab account: confirm the correct shop yourself. Every capture job and pending upload retains its own shop identity.

| Shop | Fixed existing upload folder |
| --- | --- |
| กะเพรา (`kaprao`) | `1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY` |
| ลูกสาวทำเอง (`daughter`) | `1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq` |

Uploads go directly into these **ออเดอร์รอตรวจ** folders. No folder/spreadsheet creation, permission changes or permanent deletion occur. Legacy/unconfigured records remain in a separately viewable, manually shareable report; binding a shop never silently assigns or auto-uploads old records.

## Proof coverage

- READY observations are logged before screenshot requests. One bitmap can prove several GFs. Validation retains each stable visible target independently; missing targets remain eligible for retry. A partial bitmap is not discarded merely because another GF dropped out.
- Foreground Grab, the GF, and the relevant status/clock must be visible. DELAY requires GF + Delayed + terminal clock on the same card. READY needs the selected Ready tab or a positive Ready status. Unknown tab selection alone is insufficient.
- Capture pauses auto-scrolling/poll scans. Card text and bounds are validated on both sides of the bitmap callback. This is accessibility validation, not pixel OCR; human review still matters.
- Recapture once per minute helps with reused GF numbers. Previous terminal times partition evidence windows. READY after completion or before a previous terminal cannot prove a later instance. Missing clocks stay UNKNOWN. Same-GF/same-minute collisions may remain unresolved.
- Every observed terminal row is journalled before any screenshot or reposition. Repeated viewport failures are recorded as missing; scanning proceeds and later sweeps retry. Existing valid files are always eligible for sharing/upload.
- History goes back to the top, sweeps forward, counts Completed + Cancelled instances and captures their rows, including Delayed without Ready. It returns to Ready. Safety-cap/stalled scans remain incomplete.
- Retained close schedule: weekdays 19:15, Saturday 16:15, Sunday manual. Incomplete sweeps retry after 5 minutes; completed sweeps revisit after 15 minutes for late rows. These require a running service and accessible Grab, not just an Android alarm.

## Reports and manual fallback

Each History pass atomically saves `files/reports/<shop>_summary-YYYY-MM-DD.txt` and a JSON manifest before offering revisions to Drive. They include observed total orders, Completed/Cancelled, Grab delayed count/%, Ready evidence count, Actual delayed `max(Grab - ReadyEvidence, 0)`/%, GF/finish times and missing READY/HISTORY/DELAY/UNKNOWN lists. No screenshot count is used as order count.

The manifest links observations and actual photo record IDs. Each changed report gets a new content hash, preventing an older retry from overwriting a newer report. Consumers should use the newest revision for that shop/day and inspect completeness. Incomplete/unverified-day percentages are **PROVISIONAL**; whole-day Total Orders is UNKNOWN. Actual is an evidence-based calculation, not confirmation that Grab adjusted its backend.

Explicit History date labels supported: Today/วันนี้, Yesterday/เมื่อวาน/เมื่อวานนี้, ISO dates. Unsupported date headers or clock-only rows remain UNKNOWN. Empty lists alone cannot certify a zero-order day. All figures must be checked against real screenshots before downstream Sheet entry.

Manual report actions: matched READY+DELAY sets, unmatched DELAY alone, all actual photos for the day, summary text file, copy summary and CSV. Automatic upload sends each physical valid photo once, including READY-only and all HISTORY photos; the manifest maps shared photos to all covered instances. Original photos are in Pictures/ReadyProof. No automatic cleanup runs: monitor free space, including private upload copies.

## Google setup required for real auto-upload

1. In the owner's Google Cloud project enable **Google Drive API** and configure OAuth branding/consent.
2. Register an **Android OAuth client** for `io.github.panuwattegif.readyproof` with the actual release certificate SHA-1. Use `keytool -list -v -storetype PKCS12 -keystore readyproof/keystore/readyproof.p12 -alias readyproof` (existing key/password documented in keystore/README.md). No Google secret is embedded in the APK.
3. Add shop accounts as test users if consent is in testing. Google testing restrictions, Workspace policies, OAuth verification, unavailable Play services, grant expiration/revocation can interrupt access.
4. The app requests restricted `https://www.googleapis.com/auth/drive` because these fixed existing folders were not created/Picker-authorized by the app. `drive.file` alone cannot grant arbitrary existing-folder access by ID. Full Drive needs informed consent and may need Google verification. Code only accesses the two allowed parents and its own uploaded files.
5. On each phone select the shop, choose an account with permission to add files to that folder, and grant consent. Background work authorizes that exact account. Tokens remain in Google's cache; the app does not persist them or show consent UI in the background. Resolve AUTH_REQUIRED from the Drive page.

**Signing limitation:** the existing signing key is public in this repository. Retaining it enables installation over existing APKs, but it is unsuitable for broadly distributed production software requesting Drive access: another party could sign an impersonating APK. Private-key migration and OAuth re-registration need a planned update path. No silent signing-key change is included. CI does not configure Google Cloud or authorize a live account.

## Upload recovery

Private immutable payloads plus AtomicFile journals survive restarts. WorkManager requires connected network and retries with exponential backoff; a 15-minute recovery worker is also scheduled. Android may defer execution. Startup reconciles the retained capture journal, including captures made while auto-upload was disabled.

Generated Drive IDs are persisted before upload. Retrying after a lost response reuses the same ID; a 409 is accepted only after verifying the ID, fixed parent, shop metadata, content key and MD5. UPLOADED is set only after remote content verification. A bad file does not prevent later valid files being attempted. Originals and queued payloads are not automatically deleted. Network/auth/upload errors never propagate into the capture pipeline.

## Validation and practical limits

CI: `:core:test :app:testDebugUnitTest`, then `:app:assembleRelease`. Synthetic fixtures cover partial multi-GF proof, moved/mismatched cards, repeated GF boundaries, shop/legacy isolation, UNKNOWN date/clock, missing proof, totals vs screenshots, actual calculations, upload timeouts/401, routing and verified duplicate retries.

Real device verification remains necessary for Grab layouts/scrolling, correct selected date, photo contents, Google consent/folder access, offline/reconnect/restart and manual sharing. Screen lock, service termination, sensitive/secure windows, accessibility omissions, layout changes and disappearing orders prevent any 100% guarantee. Unit/build success does not prove live Grab/Google end-to-end success.

Official references: [Android authorization](https://developer.android.com/identity/authorization), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Drive uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
