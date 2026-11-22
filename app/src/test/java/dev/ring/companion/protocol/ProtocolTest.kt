package dev.ring.companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {

    @Test fun cipherMatchesKnownPairs() {
        val pairs = listOf(
            "f3584606472b260e0dd2ebb21f6c3a3b" to "28c98297b3917e5e38dda04c30fa8d33",
            "f6b68b81b6e0ac0fd882b33e600bcefa" to "57d38b39eacb4230b1c115fcf59adf70",
        )
        for ((pt, ct) in pairs) {
            assertArrayEquals(hex(ct), JieliCipher.encryptRandom(hex(pt)))
        }
    }

    @Test fun authHandshakeCompletes() {
        val random = hex("f3584606472b260e0dd2ebb21f6c3a3b")
        val auth = RcspAuth(random = random)
        assertArrayEquals(hex("00f3584606472b260e0dd2ebb21f6c3a3b"), auth.firstMessage)

        // Ring proves it has the key -> we answer "pass".
        val reply1 = auth.onMessage(hex("0128c98297b3917e5e38dda04c30fa8d33"))
        assertArrayEquals(RcspAuth.AUTH_OK, reply1)

        // Ring challenges us -> we encrypt its random.
        val reply2 = auth.onMessage(hex("00f6b68b81b6e0ac0fd882b33e600bcefa"))
        assertArrayEquals(hex("0157d38b39eacb4230b1c115fcf59adf70"), reply2)

        assertNull(auth.onMessage(RcspAuth.AUTH_OK))
        assertTrue(auth.isDone)
    }

    @Test fun authRejectsWrongKey() {
        val auth = RcspAuth(random = hex("f3584606472b260e0dd2ebb21f6c3a3b"))
        assertNull(auth.onMessage(hex("01" + "00".repeat(16))))
        assertTrue(auth.isRejected)
    }

    @Test fun rcspEncodeAndParse() {
        val req = Rcsp.request(Rcsp.OP_GET_TARGET_INFO, sn = 1, payload = hex("ffffffff00"))
        assertArrayEquals(hex("fedcbac0030006 01ffffffff00ef"), req.encode())

        val rsp = Rcsp.parse(hex("fedcba0007000300 02ffef"))
        assertNotNull(rsp)
        assertEquals(0, rsp!!.status)
        assertEquals(2, rsp.sn)
        assertArrayEquals(hex("ff"), rsp.payload)
    }

    @Test fun rcspFramerJoinsChunks() {
        val framer = Rcsp.Framer()
        val whole = hex("fedcba0007000300 02ffef")
        assertTrue(framer.feed(whole.copyOfRange(0, 5)).isEmpty())
        val out = framer.feed(whole.copyOfRange(5, whole.size))
        assertEquals(1, out.size)
    }

    @Test fun ycbtTimeSyncMatchesKnownFrame() {
        val frame = Ycbt.timeSync(LocalDateTime.of(2026, 4, 20, 3, 45, 3))
        assertArrayEquals(hex("01000e00ea070414032d03009cc7"), frame)
    }

    @Test fun ycbtCommandsMatchKnownFrames() {
        assertArrayEquals(hex("03090900010002a0de"), Ycbt.realData(on = true, sport = false))
        assertEquals("02000800 4743".replace(" ", ""), Ycbt.getDeviceInfo().toHex("").substring(0, 12))
    }

    @Test fun ycbtFramerAndDecoders() {
        val framer = Ycbt.Framer()
        val live = Ycbt.frame(Ycbt.T_LIVE_ACTIVITY, hex("0806f9034000"))
        val frames = framer.feed(live.copyOfRange(0, 3)) + framer.feed(live.copyOfRange(3, live.size))
        assertEquals(1, frames.size)
        assertTrue(frames[0].crcOk)
        val activity = Ycbt.decodeLiveActivity(frames[0].payload)!!
        assertEquals(1544, activity.steps)
        assertEquals(1017, activity.distanceM)
        assertEquals(64, activity.calories)

        val beacon = Ycbt.decodeBeacon(hex("079005 00a903 003b0000"))!!
        assertEquals(1424, beacon.steps)
        assertEquals(937, beacon.distanceM)
        assertEquals(59, beacon.calories)
    }

    @Test fun standardHrFlags() {
        assertEquals(StandardHr.Reading(63, false), StandardHr.decode(hex("043f")))
        assertEquals(StandardHr.Reading(72, true), StandardHr.decode(hex("0648")))
    }

    @Test
    fun sleepHistoryDecodes() {
        val utc = java.time.ZoneOffset.UTC
        fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
        val t0 = 848_534_400L
        val stages = listOf(0xF2 to 1800L, 0xF1 to 2400L, 0xF3 to 900L, 0xF4 to 120L)
        var t = t0
        var body = ByteArray(0)
        stages.forEach { (st, len) -> body += byteArrayOf(st.toByte()) + le(t, 4) + le(len, 3); t += len }
        val rec = byteArrayOf(0xAF.toByte(), 0xFA.toByte()) + le(20L + body.size, 2) + le(t0, 4) + le(t, 4) +
            le(0xFFFF, 2) + ByteArray(6) + body
        val out = Ycbt.decodeSleepHistory(rec + rec, utc)
        assertEquals(2, out.size)
        val r = out[0]
        assertEquals(4, r.stages.size)
        assertEquals(java.time.Instant.parse("2026-11-21T00:00:00Z").toEpochMilli(), r.startMs)
        assertEquals(0xF1, r.stages[1].stage)
        assertEquals(2400, r.stages[1].seconds)
        val nights = dev.ring.companion.data.SleepNight.fromRecords(out)
        assertEquals(1, nights.size) // the repeated record is skipped
        assertEquals(2400L, nights[0].deepSec)
        assertEquals(1800L + 2400 + 900, nights[0].asleepSec)
    }

    @Test
    fun heartHistoryDecodes() {
        val p = hex("876d93320053b570933200525e7193320053")
        val out = Ycbt.decodeHeartHistory(p, java.time.ZoneOffset.UTC)
        assertEquals(listOf(0x53, 0x52, 0x53), out.map { it.second })
        assertEquals(java.time.Instant.parse("2026-11-20T20:43:51Z").toEpochMilli(), out[0].first)
    }

    @Test
    fun combinedHistoryDecodes() {
        // Two records as the ring sent them.
        val data = hex(
            "5e719332f100537e5462112503000f000000e8f3" +
                "757893320101477c4f620e2e05000f000000dfd5",
        )
        val out = Ycbt.decodeCombinedHistory(data, java.time.ZoneOffset.UTC)
        assertEquals(2, out.size)
        assertEquals(java.time.Instant.parse("2026-11-20T21:00:14Z").toEpochMilli(), out[0].timeMs)
        assertEquals(241, out[0].steps)
        assertEquals(83, out[0].heartRate)
        assertEquals(98, out[0].spo2)
        assertEquals(17, out[0].breathsPerMin)
        assertEquals(37, out[0].hrvMs)
        assertEquals(71, out[1].heartRate)
        assertEquals(98, out[1].spo2)
    }

    @Test
    fun firmwareRules() {
        val fc = dev.ring.companion.firmware.FirmwareCheck
        val r11m = mapOf(
            "mac_url" to "x.zip", "mac_bNo" to "2", "mac_sNo" to "34", "mac" to "00:11:22:33:44:01,00:11:22:33:44:02",
            "url" to "", "bNo" to "1", "sNo" to "0", "autoUpdateVersion" to "", "allUpdateVersion" to "",
        )
        // Not on the targeted list, general release is old: up to date.
        assertEquals(dev.ring.companion.firmware.FirmwareCheck.State.UpToDate, fc.evaluate(r11m, 2 to 12, "AA:BB:CC:DD:EE:FF").state)
        // On the targeted list: 2.34 offered.
        val listed = fc.evaluate(r11m, 2 to 12, "00:11:22:33:44:02")
        assertEquals(dev.ring.companion.firmware.FirmwareCheck.State.Available, listed.state)
        assertEquals("2.34", listed.latest)
        // General release newer, allowed list excludes us.
        val gen = mapOf("url" to "f.zip", "bNo" to "2", "sNo" to "20", "allUpdateVersion" to "2.10,2.11", "autoUpdateVersion" to "")
        assertEquals(dev.ring.companion.firmware.FirmwareCheck.State.UpToDate, fc.evaluate(gen, 2 to 12, null).state)
        assertEquals(dev.ring.companion.firmware.FirmwareCheck.State.Available, fc.evaluate(gen + ("allUpdateVersion" to ""), 2 to 12, null).state)
        assertEquals(dev.ring.companion.firmware.FirmwareCheck.State.Required, fc.evaluate(gen + ("autoUpdateVersion" to "2.12"), 2 to 12, null).state)
        // Display pads the minor number ("2.05"); comparison is numeric.
        assertEquals(2 to 5, fc.parseVersion("2.05"))
    }

    @Test
    fun wakeTimeAdjustedByHeartRate() {
        val min = 60_000L
        val t0 = 1_000_000_000_000L // 00:27 stand-in
        val end = t0 + 425 * min    // 07:32
        val night = dev.ring.companion.data.SleepNight(t0, end, listOf(Ycbt.SleepStage(Ycbt.Stage.LIGHT, t0, (425 * 60))))
        // Readings every 30 min from 00:33; sleeping ~51, then 76 at "07:00" and 57 at "07:30".
        val hr = (0 until 13).map { t0 + (3 + 30 * it) * min to (if (it % 2 == 0) 52 else 50) } +
            listOf(t0 + 393 * min to 76, t0 + 423 * min to 57)
        val (adj, info) = night.withHeartRate(hr)
        assertEquals(t0 + 393 * min, info!!.wakeMs)
        assertEquals(null, info.onsetMs)
        assertEquals(76, info.awakeBpm)
        // Everything after the 76 bpm reading is now awake.
        assertEquals((425L - 393) * 60, adj.awakeSec)
        assertEquals(393L * 60, adj.asleepSec)
        // A night with no awake-looking reading is left alone.
        assertEquals(null, night.withHeartRate(hr.dropLast(2)).second)
    }
}
