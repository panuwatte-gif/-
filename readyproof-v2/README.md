# ReadyProof V2

Fresh implementation under `readyproof-v2/`. It uses the same application ID and original signing certificate as ReadyProof. Its separate `readyproof_v2.db` and image directory leave existing app data intact. No Grab API, OAuth, scheduled History navigation, or automatic upload.

## Operation

Enable Accessibility for ReadyProof. Leave GrabMerchant on **Ready** on the dedicated OPPO. The monitor saves one full-screen screenshot when a visible GF lacks evidence. An unsuccessful capture stays Pending. For History, select the order date in ReadyProof, select the same date in GrabMerchant and scroll Grab's list to the top so the Completed/Cancelled header is visible before tapping Start. The sweep waits for that header screenshot and scrolls one step only after the current screenshot has been persisted. Tap Stop to resume Ready monitoring. Use Share to choose Save to Google Drive; each valid case can be shared even if others are missing.

The History total is read from the visible header. The report says UNKNOWN until Total, Completed and Cancelled are found, reconcile, and each row has an image. Only Delayed inside the same card creates a case. Repeated GF instances without a unique temporal match are UNKNOWN.

## Build and rollback

The CI workflow tests and builds two APKs in one artifact: V2 and the user's chosen 1.0.86 source rebuilt with a strictly higher versionCode using the original signing key. The rollback installs over V2, retaining app data. Do **not** install the old 1.0.128 APK over V2, because Android rejects a lower versionCode.

Local logic tests: `cd readyproof-v2 && ../readyproof/gradlew :core:test`. APK build needs Android SDK and the original keystore in `readyproof/keystore/`.

Build and emulator installation checks are gates, but live Grab UI behavior, OPPO battery management, screenshot permission, and the actual ordering of 77 cards must still be tested on the OPPO A5i. Do not describe CI success as device validation.
