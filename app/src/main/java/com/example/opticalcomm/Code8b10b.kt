package com.example.opticalcomm

/**
 * 8b/10b ラインコード(Widmer-Franaszek)。データ D.x.y と、同期用のカンマ K28.5 のみ対応。
 * 符号は先頭ビット(a)から送信順の文字列("0"/"1" 10文字)で扱う。
 *
 * RD(running disparity)は `true` = RD+、`false` = RD−。送信開始時は RD−。
 */
object Code8b10b {
    class Symbol(val bits: String, val rdAfter: Boolean)

    /** 5b/6b: x = 0..31、"RD−用/RD+用"(同じなら1つだけ)。 */
    private val T6 = arrayOf(
        "100111/011000", "011101/100010", "101101/010010", "110001", "110101/001010",
        "101001", "011001", "111000/000111", "111001/000110", "100101",
        "010101", "110100", "001101", "101100", "011100",
        "010111/101000", "011011/100100", "100011", "010011", "110010",
        "001011", "101010", "011010", "111010/000101", "110011/001100",
        "100110", "010110", "110110/001001", "001110", "101110/010001",
        "011110/100001", "101011/010100",
    )

    /** 3b/4b: y = 0..6 (D.x.7 は別扱い)。 */
    private val T4 = arrayOf("1011/0100", "1001", "0101", "1100/0011", "1101/0010", "1010", "0110")
    private const val P7 = "1110/0001"
    private const val A7 = "0111/1000"

    /** K28.y の 6b(RD−用/RD+用)と、6b 後の RD に応じた K28.5 の 4b。 */
    private const val K28_6B = "001111/110000"
    private const val K285_4B = "0101/1010"

    private fun pick(entry: String, rdPlus: Boolean): String {
        val i = entry.indexOf('/')
        return if (i < 0) entry else if (rdPlus) entry.substring(i + 1) else entry.substring(0, i)
    }

    /** 符号語の1の数から RD を更新する(1が過半→RD+、過少→RD−、同数→不変)。 */
    private fun nextRd(code: String, rd: Boolean): Boolean {
        val ones = code.count { it == '1' }
        return when {
            ones * 2 > code.length -> true
            ones * 2 < code.length -> false
            else -> rd
        }
    }

    fun encodeData(byte: Int, rd: Boolean): Symbol {
        require(byte in 0..255)
        val x = byte and 0x1F
        val y = byte shr 5
        val s6 = pick(T6[x], rd)
        val rd1 = nextRd(s6, rd)
        val entry4 = when {
            y < 7 -> T4[y]
            (!rd1 && x in intArrayOf(17, 18, 20)) || (rd1 && x in intArrayOf(11, 13, 14)) -> A7
            else -> P7
        }
        val s4 = pick(entry4, rd1)
        return Symbol(s6 + s4, nextRd(s4, rd1))
    }

    fun encodeComma(rd: Boolean): Symbol {
        val s6 = pick(K28_6B, rd)
        val rd1 = nextRd(s6, rd)
        val s4 = pick(K285_4B, rd1)
        return Symbol(s6 + s4, nextRd(s4, rd1))
    }

    /** RD− 開始で K28.5 を2つ続けた20ビット。受信側の同期パターン。 */
    val PREAMBLE: String = encodeComma(false).let { a -> a.bits + encodeComma(a.rdAfter).bits }

    private val commaCodes = setOf(encodeComma(false).bits, encodeComma(true).bits)

    /** 符号語 → データバイト。全バイト × 両RD から作る。 */
    private val dataByCode: Map<String, Int> = buildMap {
        for (b in 0..255) for (rd in booleanArrayOf(false, true)) {
            val code = encodeData(b, rd).bits
            check(put(code, b)?.let { it != b } != true) { "8b/10b table collision: $code" }
        }
    }

    sealed interface Decoded {
        data class Data(val byte: Int, val rdAfter: Boolean) : Decoded
        data class Error(val reason: String) : Decoded
    }

    fun decode(code: String, rd: Boolean): Decoded {
        if (code in commaCodes) return Decoded.Error("想定外のK28.5")
        val byte = dataByCode[code] ?: return Decoded.Error("無効な10bit符号($code)")
        val expected = encodeData(byte, rd)
        if (expected.bits != code) return Decoded.Error("ディスパリティ違反($code)")
        return Decoded.Data(byte, expected.rdAfter)
    }
}
