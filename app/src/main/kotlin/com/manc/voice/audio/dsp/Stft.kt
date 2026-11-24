package com.manc.voice.audio.dsp

/**
 * Streaming STFT/ISTFT with 50% overlap and sqrt-Hann windows.
 * frameSize must be a power of two; hop = frameSize/2.
 * Feed [hop] new samples per call; get [hop] reconstructed samples out.
 */
class Stft(val frameSize: Int) {
    val hop = frameSize / 2
    val bins = frameSize / 2 + 1
    private val win = Window.sqrtHann(frameSize)
    private val fft = Fft(frameSize)
    private val inBuf = DoubleArray(frameSize)      // sliding analysis buffer
    private val olaBuf = DoubleArray(frameSize)     // overlap-add synthesis buffer
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)

    val mag = DoubleArray(bins)
    private val phRe = DoubleArray(bins)
    private val phIm = DoubleArray(bins)

    /** Push hop samples, compute spectrum into mag/phase. Returns true when a frame is ready. */
    fun analyze(input: FloatArray, offset: Int) {
        // shift left by hop, append new hop
        System.arraycopy(inBuf, hop, inBuf, 0, frameSize - hop)
        for (i in 0 until hop) inBuf[frameSize - hop + i] = input[offset + i].toDouble()
        for (i in 0 until frameSize) { re[i] = inBuf[i] * win[i]; im[i] = 0.0 }
        fft.transform(re, im, false)
        for (k in 0 until bins) {
            phRe[k] = re[k]; phIm[k] = im[k]
            mag[k] = Math.hypot(re[k], im[k])
        }
    }

    /** Apply a per-bin real gain to the stored spectrum and overlap-add hop samples into out. */
    fun synthesize(gain: DoubleArray, out: FloatArray, offset: Int) {
        for (k in 0 until bins) {
            val g = gain[k]
            re[k] = phRe[k] * g
            im[k] = phIm[k] * g
        }
        // Hermitian symmetry for real output
        for (k in 1 until bins - 1) {
            re[frameSize - k] = re[k]
            im[frameSize - k] = -im[k]
        }
        fft.transform(re, im, true)
        for (i in 0 until frameSize) olaBuf[i] += re[i] * win[i]
        for (i in 0 until hop) out[offset + i] = olaBuf[i].toFloat()
        System.arraycopy(olaBuf, hop, olaBuf, 0, frameSize - hop)
        for (i in frameSize - hop until frameSize) olaBuf[i] = 0.0
    }
}
