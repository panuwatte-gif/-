# ReadyProof Clean: OPPO History and Ready verification

This change touches only readyproof-clean. The original ReadyProof engine, APK, signing key, data and releases are preserved.

## Fixed from the user's actual evidence

The OPPO Android 14 dump exposes all four order tabs without a selected flag. History has the merged description "เสร็จสมบูรณ์ 77 ยกเลิก 0", GF-133 completed 19:32 and GF-700 completed 19:09 / Delayed 3 minutes. It must confirm History from terminal content, keep GF-133 out of Delayed and never call the two visible orders a complete 77-order day.

Native scrolling is attempted only within the order viewport. If it does not change card identities/positions, a bounded gesture overlaps pages. The service declares canPerformGestures. Only completed movement attempts contribute to stalled-list detection; screenshot callbacks and timer polls cannot terminate a pass.

A manually selected date must match the visible History header. Totals are read for yesterday as well as today. Missing or conflicting dates stop with an incomplete report. Finishing or failing a sweep leaves History open.

READY photos are saved only for pending order identities and valid visible cards. Ready content cannot emit Delayed. Saved subsets remain shareable during incomplete scans. The report displays only Delayed cases, with paired Ready/History photos or a lone History photo.

## Required real-device check (not claimed passed by CI)

1. On the shop's OPPO, disable the original ReadyProof accessibility service while Clean watches; two active automation services can race.
2. Keep the same orders on Ready: one valid photo per pending identity, no periodic duplicate saves.
3. Select yesterday in Grab and the same date in Clean. Start sweep; confirm the first two orders get images and the next viewport actually moves.
4. Confirm all History identities have photo coverage and the header total is satisfied before COMPLETE.
5. Scroll History manually after the sweep: it must remain there.
6. Verify each Delayed image contains its own GF and delay label; Ready-only photos are not Delayed.
7. Share the valid evidence even if an Android screenshot or movement fails.

CI checks the supplied OPPO tree and compiles/sign-verifies the APK. CI cannot prove physical gesture delivery on the shop's phone. The service uses accessibility metadata around capture, not pixel OCR; clipped or missing metadata can still require a retry. Reports must stay provisional until coverage is verified.

Ready deduplication is per confirmed stay: a reused GF is eligible again only after two complete top-to-bottom sweeps confirm its absence for at least 15 seconds. Partial viewports never clear proof. Existing day counters still count distinct GF codes; they do not prove distinct order instance coverage, especially if codes are reused. Restart seeds existing day proof conservatively.
