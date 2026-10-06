package io.github.panuwattegif.readyproof

import io.github.panuwattegif.readyproof.core.Json
import io.github.panuwattegif.readyproof.core.Shop
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.test.*

class DriveApiTest {
    private class Response(url: String, val code: Int, val body: String = "{}", val failWrite: Boolean = false) : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getOutputStream(): OutputStream {
            if (failWrite) throw IOException("simulated network loss")
            return sent
        }
    }
    private fun entry(file: File) = UploadEntry("a".repeat(64), Shop.KAPRAO.id, "kaprao_GF-1_HISTORY_2026-10-07.jpg",
        "image/jpeg", MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, "fixed-id")
    private fun verified(e: UploadEntry, folder: String = Shop.KAPRAO.folderId, md5: String = e.md5) = Json.write(mapOf(
        "id" to e.remoteId, "parents" to listOf(folder), "trashed" to false, "md5Checksum" to md5,
        "appProperties" to mapOf("readyproofKey" to e.key, "shopId" to e.shopId)))

    @Test fun retryAfterLostResponseReusesIdAndVerifiesTheExistingFile() {
        val file = File.createTempFile("proof", ".jpg").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        try {
            val e = entry(file)
            val calls = mutableListOf<Response>()
            val api = DriveApi("test-token") { url ->
                Response(url, if (url.contains("/upload/")) 409 else 200, verified(e)).also { calls += it }
            }
            api.upload(e, file, Shop.KAPRAO.folderId)
            assertEquals(2, calls.size)
            assertTrue(calls.first().sent.toString("UTF-8").contains("\"id\":\"fixed-id\""))
            assertTrue(calls.first().sent.toString("UTF-8").contains(Shop.KAPRAO.folderId))
            assertFalse(calls.first().sent.toString("UTF-8").contains(Shop.DAUGHTER.folderId))
            assertTrue(calls.last().url.path.endsWith("/fixed-id"))
            assertEquals(byteArrayOf(1,2,3,4).toList(), file.readBytes().toList())
        } finally { file.delete() }
    }
    @Test fun wrongShopIsRefusedBeforeAnyNetworkRequest() {
        val file = File.createTempFile("proof", ".jpg").apply { writeText("image") }
        try {
            val api = DriveApi("test-token") { error("Must not call network") }
            assertFailsWith<IllegalArgumentException> { api.upload(entry(file), file, Shop.DAUGHTER.folderId) }
        } finally { file.delete() }
    }
    @Test fun duplicateMustMatchContentAndParentBeforeBeingAccepted() {
        val file = File.createTempFile("proof", ".jpg").apply { writeText("image") }
        try {
            val e = entry(file)
            val api = DriveApi("test-token") { url -> Response(url, if (url.contains("/upload/")) 409 else 200,
                verified(e, folder = Shop.DAUGHTER.folderId)) }
            assertFailsWith<IllegalArgumentException> { api.upload(e, file, Shop.KAPRAO.folderId) }
        } finally { file.delete() }
    }
    @Test fun networkOrAuthFailureKeepsTheLocalEvidenceAndDoesNotBecomeSuccess() {
        val file = File.createTempFile("proof", ".jpg").apply { writeText("image") }
        try {
            val e = entry(file)
            val broken = DriveApi("test-token") { url -> Response(url, 200, failWrite = true) }
            assertFailsWith<IOException> { broken.upload(e, file, Shop.KAPRAO.folderId) }
            val unauthorized = DriveApi("test-token") { url -> Response(url, 401) }
            assertEquals(401, assertFailsWith<DriveApi.HttpError> { unauthorized.upload(e, file, Shop.KAPRAO.folderId) }.code)
            assertEquals("image", file.readText())
            assertEquals("PENDING", e.state)
        } finally { file.delete() }
    }
}
