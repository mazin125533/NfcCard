package io.github.mazin125533.nfccard

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * NFC Forum Type 4 Tag（NDEF マッピング v2.0）を読み取り専用でエミュレートする。
 *
 * 読み取り側の流れ:
 *  1. SELECT NDEF Tag Application (AID D2760000850101)
 *  2. SELECT CC ファイル (E103) → READ BINARY
 *  3. SELECT NDEF ファイル (E104) → READ BINARY で NLEN → READ BINARY で本体
 */
class NdefHostApduService : HostApduService() {

    private enum class Selected { NONE, APP, CC, NDEF }

    private var selected = Selected.NONE
    private var ndefFile: ByteArray? = null
    /** 1回かざすごとに送信完了の通知は1度だけ（読み直す端末もあるため） */
    private var sentNotified = false

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        return when (ins) {
            INS_SELECT -> handleSelect(apdu, p1)
            INS_READ_BINARY -> handleReadBinary(apdu, p1, p2)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun handleSelect(apdu: ByteArray, p1: Int): ByteArray {
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)

        // アプリケーション選択（DF 名で選択）
        if (p1 == 0x04) {
            if (!data.contentEquals(NDEF_AID)) return SW_FILE_NOT_FOUND
            // 画面が閉じている / URL 未設定なら、タグが無いふりをする
            val file = SharedUrl.ndefFile
            if (!SharedUrl.active || file == null) {
                selected = Selected.NONE
                return SW_FILE_NOT_FOUND
            }
            ndefFile = file   // 読み取り中に URL が変わっても一貫した内容を返す
            selected = Selected.APP
            return SW_OK
        }

        // ファイル選択（ファイル ID で選択）
        if (p1 == 0x00 && selected != Selected.NONE && lc == 2) {
            selected = when {
                data.contentEquals(CC_FILE_ID) -> Selected.CC
                data.contentEquals(NDEF_FILE_ID) -> Selected.NDEF
                else -> return SW_FILE_NOT_FOUND
            }
            return SW_OK
        }
        return SW_FILE_NOT_FOUND
    }

    private fun handleReadBinary(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val file = when (selected) {
            Selected.CC -> CAPABILITY_CONTAINER
            Selected.NDEF -> ndefFile ?: return SW_FILE_NOT_FOUND
            else -> return SW_FILE_NOT_FOUND
        }
        val offset = (p1 shl 8) or p2
        if (offset > file.size) return SW_WRONG_P1P2
        // Le = 0 は 256 バイト
        val le = if (apdu.size >= 5) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        val end = minOf(file.size, offset + le)
        val chunk = file.copyOfRange(offset, end)

        if (selected == Selected.NDEF && end == file.size && offset > 0 && !sentNotified) {
            sentNotified = true
            SharedUrl.onSent?.invoke()   // 本体を最後まで読まれた
        }
        return chunk + SW_OK
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
        ndefFile = null
        sentNotified = false
    }

    companion object {
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0

        private val NDEF_AID = hex("D2760000850101")
        private val CC_FILE_ID = hex("E103")
        private val NDEF_FILE_ID = hex("E104")

        private val SW_OK = hex("9000")
        private val SW_WRONG_LENGTH = hex("6700")
        private val SW_FILE_NOT_FOUND = hex("6A82")
        private val SW_WRONG_P1P2 = hex("6B00")
        private val SW_INS_NOT_SUPPORTED = hex("6D00")

        /** Capability Container (15 バイト) */
        private val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F,                 // CCLEN
            0x20,                       // マッピングバージョン 2.0
            0x00, 0xFF.toByte(),        // MLe: 一度に読める最大バイト数
            0x00, 0xFF.toByte(),        // MLc: 一度に書ける最大バイト数
            0x04, 0x06,                 // NDEF File Control TLV
            0xE1.toByte(), 0x04,        // NDEF ファイル ID
            (SharedUrl.MAX_NDEF_FILE_SIZE shr 8).toByte(),
            SharedUrl.MAX_NDEF_FILE_SIZE.toByte(),
            0x00,                       // 読み取り: 許可
            0xFF.toByte(),              // 書き込み: 禁止（読み取り専用）
        )

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
