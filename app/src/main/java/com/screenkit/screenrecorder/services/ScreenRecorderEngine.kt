package com.screenkit.screenrecorder.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import com.screenkit.screenrecorder.data.CaptureAudioMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 + AAC screen recorder built directly on MediaCodec and MediaMuxer.
 *
 * MediaRecorder cannot mix an audio stream it did not create, and AudioPlaybackCapture (the only
 * supported way to record the sound a phone is playing) hands back raw PCM. So when the user asks
 * for device audio, or device audio plus mic, ScreenKit records the display into an encoder input
 * surface and encodes the mixed PCM itself. Microphone-only and silent recordings keep using the
 * simpler MediaRecorder path in ScreenCaptureService.
 */
internal class ScreenRecorderEngine(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val audioMode: CaptureAudioMode,
    private val projection: MediaProjection,
    private val descriptor: ParcelFileDescriptor?,
    private val file: File?,
    private val onAudioUnavailable: (String) -> Unit,
) {
    private class PcmChunk(val samples: ShortArray, val capturedAtNanos: Long)

    private class CaptureSource(
        val record: AudioRecord,
        val queue: LinkedBlockingQueue<PcmChunk>,
        val thread: Thread,
    )

    /** MediaMuxer only accepts direct buffers, so a default encoder buffer is copied here. */
    private class StashedSample(val buffer: ByteBuffer, val size: Int, val ptsUs: Long, val flags: Int)

    private val captureActive = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val muxerLock = Any()
    private val audioSources = mutableListOf<CaptureSource>()
    private val videoStash = ArrayDeque<StashedSample>()
    private val audioStash = ArrayDeque<StashedSample>()

    @Volatile private var videoCodec: MediaCodec? = null
    @Volatile private var audioCodec: MediaCodec? = null
    @Volatile private var audioAbandoned = false
    @Volatile private var videoTrackIndex = -1
    @Volatile private var audioTrackIndex = -1
    @Volatile private var muxerStarted = false
    @Volatile private var wroteVideoSample = false
    @Volatile private var lastAudioInputPtsUs = 0L
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoDrain: Thread? = null
    private var audioDrain: Thread? = null
    private var mixerThread: Thread? = null
    private var baseUs = 0L
    private var sessionStartElapsed = 0L
    private var drainDeadline = 0L
    private var clockMode = CLOCK_UNKNOWN
    private var firstVideoRawUs = -1L
    private var firstAudioRawUs = -1L

    /** Surface the caller feeds the display into. */
    val surface: Surface get() = inputSurface ?: error("The encoder surface is not ready.")

    fun start() {
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val video = MediaCodec.createEncoderByType(VIDEO_MIME)
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val createdSurface = video.createInputSurface()
        video.start()
        videoCodec = video
        inputSurface = createdSurface

        muxer = createMuxer() ?: throw IllegalStateException("Could not open the recording output.")
        baseUs = System.nanoTime() / 1_000L
        sessionStartElapsed = SystemClock.elapsedRealtime()

        startAudioCapture()

        captureActive.set(true)
        videoDrain = drainLoop(video, isVideo = true).also { it.start() }
        audioCodec?.let { codec -> audioDrain = drainLoop(codec, isVideo = false).also { it.start() } }
        if (audioSources.isNotEmpty()) {
            mixerThread = mixerLoop().also { it.start() }
        }
    }

    /**
     * Finishes the file. Returns true when a playable recording was written; false when the muxer
     * never received enough data (for example a recording stopped a moment after it began).
     */
    fun stop(): Boolean {
        captureActive.set(false)
        audioSources.forEach { source -> runCatching { source.record.stop() } }
        audioSources.forEach { source -> runCatching { source.thread.join(400L) } }
        mixerThread?.let { thread -> runCatching { thread.join(700L) } }

        drainDeadline = SystemClock.elapsedRealtime() + 2_500L
        signalAudioEndOfStream()
        runCatching { videoCodec?.signalEndOfInputStream() }
        videoDrain?.let { thread -> runCatching { thread.join(2_600L) } }
        audioDrain?.let { thread -> runCatching { thread.join(2_600L) } }

        var finalized = false
        synchronized(muxerLock) {
            val current = muxer
            if (muxerStarted && current != null) {
                finalized = runCatching { current.stop(); true }.getOrDefault(false)
                muxerStarted = false
            }
        }
        releaseResources()
        return finalized && wroteVideoSample
    }

    /** Releases everything without finalizing; used when a session fails or is cancelled. */
    fun abort() {
        captureActive.set(false)
        audioSources.forEach { source -> runCatching { source.record.stop() } }
        drainDeadline = SystemClock.elapsedRealtime()
        videoDrain?.let { thread -> runCatching { thread.join(300L) } }
        audioDrain?.let { thread -> runCatching { thread.join(300L) } }
        mixerThread?.let { thread -> runCatching { thread.join(300L) } }
        releaseResources()
    }

    // ------------------------------------------------------------------ setup

    private fun createMuxer(): MediaMuxer? {
        val target = descriptor
        if (target != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            return runCatching {
                MediaMuxer(target.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }.getOrNull()
        }
        val path = file ?: return null
        path.parentFile?.mkdirs()
        return runCatching {
            MediaMuxer(path.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }.getOrNull()
    }

    private fun startAudioCapture() {
        if (!audioMode.usesMicrophone && !audioMode.usesDeviceAudio) return
        if (audioMode.usesDeviceAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onAudioUnavailable("Device audio needs Android 10 or newer.")
        }

        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1_024)
        }
        val codec = runCatching {
            MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull()
        if (codec == null) {
            onAudioUnavailable("No audio encoder is available; this recording is silent.")
            return
        }

        val wantsMicrophone = audioMode.usesMicrophone
        val wantsDevice = audioMode.usesDeviceAudio
        val canUseDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val microphone = if (wantsMicrophone) startSource(openMicrophone()) else null
        val device = if (wantsDevice && canUseDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSource(openDeviceAudio())
        } else {
            null
        }
        if (wantsMicrophone && microphone == null) {
            onAudioUnavailable("The microphone could not be opened, so it is missing from this recording.")
        }
        if (wantsDevice && device == null) {
            onAudioUnavailable("Device audio was refused: the app on screen blocks capture, or audio was not included in Android's screen-share consent.")
        }
        if (microphone == null && device == null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (wantsMicrophone || wantsDevice) {
                onAudioUnavailable("No audio source started, so this recording is silent.")
            }
            return
        }
        audioCodec = codec
    }

    private fun startSource(record: AudioRecord?): CaptureSource? {
        if (record == null) return null
        val started = runCatching {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (!started) {
            runCatching { record.release() }
            return null
        }
        val queue = LinkedBlockingQueue<PcmChunk>(48)
        val thread = Thread({ readSource(record, queue) }, "ScreenKitAudioReader").apply { isDaemon = true }
        val source = CaptureSource(record, queue, thread)
        audioSources += source
        thread.start()
        return source
    }

    private fun openMicrophone(): AudioRecord? = runCatching {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(pcmFormat())
            .setBufferSizeInBytes(maxOf(minimum, 8 * 1_024) * 2)
            .build()
    }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openDeviceAudio(): AudioRecord? = runCatching {
        val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        AudioRecord.Builder()
            .setAudioFormat(pcmFormat())
            .setAudioPlaybackCaptureConfig(configuration)
            .setBufferSizeInBytes(maxOf(minimum, 16 * 1_024) * 2)
            .build()
    }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: null

    private fun pcmFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(CHANNEL_MASK)
        .build()

    // ------------------------------------------------------------------ audio threads

    private fun readSource(record: AudioRecord, queue: LinkedBlockingQueue<PcmChunk>) {
        val samples = ShortArray(SAMPLES_PER_CHUNK)
        while (captureActive.get()) {
            val started = System.nanoTime()
            val read = runCatching { record.read(samples, 0, samples.size) }.getOrDefault(-1)
            val finished = System.nanoTime()
            if (read < 0) break
            if (read == 0) continue
            val chunk = PcmChunk(samples.copyOf(read), (started + finished) / 2)
            if (!queue.offer(chunk)) {
                queue.poll()
                queue.offer(chunk)
            }
        }
    }

    private fun mixerLoop(): Thread = Thread({
        val mixed = ShortArray(SAMPLES_PER_CHUNK)
        val sources = audioSources.toList()
        while (captureActive.get() && !audioAbandoned) {
            val chunks = arrayOfNulls<PcmChunk>(sources.size)
            var anyChunk = false
            for (index in sources.indices) {
                val chunk = sources[index].queue.poll()
                if (chunk != null) {
                    chunks[index] = chunk
                    anyChunk = true
                }
            }
            if (!anyChunk) {
                runCatching { Thread.sleep(4L) }
                continue
            }
            var length = 0
            var capturedAtNanos = Long.MAX_VALUE
            for (chunk in chunks) {
                if (chunk == null) continue
                if (chunk.samples.size > length) length = chunk.samples.size
                if (chunk.capturedAtNanos < capturedAtNanos) capturedAtNanos = chunk.capturedAtNanos
            }
            if (length <= 0) continue
            Arrays.fill(mixed, 0, length, 0.toShort())
            for (chunk in chunks) {
                if (chunk == null) continue
                val samples = chunk.samples
                for (index in samples.indices) {
                    val sum = mixed[index] + samples[index]
                    mixed[index] = when {
                        sum > Short.MAX_VALUE -> Short.MAX_VALUE
                        sum < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> sum.toShort()
                    }
                }
            }
            feedAudio(mixed, length, capturedAtNanos / 1_000L)
        }
    }, "ScreenKitAudioMixer").apply { isDaemon = true }

    private fun feedAudio(samples: ShortArray, length: Int, rawPtsUs: Long) {
        val codec = audioCodec ?: return
        if (audioAbandoned) return
        val bytes = ByteArray(length * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, length)
        var offset = 0
        var attempts = 0
        while (captureActive.get() && offset < bytes.size) {
            val index = runCatching { codec.dequeueInputBuffer(20_000L) }.getOrDefault(-1)
            if (index < 0) {
                attempts++
                if (attempts > 120 || audioAbandoned) return
                continue
            }
            val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return
            buffer.clear()
            val size = minOf(buffer.remaining(), bytes.size - offset)
            buffer.put(bytes, offset, size)
            lastAudioInputPtsUs = rawPtsUs
            runCatching { codec.queueInputBuffer(index, 0, size, rawPtsUs, 0) }
            offset += size
        }
    }

    private fun signalAudioEndOfStream() {
        val codec = audioCodec ?: return
        if (audioAbandoned) return
        runCatching {
            val index = codec.dequeueInputBuffer(80_000L)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, lastAudioInputPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    // ------------------------------------------------------------------ encoder threads

    private fun drainLoop(codec: MediaCodec, isVideo: Boolean): Thread = Thread({
        val info = MediaCodec.BufferInfo()
        var endOfStream = false
        while (!endOfStream) {
            if (drainDeadline > 0L && SystemClock.elapsedRealtime() > drainDeadline) break
            val index = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }
                .getOrDefault(Int.MIN_VALUE)
            when {
                index == Int.MIN_VALUE -> break
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (isVideo) nudgeAudioStart()
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> registerTrack(codec, isVideo)
                index >= 0 -> {
                    val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        handleSample(isVideo, buffer, info)
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOfStream = true
                }
            }
        }
    }, if (isVideo) "ScreenKitVideoEncode" else "ScreenKitAudioEncode").apply { isDaemon = true }

    private fun registerTrack(codec: MediaCodec, isVideo: Boolean) {
        val format = runCatching { codec.outputFormat }.getOrNull() ?: return
        synchronized(muxerLock) {
            val current = muxer ?: return
            if (muxerStarted) return
            if (isVideo) {
                if (videoTrackIndex < 0) {
                    videoTrackIndex = runCatching { current.addTrack(format) }.getOrDefault(-1)
                }
            } else {
                if (audioTrackIndex < 0) {
                    audioTrackIndex = runCatching { current.addTrack(format) }.getOrDefault(-1)
                }
            }
            maybeStartMuxerLocked()
        }
    }

    /** Some encoders only publish their output format once the first buffer has been produced. */
    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val current = muxer ?: return
        if (videoTrackIndex < 0) return
        val audioExpected = audioCodec != null && !audioAbandoned
        if (audioExpected && audioTrackIndex < 0) return
        val started = runCatching {
            current.start()
            true
        }.getOrDefault(false)
        if (!started) return
        muxerStarted = true
        flushStashLocked(current)
    }

    /** Keeps a stalled audio encoder from holding back a video-only recording. */
    private fun nudgeAudioStart() {
        val codec = audioCodec ?: return
        if (audioAbandoned || videoTrackIndex < 0) return
        if (SystemClock.elapsedRealtime() - sessionStartElapsed < 2_500L) return
        audioAbandoned = true
        onAudioUnavailable("Audio did not start in time; this recording has video only.")
        runCatching { codec.stop() }
        runCatching { codec.release() }
        audioCodec = null
        synchronized(muxerLock) { maybeStartMuxerLocked() }
    }

    private fun handleSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        val ptsUs = if (isVideo) videoPtsUs(info.presentationTimeUs) else audioPtsUs(info.presentationTimeUs)
        synchronized(muxerLock) {
            val current = muxer ?: return
            if (!muxerStarted || track < 0) {
                val stash = if (isVideo) videoStash else audioStash
                val limit = if (isVideo) 24 else 400
                if (stash.size >= limit) stash.removeFirst()
                val copy = ByteBuffer.allocateDirect(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                copy.put(buffer)
                copy.flip()
                stash.addLast(StashedSample(copy, info.size, ptsUs, info.flags))
                return
            }
            val out = MediaCodec.BufferInfo().apply { set(0, info.size, ptsUs, info.flags) }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            runCatching { current.writeSampleData(track, buffer, out) }
            if (isVideo) wroteVideoSample = true
        }
    }

    private fun flushStashLocked(current: MediaMuxer) {
        while (videoStash.isNotEmpty()) {
            writeStashedLocked(current, videoTrackIndex, videoStash.removeFirst())
        }
        while (audioStash.isNotEmpty()) {
            writeStashedLocked(current, audioTrackIndex, audioStash.removeFirst())
        }
    }

    private fun writeStashedLocked(current: MediaMuxer, track: Int, sample: StashedSample) {
        if (track < 0 || sample.size <= 0) return
        val buffer = sample.buffer
        buffer.position(0)
        buffer.limit(sample.size)
        val out = MediaCodec.BufferInfo().apply { set(0, sample.size, sample.ptsUs, sample.flags) }
        runCatching { current.writeSampleData(track, buffer, out) }
        if (track == videoTrackIndex) wroteVideoSample = true
    }

    // ------------------------------------------------------------------ timestamps

    /**
     * Surface-input encoders stamp frames with CLOCK_MONOTONIC microseconds, the same clock as
     * System.nanoTime(). Both tracks are therefore offset from one base so they stay in sync. If a
     * device uses a different clock, each track is anchored to its own first sample instead.
     */
    private fun videoPtsUs(rawUs: Long): Long {
        if (clockMode == CLOCK_UNKNOWN) {
            val delta = rawUs - baseUs
            clockMode = if (delta in -1_000_000L..90_000_000L) CLOCK_SHARED else CLOCK_ANCHORED
        }
        if (firstVideoRawUs < 0L) firstVideoRawUs = rawUs
        val value = if (clockMode == CLOCK_SHARED) rawUs - baseUs else rawUs - firstVideoRawUs
        val clamped = value.coerceAtLeast(0L)
        val next = if (clamped > lastVideoPtsUs) clamped else lastVideoPtsUs + 1L
        lastVideoPtsUs = next
        return next
    }

    private fun audioPtsUs(rawUs: Long): Long {
        if (firstAudioRawUs < 0L) firstAudioRawUs = rawUs
        val value = if (clockMode == CLOCK_ANCHORED) rawUs - firstAudioRawUs else rawUs - baseUs
        val clamped = value.coerceAtLeast(0L)
        val next = if (clamped > lastAudioPtsUs) clamped else lastAudioPtsUs + 1L
        lastAudioPtsUs = next
        return next
    }

    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    // ------------------------------------------------------------------ teardown

    private fun releaseResources() {
        if (!released.compareAndSet(false, true)) return
        audioSources.forEach { source -> runCatching { source.record.release() } }
        audioSources.clear()
        val audio = audioCodec
        if (audio != null) {
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
        audioCodec = null
        val video = videoCodec
        if (video != null) {
            runCatching { video.stop() }
            runCatching { video.release() }
        }
        videoCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        synchronized(muxerLock) {
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        videoStash.clear()
        audioStash.clear()
    }

    private companion object {
        const val VIDEO_MIME = "video/avc"
        const val AUDIO_MIME = "audio/mp4a-latm"
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val SAMPLES_PER_CHUNK = 960
        const val CLOCK_UNKNOWN = 0
        const val CLOCK_SHARED = 1
        const val CLOCK_ANCHORED = 2
    }
}
