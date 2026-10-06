package io.github.panuwattegif.readyproof.core

/** Routing is immutable; neither filenames nor editable text can choose a Drive destination. */
enum class Shop(val id: String, val label: String, val folderId: String) {
    KAPRAO("kaprao", "กะเพรา", "1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY"),
    DAUGHTER("daughter", "ลูกสาวทำเอง", "1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq");

    companion object {
        fun fromId(id: String?): Shop? = entries.firstOrNull { it.id == id }
    }
}
