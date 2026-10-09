package com.example.syntaxcam

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Records the *filtered* live frames (what you see in the preview) plus microphone audio into an MP4.
 * Frames are drawn onto the H.264 encoder's input surface; audio goes through an AAC encoder; a MediaMuxer joins them.
 */
class VideoRecorder(private val dir: File, private val wantAudio: Boolean) {
    private val sampleRate = 44100
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var audioRec: AudioRecord? = null
    private var outFile: File? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private var tracksNeeded = 1
    private var tracksAdded = 0
    private var vTrack = -1
    private var aTrack = -1
    @Volatile private var muxerStarted = false
    @Volatile private var running = false
    @Volatile private var lastFrameUs = 0L
    @Volatile private var frames = 0
    private var startUs = 0L
    private var lastV = -1L
    private var lastA = -1L

    fun start(frameW: Int, frameH: Int): Boolean {
        return try {
            val w = 720
            val h = ((w.toFloat() * frameH / frameW) / 16f).roundToInt().coerceAtLeast(1) * 16
            dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val f = File(dir, "rec_${System.currentTimeMillis()}.mp4")
            outFile = f
            muxer = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val ve = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = ve.createInputSurface()
            ve.start()
            vEnc = ve

            val audio = wantAudio && setupAudio()
            tracksNeeded = if (audio) 2 else 1
            startUs = System.nanoTime() / 1000
            running = true
            vThread = thread(name = "syntax-video") { videoLoop() }
            if (audio) aThread = thread(name = "syntax-audio") { audioLoop() }
            true
        } catch (e: Throwable) {
            release(); false
        }
    }

    private fun setupAudio(): Boolean = try {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            false
        } else {
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); false
            } else {
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ae.start()
                audioRec = rec; aEnc = ae
                true
            }
        }
    } catch (e: Throwable) { false }

    /** Called for every processed preview frame while recording (~30 fps max). */
    fun addFrame(bmp: Bitmap) {
        if (!running) return
        val now = System.nanoTime() / 1000
        if (now - lastFrameUs < 28_000) return
        lastFrameUs = now
        val s = surface ?: return
        try {
            val c = s.lockCanvas(null)
            c.drawBitmap(bmp, null, dst, paint)
            s.unlockCanvasAndPost(c)
            frames++
        } catch (_: Throwable) { }
    }

    /** Stops, finalises the file and returns it (or null if nothing usable was recorded). */
    fun stop(): File? {
        if (!running) { release(); return null }
        running = false
        runCatching { vEnc?.signalEndOfInputStream() }
        runCatching { aThread?.join(4000) }
        runCatching { vThread?.join(4000) }
        val ok = muxerStarted && frames > 0
        runCatching { if (muxerStarted) muxer?.stop() }
        release()
        return if (ok) outFile else null
    }

    private fun videoLoop() {
        val enc = vEnc ?: return
        try { while (!drain(enc, true, 10_000)) { } } catch (_: Throwable) { }
    }

    private fun audioLoop() {
        val enc = aEnc ?: return
        val rec = audioRec ?: return
        val buf = ByteArray(4096)
        var samples = 0L
        try {
            rec.startRecording()
            val t0 = System.nanoTime() / 1000
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = enc.getInputBuffer(idx)!!
                        ib.clear(); ib.put(buf, 0, n)
                        enc.queueInputBuffer(idx, 0, n, t0 + samples * 1_000_000L / sampleRate, 0)
                        samples += n / 2
                    }
                }
                drain(enc, false, 0)
            }
            val idx = enc.dequeueInputBuffer(100_000)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, t0 + samples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline && !drain(enc, false, 20_000)) { }
        } catch (e: Throwable) {
            audioFailed()
        } finally {
            runCatching { rec.stop() }
        }
    }

    private fun drain(enc: MediaCodec, video: Boolean, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = enc.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(enc.outputFormat, video)
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && buf != null) writeSample(buf, info, video)
                    enc.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
            }
        }
    }

    private fun onFormat(fmt: MediaFormat, video: Boolean) {
        synchronized(lock) {
            val m = muxer ?: return
            if (video) vTrack = m.addTrack(fmt) else aTrack = m.addTrack(fmt)
            tracksAdded++
            if (tracksAdded >= tracksNeeded && !muxerStarted) { m.start(); muxerStarted = true; ready.countDown() }
        }
    }

    private fun audioFailed() {
        synchronized(lock) {
            tracksNeeded = 1
            val m = muxer
            if (!muxerStarted && m != null && tracksAdded >= tracksNeeded) { m.start(); muxerStarted = true; ready.countDown() }
        }
    }

    private fun writeSample(buf: ByteBuffer, info: MediaCodec.BufferInfo, video: Boolean) {
        if (!muxerStarted && !ready.await(3, TimeUnit.SECONDS)) return
        var pts = info.presentationTimeUs - startUs
        if (pts < 0) pts = 0
        val last = if (video) lastV else lastA
        if (pts <= last) pts = last + 1
        if (video) lastV = pts else lastA = pts
        info.presentationTimeUs = pts
        buf.position(info.offset); buf.limit(info.offset + info.size)
        synchronized(lock) {
            try { muxer?.writeSampleData(if (video) vTrack else aTrack, buf, info) } catch (_: Throwable) { }
        }
    }

    private fun release() {
        runCatching { vEnc?.stop() }; runCatching { vEnc?.release() }
        runCatching { aEnc?.stop() }; runCatching { aEnc?.release() }
        runCatching { audioRec?.release() }
        runCatching { surface?.release() }
        runCatching { muxer?.release() }
        vEnc = null; aEnc = null; audioRec = null; surface = null; muxer = null
    }
}
