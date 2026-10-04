package io.github.kkursun.openplay.phone

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil

/** Packs H.264 (Annex B access units) and AAC (ADTS frames) into MPEG-TS packets, as HLS segments take them. */
class TsMuxer(private val hasVideo: Boolean, private val hasAudio: Boolean) {
    private val counters = IntArray(0x2000)
    private var config = emptyList<ByteArray>() // SPS and PPS, put before every keyframe

    /** Sets the SPS and PPS (MediaCodec's codec config, or csd-0 and csd-1) put before every keyframe. */
    fun videoConfig(annexB: ByteArray) {
        config = nalUnits(annexB).filter { nalType(it) == 7 || nalType(it) == 8 }
    }

    /** The codec name HLS master playlists use for the video ("avc1.64001F"), from its SPS. */
    fun videoCodec(): String = config.firstOrNull { nalType(it) == 7 && it.size >= 4 }
        ?.let { String.format(Locale.ROOT, "avc1.%02X%02X%02X", it[1], it[2], it[3]) } ?: ""

    /** The tables players read first: written at the start of every segment. */
    fun tables(out: ByteArrayOutputStream) {
        section(out, 0, byteArrayOf(0x00, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PMT shr 8)).toByte(), PMT.toByte()), 0x00)
        val streams = ByteArrayOutputStream()
        if (hasVideo) streams.write(byteArrayOf(0x1B, (0xE0 or (VIDEO shr 8)).toByte(), VIDEO.toByte(), 0xF0.toByte(), 0x00))
        if (hasAudio) streams.write(byteArrayOf(0x0F, (0xE0 or (AUDIO shr 8)).toByte(), AUDIO.toByte(), 0xF0.toByte(), 0x00))
        val pcr = if (hasVideo) VIDEO else AUDIO
        section(out, PMT, byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, (0xE0 or (pcr shr 8)).toByte(), pcr.toByte(), 0xF0.toByte(), 0x00) + streams.toByteArray(), 0x02)
    }

    /** One access unit; pts and dts in 90 kHz ticks. */
    fun video(out: ByteArrayOutputStream, accessUnit: ByteArray, pts: Long, dts: Long, key: Boolean) {
        val nals = nalUnits(accessUnit).filter { nalType(it) != 9 }
        val es = ByteArrayOutputStream()
        es.write(byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())) // access unit delimiter, which Apple's players want
        if (key && nals.none { nalType(it) == 7 }) config.forEach { es.write(START); es.write(it) }
        nals.forEach { es.write(START); es.write(it) }
        packets(out, VIDEO, pes(0xE0, es.toByteArray(), pts, dts), pcr = maxOf(0, dts - 9000), randomAccess = key)
    }

    /** One ADTS frame; pts in 90 kHz ticks. */
    fun audio(out: ByteArrayOutputStream, frame: ByteArray, pts: Long) {
        packets(out, AUDIO, pes(0xC0, frame, pts, pts), pcr = if (hasVideo) null else maxOf(0, pts - 9000), randomAccess = !hasVideo)
    }

    private fun pes(stream: Int, payload: ByteArray, pts: Long, dts: Long): ByteArray {
        val both = dts != pts
        val header = if (both) 10 else 5
        val length = 3 + header + payload.size
        val out = ByteArrayOutputStream(payload.size + 19)
        out.write(byteArrayOf(0, 0, 1, stream.toByte()))
        val sized = if (stream == 0xE0 || length > 0xFFFF) 0 else length // video may leave it open
        out.write(sized shr 8)
        out.write(sized and 0xFF)
        out.write(0x80)
        out.write(if (both) 0xC0 else 0x80)
        out.write(header)
        timestamp(out, if (both) 3 else 2, pts)
        if (both) timestamp(out, 1, dts)
        out.write(payload)
        return out.toByteArray()
    }

    private fun timestamp(out: ByteArrayOutputStream, prefix: Int, t: Long) {
        val v = t and 0x1FFFFFFFFL
        out.write((prefix shl 4 or ((v shr 29).toInt() and 0x0E) or 1))
        out.write((v shr 22).toInt() and 0xFF)
        out.write(((v shr 14).toInt() and 0xFE) or 1)
        out.write((v shr 7).toInt() and 0xFF)
        out.write(((v shl 1).toInt() and 0xFE) or 1)
    }

    private fun packets(out: ByteArrayOutputStream, pid: Int, pes: ByteArray, pcr: Long?, randomAccess: Boolean) {
        var pos = 0
        var first = true
        while (pos < pes.size) {
            var fields = ByteArray(0) // the adaptation field after its length byte
            if (first && (pcr != null || randomAccess)) {
                fields = ByteArray(if (pcr != null) 7 else 1)
                fields[0] = ((if (randomAccess) 0x40 else 0) or (if (pcr != null) 0x10 else 0)).toByte()
                if (pcr != null) {
                    val base = pcr and 0x1FFFFFFFFL
                    fields[1] = (base shr 25).toByte()
                    fields[2] = (base shr 17).toByte()
                    fields[3] = (base shr 9).toByte()
                    fields[4] = (base shr 1).toByte()
                    fields[5] = (((base and 1) shl 7) or 0x7E).toByte()
                    fields[6] = 0
                }
            }
            var adaptation = if (fields.isEmpty()) 0 else 1 + fields.size
            val payload = minOf(pes.size - pos, 184 - adaptation)
            val stuffing = 184 - adaptation - payload
            if (stuffing > 0) {
                if (adaptation == 0 && stuffing == 1) {
                    adaptation = 1
                } else {
                    fields = (if (fields.isEmpty()) byteArrayOf(0) else fields) + ByteArray(stuffing - (if (adaptation == 0) 2 else 0)) { 0xFF.toByte() }
                    adaptation = 1 + fields.size
                }
            }
            val packet = ByteArray(188)
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = pid.toByte()
            packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or counter(pid)).toByte()
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                fields.copyInto(packet, 5)
            }
            pes.copyInto(packet, 4 + adaptation, pos, pos + payload)
            out.write(packet)
            pos += payload
            first = false
        }
    }

    private fun section(out: ByteArrayOutputStream, pid: Int, body: ByteArray, table: Int) {
        val length = body.size + 4 // and the CRC
        val section = byteArrayOf(table.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte()) + body
        val crc = crc32(section)
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or counter(pid)).toByte()
        packet[4] = 0 // pointer field
        section.copyInto(packet, 5)
        for (i in 0..3) packet[5 + section.size + i] = (crc shr (24 - 8 * i)).toByte()
        out.write(packet)
    }

    private fun counter(pid: Int): Int = counters[pid].also { counters[pid] = (it + 1) and 0xF }

    companion object {
        const val PMT = 0x1000
        const val VIDEO = 0x100
        const val AUDIO = 0x101
        private val START = byteArrayOf(0, 0, 0, 1)

        fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1F

        /** The NAL units of H.264 in Annex B (start codes) or, failing that, as 4-byte lengths before each. */
        fun nalUnits(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>() // first byte after each start code
            var i = 0
            while (i + 2 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    starts += i + 3
                    i += 3
                } else {
                    i++
                }
            }
            if (starts.isEmpty() || (starts[0] > 4)) return lengthPrefixed(data)
            return starts.mapIndexed { n, s ->
                var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
                while (end > s && data[end - 1].toInt() == 0) end-- // the next 4-byte start code's leading zero
                data.copyOfRange(s, end)
            }.filter { it.isNotEmpty() }
        }

        private fun lengthPrefixed(data: ByteArray): List<ByteArray> {
            val nals = mutableListOf<ByteArray>()
            var i = 0
            while (i + 4 <= data.size) {
                val n = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                if (n <= 0 || i + 4 + n > data.size) break
                nals += data.copyOfRange(i + 4, i + 4 + n)
                i += 4 + n
            }
            return nals
        }

        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        /** An ADTS header for a raw AAC frame: objectType 2 is AAC-LC, rate an index (3 = 48 kHz, 4 = 44.1 kHz). */
        fun adts(frame: ByteArray, objectType: Int, rate: Int, channels: Int): ByteArray {
            val length = frame.size + 7
            val profile = (if (objectType in 1..4) objectType else 2) - 1 // HE-AAC rides on an LC header
            return byteArrayOf(
                0xFF.toByte(), 0xF1.toByte(),
                ((profile shl 6) or (rate shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(),
                (length shr 3).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            ) + frame
        }

        /** (objectType, rate index, channels) from an AAC AudioSpecificConfig (csd-0). */
        fun audioConfig(asc: ByteArray): Triple<Int, Int, Int> {
            val a = asc[0].toInt() and 0xFF
            val b = asc[1].toInt() and 0xFF
            return Triple(a shr 3, ((a and 7) shl 1) or (b shr 7), (b shr 3) and 0xF)
        }

        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun ticks(us: Long) = TS_START + us * 9 / 100
    }
}

/** Cuts a stream into MPEG-TS segments live0.ts, live1.ts... listed in live.m3u8, like ffmpeg's hls muxer: a sliding
 * live playlist of window segments, or an EVENT playlist of all of them if window is 0. Segments start at keyframes
 * once target seconds have passed. */
class HlsWriter(
    private val dir: File,
    private val target: Double,
    private val window: Int,
    private val hasVideo: Boolean,
    private val hasAudio: Boolean,
    var audioCodec: String = "mp4a.40.2",
) {
    val muxer = TsMuxer(hasVideo, hasAudio)
    private var segment = ByteArrayOutputStream()
    private var start = -1L // presentation time of the current segment's first frame, µs
    private var latest = -1L
    private var step = 0L // latest frame's duration, µs
    private val lengths = mutableListOf<Double>() // seconds, of every segment written
    private val sizes = mutableListOf<Int>()
    private var first = 0 // the first segment still listed
    var ended = false
        private set

    val count: Int @Synchronized get() = lengths.size

    @Synchronized
    fun video(accessUnit: ByteArray, ptsUs: Long, dtsUs: Long, key: Boolean) {
        if (ended || !cut(ptsUs, key)) return
        muxer.video(segment, accessUnit, TsMuxer.ticks(ptsUs), TsMuxer.ticks(dtsUs), key)
        seen(ptsUs)
    }

    @Synchronized
    fun audio(adtsFrame: ByteArray, ptsUs: Long) {
        if (ended || !cut(ptsUs, !hasVideo)) return
        muxer.audio(segment, adtsFrame, TsMuxer.ticks(ptsUs))
        if (!hasVideo) seen(ptsUs)
    }

    private fun seen(ptsUs: Long) {
        if (ptsUs > latest) {
            if (latest >= 0) step = ptsUs - latest
            latest = ptsUs
        }
    }

    /** Starts the next segment if this frame should begin one; false while waiting for the first keyframe. */
    private fun cut(ptsUs: Long, key: Boolean): Boolean {
        if (start < 0) {
            if (!key) return false
            start = ptsUs
            muxer.tables(segment)
        } else if (key && ptsUs - start >= target * 1e6 - 1000) {
            close(ptsUs)
            start = ptsUs
            muxer.tables(segment)
        }
        return true
    }

    private fun close(endUs: Long) {
        if (start < 0 || segment.size() == 0) return
        val n = lengths.size
        write("live$n.ts", segment.toByteArray())
        lengths += (endUs - start).coerceAtLeast(1000) / 1e6
        sizes += segment.size()
        segment = ByteArrayOutputStream()
        if (window > 0 && lengths.size - first > window) {
            first++
            File(dir, "live${first - 3}.ts").delete() // a couple more kept for players still fetching them
        }
        write("live.m3u8", playlist().toByteArray())
    }

    /** Writes out what's left and ends the playlist. */
    @Synchronized
    fun finish() {
        if (ended) return
        close(latest + step)
        ended = true
        write("live.m3u8", playlist().toByteArray())
    }

    /** The stream's codecs for master playlists' CODECS, or "" if the video's aren't known yet (leaving them out
     * beats a list missing the video). */
    fun codecs(): String {
        val video = muxer.videoCodec()
        if (hasVideo && video.isEmpty()) return ""
        return listOfNotNull(video.takeIf { hasVideo }, audioCodec.takeIf { hasAudio }).joinToString(",")
    }

    /** The highest bitrate of a segment so far, for master playlists' BANDWIDTH. */
    @Synchronized
    fun peakBitrate(): Int = lengths.indices.maxOfOrNull { (sizes[it] * 8 / lengths[it]).toInt() } ?: 1_000_000

    private fun playlist(): String {
        val listed = lengths.indices.drop(first)
        val longest = listed.maxOfOrNull { lengths[it] } ?: target
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:${maxOf(1, ceil(longest).toInt())}\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        // Players start growing playlists near their end, like live streams, unless told otherwise.
        if (window == 0) sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-START:TIME-OFFSET=0\n")
        for (i in listed) sb.append(String.format(Locale.ROOT, "#EXTINF:%.6f,\nlive%d.ts\n", lengths[i], i))
        if (ended) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    private fun write(name: String, data: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(data)
        tmp.renameTo(File(dir, name)) // whole, never half-written, when the TV reads it
    }
}

/** Decode timestamps for video read in decode order with presentation times only (all MediaExtractor gives): the
 * presentation times in increasing order, moved back by however far the first frames are reordered. */
class DecodeTimes<T>(private val window: Int = 32) {
    private val frames = ArrayDeque<Pair<T, Long>>()
    private val times = PriorityQueue<Long>()
    private var shift = -1L
    private var last = Long.MIN_VALUE

    /** Adds a frame; returns the frames now ready, with their decode times. */
    fun add(frame: T, ptsUs: Long): List<Pair<T, Long>> {
        frames.addLast(frame to ptsUs)
        times.add(ptsUs)
        return if (frames.size > window) listOf(next()) else emptyList()
    }

    fun flush(): List<Pair<T, Long>> = buildList { while (frames.isNotEmpty()) add(next()) }

    private fun next(): Pair<T, Long> {
        if (shift < 0) {
            val sorted = times.sorted()
            shift = frames.indices.maxOf { sorted[it] - frames[it].second }.coerceAtLeast(0)
        }
        val (frame, _) = frames.removeFirst()
        val dts = maxOf(times.poll()!! - shift, last + 1)
        last = dts
        return frame to dts
    }
}
