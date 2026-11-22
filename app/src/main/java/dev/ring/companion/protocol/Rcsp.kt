package dev.ring.companion.protocol

import java.security.SecureRandom
import java.util.UUID

/**
 * Jieli RCSP: framed command channel on 0000ae01 (write) / 0000ae02 (notify).
 *
 *   FE DC BA | flags | opcode | len (BE u16) | sn [+status] [+xmOp] payload | EF
 *
 * Every command is gated behind [RcspAuth].
 */
object Rcsp {
    val SERVICE: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

    const val OP_DATA = 0x01
    const val OP_GET_TARGET_INFO = 0x03
    const val OP_SEARCH_DEVICE = 0x19

    private val MAGIC = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
    private const val END = 0xEF.toByte()
    private const val FLAG_REQ = 0x80
    private const val FLAG_HAS_RESP = 0x40

    data class Packet(
        val isRequest: Boolean,
        val hasResponse: Boolean,
        val op: Int,
        val sn: Int,
        val payload: ByteArray,
        val status: Int = 0,
        val xmOp: Int = 0,
    ) {
        fun encode(): ByteArray {
            val flags = (if (isRequest) FLAG_REQ else 0) or (if (hasResponse) FLAG_HAS_RESP else 0)
            val head = if (isRequest) byteArrayOf(sn.toByte()) else byteArrayOf(status.toByte(), sn.toByte())
            val xm = if (op == OP_DATA) byteArrayOf(xmOp.toByte()) else ByteArray(0)
            val body = head + xm + payload
            val len = body.size
            return MAGIC + byteArrayOf(flags.toByte(), op.toByte(), (len shr 8).toByte(), len.toByte()) +
                body + byteArrayOf(END)
        }
    }

    fun request(op: Int, sn: Int, payload: ByteArray = ByteArray(0), expectResponse: Boolean = true) =
        Packet(isRequest = true, hasResponse = expectResponse, op = op, sn = sn, payload = payload)

    fun parse(buf: ByteArray): Packet? {
        if (buf.size < 8 || !buf.copyOfRange(0, 3).contentEquals(MAGIC) || buf.last() != END) return null
        val flags = buf[3].toInt() and 0xFF
        val op = buf[4].toInt() and 0xFF
        val len = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
        if (7 + len + 1 != buf.size) return null
        val body = buf.copyOfRange(7, 7 + len)
        val isReq = flags and FLAG_REQ != 0
        var rest: ByteArray
        val sn: Int
        val status: Int
        if (isReq) {
            status = 0
            sn = body.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
            rest = if (body.isNotEmpty()) body.copyOfRange(1, body.size) else body
        } else {
            status = body.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
            sn = body.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            rest = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0)
        }
        var xm = 0
        if (op == OP_DATA && rest.isNotEmpty()) {
            xm = rest[0].toInt() and 0xFF
            rest = rest.copyOfRange(1, rest.size)
        }
        return Packet(isReq, flags and FLAG_HAS_RESP != 0, op, sn, rest, status, xm)
    }

    /** Vibrate / blink the ring for [seconds]. */
    fun searchDevicePayload(seconds: Int): ByteArray {
        val s = seconds.coerceIn(1, 60)
        return byteArrayOf(1, 1, (s shr 8).toByte(), s.toByte(), 0, 1)
    }

    /** Reassembles RCSP frames that BLE split across notifications. */
    class Framer {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<Packet> {
            buf += chunk
            val out = mutableListOf<Packet>()
            while (true) {
                val start = indexOfMagic()
                if (start < 0) { buf = ByteArray(0); break }
                if (start > 0) buf = buf.copyOfRange(start, buf.size)
                if (buf.size < 8) break
                val len = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
                val total = 7 + len + 1
                if (buf.size < total) break
                if (buf[total - 1] != END) { buf = buf.copyOfRange(1, buf.size); continue }
                parse(buf.copyOfRange(0, total))?.let(out::add)
                buf = buf.copyOfRange(total, buf.size)
            }
            return out
        }

        private fun indexOfMagic(): Int {
            for (i in 0..buf.size - 3) {
                if (buf[i] == MAGIC[0] && buf[i + 1] == MAGIC[1] && buf[i + 2] == MAGIC[2]) return i
            }
            return -1
        }
    }
}

/**
 * Mutual challenge-response handshake. Usage:
 *   1. write [RESET_FRAME], wait ~50 ms, write [firstMessage]
 *   2. feed every notification from ae02 into [onMessage]; write whatever it returns
 *   3. [isDone] turns true when the ring accepts us
 */
class RcspAuth(
    private val key: ByteArray = JieliCipher.DEFAULT_LINK_KEY,
    random: ByteArray? = null,
) {
    companion object {
        val RESET_FRAME: ByteArray = hex("FEDCBAC00600020001EF")
        val AUTH_OK: ByteArray = byteArrayOf(0x02, 'p'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte())
    }

    private val appRandom: ByteArray = random ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var verified = false

    var isDone = false
        private set
    var isRejected = false
        private set

    val firstMessage: ByteArray get() = byteArrayOf(0x00) + appRandom

    /** Returns the bytes to write back, or null when nothing needs sending. */
    fun onMessage(data: ByteArray): ByteArray? {
        if (!verified) {
            if (data.size == 17 && data[0].toInt() == 0x01) {
                val expected = JieliCipher.encryptRandom(appRandom, key)
                return if (data.copyOfRange(1, 17).contentEquals(expected)) {
                    verified = true
                    AUTH_OK
                } else {
                    isRejected = true
                    null
                }
            }
            return null
        }
        if (data.size == 17 && data[0].toInt() == 0x00) {
            return byteArrayOf(0x01) + JieliCipher.encryptRandom(data.copyOfRange(1, 17), key)
        }
        if (data.size >= 5 && data.copyOfRange(0, 5).contentEquals(AUTH_OK)) {
            isDone = true
        }
        return null
    }
}
