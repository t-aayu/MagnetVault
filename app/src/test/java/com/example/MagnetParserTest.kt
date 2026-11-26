package com.example

import com.example.domain.magnet.CanonicalIdentityKey
import com.example.domain.magnet.MagnetParseResult
import com.example.domain.magnet.MagnetParser
import com.example.domain.magnet.TorrentHashFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetParserTest {

    // SHA-1 bytes for "0123456789abcdef0123456789abcdef01234567":
    // 0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef,0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef,0x01,0x23,0x45,0x67
    // RFC 4648 Base32 encoding of those exact 20 bytes: AEruKZ4JVPG66AJDIVTYTK6N54ASGRLH
    private val sampleHexLower = "0123456789abcdef0123456789abcdef01234567"
    private val sampleHexUpper = sampleHexLower.uppercase()
    private val sampleBase32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"
    private val sampleBtmh = "1220" + "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `btih hex upper lower and base32 normalize to identical canonical lowercase hex`() {
        assertEquals(sampleHexLower, MagnetParser.normalizeBtihToHex(sampleHexLower))
        assertEquals(sampleHexLower, MagnetParser.normalizeBtihToHex(sampleHexUpper))
        assertEquals(sampleHexLower, MagnetParser.normalizeBtihToHex(sampleBase32))
        assertEquals(sampleHexLower, MagnetParser.normalizeBtihToHex(sampleBase32.lowercase()))
        assertEquals(sampleHexLower, MagnetParser.normalizeBtihToHex("$sampleBase32===="))

        val fromHex = MagnetParser.parseMagnetInput("magnet:?xt=urn:btih:$sampleHexUpper&dn=Ubuntu+ISO")
        val fromBase32 = MagnetParser.parseMagnetInput("magnet:?xt=urn:btih:$sampleBase32&dn=Ubuntu%20ISO")

        assertTrue(fromHex is MagnetParseResult.Valid)
        assertTrue(fromBase32 is MagnetParseResult.Valid)
        fromHex as MagnetParseResult.Valid
        fromBase32 as MagnetParseResult.Valid

        assertEquals(fromHex.primaryIdentity, fromBase32.primaryIdentity)
        assertEquals("btih:$sampleHexLower", fromHex.primaryIdentity.storageKey)
        assertEquals("Ubuntu ISO", fromHex.displayName)
        assertEquals("Ubuntu ISO", fromBase32.displayName)
    }

    @Test
    fun `btmh v2 multihash stays distinct from btih v1 identity family`() {
        val v2Result = MagnetParser.parseMagnetInput("magnet:?xt=urn:btmh:${sampleBtmh.uppercase()}&dn=Debian+v2&xl=1048576")
        assertTrue(v2Result is MagnetParseResult.Valid)
        v2Result as MagnetParseResult.Valid

        assertEquals(TorrentHashFamily.BTMH, v2Result.primaryIdentity.family)
        assertEquals(sampleBtmh, v2Result.primaryIdentity.canonicalHex)
        assertEquals("btmh:$sampleBtmh", v2Result.primaryIdentity.storageKey)
        assertEquals(1048576L, v2Result.exactLengthBytes)
        assertNotEquals(
            CanonicalIdentityKey.VerifiedHash(TorrentHashFamily.BTIH, sampleHexLower).storageKey,
            v2Result.primaryIdentity.storageKey
        )
    }

    @Test
    fun `hybrid magnet preserves both btih and btmh identities and filters unsafe trackers`() {
        val raw = "magnet:?xt=urn:btmh:$sampleBtmh&xt.2=urn:btih:$sampleBase32" +
            "&dn=Hybrid+Release+1080p" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=javascript%3Aalert(1)" +
            "&tr=https%3A%2F%2Ftracker.example.org%2Fannounce"

        val parsed = MagnetParser.parseMagnetInput(raw)
        assertTrue(parsed is MagnetParseResult.Valid)
        parsed as MagnetParseResult.Valid

        assertEquals(2, parsed.allIdentities.size)
        assertEquals(TorrentHashFamily.BTIH, parsed.primaryIdentity.family)
        assertEquals(sampleHexLower, parsed.primaryIdentity.canonicalHex)
        assertEquals(TorrentHashFamily.BTMH, parsed.allIdentities[1].family)
        assertEquals(sampleBtmh, parsed.allIdentities[1].canonicalHex)
        assertEquals(
            listOf(
                "udp://tracker.opentrackr.org:1337/announce",
                "https://tracker.example.org/announce"
            ),
            parsed.trackers
        )
    }

    @Test
    fun `empty oversized malformed or non-magnet inputs are rejected cleanly`() {
        assertTrue(MagnetParser.parseMagnetInput("") is MagnetParseResult.Empty)
        assertTrue(MagnetParser.parseMagnetInput("   \t ") is MagnetParseResult.Empty)

        assertTrue(MagnetParser.parseMagnetInput("https://example.com/file.torrent") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?dn=NoHashHere") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?xt=urn:btih:12345") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?xt=urn:btih:01890189018901890189018901890189") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?xt=urn:btmh:deadbeef") is MagnetParseResult.Invalid)
        assertTrue(MagnetParser.parseMagnetInput("magnet:?xt=urn:btih:$sampleHexLower\n&dn=BadCtrl") is MagnetParseResult.Invalid)

        val oversized = "magnet:?xt=urn:btih:$sampleHexLower&dn=" + "a".repeat(MagnetParser.MAX_MAGNET_LENGTH + 10)
        assertTrue(MagnetParser.parseMagnetInput(oversized) is MagnetParseResult.Invalid)

        assertNull(MagnetParser.normalizeBtihToHex("not-a-hash"))
        assertNull(MagnetParser.normalizeBtmhToHex(sampleHexLower))
    }
}
