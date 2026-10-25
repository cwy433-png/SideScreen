package com.sidescreen.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthHandshakeTest {
    @Test
    fun encodesGoldenBytes() {
        val token = ByteArray(32) { it.toByte() }
        val bytes = AuthHandshake.encodeRequest(token, "iPad Air")
        val expected =
            byteArrayOf(0x53, 0x53, 0x57, 0x41) +
                ByteArray(32) { it.toByte() } +
                byteArrayOf(8) +
                "iPad Air".toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun rejectsNameLongerThan64() {
        val longName = "x".repeat(65)
        try {
            AuthHandshake.encodeRequest(ByteArray(32), longName)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // OK
        }
    }

    @Test
    fun rejectsTokenWrongSize() {
        try {
            AuthHandshake.encodeRequest(ByteArray(31), "x")
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // OK
        }
    }

    @Test
    fun parseOKResponse() {
        val r = AuthHandshake.parseResponse(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x00))
        assertEquals(AuthHandshake.ResponseStatus.OK, r)
    }

    @Test
    fun parseInvalidTokenResponse() {
        val r = AuthHandshake.parseResponse(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x01))
        assertEquals(AuthHandshake.ResponseStatus.INVALID_TOKEN, r)
    }

    @Test
    fun parseInvalidMagicResponseReturnsNull() {
        val r = AuthHandshake.parseResponse(byteArrayOf(0x58, 0x58, 0x58, 0x58, 0x00))
        assertNull(r)
    }

    // ---- code pairing (issue #35) ----

    @Test
    fun pairingRequestGoldenBytes() {
        val bytes = AuthHandshake.encodePairingRequest("4729-3185", "Boox Tab")
        val codeField = ByteArray(32)
        "47293185".toByteArray().copyInto(codeField)
        val expected =
            byteArrayOf(0x53, 0x53, 0x50, 0x43) +
                codeField +
                byteArrayOf(8) +
                "Boox Tab".toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun pairingRequestIsSswaPrefixLength() {
        // Same 37-byte fixed prefix as SSWA so an old host answers
        // invalidMagic instead of stalling on a short read.
        val bytes = AuthHandshake.encodePairingRequest("12345678", "x")
        assertEquals(37 + 1, bytes.size)
    }

    @Test
    fun pairingRequestRejectsWrongCodeLength() {
        try {
            AuthHandshake.encodePairingRequest("1234", "x")
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // OK
        }
    }

    @Test
    fun parsePairingOkHeader() {
        val h = AuthHandshake.parsePairingResponseHeader(byteArrayOf(0x53, 0x53, 0x50, 0x52, 0x00))
        assertEquals(
            AuthHandshake.PairingHeader.Pairing(AuthHandshake.PairingStatus.OK),
            h,
        )
    }

    @Test
    fun parsePairingRejectedHeader() {
        val h = AuthHandshake.parsePairingResponseHeader(byteArrayOf(0x53, 0x53, 0x50, 0x52, 0x01))
        assertEquals(
            AuthHandshake.PairingHeader.Pairing(AuthHandshake.PairingStatus.INVALID_CODE),
            h,
        )
    }

    @Test
    fun parsePairingLegacyHostHeader() {
        // Old host answers SSWR/invalidMagic to the unknown SSPC magic.
        val h = AuthHandshake.parsePairingResponseHeader(byteArrayOf(0x53, 0x53, 0x57, 0x52, 0x02))
        assertEquals(AuthHandshake.PairingHeader.LegacyHost, h)
    }

    @Test
    fun parsePairingGarbageReturnsNull() {
        assertNull(AuthHandshake.parsePairingResponseHeader(byteArrayOf(0x58, 0x58, 0x58, 0x58, 0x00)))
    }
}
