# STM32 USB Probe

Generic Android USB Host diagnostic and CZAR V1 protocol benchmark for the STM32 robot-controller link. This module is intentionally independent of `:usb_ecm`, `:usb_probe` and the production Bridge.

## Scope

- Enumerates attached USB devices without a VID/PID allowlist.
- Logs VID/PID, device class, USB version, configurations, interfaces and endpoints.
- Reads raw Device and Configuration descriptors with standard GET_DESCRIPTOR requests.
- Requests Android USB Host permission and opens the selected device.
- Detects a CDC-ACM control interface (02/02) and a CDC Data interface (0A), or a vendor-specific interface with a bulk IN/OUT pair.
- Claims interfaces and optionally asserts DTR/RTS.
- Receives USB bulk-IN data on a dedicated reader thread and sends requests on a separate executor.
- Implements CZAR V1 request encoding, incremental response decoding, CRC validation, session/sequence matching, payload echo validation and RTT measurement.
- Provides a stop-and-wait payload-size sweep for 1, 16, 32, 63, 64, 65, 128, 256, 512, 1024 and 2048-byte payloads.

## CZAR V1

The wire format is frozen. All multi-byte fields are little-endian. CRC is CRC-32/ISO-HDLC over VERSION through the end of PAYLOAD, excluding MAGIC and the CRC field. The Android implementation is in `src/main/java/com/example/stm32usbprobe/CzarProtocol.java`; its unit tests are in `src/test/java/com/example/stm32usbprobe/CzarProtocolTest.java`.

See [CZAR_V1.md](CZAR_V1.md) for exact offsets, status values, timestamp semantics and the shared CRC reference vector.

The benchmark uses one outstanding request at a time. A response is accepted only after its frame CRC, VERSION, TYPE, SESSION_ID, SEQUENCE and status/payload semantics have been checked. A timeout aborts the sweep rather than sending another request into an uncertain stream.

## Build and test

From repository root:

```sh
gradle :stm32_usb_probe:testDebugUnitTest --no-configuration-cache
gradle :stm32_usb_probe:assembleDebug --no-configuration-cache
adb install -r stm32_usb_probe/build/outputs/apk/debug/stm32_usb_probe-debug.apk
adb logcat -s STM32UsbProbe
```

The module uses the repository's JUnit 4.13.2 dependency and has no additional runtime libraries.

## Hardware test

1. Flash STM32 firmware that implements the frozen CZAR V1 contract over USB Device CDC-ACM.
2. Connect STM32 to the Galaxy S9+ through a known-good OTG adapter and data cable.
3. Open the app and inspect VID/PID, raw descriptors, interfaces and endpoint addresses.
4. Confirm the Android permission dialog and successful interface claims.
5. Run one 64-byte CZAR transaction. Confirm matching SESSION_ID/SEQUENCE, STATUS=OK and exact payload echo.
6. Run the payload-size sweep. The output includes RTT distribution, STM32 processing time and completed frames per second.
7. Repeat disconnect/reconnect and check that the app releases and reacquires the interface cleanly.

USB transfer boundaries are not application-frame boundaries. The response decoder handles fragmented and concatenated byte streams and does not treat MAGIC inside a valid payload as a new frame.

No robot commands, motor control, IMU protocol, watchdog or safety functions are implemented in this diagnostic app.
