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
    /** 現在フレームを組み立て中かどうか */
    private var assembling: Boolean = false

    /** バッファ/状態を初期化 */
    fun reset() {
        assembling = false
        buf.reset()
    }

    fun feed(packet: ByteArray) {
        var b = packet

        if (!assembling) {
            val soi = indexOfSOI(b)
            if (soi == -1) {
                // まだ開始マーカーがない → 次のパケットを待つ
                return
            }
            assembling = true
            buf.reset()
            if (soi > 0) b = b.copyOfRange(soi, b.size)
            Timber.i("REASM: start frame at SOI (offset=%d)", soi)
        } else {
            // 既に組み立て中に新しい SOI が来たら前フレームを破棄して再スタート
            val soi = indexOfSOI(b)
            if (soi != -1) {
                Timber.w("REASM: restart on new SOI (previous frame dropped)")
                buf.reset()
                assembling = true
                b = b.copyOfRange(soi, b.size)
                Timber.i("REASM: start frame at SOI (offset=%d)", soi)
            }
        }

        buf.write(b)

        // 安全のため最大サイズを超えたら捨てる
        if (buf.size() > maxFrameBytes) {
            Timber.w("REASM: frame too large (%d) → reset", buf.size())
            reset()
            return
        }

        val all = buf.toByteArray()
        val eoi = indexOfEOI(all)
        if (eoi != -1) {
            // 1 フレーム確定
            val frame = all.copyOfRange(0, eoi + 2)
            Timber.i("REASM: complete frame len=%d", frame.size)
            onFrame(frame)

            // 余剰データ（次フレームの先頭など）があれば再帰的に処理
            val trailingLen = all.size - (eoi + 2)
            reset()
            if (trailingLen > 0) {
                val trailing = all.copyOfRange(eoi + 2, all.size)
                feed(trailing)
            }
        }
    }

    private fun indexOfSOI(a: ByteArray): Int {
        for (i in 0 until a.size - 1) {
            if (a[i] == 0xFF.toByte() && a[i + 1] == 0xD8.toByte()) return i
        }
        return -1
    }

    private fun indexOfEOI(a: ByteArray): Int {
        for (i in 0 until a.size - 1) {
            if (a[i] == 0xFF.toByte() && a[i + 1] == 0xD9.toByte()) return i
        }
        return -1
    }
}