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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private Button benchmarkButton;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean rxRunning = new AtomicBoolean(false);
    private final AtomicBoolean rxWorkerActive = new AtomicBoolean(false);
    private final AtomicInteger ioGeneration = new AtomicInteger(0);
    private final AtomicBoolean transactionRunning = new AtomicBoolean(false);
    private final Object pendingLock = new Object();
    private final CzarProtocol.StreamDecoder czarDecoder = new CzarProtocol.StreamDecoder();
    private final SecureRandom secureRandom = new SecureRandom();
    private final int sessionId = secureRandom.nextInt();
    private int nextSequence;
    private boolean pendingActive;
    private boolean pendingAborted;
    private int pendingSessionId;
    private int pendingSequence;
    private CzarProtocol.Response pendingResponse;
    private long pendingResponseRxNs;
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
        sendButton = addButton(root, "3. SEND CZAR V1 (64 B)", v -> sendCzarSingle());
        benchmarkButton = addButton(root, "4. CZAR V1 BENCHMARK SWEEP", v -> runCzarBenchmark());
        sendButton.setEnabled(false);
        benchmarkButton.setEnabled(false);

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

        log("STM32 USB Probe 0.2 - CZAR V1");
        log("Generic USB Host discovery. No VID/PID allowlist.");
        log(String.format(Locale.US, "CZAR session=%08X; stop-and-wait; max payload=%d B",
                sessionId, CzarProtocol.MAX_PAYLOAD_LENGTH));
        log("Expected peer: STM32 CDC-ACM firmware implementing CZAR V1.");
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
            log(String.format(Locale.US, " CONFIG index=%d value=%d selfPowered=%b remoteWakeup=%b maxPower=%d interfaces=%d",
                    c, config.getId(), config.isSelfPowered(), config.isRemoteWakeup(),
                    config.getMaxPower(), config.getInterfaceCount()));
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
            int descriptorValue = 0x0200 | index;
            int h = connection.controlTransfer(0x80, 0x06, descriptorValue, 0,
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
            int r = connection.controlTransfer(0x80, 0x06, descriptorValue, 0,
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
        benchmarkButton.setEnabled(true);
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
        if (bulkIn == null || connection == null) return;
        rxRunning.set(true);
        if (!rxWorkerActive.compareAndSet(false, true)) return;
        final int generation = ioGeneration.get();
        ioExecutor.execute(() -> {
            byte[] buffer = new byte[512];
            while (rxRunning.get() && generation == ioGeneration.get()) {
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
                    long rxChunkCompleteNs = System.nanoTime();
                    byte[] packet = new byte[n];
                    System.arraycopy(buffer, 0, packet, 0, n);
                    long crcErrorsBefore = czarDecoder.getCrcErrorCount();
                    long invalidLengthsBefore = czarDecoder.getInvalidLengthCount();
                    long decoderOverflowsBefore = czarDecoder.getBufferOverflowCount();
                    List<CzarProtocol.Response> responses =
                            czarDecoder.feed(packet, 0, packet.length);
                    long crcErrors = czarDecoder.getCrcErrorCount() - crcErrorsBefore;
                    long invalidLengths =
                            czarDecoder.getInvalidLengthCount() - invalidLengthsBefore;
                    long decoderOverflows =
                            czarDecoder.getBufferOverflowCount() - decoderOverflowsBefore;
                    log("USB RX chunk bytes=" + n);
                    if (crcErrors > 0) {
                        log("CZAR RX discarded invalid-CRC candidate(s)=" + crcErrors);
                    }
                    if (invalidLengths > 0) {
                        log("CZAR RX rejected oversized PAYLOAD_LEN candidate(s)=" + invalidLengths);
                    }
                    if (decoderOverflows > 0) {
                        log("CZAR RX decoder discontinuity/overflow events=" + decoderOverflows);
                    }
                    handleCzarResponses(responses, rxChunkCompleteNs);
                } else if (n < 0 && rxRunning.get()) {
                    log("RX bulkTransfer result=" + n);
                }
            }
            rxWorkerActive.set(false);
            if (rxRunning.get() && generation != ioGeneration.get()) startReceiver();
        });
    }

    private static final int CZAR_RESPONSE_TIMEOUT_MS = 2500;
    private static final int CZAR_TX_TIMEOUT_MS = 2500;
    private static final int BENCHMARK_ITERATIONS = 5;
    private static final int[] BENCHMARK_PAYLOAD_SIZES =
            {1, 16, 32, 63, 64, 65, 128, 256, 512, 1024, 2048};

    private void sendCzarSingle() {
        if (!transactionRunning.compareAndSet(false, true)) return;
        setTransactionButtonsEnabled(false);
        txExecutor.execute(() -> {
            TransactionResult result = executeCzarTransaction(64);
            if (result == null) {
                log("CZAR transaction could not start: USB bulk transport is not ready.");
            } else {
                logTransactionResult("CZAR SINGLE", result);
            }
            transactionRunning.set(false);
            setTransactionButtonsEnabled(connection != null && bulkOut != null && bulkIn != null);
        });
    }

    private void runCzarBenchmark() {
        if (!transactionRunning.compareAndSet(false, true)) return;
        setTransactionButtonsEnabled(false);
        txExecutor.execute(() -> {
            log("CZAR BENCHMARK BEGIN iterations=" + BENCHMARK_ITERATIONS
                    + " sizes=" + Arrays.toString(BENCHMARK_PAYLOAD_SIZES));
            log("CSV payload_len,samples,min_rtt_us,avg_rtt_us,median_rtt_us,p95_rtt_us,"
                    + "p99_rtt_us,max_rtt_us,stddev_rtt_us,stm32_proc_avg_us,fps");
            boolean aborted = false;
            for (int payloadLength : BENCHMARK_PAYLOAD_SIZES) {
                List<Long> rttSamples = new ArrayList<>();
                List<Long> processingSamples = new ArrayList<>();
                long groupStartNs = System.nanoTime();
                for (int iteration = 0; iteration < BENCHMARK_ITERATIONS; iteration++) {
                    TransactionResult result = executeCzarTransaction(payloadLength);
                    if (result == null || !result.received || !result.valid
                            || result.response == null
                            || result.response.status != CzarProtocol.STATUS_OK) {
                        logTransactionResult("BENCH FAIL len=" + payloadLength
                                + " iteration=" + (iteration + 1), result);
                        aborted = true;
                        break;
                    }
                    rttSamples.add(result.rttNs);
                    processingSamples.add(result.processingUs);
                }
                long groupElapsedNs = System.nanoTime() - groupStartNs;
                if (!rttSamples.isEmpty()) {
                    logBenchmarkRow(payloadLength, rttSamples, processingSamples, groupElapsedNs);
                }
                if (aborted) break;
            }
            log("CZAR BENCHMARK " + (aborted ? "ABORTED" : "COMPLETE"));
            transactionRunning.set(false);
            setTransactionButtonsEnabled(connection != null && bulkOut != null && bulkIn != null);
        });
    }

    private TransactionResult executeCzarTransaction(int payloadLength) {
        UsbDeviceConnection current = connection;
        UsbEndpoint endpoint = bulkOut;
        if (current == null || endpoint == null || bulkIn == null) return null;

        final int sequence = nextSequence++;
        final byte[] payload;
        try {
            payload = CzarProtocol.createPayload(sessionId, sequence, payloadLength);
        } catch (IllegalArgumentException error) {
            return TransactionResult.failure("Payload error: " + error.getMessage());
        }

        final long androidTxNs = System.nanoTime();
        final byte[] request = CzarProtocol.encodeRequest(
                sessionId, sequence, androidTxNs, payload);
        synchronized (pendingLock) {
            if (pendingActive) return TransactionResult.failure("Another CZAR request is pending");
            pendingActive = true;
            pendingAborted = false;
            pendingSessionId = sessionId;
            pendingSequence = sequence;
            pendingResponse = null;
            pendingResponseRxNs = 0L;
        }

        try {
            int sent = current.bulkTransfer(endpoint, request, request.length, CZAR_TX_TIMEOUT_MS);
            if (sent != request.length) {
                return TransactionResult.failure("USB TX incomplete: " + sent
                        + "/" + request.length + " bytes");
            }

            CzarProtocol.Response response;
            long androidRxNs;
            long deadlineNs = System.nanoTime()
                    + CZAR_RESPONSE_TIMEOUT_MS * 1_000_000L;
            synchronized (pendingLock) {
                while (pendingResponse == null && !pendingAborted) {
                    long remainingNs = deadlineNs - System.nanoTime();
                    if (remainingNs <= 0L) break;
                    long waitMs = remainingNs / 1_000_000L;
                    int waitNs = (int) (remainingNs % 1_000_000L);
                    pendingLock.wait(waitMs, waitNs);
                }
                if (pendingAborted) {
                    return TransactionResult.failure("USB connection closed while waiting");
                }
                if (pendingResponse == null) {
                    return TransactionResult.timeout("No matching CZAR response within "
                            + CZAR_RESPONSE_TIMEOUT_MS + " ms");
                }
                response = pendingResponse;
                androidRxNs = pendingResponseRxNs;
            }

            long rttNs = androidRxNs - androidTxNs;
            String validation = CzarProtocol.validateResponse(
                    response, sessionId, sequence, payload);
            long processingUs = CzarProtocol.stm32ProcessingTimeUs(response);
            return new TransactionResult(true, validation == null, response,
                    rttNs, processingUs, validation);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return TransactionResult.failure("Interrupted while waiting for CZAR response");
        } catch (RuntimeException error) {
            return TransactionResult.failure("USB TX/RX exception: " + error);
        } finally {
            synchronized (pendingLock) {
                pendingActive = false;
                pendingResponse = null;
                pendingLock.notifyAll();
            }
        }
    }

    private void handleCzarResponses(
            List<CzarProtocol.Response> responses, long rxTimestampNs) {
        for (CzarProtocol.Response response : responses) {
            boolean matched = false;
            synchronized (pendingLock) {
                if (pendingActive
                        && response.sessionId == pendingSessionId
                        && response.sequence == pendingSequence
                        && pendingResponse == null) {
                    pendingResponse = response;
                    pendingResponseRxNs = rxTimestampNs;
                    pendingLock.notifyAll();
                    matched = true;
                }
            }
            if (matched) {
                log(String.format(Locale.US,
                        "CZAR RX matched session=%08X seq=%s status=%s payload=%d",
                        response.sessionId, Integer.toUnsignedString(response.sequence),
                        CzarProtocol.statusName(response.status), response.payloadLength()));
            } else {
                log(String.format(Locale.US,
                        "CZAR RX unmatched session=%08X seq=%s type=%02X version=%02X",
                        response.sessionId, Integer.toUnsignedString(response.sequence),
                        response.type, response.version));
            }
        }
    }

    private void logTransactionResult(String label, TransactionResult result) {
        if (result == null) {
            log(label + ": no result");
            return;
        }
        if (!result.received) {
            log(label + ": " + result.error);
            return;
        }
        CzarProtocol.Response response = result.response;
        log(String.format(Locale.US,
                "%s: %s status=%s RTT=%.3f ms STM32_processing=%d us",
                label, result.valid ? "VALID" : "INVALID",
                CzarProtocol.statusName(response.status),
                result.rttNs / 1_000_000.0, result.processingUs));
        if (result.error != null) log(label + ": " + result.error);
    }

    private void logBenchmarkRow(int payloadLength, List<Long> rttNs,
                                 List<Long> processingUs, long elapsedNs) {
        long[] rtt = toSortedArray(rttNs);
        long[] processing = toSortedArray(processingUs);
        double sum = 0.0;
        for (long sample : rtt) sum += sample;
        double mean = sum / rtt.length;
        double variance = 0.0;
        for (long sample : rtt) {
            double delta = sample - mean;
            variance += delta * delta;
        }
        double stddev = Math.sqrt(variance / rtt.length);
        double processingMean = 0.0;
        for (long sample : processing) processingMean += sample;
        processingMean /= processing.length;
        double fps = elapsedNs > 0
                ? rtt.length * 1_000_000_000.0 / elapsedNs : 0.0;

        log(String.format(Locale.US,
                "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f",
                payloadLength, rtt.length,
                rtt[0] / 1000.0, mean / 1000.0,
                percentile(rtt, 0.50) / 1000.0,
                percentile(rtt, 0.95) / 1000.0,
                percentile(rtt, 0.99) / 1000.0,
                rtt[rtt.length - 1] / 1000.0,
                stddev / 1000.0, processingMean, fps));
    }

    private long[] toSortedArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        Arrays.sort(result);
        return result;
    }

    private long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private void setTransactionButtonsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (sendButton != null) sendButton.setEnabled(enabled);
            if (benchmarkButton != null) benchmarkButton.setEnabled(enabled);
        });
    }

    private static final class TransactionResult {
        final boolean received;
        final boolean valid;
        final CzarProtocol.Response response;
        final long rttNs;
        final long processingUs;
        final String error;

        TransactionResult(boolean received, boolean valid, CzarProtocol.Response response,
                          long rttNs, long processingUs, String error) {
            this.received = received;
            this.valid = valid;
            this.response = response;
            this.rttNs = rttNs;
            this.processingUs = processingUs;
            this.error = error;
        }

        static TransactionResult failure(String message) {
            return new TransactionResult(false, false, null, 0L, 0L, message);
        }

        static TransactionResult timeout(String message) {
            return failure(message);
        }
    }

    private void closeDevice() {
        rxRunning.set(false);
        setTransactionButtonsEnabled(false);
        closeConnectionOnly();
        device = null;
    }

    private void closeConnectionOnly() {
        rxRunning.set(false);
        ioGeneration.incrementAndGet();
        setTransactionButtonsEnabled(false);
        czarDecoder.reset();
        synchronized (pendingLock) {
            pendingAborted = true;
            pendingLock.notifyAll();
        }
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
        ioExecutor.shutdownNow();
        txExecutor.shutdownNow();
        super.onDestroy();
    }
}
