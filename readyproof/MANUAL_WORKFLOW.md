# Manual evidence workflow

READY captures pending on-screen GF evidence immediately; no periodic repeat capture. Failed/partial screenshots release only unsaved targets for retry. A day change resets dedupe. READY GF can repeat within a day; indistinguishable re-used identifiers remain an Android UI limitation and must not be treated as proven instance matches.

No automatic tab supervisor, closing History schedule or Google Drive upload runs, including jobs retained from earlier versions. The user opens READY. At close, the user requests History sweep. Native scrolling is tried first; a bounded on-screen swipe fallback is permitted only for the explicitly requested History pass. Capture validation remains enabled. Android may reject screenshots/gestures or expose incomplete text; missing evidence and header-count discrepancies remain visible and valid subsets can always be shared.

Report contains Delayed instances only. READY+DELAY shows a pair; DELAY without READY shows the DELAY image alone. Share to the Android share sheet, choose Drive and the shop folder. Existing local records, photos and upload journal are retained; no deletion/migration occurs.

## Recover a known-good implementation without deleting app data

Android normally rejects installation of a lower versionCode over a higher one. Keep applicationId and the same signing key. In the ReadyProof Android workflow, dispatch with source_ref set to an existing verified release tag/commit. It rebuilds that implementation with a NEW, increasing workflow versionCode and the same key, so it is an update containing older behavior rather than an unsupported APK downgrade. Verify signature, unit/build tests and installation before offering it. Do not uninstall or clear app storage. Never replace or rotate the signing key to solve an install error.

Use immutable version-tag download URLs for delivery. Verify bytes and SHA256 against the release asset; a successful build alone does not prove installation on an OPPO device.
