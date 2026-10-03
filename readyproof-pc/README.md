# ReadyProof PC

Windows collector for Grab Merchant Portal, designed for exactly two independent shop sessions:

- กะเพรา
- ลูกสาวทำเอง

It uses the visible Merchant Portal DOM and screenshots only. It does not call Grab private APIs.

## What it records

Per order instance (primary key = Grab long order ID):

- GF number
- Ready evidence screenshot
- full order-detail raw text
- customer name as displayed by Grab
- customer note
- menu/table rows, quantities, prices and add-ons as displayed
- total
- History status
- delayed minutes when the page exposes Delayed/ล่าช้า
- Delay screenshot
- READY ↔ HISTORY/DELAY matching by long order ID

Delayed orders without READY evidence are retained in the database/report and are not discarded.

## Storage

Local data is written first to:

`Documents\\ReadyProofPC\\<ร้าน>\\YYYY-MM-DD\\`

with READY, DETAIL, DELAY and DATA folders plus one separate `orders.db` per shop.

The UI can optionally point each shop at its Google Drive for desktop local mirror of the **existing** `ออเดอร์รอตรวจ` folder. New evidence/JSON files are copied directly into that folder. The program does not create a new Drive hierarchy.

Configured Drive destinations:

- กะเพรา: `1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY`
- ลูกสาวทำเอง: `1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq`

## First run

1. Start `ReadyProofPC.exe`.
2. Two separate Chrome profiles/windows open side-by-side.
3. Log into the correct Grab Merchant account/store in each window once.
4. Keep the windows open. Each profile is persistent.
5. In ReadyProof PC, set the Google Drive for desktop sync path for each shop if desired.
6. During the day the program repeatedly sweeps `/ready`, captures every new order and opens its detail in a separate tab.
7. At closing, press **สแกน History ตอนนี้** for each shop. History is swept to the bottom, counted, details inspected, and delayed cases matched to READY by long order ID.

History is marked complete only when the number of distinct scanned long order IDs equals `Completed + Cancelled` from Grab's History summary. If not equal, the UI reports that History is incomplete.

## Safety / limitations

- The program never enters Grab credentials; login is manual in the dedicated Chrome profiles.
- It verifies the visible shop name marker before collecting data so the two shops do not mix.
- Grab can change Merchant Portal markup. The collector uses text/DOM heuristics and fails closed on evidence capture where possible.
- A real delayed order on the PC portal is not yet guaranteed to expose the same Delayed label as the mobile app. The scanner checks both History rows and order detail. A real delayed PC example should be used to harden the selector if Grab renders it differently.
