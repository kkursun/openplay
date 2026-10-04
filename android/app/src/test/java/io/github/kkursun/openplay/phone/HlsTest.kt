package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

// The same cases as test_media.py checks on the PC.
class HlsTest {
    private val srt = "1\n00:00:00,500 --> 00:00:02,000\nŞimdi ığdır'a gidiyoruz\n\n2\n00:00:04,000 --> 00:00:05,000\nİkinci\n"

    @Test
    fun languages() {
        assertEquals("tr" to "Turkish", language("tur"))
        assertEquals("en" to "English", language("Movie.2020.en"))
        assertEquals("tr" to "Turkish", language("2_Turkish"))
        assertEquals("" to "", language("Director's cut"))
    }

    @Test
    fun seasonPackEpisodesInOrder() {
        val pack = listOf("Show/Show.S01E10.mkv" to 900L, "Show/Sample/sample.mkv" to 20L, "Show/Show.S01E2.mkv" to 800L,
            "Show/Show.S01E1.mkv" to 850L, "Show/Subs/Show.S01E1.srt" to 1L)
        assertEquals(listOf(3, 2, 0), episodes(pack))
        assertEquals(listOf(1), episodes(listOf("Movie/movie.nfo" to 5L, "Movie/Movie.iso" to 50L)))
    }

    @Test
    fun oldTurkishSrtBecomesWebVtt() {
        val vtt = toWebVtt(srt.toByteArray(Charset.forName("windows-1254")), "srt")!!
        assertTrue(vtt, vtt.startsWith("WEBVTT\n\n00:00:00.500 --> 00:00:02.000\nŞimdi ığdır'a gidiyoruz\n\n"))
        assertTrue("Şimdi" in cuesBetween(vtt, 0.0, 4.0) && "İkinci" !in cuesBetween(vtt, 0.0, 4.0))
        assertTrue("İkinci" in cuesBetween(vtt, 4.5, 8.0))
        assertEquals("", cuesBetween(vtt, 6.0, 8.0))
    }

    @Test
    fun utf8AndUtf16Subtitles() {
        assertEquals(toWebVtt(srt.toByteArray(), "srt"), toWebVtt(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(), "srt"))
        assertEquals(toWebVtt(srt.toByteArray(), "srt"), toWebVtt(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.replace("\n", "\r\n").toByteArray(Charsets.UTF_16LE), "SRT"))
    }

    @Test
    fun tagsAndAmpersands() {
        val vtt = toWebVtt("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<font color=\"#ff0\"><i>Tom & Jerry</i></font>\n".toByteArray(), "srt")!!
        assertTrue(vtt, vtt.endsWith("00:00:01.000 --> 00:00:02.000\n<i>Tom &amp; Jerry</i>\n\n"))
        assertNull(toWebVtt("no cues here".toByteArray(), "srt"))
        assertNull(toWebVtt(srt.toByteArray(), "sub"))
    }

    @Test
    fun assDialogue() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:03.50,0:00:05.00,Default,,0,0,0,,{\\i1}Second{\\i0}, with a comma\\Nline two\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.25,Default,,0,0,0,,First\n"
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.250\nFirst\n\n00:00:03.500 --> 00:00:05.000\nSecond, with a comma\nline two\n\n",
            toWebVtt(ass.toByteArray(), "ass"))
    }

    @Test
    fun subtitleSegmentsFollowTheVideo() {
        val video = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n" +
            "#EXTINF:4.004000,\nlive0.ts\n#EXTINF:4.200000,\nlive1.ts\n"
        val vtt = toWebVtt(srt.toByteArray(), "srt")!!
        val playlist = subtitlePart("sub0.m3u8", video) { vtt }
        assertTrue(playlist, playlist.endsWith("#EXTINF:4.004000,\nsub0_0.vtt\n#EXTINF:4.200000,\nsub0_1.vtt\n"))
        val second = subtitlePart("sub0_1.vtt", video) { vtt }
        assertTrue(second, second.startsWith("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:126000,LOCAL:00:00:00.000\n\n"))
        assertTrue("İkinci" in second && "Şimdi" !in second)
    }

    @Test
    fun masterPlaylistListsSubtitles() {
        val master = masterPlaylist(listOf(Subtitle("1. Turkish", "tr"), Subtitle("2. \"Forced\"", "")), 5_000_000, "avc1.64001F,mp4a.40.2")
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"1. Turkish\",LANGUAGE=\"tr\",AUTOSELECT=YES,URI=\"sub0.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"2. 'Forced'\",AUTOSELECT=YES,URI=\"sub1.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS=\"avc1.64001F,mp4a.40.2\",SUBTITLES=\"subs\"\nlive.m3u8\n", master)
        assertFalse("SUBTITLES" in masterPlaylist(emptyList(), 1, ""))
    }
}
