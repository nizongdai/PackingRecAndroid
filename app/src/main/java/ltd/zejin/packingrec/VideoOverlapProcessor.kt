package ltd.zejin.packingrec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoOverlapProcessor {
    fun trimTail(file: File, tailUs: Long): Boolean {
        return trimFileTail(file, tailUs)
    }

    private fun loadTrackInfo(file: File): List<TrackInfo> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val infos = mutableListOf<TrackInfo>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val maxTs = findMaxTimestamp(file, i)
                infos.add(TrackInfo(i, mime, format, maxTs))
            }
            infos
        } finally {
            extractor.release()
        }
    }

    private fun findMaxTimestamp(file: File, trackIndex: Int): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var maxTs = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    break
                }
                val ts = extractor.sampleTime
                if (ts > maxTs) {
                    maxTs = ts
                }
                extractor.advance()
            }
            maxTs
        } finally {
            extractor.release()
        }
    }

    private fun trimFileTail(oldFile: File, tailUs: Long): Boolean {
        val oldTracks = loadTrackInfo(oldFile)
        if (oldTracks.isEmpty()) {
            return false
        }
        val maxOldTs = oldTracks.maxOf { it.maxTimestampUs }
        val cutoffUs = (maxOldTs - tailUs).coerceAtLeast(0L)
        if (cutoffUs <= 0L) {
            oldFile.delete()
            return true
        }
        val trimmedFile = File(oldFile.parentFile, "${oldFile.nameWithoutExtension}_trimmed.mp4")
        val muxer = MediaMuxer(trimmedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = mutableMapOf<Int, Int>()
        for (track in oldTracks) {
            val muxIndex = muxer.addTrack(track.format)
            muxerTrackIndex[track.trackIndex] = muxIndex
        }
        muxer.start()
        oldTracks.forEach { track ->
            val extractor = MediaExtractor()
            extractor.setDataSource(oldFile.absolutePath)
            extractor.selectTrack(track.trackIndex)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    break
                }
                val timeUs = extractor.sampleTime
                if (timeUs > cutoffUs) {
                    break
                }
                bufferInfo.presentationTimeUs = timeUs
                bufferInfo.size = size
                bufferInfo.offset = 0
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex[track.trackIndex]!!, buffer, bufferInfo)
                extractor.advance()
            }
            extractor.release()
        }
        muxer.stop()
        muxer.release()
        return replaceFile(oldFile, trimmedFile)
    }

    private fun replaceFile(target: File, replacement: File): Boolean {
        val backup = File(target.parentFile, "${target.nameWithoutExtension}_backup.mp4")
        if (backup.exists()) {
            backup.delete()
        }
        val renamed = target.renameTo(backup)
        if (!renamed) {
            return false
        }
        val moved = replacement.renameTo(target)
        if (!moved) {
            backup.renameTo(target)
            return false
        }
        backup.delete()
        return true
    }

    private data class TrackInfo(
        val trackIndex: Int,
        val mime: String,
        val format: MediaFormat,
        val maxTimestampUs: Long
    )
}
