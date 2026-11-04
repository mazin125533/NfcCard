package io.github.mazin125533.nfccard

import android.nfc.NdefMessage
import android.nfc.cardemulation.HostApduService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NFC リーダー（相手のスマホ）が Type 4 タグを読むときと同じ APDU を順に送り、
 * 返ってくる NDEF が共有した URL になっているかを確かめる。
 */
@RunWith(AndroidJUnit4::class)
class NdefHostApduServiceTest {

    private lateinit var service: NdefHostApduService

    @Before
    fun setUp() {
        // HostApduService はコンストラクタで Handler を作るのでメインスレッドで生成する
        InstrumentationRegistry.getInstrumentation().runOnMainSync { service = NdefHostApduService() }
        SharedUrl.active = true
    }

    @After
    fun tearDown() {
        SharedUrl.active = false
    }

    @Test
    fun readerGetsSharedUrl() {
        val url = "https://example.com/path?q=1"
        assertTrue(SharedUrl.set(url))
        assertEquals(url, readTag(maxRead = 0xFF))
    }

    @Test
    fun longUrlIsReadInChunks() {
        // MLe より長い NDEF を、リーダーが小分けに READ BINARY しても正しく読める
        val url = "https://example.com/" + "a".repeat(600)
        assertTrue(SharedUrl.set(url))
        assertEquals(url, readTag(maxRead = 0x3B))
    }

    @Test
    fun sentIsNotifiedOncePerTap() {
        assertTrue(SharedUrl.set("https://example.com/"))
        var count = 0
        SharedUrl.onSent = { count++ }
        try {
            readTag(maxRead = 0xFF)
            readTag(maxRead = 0xFF)              // 同じタップ中に読み直されても1回だけ
            assertEquals(1, count)

            service.onDeactivated(HostApduService.DEACTIVATION_LINK_LOSS)   // 離して
            readTag(maxRead = 0xFF)              // もう一度かざす
            assertEquals(2, count)
        } finally {
            SharedUrl.onSent = null
        }
    }

    @Test
    fun noResponseWhenScreenClosed() {
        assertTrue(SharedUrl.set("https://example.com/"))
        SharedUrl.active = false
        assertSw(send("00A4040007D276000085010100"), "6A82")
    }

    @Test
    fun rejectsNonHttpUrl() {
        assertFalse(SharedUrl.set("javascript:alert(1)"))
        assertFalse(SharedUrl.set("not a url"))
    }

    @Test
    fun normalizesTypedUrl() {
        assertEquals("https://example.com/me", SharedUrl.normalize("  example.com/me "))
        assertEquals("http://example.com/", SharedUrl.normalize("http://example.com/"))
        assertEquals(null, SharedUrl.normalize("javascript:alert(1)"))
        assertEquals(null, SharedUrl.normalize("not a url"))
        assertEquals(null, SharedUrl.normalize(""))
    }

    @Test
    fun noResponseWhenNoCard() {
        assertTrue(SharedUrl.set("https://example.com/"))
        SharedUrl.clear()
        assertSw(send("00A4040007D276000085010100"), "6A82")
    }

    @Test
    fun capabilityContainerIsValid() {
        assertTrue(SharedUrl.set("https://example.com/"))
        assertSw(send("00A4040007D276000085010100"), "9000")
        assertSw(send("00A4000C02E103"), "9000")
        val cc = dataOf(send("00B000000F"))
        assertEquals(15, cc.size)
        assertEquals(0x20, cc[2].toInt() and 0xFF)                 // マッピング 2.0
        assertArrayEquals(hex("0406E104"), cc.copyOfRange(7, 11))  // NDEF File Control TLV
        assertEquals(0x00, cc[13].toInt() and 0xFF)                // 読み取り可
        assertEquals(0xFF, cc[14].toInt() and 0xFF)                // 書き込み不可
    }

    /** リーダー側の手順を再現し、読めた URL を返す */
    private fun readTag(maxRead: Int): String {
        assertSw(send("00A4040007D276000085010100"), "9000")   // SELECT NDEF Tag Application
        assertSw(send("00A4000C02E103"), "9000")               // SELECT CC
        assertSw(send("00B000000F"), "9000")                   // READ CC
        assertSw(send("00A4000C02E104"), "9000")               // SELECT NDEF file
        val nlenBytes = dataOf(send("00B0000002"))             // READ NLEN
        val nlen = ((nlenBytes[0].toInt() and 0xFF) shl 8) or (nlenBytes[1].toInt() and 0xFF)

        val body = java.io.ByteArrayOutputStream()
        var offset = 2
        while (body.size() < nlen) {
            val le = minOf(maxRead, nlen - body.size())
            val apdu = byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), le.toByte())
            val chunk = dataOf(service.processCommandApdu(apdu, null))
            assertTrue("空の応答", chunk.isNotEmpty())
            body.write(chunk)
            offset += chunk.size
        }
        val message = NdefMessage(body.toByteArray())
        return message.records.single().toUri().toString()
    }

    private fun send(hexApdu: String) = service.processCommandApdu(hex(hexApdu), null)

    private fun dataOf(response: ByteArray): ByteArray {
        assertSw(response, "9000")
        return response.copyOfRange(0, response.size - 2)
    }

    private fun assertSw(response: ByteArray, sw: String) =
        assertArrayEquals(hex(sw), response.copyOfRange(response.size - 2, response.size))

    private fun hex(s: String) =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
