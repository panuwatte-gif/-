package io.github.panuwattegif.readyproof.core

/**
 * Routing is immutable; neither filenames nor editable text can choose a Drive destination.
 * [sheetId]: the shop's own Google Sheet the app rewrites every night (owner, 3 Nov).
 */
enum class Shop(val id: String, val label: String, val folderId: String, val sheetId: String? = null) {
    KAPRAO("kaprao", "กะเพรา", "1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY", "10ts4sLot10eaENr0KNUFZ5GkOH_ZGEuHu72RJxC7qm0"),
    DAUGHTER("daughter", "ลูกสาวทำเอง", "1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq");

    companion object {
        fun fromId(id: String?): Shop? = entries.firstOrNull { it.id == id }
    }
}
