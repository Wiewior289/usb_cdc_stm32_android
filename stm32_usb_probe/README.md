# STM32 USB Probe

Separate generic USB Host diagnostic app for the robot-controller link. This module is intentionally independent of `:usb_ecm` and the production Bridge.

## Scope

- Enumerates attached USB devices without a VID/PID allowlist.
- Logs VID/PID, device class, USB version, configurations, interfaces and endpoints.
- Reads raw Device and Configuration descriptors with standard GET_DESCRIPTOR requests.
- Requests Android USB Host permission and opens the selected device.
- Detects a CDC-ACM control interface (02/02) and a CDC Data interface (0A), or a vendor-specific interface with a bulk IN/OUT pair.
- Claims interfaces, optionally asserts DTR/RTS, starts one blocking bulk-IN reader and sends `PING\r\n` to bulk OUT.

## Important limits

A board with no USB device firmware cannot enumerate as a USB peripheral. A host-side app cannot make the STM32 answer SETUP packets. Before the bulk echo test, the MCU must run a CDC-ACM or compatible bulk-device firmware.

The app currently selects the first device returned by UsbManager. This is deliberate for the one-board bench setup; add a device picker before using it with multiple USB devices.

The PING operation proves only that Android's bulkTransfer accepted/transferred bytes. A matching RX echo (or MCU-generated PONG) is the end-to-end proof.

## Build

From repository root:

```sh
gradle :stm32_usb_probe:assembleDebug --no-configuration-cache
gradle :stm32_usb_probe:testDebugUnitTest --no-configuration-cache
adb install -r stm32_usb_probe/build/outputs/apk/debug/stm32_usb_probe-debug.apk
adb logcat -s STM32UsbProbe
```

## First hardware pass

1. Flash a minimal STM32 USB Device CDC-ACM firmware.
2. Connect STM32 to the Galaxy S9+ through a known-good OTG adapter and data cable.
3. Open the app and inspect VID/PID, raw descriptors, interfaces and endpoint addresses.
4. Confirm the Android permission dialog and successful interface claims.
5. Verify that the STM32 firmware receives PING and returns PONG (or echoes the payload).
6. Repeat disconnect/reconnect and check that the app releases and reacquires the interface cleanly.

No robot commands, motor control, IMU protocol, watchdog or safety functions are implemented in this diagnostic app.
