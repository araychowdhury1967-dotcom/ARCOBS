package com.mobilestreaming.studio.streaming.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.mobilestreaming.studio.core.model.RecordingEntry
import com.mobilestreaming.studio.core.model.StreamConfig
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Records the very same encoded H.264/AAC samples that are offered to RTMP. */
class RecordingMuxer(
    private val context: Context,
    private val config: StreamConfig,
) : AutoCloseable {
    private val lock = Object()
    private val createdAt = System.currentTimeMillis()
    private val displayName = "MSS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(createdAt))}.mp4"
    private var muxer: MediaMuxer? = null
    private var descriptor: android.os.ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var legacyFile: File? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private var sawVideoKeyFrame = false
    private var wroteVideo = false
    private var wroteAudio = false
    private var bytesWritten = 0L

    val name: String get() = displayName
    val currentSizeBytes: Long get() = synchronized(lock) { bytesWritten }
    val durationMs: Long get() = System.currentTimeMillis() - createdAt

    fun start() {
        synchronized(lock) {
            check(muxer == null) { "Recording has already started" }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, createdAt / 1000)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MobileStreamingStudio")
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore could not create the recording")
                outputUri = uri
                val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("Recording file could not be opened")
                descriptor = pfd
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "MobileStreamingStudio",
                ).apply { mkdirs() }
                val file = File(directory, displayName)
                legacyFile = file
                values.put(MediaStore.Video.Media.DATA, file.absolutePath)
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Storage permission or recording location unavailable")
                outputUri = uri
                val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("Recording file could not be opened")
                descriptor = pfd
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        }
    }

    fun onVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (!started && videoTrack < 0) {
            videoTrack = muxer?.addTrack(format) ?: -1
            startWhenReady()
        }
    }

    fun onAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (!started && audioTrack < 0) {
            audioTrack = muxer?.addTrack(format) ?: -1
            startWhenReady()
        }
    }

    fun writeVideo(data: ByteArray, timestampUs: Long, flags: Int) = synchronized(lock) {
        if (finished) return@synchronized
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return@synchronized
        if (!sawVideoKeyFrame) {
            if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return@synchronized
            sawVideoKeyFrame = true
        }
        if (started) {
            writeSample(videoTrack, data, timestampUs, flags)
            wroteVideo = true
        }
    }

    fun writeAudio(data: ByteArray, timestampUs: Long, flags: Int) = synchronized(lock) {
        if (finished || !started || !sawVideoKeyFrame || flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            return@synchronized
        }
        writeSample(audioTrack, data, timestampUs, flags)
        wroteAudio = true
    }

    fun finish(): RecordingEntry {
        val uri: Uri
        val duration: Long
        synchronized(lock) {
            check(!finished) { "Recording is already finalized" }
            finished = true
            duration = System.currentTimeMillis() - createdAt
            try {
                check(started && wroteVideo && wroteAudio) {
                    "No complete audio/video recording was produced; the file has been discarded"
                }
                muxer?.stop()
                muxer?.release()
                descriptor?.close()
                muxer = null
                descriptor = null
                uri = outputUri ?: throw IllegalStateException("Recording URI unavailable")
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.update(
                        uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null,
                    )
                }
            } catch (error: Throwable) {
                cleanupLocked()
                throw error
            }
        }
        val size = querySize(uri).takeIf { it > 0 } ?: bytesWritten
        return RecordingEntry(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            contentUri = uri.toString(),
            createdAtEpochMs = createdAt,
            durationMs = duration,
            sizeBytes = size,
            width = config.width,
            height = config.height,
        )
    }

    fun abort() = synchronized(lock) {
        if (finished) return@synchronized
        finished = true
        cleanupLocked()
    }

    override fun close() = abort()

    private fun startWhenReady() {
        if (!started && videoTrack >= 0 && audioTrack >= 0) {
            muxer?.start()
            started = true
        }
    }

    private fun writeSample(track: Int, data: ByteArray, timestampUs: Long, flags: Int) {
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, timestampUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        }
        muxer?.writeSampleData(track, ByteBuffer.wrap(data), info)
        bytesWritten += data.size
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun cleanupLocked() {
        runCatching { if (started) muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { descriptor?.close() }
        muxer = null
        descriptor = null
        outputUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        legacyFile?.delete()
    }
}
