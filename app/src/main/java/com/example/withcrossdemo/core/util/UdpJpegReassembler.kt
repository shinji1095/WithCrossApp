package com.example.withcrossdemo.core.util

import java.io.ByteArrayOutputStream
import timber.log.Timber

/**
 * UDPで分割到着したJPEG断片を1フレームに再構築するユーティリティ。
 * 前提: 送信側は「同一フレーム中で順序は概ね保たれる」想定。
 * - 新しいSOI(FFD8)が途中で来たら、前フレームは破棄してリスタート
 * - EOI(FFD9)を見つけたら1枚確定。余りがあれば再帰的に処理
 */
// UdpJpegReassembler.kt
class UdpJpegReassembler(
    private val onFrame: (ByteArray) -> Unit,
    private val maxFrameBytes: Int = 1_500_000
) {
    private var buf = ByteArrayOutputStream(256 * 1024)

    fun reset() { buf.reset() }

    fun feed(packet: ByteArray) {
        if (packet.isEmpty()) return
        buf.write(packet)

        // ストリームとして先頭SOI〜EOIを順に抜き出す
        while (true) {
            val all = buf.toByteArray()

            // 1) SOI の位置までスキップ
            val soi = indexOfSOI(all)
            if (soi == -1) {
                // 先頭にゴミが溜まりすぎないようガード
                if (all.size > maxFrameBytes) { Timber.w("UDP reasm: purge overflow"); reset() }
                return
            }
            if (soi > 0) {
                val trailing = all.copyOfRange(soi, all.size)
                buf.reset(); buf.write(trailing)
            }

            // 2) EOI を探す（SOI以降）
            val a2  = buf.toByteArray()
            val eoi = indexOfEOI(a2)
            if (eoi == -1) {
                if (a2.size > maxFrameBytes) { Timber.w("UDP reasm: frame too large (%d) → reset", a2.size); reset() }
                return
            }

            // 3) 1枚確定
            val frame = a2.copyOfRange(0, eoi + 2)
            onFrame(frame)

            // 4) 余りがあれば次ループで続けて処理
            val rest = a2.size - (eoi + 2)
            buf.reset()
            if (rest > 0) {
                buf.write(a2, eoi + 2, rest)
                continue
            }
            return
        }
    }

    private fun indexOfSOI(a: ByteArray): Int {
        for (i in 0 until a.size - 1)
            if (a[i] == 0xFF.toByte() && a[i + 1] == 0xD8.toByte()) return i
        return -1
    }
    private fun indexOfEOI(a: ByteArray): Int {
        for (i in 0 until a.size - 1)
            if (a[i] == 0xFF.toByte() && a[i + 1] == 0xD9.toByte()) return i
        return -1
    }
}
