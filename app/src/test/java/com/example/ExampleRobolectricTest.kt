package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.indexers.IndexerParsers
import com.example.model.IndexerSource
import com.example.model.TorrentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MagnetVault", appName)
    }

    @Test
    fun `1337x list parser handles desktop and proxy mirror rows`() {
        val html = """
            <table class="table-list"><tbody>
              <tr>
                <td class="coll-1 name"><a href="/sub/42/0/" class="icon"><i class="flaticon-movies"></i></a>
                <a href="/torrent/6123456/Ubuntu-24-04-LTS-Desktop-1080p/">Ubuntu 24.04 LTS Desktop 1080p</a></td>
                <td class="coll-2 seeds">1,245</td>
                <td class="coll-3 leeches">88</td>
                <td class="coll-date">Nov. 15th '26</td>
                <td class="coll-4 size mob-uploader">4.72 GB<span class="seeds">1245</span></td>
              </tr>
              <tr>
                <td class="coll-1 name"><a href='https://1337x.hashhackers.com/torrent/6123999/Dune-2160p-HEVC/'>Dune 2160p HEVC</a></td>
                <td>512</td>
                <td>30</td>
                <td>2 days ago</td>
                <td>12.4 GB</td>
              </tr>
            </tbody></table>
        """.trimIndent()

        val parsed = IndexerParsers.parse1337xListHtml(html, "https://x1337x.cc", TorrentCategory.ALL)
        assertEquals(2, parsed.size)
        assertEquals("Ubuntu 24.04 LTS Desktop 1080p", parsed[0].title)
        assertEquals(1245, parsed[0].seeders)
        assertEquals(88, parsed[0].leechers)
        assertEquals("4.72 GB", parsed[0].sizeLabel)
        assertEquals(TorrentCategory.MOVIES, parsed[0].category)
        assertEquals("https://x1337x.cc/torrent/6123456/Ubuntu-24-04-LTS-Desktop-1080p/", parsed[0].detailPageUrl)
        assertEquals(IndexerSource.X1337, parsed[1].source)
        assertEquals(512, parsed[1].seeders)
        assertEquals("12.4 GB", parsed[1].sizeLabel)
    }

    @Test
    fun `1337x detail extractor supports magnet href and infohash box fallback`() {
        val magnetHtml = """<a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=Test">Magnet</a>"""
        val extracted = IndexerParsers.extractMagnetFromDetailHtml(magnetHtml, "Test")
        assertNotNull(extracted)
        assertTrue(extracted!!.startsWith("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))

        val hashBoxHtml = """<div class="infohash-box"><p><strong>Infohash :</strong> <span>ABCDEF0123456789ABCDEF0123456789ABCDEF01</span></p></div>"""
        val fromHashBox = IndexerParsers.extractMagnetFromDetailHtml(hashBoxHtml, "Fallback Movie")
        assertNotNull(fromHashBox)
        assertTrue(fromHashBox!!.contains("urn:btih:abcdef0123456789abcdef0123456789abcdef01"))
    }

    @Test
    fun `cloudflare challenge detector flags interstitials and allows normal pages`() {
        assertTrue(IndexerParsers.isCloudflareOrBlockedHtml("<html><head><title>Just a moment...</title></head></html>"))
        assertFalse(IndexerParsers.isCloudflareOrBlockedHtml("<html><head><title>1337x Search</title></head><body><table></table></body></html>"))
    }

    @Test
    fun `eztv imdb suggestion and api and html parsers work end to end`() {
        val imdbJson = """
            {"d":[
              {"id":"nm0000001","l":"Person"},
              {"id":"tt0903747","l":"Breaking Bad","qid":"tvSeries"},
              {"id":"tt2301451","l":"Ozymandias","qid":"tvEpisode"}
            ]}
        """.trimIndent()
        val ids = IndexerParsers.parseImdbSuggestionIds(imdbJson)
        assertEquals(listOf("0903747", "2301451"), ids)

        val eztvApiJson = """
            {"torrents":[
              {
                "id": 99123,
                "hash": "fedcba9876543210fedcba9876543210fedcba98",
                "filename": "Breaking.Bad.S01E01.1080p.BluRay.x265-EZTV.mkv",
                "title": "Breaking Bad S01E01 1080p BluRay x265",
                "magnet_url": "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98&dn=Breaking+Bad",
                "seeds": 320,
                "peers": 42,
                "size_bytes": "1548291072",
                "date_released_unix": 1700000000
              }
            ]}
        """.trimIndent()
        val apiParsed = IndexerParsers.parseEztvApiJson(eztvApiJson, listOf("s01e01", "1080p"))
        assertEquals(1, apiParsed.size)
        assertEquals(320, apiParsed[0].seeders)
        assertEquals(IndexerSource.EZTV, apiParsed[0].source)
        assertNotNull(apiParsed[0].magnetUri)

        val eztvHtml = """
            <table>
              <tr name="hover" class="forum_header_border">
                <td></td>
                <td><a href="/ep/12345/breaking-bad-s01e01-720p/" title="Breaking Bad S01E01 720p" class="epinfo">Breaking Bad S01E01 720p</a></td>
                <td><a href="magnet:?xt=urn:btih:1111222233334444555566667777888899990000&dn=BB" class="magnet"></a></td>
                <td>845.2 MB</td>
                <td>3d 4h</td>
                <td><font color="green">145</font></td>
              </tr>
            </table>
        """.trimIndent()
        val htmlParsed = IndexerParsers.parseEztvSearchHtml(eztvHtml, "https://eztvx.to")
        assertEquals(1, htmlParsed.size)
        assertEquals("Breaking Bad S01E01 720p", htmlParsed[0].title)
        assertEquals("845.2 MB", htmlParsed[0].sizeLabel)
        assertEquals(145, htmlParsed[0].seeders)
        assertTrue(htmlParsed[0].magnetUri!!.startsWith("magnet:?xt=urn:btih:1111222233334444555566667777888899990000"))
    }

    @Test
    fun `knaben 1337x json parser extracts only 1337x hits with direct magnets`() {
        val knabenJson = """
            {
              "hits": [
                {
                  "title": "Dune Part Two 2024 1080p BluRay x265",
                  "hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "id": "1337x-777888",
                  "cachedOrigin": "1337x",
                  "cachedId": "777888",
                  "details": "https://1337x.to/torrent/777888/Dune-Part-Two/",
                  "magnetUrl": "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&dn=Dune",
                  "bytes": 3221225472,
                  "date": "2026-03-10T14:22:00+00:00",
                  "seeders": 980,
                  "peers": 110,
                  "category": ["Movies", "HD"],
                  "categoryId": [3000000]
                },
                {
                  "title": "Other Tracker Entry",
                  "hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "cachedOrigin": "ThePirateBay",
                  "details": "https://thepiratebay.org/description.php?id=123",
                  "magnetUrl": "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "bytes": 1073741824,
                  "seeders": 500,
                  "peers": 20
                }
              ]
            }
        """.trimIndent()

        val parsed = IndexerParsers.parseKnaben1337xJson(knabenJson, TorrentCategory.ALL)
        assertEquals(1, parsed.size)
        assertEquals("x1337_777888", parsed[0].id)
        assertEquals(IndexerSource.X1337, parsed[0].source)
        assertEquals(TorrentCategory.MOVIES, parsed[0].category)
        assertEquals(980, parsed[0].seeders)
        assertEquals(110, parsed[0].leechers)
        assertEquals("3.00 GB", parsed[0].sizeLabel)
        assertEquals("Mar 10, 2026", parsed[0].uploadDateLabel)
        assertTrue(parsed[0].magnetUri!!.startsWith("magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }
}
