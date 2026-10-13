package com.neondodge.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SoundFx {

    var muted = false

    private class Seg(val f0: Float, val f1: Float, val dur: Float, val noise: Float = 0f)

    private val rate = 22050

    private val collectTrack = track(synth(listOf(Seg(900f, 1300f, 0.06f), Seg(1300f, 1800f, 0.09f))))
    private val hitTrack = track(synth(listOf(Seg(300f, 70f, 0.30f, 0.45f))))
    private val overTrack = track(
        synth(listOf(Seg(440f, 340f, 0.18f), Seg(340f, 240f, 0.18f), Seg(240f, 110f, 0.45f)))
    )
    private val startTrack = track(synth(listOf(Seg(400f, 800f, 0.10f), Seg(800f, 1200f, 0.14f))))

    private fun synth(segs: List<Seg>): ShortArray {
        var total = 0
        for (s in segs) total += (s.dur * rate).toInt()
        val out = ShortArray(total)
        var idx = 0
        var phase = 0.0
        for (s in segs) {
            val n = (s.dur * rate).toInt()
            for (i in 0 until n) {
                val t = i / n.toDouble()
                val f = s.f0 + (s.f1 - s.f0) * t
                phase += 2.0 * PI * f / rate
                val tone = sin(phase) + 0.3 * sin(2.0 * phase)
                val nz = Random.nextDouble() * 2.0 - 1.0
                val mix = tone * (1.0 - s.noise) + nz * s.noise
                val attack = minOf(1.0, i / (rate * 0.005))
                val env = attack * (1.0 - t)
                out[idx++] = (mix * env * 0.45 * 32767.0).toInt().toShort()
            }
        }
        return out
    }

    private fun track(data: ShortArray): AudioTrack? {
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t
        } catch (e: Exception) {
            null
        }
    }

    private fun play(t: AudioTrack?) {
        if (muted || t == null) return
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
            // تجاهل أي خطأ صوتي حتى لا تتوقف اللعبة
        }
    }

    fun collect() = play(collectTrack)
    fun hit() = play(hitTrack)
    fun over() = play(overTrack)
    fun start() = play(startTrack)

    fun release() {
        for (t in listOf(collectTrack, hitTrack, overTrack, startTrack)) {
            try {
                t?.release()
            } catch (e: Exception) {
            }
        }
    }
}
