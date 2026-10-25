package com.sidescreen.app

object AuthHandshake {
    private val REQ_MAGIC = byteArrayOf(0x53, 0x53, 0x57, 0x41) // "SSWA"
    private val RES_MAGIC = byteArrayOf(0x53, 0x53, 0x57, 0x52) // "SSWR"

    // Code pairing (issue #35): request "SSPC" mirrors the SSWA layout
    // ([magic 4][code field 32][name_len 1][name N], 37+N bytes total) so an
    // OLD Mac host reads its full fixed prefix, sees the unknown magic, and
    // answers SSWR/invalidMagic instead of stalling on a short read.
    private val PAIR_REQ_MAGIC = byteArrayOf(0x53, 0x53, 0x50, 0x43) // "SSPC"
    private val PAIR_RES_MAGIC = byteArrayOf(0x53, 0x53, 0x50, 0x52) // "SSPR"
    const val PAIR_CODE_FIELD_LEN = 32
    const val PAIR_CODE_LEN = 8

    enum class ResponseStatus(val code: Byte) {
        OK(0x00),
        INVALID_TOKEN(0x01),
        INVALID_MAGIC(0x02),
        INVALID_NAME(0x03),
        ;

        companion object {
            fun forCode(code: Byte): ResponseStatus? = values().firstOrNull { it.code == code }
        }
    }

    /**
     * Build the wire format request:
     *   [magic 4][token 32][name_len 1][name N]
     */
    fun encodeRequest(
        token: ByteArray,
        deviceName: String,
    ): ByteArray {
        require(token.size == 32) { "token must be 32 bytes, got ${token.size}" }
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..64) { "deviceName UTF-8 length must be 1..64, got ${nameBytes.size}" }
        return REQ_MAGIC + token + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    /**
     * Parse the 5-byte response. Returns null if magic is wrong or buffer is malformed.
     */
    fun parseResponse(bytes: ByteArray): ResponseStatus? {
        if (bytes.size < 5) return null
        for (i in 0..3) if (bytes[i] != RES_MAGIC[i]) return null
        return ResponseStatus.forCode(bytes[4])
    }

    enum class PairingStatus(val code: Byte) {
        OK(0x00),
        INVALID_CODE(0x01),
        ;

        companion object {
            fun forCode(code: Byte): PairingStatus? = values().firstOrNull { it.code == code }
        }
    }

    /** Header of a pairing response: pairing status, or "old host" when the Mac answered SSWR. */
    sealed class PairingHeader {
        data class Pairing(val status: PairingStatus) : PairingHeader()

        /** The Mac replied with the legacy SSWR magic — it predates code pairing. */
        object LegacyHost : PairingHeader()
    }

    /**
     * Build the pairing request:
     *   [magic 4][code 8 ASCII digits + zero-pad 24][name_len 1][name N]
     * Separators the user typed ("4729-3185") are stripped here.
     */
    fun encodePairingRequest(
        code: String,
        deviceName: String,
    ): ByteArray {
        val digits = code.filter { it.isDigit() }
        require(digits.length == PAIR_CODE_LEN) { "code must be $PAIR_CODE_LEN digits, got ${digits.length}" }
        val codeField = ByteArray(PAIR_CODE_FIELD_LEN)
        digits.toByteArray(Charsets.US_ASCII).copyInto(codeField)
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..64) { "deviceName UTF-8 length must be 1..64, got ${nameBytes.size}" }
        return PAIR_REQ_MAGIC + codeField + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    /**
     * Parse the 5-byte pairing response header [magic 4][status 1].
     * When status is OK the caller must then read [token 32][mac_name_len 1][mac_name M].
     * Returns null on garbage; LegacyHost when an old Mac replied SSWR/invalidMagic.
     */
    fun parsePairingResponseHeader(bytes: ByteArray): PairingHeader? {
        if (bytes.size < 5) return null
        if ((0..3).all { bytes[it] == RES_MAGIC[it] }) return PairingHeader.LegacyHost
        for (i in 0..3) if (bytes[i] != PAIR_RES_MAGIC[i]) return null
        val status = PairingStatus.forCode(bytes[4]) ?: return null
        return PairingHeader.Pairing(status)
    }
}
