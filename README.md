# Ring (Android)

A native Android companion app for SmartHealth-compatible smart rings.
Kotlin + Jetpack Compose, no third-party SDKs.

## Tested hardware

| | |
| --- | --- |
| Ring | R11M (shows up as `RC20`) |
| Firmware | 2.12 |
| Main chip | Jieli AC632N (Bluetooth LE) |
| Protocols | Jieli RCSP for sign-in, Yucheng YCBT for health data |
| Motion sensor | One of QMA6100P, SC7A20X or STK8321 (the firmware supports all three and picks the one it finds) |
| Phone | Tested on Android 17 (the app supports Android 8.0 and up) |

Other rings that pair with the SmartHealth app and use the same chip and protocols may work, but haven't been tested.

## Build and install

Needs JDK 17 (set `JAVA_HOME` if it isn't your default).

```bash
./gradlew testDebugUnitTest          # protocol tests (no phone needed)
./gradlew installDebug               # build + install on a USB-connected phone
```

Close the SmartHealth app on the phone first. The ring talks to one app at a time.

## Layout

| Path | What it does |
| --- | --- |
| `protocol/JieliCipher.kt` | Cipher for the ring's sign-in handshake |
| `protocol/Rcsp.kt` | Jieli RCSP frames + sign-in (`ae01` / `ae02`) |
| `protocol/Ycbt.kt` | Yucheng YCBT frames: time, measurements, workout mode (`be940001` / `be940003`) |
| `ble/BleLink.kt` | One GATT connection, one operation at a time |
| `ring/RingSession.kt` | Connect, sign in, auto-reconnect, measurements, workout mode |
| `workout/WorkoutController.kt` | Timer, heart-rate zones, gaps, steps, GPS distance |
| `service/RingService.kt` | Foreground service that keeps the link alive with the screen off |
| `ui/screens/*` | Pair, Today, Measuring, Live workout, Summary, History, Ring |

## How live heart rate works

The ring only samples heart rate when asked. A workout sends:

1. `0x030C [1, sport]` – ring workout mode on
2. `0x0309 [1, 1, 2]` – report live data
3. `0x032F [1, 0]` – heart-rate sensor on

Readings arrive as `0x0601` frames (and on the standard `0x2A37` characteristic).
If none arrive for 20 s, the app asks again. After a reconnect it re-sends all three.

## License

MIT. See [LICENSE](LICENSE).
