# Signing key (committed on purpose)

`readyproof.p12` (alias `readyproof`, password `readyproof`) signs every build of ReadyProof.

Android only lets a new APK install over an old one when both carry the same signature.
With one fixed key in the repo, any future build (GitHub Actions, another machine, another
session) installs straight over the phone's copy and keeps its settings, screenshots log and
the accessibility permission. A key generated per build would force uninstall + full setup
on every update.

This key protects nothing else: the app is side-loaded for one shop, has no store listing,
no server, no internet permission and no API credentials. Someone holding the key could only
sign an APK that the shop owner would still have to install by hand.

If the app is ever published on a store, create a new private key for that and keep it out
of the repository.

Certificate SHA-256 (needed if the package is registered in Android Developer Console):

```
A7:81:4C:9D:B9:12:B1:0C:0B:F4:03:6B:A5:3C:DA:8D:41:F3:D0:73:BC:AF:A7:43:33:E7:AA:2F:CA:AA:D8:3D
```
