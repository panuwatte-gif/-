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

- ReadyProof is installed only on the dedicated proof phone. The shop accepts orders and marks food ready on its existing Sunmi/device without installing anything there. Screen events (plus a 10-second heartbeat) drive an `Engine` that keeps Grab on the Ready tab: it taps only tab-bar labels / the bottom "Orders" item (never a container holding an order number) and Back on a sub-page, never order actions or dialogs. Another app in front is replaced by Grab after `guardIdleMinutes`; a person touching Grab postpones it. The screen is kept on while Grab is visible until the day's summary is done.
- READY: every order listed in the Ready tab gets **one** verified screenshot per stay (`ReadyTracker`): waiting orders are not photographed again, a new order is photographed even when older ones are still listed, and a GF that leaves and comes back later is a new stay. Each new order is journalled (`READY_SEEN_PENDING_UNTIL_IMAGE`) before any screenshot.
- When the list is longer than the screen, a change in the list (or the `fullSweepMinutes` safety timer) triggers a sweep: top → bottom page by page, then it parks at the bottom where Grab appends new orders. Sideways pagers/tab strips are never scrolled.
- A photo counts only when the GF line and the status line are completely inside the list area and nothing covers their letters (other windows, Grab's floating button/bottom bar; letter positions are checked via accessibility character bounds). Cut-off orders are scrolled into view first (`SHOW_ON_SCREEN`, else a slow drag). The tree is read before and right after the bitmap; a target whose lines moved is discarded and retried. This is accessibility validation, not pixel OCR; human review still matters.
- Closing: at `closeTime` + `endBufferMinutes` (default **19:00 + 5 = 19:05**) the Ready list is swept and must be empty, then Preparing must be empty (no order card on two readings 1.5 s apart, nothing "loading"); otherwise it rechecks every `recheckMinutes` (default 5) while normal Ready capture continues, up to `endMaxWaitMinutes`. Then History is opened, scrolled to the top, Grab's own **Completed / Cancelled** totals and date are read from the header ("Today, 08 Oct 2026", Thai dates and Buddhist years supported), and the whole list is swept: every row is journalled and every Delayed row photographed (cut-off rows are brought into view first). A second, overlapping pass runs when fewer rows than Grab's totals were read; an incomplete result is re-read up to 3 times, `recheckMinutes` apart. The manual History button skips the queue checks and does not replace the evening run when used before closing.
- Verdicts: for each delayed order the first Ready shot of its stay decides — rider not there yet → **IN_TIME**; "arrived" / pink "please prepare this order" (`lateStatus`, editable) → **LATE**; no usable shot → **NO_EVIDENCE** (not proof of lateness). Grab % = delayed ÷ Grab's total; shop % = (delayed − IN_TIME) ÷ Grab's total.

## Reports and manual fallback

Each History pass atomically saves `files/reports/<shop>_summary-YYYY-MM-DD.txt` and a JSON manifest (schema 3) before offering revisions to Drive. They include observed total orders, Completed/Cancelled, Grab's header totals (`grabCompleted`/`grabCancelled`/`historyMatchesGrab`), Grab delayed count/%, per-case `verdict` and counts (`inTime`/`late`/`noEvidence`), actual delayed = delayed − IN_TIME and `shopPercent`, GF/finish times and missing READY/HISTORY/DELAY/UNKNOWN lists. No screenshot count is used as order count.

The manifest links observations and actual photo record IDs. Each changed report gets a new content hash, preventing an older retry from overwriting a newer report. Consumers should use the newest revision for that shop/day and inspect completeness. Incomplete/unverified-day percentages are **PROVISIONAL**; whole-day Total Orders is UNKNOWN. Actual is an evidence-based calculation, not confirmation that Grab adjusted its backend.

Explicit History date labels supported: a full day + month + year (English or Thai, e.g. "Today, 08 Oct 2026", "ส. 3 ต.ค. 2569"), Today/วันนี้, Yesterday/เมื่อวาน/เมื่อวานนี้, ISO dates. Unsupported date headers or clock-only rows remain UNKNOWN. Empty lists alone cannot certify a zero-order day. All figures must be checked against real screenshots before downstream Sheet entry.

Manual report actions: matched READY+DELAY sets, unmatched DELAY alone, all actual photos for the day, summary text file, copy summary and CSV. Automatic upload sends each physical valid photo once, including READY-only and all HISTORY photos; the manifest maps shared photos to all covered instances. Original photos are in Pictures/ReadyProof. No automatic cleanup runs: monitor free space, including private upload copies.

## Google setup required for real auto-upload

1. In the owner's Google Cloud project enable **Google Drive API** and configure OAuth branding/consent.
2. Register an **Android OAuth client** for `io.github.panuwattegif.readyproof` with the actual release certificate SHA-1. Use `keytool -list -v -storetype PKCS12 -keystore readyproof/keystore/readyproof.p12 -alias readyproof` (existing key/password documented in keystore/README.md). No Google secret is embedded in the APK.
3. Add shop accounts as test users if consent is in testing. Google testing restrictions, Workspace policies, OAuth verification, unavailable Play services, grant expiration/revocation can interrupt access.
4. The app requests restricted `https://www.googleapis.com/auth/drive` because these fixed existing folders were not created/Picker-authorized by the app. `drive.file` alone cannot grant arbitrary existing-folder access by ID. Full Drive needs informed consent and may need Google verification. Code only accesses the two allowed parents and its own uploaded files.
5. On each phone select the shop, choose an account with permission to add files to that folder, and grant consent. Background work authorizes that exact account. Tokens remain in Google's cache; the app does not persist them or show consent UI in the background. Resolve AUTH_REQUIRED from the Drive page.

**Signing limitation:** the existing signing key is public in this repository. Retaining it enables installation over existing APKs, but it is unsuitable for broadly distributed production software requesting Drive access: another party could sign an impersonating APK. Private-key migration and OAuth re-registration need a planned update path. No silent signing-key change is included. CI does not configure Google Cloud or authorize a live account.

## Upload recovery

New photos stay staged locally during the day. Only a report committed after the closing time releases a shop/day batch. Daytime manual History tests save local reports without releasing uploads. Each batch includes valid photos, summary and source manifest; missing proof never prevents uploading the valid subset. The `UPLOAD_DONE-YYYY-MM-DD` marker is uploaded last, only after every staged member has passed remote verification. It lists exact remote filenames, hashes and photo record IDs, plus local photo-read failures. UPLOAD_DONE means the listed subset was uploaded, not that every order has proof. Consumers must inspect manifest completeness and real images. An unchanged batch is not sent again; later closing passes that add evidence can release corrections without uploading an already-uploaded physical file again. Consumers should reprocess only new revisions.

Atomic batch-request snapshots recover process death before staging. Outbox entries from older versions are not released merely because auto-upload is enabled; only membership in a newly committed closing batch authorizes them. Failed released batches can retry the next day, while that day's new captures stay local. No remote cleanup or hidden API is used.

Private immutable payloads plus AtomicFile journals survive restarts. WorkManager requires connected network and retries with exponential backoff; a 15-minute recovery worker is also scheduled. Android may defer execution. Startup reconciles the retained capture journal, including captures made while auto-upload was disabled.

Generated Drive IDs are persisted before upload. Retrying after a lost response reuses the same ID; a 409 is accepted only after verifying the ID, fixed parent, shop metadata, content key and MD5. UPLOADED is set only after remote content verification. A bad file does not prevent later valid files being attempted. Originals and queued payloads are not automatically deleted. Network/auth/upload errors never propagate into the capture pipeline.

## Validation and practical limits

CI: `:core:test :app:testDebugUnitTest`, then `:app:assembleRelease`. Synthetic fixtures cover partial multi-GF proof, moved/mismatched cards, repeated GF boundaries, shop/legacy isolation, UNKNOWN date/clock, missing proof, totals vs screenshots, actual calculations, upload timeouts/401, routing and verified duplicate retries.

Real device verification remains necessary for Grab layouts/scrolling, correct selected date, photo contents, Google consent/folder access, offline/reconnect/restart and manual sharing. Screen lock, service termination, sensitive/secure windows, accessibility omissions, layout changes and disappearing orders prevent any 100% guarantee. Unit/build success does not prove live Grab/Google end-to-end success.

Official references: [Android authorization](https://developer.android.com/identity/authorization), [Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Drive uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
