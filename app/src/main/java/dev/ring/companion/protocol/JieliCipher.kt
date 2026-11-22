package dev.ring.companion.protocol

/**
 * The 128-bit block cipher used by Jieli's RCSP authentication handshake.
 *
 * Block 16 bytes, key 16 bytes, 8 rounds. Each round:
 *  1. optional pre-mix with the original input (second pass, round 2 only)
 *  2. mix with first half of the round key (XOR at mask positions, ADD elsewhere)
 *  3. two S-boxes split by byte position
 *  4. mix with second half of the round key (direction flipped)
 *  5. pair-wise linear diffusion
 * A final 16-byte tweak follows the 8 rounds.
 */
object JieliCipher {

    val DEFAULT_LINK_KEY: ByteArray = hex("06775f87918dd423005df1d8cf0c142b")
    val DEFAULT_SALT: ByteArray = hex("112233332211")

    private val RCON = hexInts(
        "64ac285ac9b337c50a10b7a3bab197463d05dc666ef69af80d589567c6aaabec" +
        "a0689b96d4ebbf434936e96a89d8c38a946399bc7bbec122bb5c71d51f92575d" +
        "8f44411d51e64017fbfd193234b8612aca236fda39f7a2017fd631e7de8004dd" +
        "2c5982afa8e00fcda1123e30d11cd03a33722e4f9002130675ce87c2efb2ad7d" +
        "3815e1529f7a6c2f27c4e281a9cf8dc0d7dfff6076148c5e5509e408c74220fc" +
        "d25091d94c629ee8b9a6f91a00210bfa359c4e4b6948cb0ec8a45bea8407b418" +
        "f4ae6bdba7cc3f8b4a0c3c25e5544d4583ed11f0b05393f27426b59d6d7cf32d" +
        "f156247e471b86bd708e1e3b731603b6ac285ac9b337c50a10b7a3bab1974688"
    )

    private val SBOX1 = hexInts(
        "012de293be4515ae780387a4b838cf3f08670994eb26a86bbd18341bbbbf72f7" +
        "4035489c512f3b55e3c09fd8d3f38db1ffa73edc8677d7a611fbf4ba92916483" +
        "f133efda2cb5b22b88d199cb8c841d14819771ca5fa38b573c82c4525c1ce8a0" +
        "04b4854af61354b6df0c1a8edee039fc209b244ea9989eabf260d06ceafac7d9" +
        "00d41f6e43bcec5389fe7a5d49c932c2f99af86d16db599644e9cde646428f0a" +
        "c1ccb965b0d2c6ac1e4162292e0e7450025ac3257b8a2a5bf0060d476f709d7e" +
        "10ce1227d54c4fd679306836757de4ed806a9037a25e76aac57f3dafa5e51961" +
        "fd4d7cb70beead4b22f5e7732321c805e166ddb3586963560fa1319517073a28"
    )

    private val SBOX2 = hexInts(
        "8000b00960efb9fd10129fe469baadf8c038c2654f0694fc19de6a1b5d4ea882" +
        "70ede8ec72b315c3ffabb6474401ac25c9fa8e411a21cbd30d6efe2658da320f" +
        "20a99d8498059cbb228c63e7c5e173c6af245b876627f757f496b1b75c8bd554" +
        "79dfaaf63ea3f111caf5d1177b9383bcbd521eebaeccd63508c88ab4e2cdbfd9" +
        "d050593f4d62340a4888b5564c2e6b9ed23d3c0313fb9751754a917123be762a" +
        "5ff9d4550bdc37311674d777a7e607dba42f46f3614567e30ca23b1c8518041d" +
        "29a08fb25ad8a67eee8d534ba19ac10e7a49a52c81c4c7362b7f439533f26c68" +
        "6df00228cedd9bea5e997c1486cfe542b840782d3ae9641f92907d396fe08930"
    )

    /** Bytes 0,3,4,7,8,11,12,15 use XOR (and SBOX1); the rest use ADD (and SBOX2). */
    private const val XOR_MASK = 0x9999

    private val ROUND2_ADD = mapOf(0 to -0x17, 2 to -0x21, 4 to -0x4d, 6 to -0x6b,
                                   9 to -0x1b, 11 to -0x3f, 13 to -0x59, 15 to -0x7d)
    private val ROUND2_XOR = mapOf(1 to 0xe5, 3 to 0xc1, 5 to 0xa7, 7 to 0x83,
                                   8 to 0xe9, 10 to 0xdf, 12 to 0xb3, 14 to 0x95)

    /** Encrypt a 16-byte challenge. Returns 16 bytes. */
    fun encryptRandom(
        random16: ByteArray,
        key: ByteArray = DEFAULT_LINK_KEY,
        salt: ByteArray = DEFAULT_SALT,
    ): ByteArray {
        require(random16.size == 16) { "random must be 16 bytes" }
        require(key.size == 16) { "key must be 16 bytes" }
        require(salt.size == 6) { "salt must be 6 bytes" }

        val input = IntArray(16) { random16[it].toInt() and 0xFF }
        val k = IntArray(16) { key[it].toInt() and 0xFF }

        val out = encryptBlock(input, keySchedule(k), mode = 0, original = null)
        for (i in 0 until 16) {
            out[i] = ((salt[i % 6].toInt() and 0xFF) + (out[i] xor input[i])) and 0xFF
        }

        val modKey = IntArray(16) { i ->
            val add = ROUND2_ADD[i]
            if (add != null) (k[i] + add) and 0xFF else k[i] xor ROUND2_XOR.getValue(i)
        }
        val result = encryptBlock(out, keySchedule(modKey), mode = 1, original = out.copyOf())
        return ByteArray(16) { result[it].toByte() }
    }

    // ---------------------------------------------------------------- internals

    private fun isXor(i: Int) = ((1 shl i) and XOR_MASK) != 0

    private fun rotl3(b: Int) = ((b shl 3) or (b ushr 5)) and 0xFF

    private fun keySchedule(key: IntArray): IntArray {
        val sched = IntArray(272)
        for (i in 0 until 16) sched[i] = key[i]
        val kbuf = IntArray(17)
        var x = 0
        for (i in 0 until 16) {
            kbuf[i] = key[i]
            x = x xor key[i]
        }
        kbuf[16] = x
        for (r in 1..16) {
            for (i in 0 until 17) kbuf[i] = rotl3(kbuf[i])
            var idx = r
            for (k in 0 until 16) {
                val rc = RCON[(r - 1) * 16 + (15 - k)]
                sched[16 + (r - 1) * 16 + k] = (rc + kbuf[idx]) and 0xFF
                idx = if (idx < 16) idx + 1 else 0
            }
        }
        return sched
    }

    private fun mix(s: IntArray, src: IntArray, off: Int, invert: Boolean = false) {
        for (i in 0 until 16) {
            s[i] = if (isXor(i) xor invert) s[i] xor src[off + i]
                   else (s[i] + src[off + i]) and 0xFF
        }
    }

    private fun subBytes(s: IntArray) {
        for (i in 0 until 16) s[i] = if (isXor(i)) SBOX1[s[i]] else SBOX2[s[i]]
    }

    /** Pair-wise sums and double-sums folded through a fixed permutation (mod 256). */
    private fun diffuse(s: IntArray) {
        val b = s
        var c14 = b[1] + b[0] * 2;  var c1 = b[1] + b[0]
        var c19 = b[3] + b[2] * 2;  var c20 = b[3] + b[2]
        var c16 = b[5] + b[4] * 2;  var c13 = b[5] + b[4]
        var c2 = b[7] + b[6] * 2;   var c3 = b[7] + b[6]
        var c4 = b[9] + b[8] * 2;   var c15 = b[9] + b[8]
        var c5 = b[13] + b[12] * 2; var c18 = b[13] + b[12]
        var c17 = b[15] + b[14] * 2; var c10 = b[15] + b[14]
        val c6 = c20 + c16 * 2
        var c7 = b[11] + b[10] * 2; var c9 = b[11] + b[10]
        val c8 = c10 + c5 * 2
        var c22 = c9 + c4 * 2;      c9 += c4
        c10 += c5
        var c11 = c18 + c17 * 2
        c4 = c7 + c15
        var c21 = c13 + c2 * 2;     c2 += c13
        var c12 = c3 + c14
        c13 = c1 + c19 * 2
        c5 = c4 + c11 * 2;          c3 += c14 * 2
        c14 = c12 + c6
        c15 += c7 * 2
        c16 += c20
        c17 += c18
        c20 = c2 + c22 * 2
        val n1 = c5 + c14
        c18 = c10 + c13
        c19 += c1
        c1 = c17 + c15
        val n3 = c18 + c20
        c7 = c16 + c3 * 2
        c12 += c6 * 2
        val n2 = c18 + c20 * 2
        c11 += c4
        c17 += c15 * 2
        c20 = c19 + c21 * 2
        val n5 = c1 + c7
        c4 = c8 + c9
        c21 += c19
        val n4 = c1 + c7 * 2
        c10 += c13 * 2
        val n7 = c4 + c20
        c22 += c2
        val n9 = c11 + c12
        val n8 = c11 + c12 * 2
        val n10 = c22 + c10 * 2
        c9 += c8 * 2
        val n13 = c17 + c21
        c3 += c16
        val n12 = c21 + c17 * 2
        val n0 = c14 + c5 * 2
        val n6 = c4 + c20 * 2
        val n11 = c10 + c22
        val n15 = c9 + c3
        val n14 = c3 + c9 * 2
        val out = intArrayOf(n0, n1, n2, n3, n4, n5, n6, n7, n8, n9, n10, n11, n12, n13, n14, n15)
        for (i in 0 until 16) s[i] = out[i] and 0xFF
    }

    private fun encryptBlock(input: IntArray, sched: IntArray, mode: Int, original: IntArray?): IntArray {
        val s = input.copyOf()
        val orig = original ?: input.copyOf()
        var off = 0
        for (r in 0 until 8) {
            if (mode != 0 && r == 2) mix(s, orig, 0)
            mix(s, sched, off)
            subBytes(s)
            mix(s, sched, off + 16, invert = true)
            diffuse(s)
            off += 32
        }
        mix(s, sched, 0x100)
        return s
    }

    private fun hexInts(h: String) = IntArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16) }
}

fun hex(h: String): ByteArray {
    val clean = h.replace(" ", "")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(sep: String = " "): String = joinToString(sep) { "%02x".format(it.toInt() and 0xFF) }
