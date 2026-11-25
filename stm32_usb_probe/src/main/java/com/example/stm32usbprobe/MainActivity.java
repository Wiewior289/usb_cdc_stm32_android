package com.example.stm32usbprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic Android USB-host probe for an STM32 CDC-ACM or vendor-bulk device.
 * It does not assume a VID/PID and does not depend on the repository's CDC-ECM code.
 */
public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.example.stm32usbprobe.USB_PERMISSION";
    private static final int IO_TIMEOUT_MS = 1200;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface controlInterface;
    private UsbInterface dataInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private TextView output;
    private Button sendButton;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean rxRunning = new AtomicBoolean(false);
    private boolean receiverRegistered;
    private boolean controlClaimed;
    private boolean dataClaimed;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("USB_PERMISSION granted=" + granted);
            if (granted && device != null) openDevice(device);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        addButton(root, "1. SCAN USB + DESCRIPTORS", v -> scanDevices());
        addButton(root, "2. CLOSE / RELEASE", v -> closeDevice());
        sendButton = addButton(root, "3. SEND PING (CDC/BULK ECHO)", v -> sendPing());
        sendButton.setEnabled(false);

        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        receiverRegistered = true;

        log("STM32 USB Probe 0.1");
        log("Generic USB Host discovery. No VID/PID allowlist.");
        log("Expected next step: STM32 CDC-ACM firmware with bulk IN/OUT echo.");
        scanDevices();
    }

    private Button addButton(LinearLayout root, String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(click);
        root.addView(button);
        return button;
    }

    private void scanDevices() {
        closeDevice();
        List<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());
        log("--------------------------------------------------");
        log("USB HOST devices=" + devices.size());
        if (devices.isEmpty()) {
            log("No downstream USB device. Check OTG adapter, cable and VBUS.");
            return;
        }
        for (UsbDevice candidate : devices) {
            logDevice(candidate);
        }
        // The first scan candidate is selected explicitly for this one-device lab setup.
        device = devices.get(0);
        log(String.format(Locale.US, "Selected %04X:%04X (%s)",
                device.getVendorId(), device.getProductId(), device.getDeviceName()));
        if (usbManager.hasPermission(device)) {
            openDevice(device);
        } else {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
            log("USB permission requested. Accept Android system dialog.");
        }
    }

    private void logDevice(UsbDevice d) {
        log(String.format(Locale.US,
                "DEVICE %04X:%04X name=%s deviceClass=%02X/%02X/%02X " +
                "USB=%s configs=%d interfaces=%d",
                d.getVendorId(), d.getProductId(), d.getDeviceName(),
                d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol(),
                d.getVersion(), d.getConfigurationCount(), d.getInterfaceCount()));

        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration config = d.getConfiguration(c);
            log(String.format(Locale.US, " CONFIG index=%d value=%d attrs=%02X maxPower=%d interfaces=%d",
                    c, config.getId(), config.getAttributes(), config.getMaxPower(),
                    config.getInterfaceCount()));
            for (int i = 0; i < config.getInterfaceCount(); i++) {
                UsbInterface iface = config.getInterface(i);
                log(String.format(Locale.US,
                        "  IF id=%d alt=%d class=%02X sub=%02X proto=%02X eps=%d name=%s",
                        iface.getId(), iface.getAlternateSetting(),
                        iface.getInterfaceClass(), iface.getInterfaceSubclass(),
                        iface.getInterfaceProtocol(), iface.getEndpointCount(),
                        iface.getName()));
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    log(String.format(Locale.US,
                            "   EP addr=%02X dir=%s type=%s maxPacket=%d interval=%d",
                            ep.getAddress(),
                            ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                            endpointType(ep.getType()), ep.getMaxPacketSize(),
                            ep.getInterval()));
                }
            }
        }
    }

    private void openDevice(UsbDevice target) {
        closeConnectionOnly();
        if (target == null || !usbManager.hasPermission(target)) {
            log("OPEN blocked: USB permission missing.");
            return;
        }
        connection = usbManager.openDevice(target);
        if (connection == null) {
            log("OPEN FAILED: UsbManager.openDevice returned null.");
            return;
        }
        device = target;
        log("OPEN OK fd=" + connection.getFileDescriptor());
        readRawDescriptors();
        findEndpointsAndClaim();
    }

    private void readRawDescriptors() {
        byte[] deviceDescriptor = new byte[18];
        int n = connection.controlTransfer(0x80, 0x06, 0x0100, 0,
                deviceDescriptor, deviceDescriptor.length, 1000);
        log("GET_DESCRIPTOR DEVICE result=" + n +
                (n > 0 ? " raw=" + hex(deviceDescriptor, Math.min(n, deviceDescriptor.length)) : ""));
        for (int index = 0; index < device.getConfigurationCount(); index++) {
            byte[] header = new byte[9];
            int h = connection.controlTransfer(0x80, 0x06, 0x0200, index,
                    header, header.length, 1000);
            if (h < 9) {
                log("GET_DESCRIPTOR CONFIG index=" + index + " header result=" + h);
                continue;
            }
            int totalLength = (header[2] & 0xFF) | ((header[3] & 0xFF) << 8);
            if (totalLength < 9 || totalLength > 4096) {
                log("CONFIG index=" + index + " invalid wTotalLength=" + totalLength);
                continue;
            }
            byte[] raw = new byte[totalLength];
            int r = connection.controlTransfer(0x80, 0x06, 0x0200, index,
                    raw, raw.length, 1500);
            log("GET_DESCRIPTOR CONFIG index=" + index + " total=" + totalLength +
                    " result=" + r +
                    (r > 0 ? " raw=" + hex(raw, Math.min(r, raw.length)) : ""));
        }
    }

    private void findEndpointsAndClaim() {
        controlInterface = null;
        dataInterface = null;
        bulkIn = null;
        bulkOut = null;

        // Prefer CDC-ACM data interface (class 0x0A), but allow a vendor-specific
        // interface with a matched bulk IN + OUT pair for later custom firmware.
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration config = device.getConfiguration(c);
            for (int i = 0; i < config.getInterfaceCount(); i++) {
                UsbInterface iface = config.getInterface(i);
                if (iface.getInterfaceClass() == 0x02 &&
                        iface.getInterfaceSubclass() == 0x02) controlInterface = iface;
                if (iface.getInterfaceClass() == 0x0A) {
                    if (hasBulkPair(iface)) dataInterface = iface;
                }
            }
        }
        if (dataInterface == null) {
            for (int c = 0; c < device.getConfigurationCount() && dataInterface == null; c++) {
                UsbConfiguration config = device.getConfiguration(c);
                for (int i = 0; i < config.getInterfaceCount(); i++) {
                    UsbInterface iface = config.getInterface(i);
                    if (hasBulkPair(iface)) { dataInterface = iface; break; }
                }
            }
        }
        if (dataInterface == null) {
            log("NO BULK IN/OUT PAIR. Enumeration succeeded; no data interface available.");
            log("This is expected if STM32 currently has no USB device firmware.");
            return;
        }

        for (int e = 0; e < dataInterface.getEndpointCount(); e++) {
            UsbEndpoint ep = dataInterface.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) bulkOut = ep;
        }

        if (controlInterface != null && controlInterface != dataInterface) {
            controlClaimed = connection.claimInterface(controlInterface, true);
            log("CLAIM CDC control IF=" + controlInterface.getId() + " -> " + controlClaimed);
        }
        dataClaimed = connection.claimInterface(dataInterface, true);
        log("CLAIM data IF=" + dataInterface.getId() + " -> " + dataClaimed);
        if (!dataClaimed || (controlInterface != null && controlInterface != dataInterface &&
                !controlClaimed)) {
            log("CLAIM FAILED. Closing connection.");
            closeConnectionOnly();
            return;
        }

        if (dataInterface.getAlternateSetting() != 0) {
            boolean alt = connection.setInterface(dataInterface);
            log("SET_INTERFACE alt=" + dataInterface.getAlternateSetting() + " -> " + alt);
        }
        if (controlInterface != null) {
            int dtr = connection.controlTransfer(0x21, 0x22, 0x0003,
                    controlInterface.getId(), null, 0, 1000);
            log("CDC SET_CONTROL_LINE_STATE DTR|RTS result=" + dtr);
        }

        log(String.format(Locale.US,
                "BULK READY IN=0x%02X max=%d OUT=0x%02X max=%d",
                bulkIn.getAddress(), bulkIn.getMaxPacketSize(),
                bulkOut.getAddress(), bulkOut.getMaxPacketSize()));
        sendButton.setEnabled(true);
        startReceiver();
    }

    private boolean hasBulkPair(UsbInterface iface) {
        boolean in = false, out = false;
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = true;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = true;
        }
        return in && out;
    }

    private void startReceiver() {
        if (bulkIn == null || connection == null || !rxRunning.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            byte[] buffer = new byte[512];
            while (rxRunning.get()) {
                UsbDeviceConnection current = connection;
                UsbEndpoint endpoint = bulkIn;
                if (current == null || endpoint == null) break;
                int n;
                try {
                    n = current.bulkTransfer(endpoint, buffer, buffer.length, IO_TIMEOUT_MS);
                } catch (RuntimeException error) {
                    log("RX exception: " + error);
                    break;
                }
                if (n > 0) {
                    byte[] packet = new byte[n];
                    System.arraycopy(buffer, 0, packet, 0, n);
                    log("RX n=" + n + " hex=" + hex(packet, n) +
                            " ascii=" + printable(packet));
                } else if (n < 0 && rxRunning.get()) {
                    log("RX bulkTransfer result=" + n);
                }
            }
            rxRunning.set(false);
        });
    }

    private void sendPing() {
        UsbDeviceConnection current = connection;
        UsbEndpoint endpoint = bulkOut;
        if (current == null || endpoint == null) {
            log("TX blocked: bulk transport is not ready.");
            return;
        }
        byte[] payload = "PING\r\n".getBytes(StandardCharsets.US_ASCII);
        ioExecutor.execute(() -> {
            int n;
            try {
                n = current.bulkTransfer(endpoint, payload, payload.length, 1500);
            } catch (RuntimeException error) {
                log("TX exception: " + error);
                return;
            }
            log("TX PING bytes=" + payload.length + " result=" + n +
                    " endpoint=0x" + String.format(Locale.US, "%02X", endpoint.getAddress()));
            log("Expected echo response: PONG or echoed PING. TX completion alone is not peer acknowledgement.");
        });
    }

    private void closeDevice() {
        rxRunning.set(false);
        sendButton.setEnabled(false);
        closeConnectionOnly();
        device = null;
    }

    private void closeConnectionOnly() {
        rxRunning.set(false);
        sendButton.setEnabled(false);
        if (connection != null) {
            if (dataClaimed && dataInterface != null) {
                try { log("RELEASE data -> " + connection.releaseInterface(dataInterface)); }
                catch (Exception error) { log("RELEASE data exception=" + error); }
            }
            if (controlClaimed && controlInterface != null) {
                try { log("RELEASE control -> " + connection.releaseInterface(controlInterface)); }
                catch (Exception error) { log("RELEASE control exception=" + error); }
            }
            try { connection.close(); } catch (Exception ignored) { }
        }
        connection = null;
        controlClaimed = false;
        dataClaimed = false;
        controlInterface = null;
        dataInterface = null;
        bulkIn = null;
        bulkOut = null;
    }

    private String endpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISO";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INT";
            default: return "?" + type;
        }
    }

    private String hex(byte[] bytes, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return out.toString();
    }

    private String printable(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            out.append(c >= 32 && c <= 126 ? (char)c : '.');
        }
        return out.toString();
    }

    private void log(String message) {
        runOnUiThread(() -> {
            if (output != null) {
                output.append(message + "\n");
            }
            android.util.Log.i("STM32UsbProbe", message);
        });
    }

    @Override protected void onDestroy() {
        closeDevice();
        if (receiverRegistered) {
            try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        }
        ioExecutor.shutdownNow();\n        txExecutor.shutdownNow();
        super.onDestroy();
    }
}
