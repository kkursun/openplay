package io.github.kkursun.openplay.phone

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

// The same playlists, subtitles and file picking as mirror.py on the PC, for what this phone streams itself.

/** Seconds of video in each segment of a video sent from the phone (cut at its own keyframes, so roughly). */
const val SEGMENT = 4.0

/** Timestamps in this app's MPEG-TS start at 1.4 s, like ffmpeg's: subtitle cues are lined up against that. */
const val TS_START = 126000L // 90 kHz ticks

const val HLS = "application/vnd.apple.mpegurl"

val SUBTITLE_FILES = setOf("srt", "ass", "ssa", "vtt")
val VIDEO_FILES = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg")

// ponytail: common languages only; others still play, but TVs can't match them to their own language
private val LANGUAGES = listOf( // code HLS players match against, name, other spellings found in file names
    listOf("en", "English", "eng"), listOf("tr", "Turkish", "tur", "turkce", "türkçe"),
    listOf("de", "German", "ger", "deu"), listOf("fr", "French", "fre", "fra"), listOf("es", "Spanish", "spa"),
    listOf("it", "Italian", "ita"), listOf("pt", "Portuguese", "por"), listOf("ru", "Russian", "rus"),
    listOf("ar", "Arabic", "ara"), listOf("nl", "Dutch", "dut", "nld"), listOf("pl", "Polish", "pol"),
    listOf("ja", "Japanese", "jpn"), listOf("ko", "Korean", "kor"), listOf("zh", "Chinese", "chi", "zho"),
)

/** (code, name) of the first language named in text, like "tur", "Movie.en" or "2_English"; blank if none. */
fun language(text: String): Pair<String, String> {
    for (word in Regex("[\\p{L}]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }) {
        val match = LANGUAGES.firstOrNull { word == it[0] || word == it[1].lowercase(Locale.ROOT) || word in it.drop(2) }
        if (match != null) return match[0] to match[1]
    }
    return "" to ""
}

/** Which of a torrent's files, (path, size) each, can be picked: its videos in episode order ("2" before "10"),
 * leaving out samples and extras under a tenth the size of the biggest. Or the biggest file if no video. */
fun episodes(files: List<Pair<String, Long>>): List<Int> {
    val videos = files.indices.filter { File(files[it].first).extension.lowercase(Locale.ROOT) in VIDEO_FILES }
    val biggest = videos.maxOfOrNull { files[it].second } ?: 0
    val natural = Comparator<Int> { a, b -> compareNatural(files[a].first, files[b].first) }
    return videos.filter { files[it].second >= biggest / 10 }.sortedWith(natural)
        .ifEmpty { listOfNotNull(files.indices.maxByOrNull { files[it].second }) }
}

private fun compareNatural(a: String, b: String): Int {
    val split = { s: String -> Regex("\\d+|\\D+").findAll(s.lowercase(Locale.ROOT)).map { it.value }.toList() }
    val x = split(a)
    val y = split(b)
    for (i in 0 until minOf(x.size, y.size)) {
        val m = x[i].toBigIntegerOrNull()
        val n = y[i].toBigIntegerOrNull()
        val c = if (m != null && n != null) m.compareTo(n) else x[i].compareTo(y[i])
        if (c != 0) return c
    }
    return x.size - y.size
}

/** Text of a subtitle file: UTF-8 or UTF-16 if it says so, else Windows-1254, the old Turkish code page, which also
 * reads English and Western European text right. */
fun decodeText(data: ByteArray): String {
    fun bom(vararg b: Int) = data.size >= b.size && b.indices.all { data[it] == b[it].toByte() }
    return when {
        bom(0xEF, 0xBB, 0xBF) -> String(data, 3, data.size - 3, Charsets.UTF_8)
        bom(0xFF, 0xFE) -> String(data, 2, data.size - 2, Charsets.UTF_16LE)
        bom(0xFE, 0xFF) -> String(data, 2, data.size - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            // ponytail: old Cyrillic, Greek or Arabic code pages come out garbled; guessing them needs a detector
            String(data, Charset.forName("windows-1254"))
        }
    }
}

private fun clock(seconds: Double): String {
    val ms = Math.round(seconds * 1000)
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

/** "01:02:03.500", "02:03.500" or SRT's "01:02:03,500" (or ASS's "1:02:03.50") in seconds. */
fun seconds(timestamp: String): Double =
    timestamp.replace(',', '.').split(":").reversed().withIndex().sumOf { (i, part) -> part.toDouble() * Math.pow(60.0, i.toDouble()) }

private val TAG = Regex("</?([a-zA-Z]+)[^>]*>")

private fun cueText(text: String): String =
    text.replace(Regex("\\{\\\\[^}]*\\}"), "") // {\an8} and other ASS overrides some SRT files carry
        .replace(TAG) { if (it.groupValues[1].lowercase(Locale.ROOT) in setOf("i", "b", "u")) it.value.lowercase(Locale.ROOT) else "" }
        .replace(Regex("&(?![a-zA-Z]+;|#\\d+;)"), "&amp;")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/** A subtitle file (srt, ass, ssa or vtt) as WebVTT with cues like "00:00:01.500 --> 00:00:03.000", or null
 * if it holds none. */
fun toWebVtt(data: ByteArray, extension: String): String? {
    val text = decodeText(data).replace("\r\n", "\n").replace('\r', '\n')
    val cues = mutableListOf<Triple<Double, Double, String>>()
    when (extension.lowercase(Locale.ROOT)) {
        "srt", "vtt" -> for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines()
            val at = lines.indexOfFirst { "-->" in it }
            val times = if (at >= 0) Regex("([\\d:.,]+)\\s*-->\\s*([\\d:.,]+)").find(lines[at]) else null
            if (times != null) {
                val words = cueText(lines.drop(at + 1).joinToString("\n"))
                if (words.isNotEmpty()) cues += Triple(seconds(times.groupValues[1]), seconds(times.groupValues[2]), words)
            }
        }
        "ass", "ssa" -> {
            var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            for (line in text.lines()) {
                if (line.startsWith("Format:") && "text" in line.lowercase(Locale.ROOT)) {
                    fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                } else if (line.startsWith("Dialogue:")) {
                    val parts = line.substringAfter(':').split(",", limit = fields.size)
                    if (parts.size < fields.size) continue
                    val get = { name: String -> parts[fields.indexOf(name)].trim() }
                    val words = cueText(get("text").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
                    if (words.isNotEmpty()) cues += Triple(seconds(get("start")), seconds(get("end")), words)
                }
            }
        }
        else -> return null
    }
    if (cues.isEmpty()) return null
    return "WEBVTT\n\n" + cues.sortedBy { it.first }.joinToString("") { "${clock(it.first)} --> ${clock(it.second)}\n${it.third}\n\n" }
}

/** The cues of a WebVTT file shown between start and end seconds. */
fun cuesBetween(vtt: String, start: Double, end: Double): String {
    val blocks = vtt.split("\n\n").toMutableList()
    if (!vtt.endsWith("\n")) blocks.removeAt(blocks.lastIndex) // halfway through being written
    return blocks.mapNotNull { block ->
        val m = Regex("(\\S+) --> (\\S+)").find(block) ?: return@mapNotNull null
        if (seconds(m.groupValues[1]) < end && seconds(m.groupValues[2]) > start) block.substring(m.range.first) else null
    }.joinToString("\n\n")
}

data class Subtitle(val name: String, val lang: String)

/** A master playlist for live.m3u8 with the subtitles as WebVTT renditions. Apple TVs show the one in their own
 * language and offer the rest in their menu; Cast devices switch when told to. */
fun masterPlaylist(subtitles: List<Subtitle>, bandwidth: Int, codecs: String): String {
    val media = subtitles.withIndex().joinToString("") { (n, sub) ->
        "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${sub.name.replace('"', '\'')}\"," +
            (if (sub.lang.isNotEmpty()) "LANGUAGE=\"${sub.lang}\"," else "") + "AUTOSELECT=YES,URI=\"sub$n.m3u8\"\n"
    }
    val stream = "BANDWIDTH=$bandwidth" + (if (codecs.isNotEmpty()) ",CODECS=\"$codecs\"" else "") +
        (if (subtitles.isNotEmpty()) ",SUBTITLES=\"subs\"" else "")
    return "#EXTM3U\n#EXT-X-VERSION:3\n$media#EXT-X-STREAM-INF:$stream\nlive.m3u8\n"
}

/** subN.m3u8, a subtitle playlist with the same segments as the video's, or subN_M.vtt, subtitle N's cues during
 * video segment M. video is live.m3u8 as it stands, vtt the whole of subtitle N. */
fun subtitlePart(name: String, video: String, vtt: (Int) -> String): String {
    Regex("sub(\\d+)\\.m3u8").matchEntire(name)?.let { m ->
        return video.replace(Regex("^live(\\d+)\\.\\w+$", RegexOption.MULTILINE)) { "sub${m.groupValues[1]}_${it.groupValues[1]}.vtt" }
    }
    val (n, segment) = Regex("sub(\\d+)_(\\d+)\\.vtt").matchEntire(name)!!.destructured
    val lengths = Regex("#EXTINF:([\\d.]+)").findAll(video).map { it.groupValues[1].toDouble() }.toList()
    val first = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(video)?.groupValues?.get(1)?.toInt() ?: 0
    val index = segment.toInt() - first
    val start = lengths.take(index).sum()
    return "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:$TS_START,LOCAL:00:00:00.000\n\n" +
        cuesBetween(vtt(n.toInt()), start, start + lengths[index])
}
