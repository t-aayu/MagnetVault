package com.example

import com.example.data.indexers.IndexerParsers
import com.example.data.indexers.ParserException
import com.example.data.network.MirrorBlockedException
import com.example.model.SwarmHealth
import com.example.model.TorrentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IndexerParsersPhase1Test {

    @Test
    fun `apibay parser distinguishes unknown metadata from zero and handles legitimate empty`() {
        val emptyResp = """[{"id":"0","name":"No results returned","info_hash":"0000000000000000000000000000000000000000","leechers":"0","seeders":"0","size":"0","added":"0","category":"0"}]"""
        assertTrue(IndexerParsers.parseApiBayJson(emptyResp, "https://apibay.org").isEmpty())

        val json = """
            [
              {
                "id": "101",
                "name": "Zero Seeders Release",
                "info_hash": "0123456789ABCDEF0123456789ABCDEF01234567",
                "seeders": "0",
                "leechers": "0",
                "size": "0",
                "added": "0",
                "category": "201"
              },
              {
                "id": "102",
                "name": "Unknown Swarm Release",
                "info_hash": "fedcba9876543210fedcba9876543210fedcba98",
                "seeders": "",
                "leechers": "",
                "size": "",
                "added": "",
                "category": "201"
              }
            ]
        """.trimIndent()

        val parsed = IndexerParsers.parseApiBayJson(json, "https://apibay.org")
        assertEquals(2, parsed.size)

        // Known zero vs unknown
        assertEquals(0, parsed[0].knownSeeders)
        assertEquals(0, parsed[0].knownLeechers)
        assertEquals(SwarmHealth.DEAD, parsed[0].swarmHealth)
        assertEquals("0123456789abcdef0123456789abcdef01234567", parsed[0].canonicalHashHex)

        assertNull(parsed[1].knownSeeders)
        assertNull(parsed[1].knownLeechers)
        assertNull(parsed[1].knownSizeBytes)
        assertNull(parsed[1].knownUploadEpochSeconds)
        assertEquals(SwarmHealth.UNKNOWN, parsed[1].swarmHealth)
        assertEquals("Unknown", parsed[1].sizeLabel)
        assertEquals("Unknown", parsed[1].uploadDateLabel)
    }

    @Test
    fun `yts parser handles legitimate empty zero seeders unknown metadata and malformed json`() {
        val emptyJson = """{"status":"ok","data":{"movie_count":0}}"""
        assertTrue(IndexerParsers.parseYtsJson(emptyJson).isEmpty())

        val validJson = """
            {
              "status": "ok",
              "data": {
                "movie_count": 1,
                "movies": [
                  {
                    "id": 55,
                    "title_long": "Interstellar (2014)",
                    "url": "https://yts.mx/movies/interstellar-2014",
                    "torrents": [
                      {
                        "hash": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "quality": "1080p",
                        "type": "bluray",
                        "seeds": 0,
                        "peers": 2,
                        "size": "2.15 GB",
                        "size_bytes": 2308544921,
                        "date_uploaded_unix": 1700000000
                      },
                      {
                        "hash": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                        "quality": "720p",
                        "type": "web",
                        "size": ""
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = IndexerParsers.parseYtsJson(validJson)
        assertEquals(2, parsed.size)
        assertEquals(0, parsed[0].knownSeeders)
        assertEquals(SwarmHealth.DEAD, parsed[0].swarmHealth)
        assertNull(parsed[1].knownSeeders)
        assertNull(parsed[1].knownLeechers)
        assertNull(parsed[1].knownSizeBytes)
        assertEquals(SwarmHealth.UNKNOWN, parsed[1].swarmHealth)

        try {
            IndexerParsers.parseYtsJson("{not-json")
            fail("Expected ParserException for malformed YTS JSON")
        } catch (_: ParserException) {
            // expected
        }
    }

    @Test
    fun `eztv html and api parsers preserve unknown vs zero and reject cloudflare or malformed payloads`() {
        val eztvHtml = """
            <table>
              <tr name="hover" class="forum_header_border">
                <td></td>
                <td><a href="/ep/777/show-s01e01/" class="epinfo">Show S01E01 1080p</a></td>
                <td><a href="magnet:?xt=urn:btih:1111222233334444555566667777888899990000&dn=Show" class="magnet"></a></td>
                <td>—</td>
                <td></td>
                <td>-</td>
              </tr>
            </table>
        """.trimIndent()
        val parsed = IndexerParsers.parseEztvSearchHtml(eztvHtml, "https://eztvx.to")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].knownSeeders)
        assertNull(parsed[0].knownSizeBytes)
        assertEquals("Unknown", parsed[0].sizeLabel)
        assertEquals("Unknown", parsed[0].uploadDateLabel)
        assertEquals(SwarmHealth.UNKNOWN, parsed[0].swarmHealth)

        try {
            IndexerParsers.parseEztvSearchHtml("<html><title>Just a moment...</title></html>", "https://eztvx.to")
            fail("Expected MirrorBlockedException for Cloudflare page")
        } catch (_: MirrorBlockedException) {
            // expected
        }

        try {
            IndexerParsers.parseEztvApiJson("not valid json")
            fail("Expected ParserException for malformed EZTV JSON")
        } catch (_: ParserException) {
            // expected
        }
    }

    @Test
    fun `nyaa and limetorrents rss parsers handle valid empty unknown fields and malformed xml`() {
        val emptyRss = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Nyaa</title></channel></rss>"""
        assertTrue(IndexerParsers.parseNyaaRss(emptyRss).isEmpty())
        assertTrue(IndexerParsers.parseLimeTorrentsRss(emptyRss, TorrentCategory.ALL).isEmpty())

        val nyaaUnknownRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:nyaa="https://nyaa.si/xmlns/nyaa">
              <channel>
                <item>
                  <title>Anime Episode 01 1080p</title>
                  <guid>https://nyaa.si/view/12345</guid>
                  <nyaa:infoHash>0123456789abcdef0123456789abcdef01234567</nyaa:infoHash>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val nyaaItems = IndexerParsers.parseNyaaRss(nyaaUnknownRss)
        assertEquals(1, nyaaItems.size)
        assertNull(nyaaItems[0].knownSeeders)
        assertNull(nyaaItems[0].knownLeechers)
        assertNull(nyaaItems[0].knownSizeBytes)
        assertEquals(SwarmHealth.UNKNOWN, nyaaItems[0].swarmHealth)

        val limeMissingDescRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Linux ISO 2026</title>
                  <link>https://www.limetorrents.lol/Linux-ISO-torrent-9876.html</link>
                  <enclosure url="https://itorrents.org/torrent/ABCDEF0123456789ABCDEF0123456789ABCDEF01.torrent" />
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val limeItems = IndexerParsers.parseLimeTorrentsRss(limeMissingDescRss, TorrentCategory.ALL)
        assertEquals(1, limeItems.size)
        assertNull(limeItems[0].knownSeeders)
        assertNull(limeItems[0].knownLeechers)
        assertEquals(SwarmHealth.UNKNOWN, limeItems[0].swarmHealth)

        try {
            IndexerParsers.parseNyaaRss("<rss><channel><item><title>broken")
            fail("Expected ParserException on malformed XML")
        } catch (_: ParserException) {
            // expected
        }
    }

    @Test
    fun `1337x html parser distinguishes valid empty search page from unrecognized html and cloudflare`() {
        val validEmpty = """<html><head><title>1337x Search</title></head><body><div class="box-info">No results were returned.</div></body></html>"""
        assertTrue(IndexerParsers.parse1337xListHtml(validEmpty, "https://x1337x.cc", TorrentCategory.ALL).isEmpty())

        try {
            IndexerParsers.parse1337xListHtml("<html><body><div>Totally unrelated parking page</div></body></html>", "https://x1337x.cc", TorrentCategory.ALL)
            fail("Expected ParserException on unrecognized HTML")
        } catch (_: ParserException) {
            // expected
        }

        try {
            IndexerParsers.parse1337xListHtml("<html><head><title>Attention Required! | Cloudflare</title></head></html>", "https://x1337x.cc", TorrentCategory.ALL)
            fail("Expected MirrorBlockedException on Cloudflare interstitial")
        } catch (_: MirrorBlockedException) {
            // expected
        }
    }
}
