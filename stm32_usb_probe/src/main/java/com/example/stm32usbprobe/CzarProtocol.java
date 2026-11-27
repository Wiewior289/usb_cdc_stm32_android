package com.example.stm32usbprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CZAR V1 wire protocol. Pure Java: no Android dependencies.
 *
 * All multi-byte integers are little-endian. CRC is CRC-32/ISO-HDLC over
 * VERSION through the end of PAYLOAD; MAGIC and the CRC field are excluded.
 */
public final class CzarProtocol {
    public static final byte[] MAGIC = new byte[] {0x43, 0x5A, 0x41, 0x52};
    public static final int VERSION = 0x01;
    public static final int TYPE_REQUEST = 0x01;
    public static final int TYPE_RESPONSE = 0x02;

    public static final int STATUS_OK = 0x00;
    public static final int STATUS_LENGTH_ERROR = 0x01;
    public static final int STATUS_PAYLOAD_ERROR = 0x02;
    public static final int STATUS_UNSUPPORTED_TYPE = 0x03;
    public static final int STATUS_CRC_ERROR = 0x04;

    public static final int MIN_PAYLOAD_LENGTH = 1;
    public static final int MAX_PAYLOAD_LENGTH = 2048;
    public static final int REQUEST_HEADER_LENGTH = 24;
    public static final int RESPONSE_HEADER_LENGTH = 25;
    public static final int CRC_LENGTH = 4;
    public static final int MAX_REQUEST_FRAME_LENGTH =
            REQUEST_HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CRC_LENGTH;
    public static final int MAX_RESPONSE_FRAME_LENGTH =
            RESPONSE_HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CRC_LENGTH;

    private static final long CRC32_REFLECTED_POLYNOMIAL = 0xEDB88320L;

    private CzarProtocol() { }

    public static byte[] createPayload(int sessionId, int sequence, int payloadLength) {
        if (payloadLength < MIN_PAYLOAD_LENGTH || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("PAYLOAD_LEN must be in range 1..2048");
        }
        byte[] payload = new byte[payloadLength];
        for (int i = 0; i < payloadLength; i++) {
            // Java int overflow is defined; retaining the low byte implements modulo 256.
            payload[i] = (byte) (sessionId + 31 * sequence + 17 * i);
        }
        return payload;
    }

    public static byte[] encodeRequest(
            int sessionId, int sequence, long androidTxNs, byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("payload == null");
        if (payload.length < MIN_PAYLOAD_LENGTH || payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("PAYLOAD_LEN must be in range 1..2048");
        }

        int crcOffset = REQUEST_HEADER_LENGTH + payload.length;
        byte[] frame = new byte[crcOffset + CRC_LENGTH];
        System.arraycopy(MAGIC, 0, frame, 0, MAGIC.length);
        frame[4] = (byte) VERSION;
        frame[5] = (byte) TYPE_REQUEST;
        putU32Le(frame, 6, sessionId);
        putU32Le(frame, 10, sequence);
        putU16Le(frame, 14, payload.length);
        putU64Le(frame, 16, androidTxNs);
        System.arraycopy(payload, 0, frame, REQUEST_HEADER_LENGTH, payload.length);
        putU32Le(frame, crcOffset, crc32(frame, 4, 20 + payload.length));
        return frame;
    }

    /** Returns the unsigned CRC-32/ISO-HDLC value in the range 0..0xFFFFFFFF. */
    public static long crc32(byte[] data, int offset, int length) {
        if (data == null) throw new IllegalArgumentException("data == null");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("Invalid CRC range");
        }
        long crc = 0xFFFFFFFFL;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFFL;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1L) != 0
                        ? (crc >>> 1) ^ CRC32_REFLECTED_POLYNOMIAL
                        : (crc >>> 1);
            }
        }
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    public static long stm32ProcessingTimeUs(Response response) {
        if (response == null) throw new IllegalArgumentException("response == null");
        // Unsigned modulo-2^32 delta; valid when processing is shorter than one TIM2 period.
        return Integer.toUnsignedLong(response.stm32TxUs - response.stm32RxUs);
    }

    /**
     * Validates the decoded response against one outstanding stop-and-wait request.
     * A null return means structurally and semantically acceptable. A non-OK STATUS
     * is a valid protocol diagnostic, not a successful payload echo.
     */
    public static String validateResponse(
            Response response, int expectedSessionId, int expectedSequence,
            byte[] expectedPayload) {
        if (response == null) return "Missing response";
        if (response.version != VERSION) return "Unsupported response VERSION=" + response.version;
        if (response.type != TYPE_RESPONSE) return "Unexpected response TYPE=" + response.type;
        if (response.sessionId != expectedSessionId) return "SESSION_ID mismatch";
        if (response.sequence != expectedSequence) return "SEQUENCE mismatch";
        if (!isKnownStatus(response.status)) return "Unknown STATUS=" + response.status;

        if (response.status == STATUS_CRC_ERROR && response.payload.length != 0) {
            return "CRC_ERROR response must have PAYLOAD_LEN=0";
        }
        if (response.status == STATUS_OK) {
            if (expectedPayload == null) return "Missing expected payload";
            if (response.payload.length != expectedPayload.length) {
                return "OK response PAYLOAD_LEN mismatch";
            }
            if (!Arrays.equals(response.payload, expectedPayload)) {
                return "PAYLOAD echo mismatch";
            }
        }
        return null;
    }

    public static boolean isKnownStatus(int status) {
        return status >= STATUS_OK && status <= STATUS_CRC_ERROR;
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_OK: return "OK";
            case STATUS_LENGTH_ERROR: return "LENGTH_ERROR";
            case STATUS_PAYLOAD_ERROR: return "PAYLOAD_ERROR";
            case STATUS_UNSUPPORTED_TYPE: return "UNSUPPORTED_TYPE";
            case STATUS_CRC_ERROR: return "CRC_ERROR";
            default: return "UNKNOWN(" + status + ")";
        }
    }

    public static final class Response {
        public final int version;
        public final int type;
        public final int sessionId;
        public final int sequence;
        public final int stm32RxUs;
        public final int stm32TxUs;
        public final int status;
        public final byte[] payload;

        private Response(int version, int type, int sessionId, int sequence,
                         int stm32RxUs, int stm32TxUs, int status, byte[] payload) {
            this.version = version;
            this.type = type;
            this.sessionId = sessionId;
            this.sequence = sequence;
            this.stm32RxUs = stm32RxUs;
            this.stm32TxUs = stm32TxUs;
            this.status = status;
            this.payload = payload;
        }

        public int payloadLength() {
            return payload.length;
        }
    }

    /**
     * Incremental response decoder. USB transfer boundaries are deliberately ignored.
     * Valid frames consume their complete declared length, so MAGIC inside a valid
     * payload cannot split that frame. On malformed candidates it advances one byte
     * and searches again, retaining a possible 1..3-byte MAGIC prefix across feeds.
     */
    public static final class StreamDecoder {
        private static final int MAX_BUFFER_LENGTH = MAX_RESPONSE_FRAME_LENGTH * 4;
        private byte[] buffer = new byte[MAX_RESPONSE_FRAME_LENGTH + 512];
        private int size;
        private long crcErrorCount;
        private long invalidLengthCount;
        private long bufferOverflowCount;

        public synchronized List<Response> feed(byte[] data, int offset, int length) {
            if (data == null) throw new IllegalArgumentException("data == null");
            if (offset < 0 || length < 0 || offset > data.length - length) {
                throw new IndexOutOfBoundsException("Invalid input range");
            }
            if (length == 0) return new ArrayList<>();

            if (length > MAX_BUFFER_LENGTH) {
                offset += length - MAX_BUFFER_LENGTH;
                length = MAX_BUFFER_LENGTH;
                size = 0;
                bufferOverflowCount++;
            } else if (size + length > MAX_BUFFER_LENGTH) {
                // A discontinuity is safer than retaining a partial, ambiguous frame.
                size = 0;
                bufferOverflowCount++;
            }
            ensureCapacity(size + length);
            System.arraycopy(data, offset, buffer, size, length);
            size += length;

            List<Response> decoded = new ArrayList<>();
            while (true) {
                int magicAt = findMagic();
                if (magicAt < 0) {
                    retainMagicPrefix();
                    break;
                }
                if (magicAt > 0) discard(magicAt);
                if (size < RESPONSE_HEADER_LENGTH) break;

                int payloadLength = readU16Le(buffer, 14);
                if (payloadLength > MAX_PAYLOAD_LENGTH) {
                    invalidLengthCount++;
                    discard(1);
                    continue;
                }

                int frameLength = RESPONSE_HEADER_LENGTH + payloadLength + CRC_LENGTH;
                if (size < frameLength) break;

                int crcOffset = RESPONSE_HEADER_LENGTH + payloadLength;
                long expectedCrc = readU32Le(buffer, crcOffset);
                long actualCrc = crc32(buffer, 4, 21 + payloadLength);
                if (actualCrc != expectedCrc) {
                    crcErrorCount++;
                    discard(1);
                    continue;
                }

                byte[] payload = Arrays.copyOfRange(
                        buffer, RESPONSE_HEADER_LENGTH, RESPONSE_HEADER_LENGTH + payloadLength);
                decoded.add(new Response(
                        buffer[4] & 0xFF,
                        buffer[5] & 0xFF,
                        readU32LeAsInt(buffer, 6),
                        readU32LeAsInt(buffer, 10),
                        readU32LeAsInt(buffer, 16),
                        readU32LeAsInt(buffer, 20),
                        buffer[24] & 0xFF,
                        payload));
                discard(frameLength);
            }
            return decoded;
        }

        public synchronized void reset() {
            size = 0;
        }

        public synchronized long getCrcErrorCount() {
            return crcErrorCount;
        }

        public synchronized long getInvalidLengthCount() {
            return invalidLengthCount;
        }

        public synchronized long getBufferOverflowCount() {
            return bufferOverflowCount;
        }

        private void ensureCapacity(int required) {
            if (required <= buffer.length) return;
            int capacity = Math.min(MAX_BUFFER_LENGTH,
                    Math.max(required, buffer.length * 2));
            buffer = Arrays.copyOf(buffer, capacity);
        }

        private int findMagic() {
            for (int i = 0; i <= size - MAGIC.length; i++) {
                if (buffer[i] == MAGIC[0] && buffer[i + 1] == MAGIC[1]
                        && buffer[i + 2] == MAGIC[2] && buffer[i + 3] == MAGIC[3]) {
                    return i;
                }
            }
            return -1;
        }

        private void retainMagicPrefix() {
            int keep = 0;
            int max = Math.min(MAGIC.length - 1, size);
            for (int n = max; n > 0; n--) {
                boolean match = true;
                for (int i = 0; i < n; i++) {
                    if (buffer[size - n + i] != MAGIC[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    keep = n;
                    break;
                }
            }
            if (keep > 0) {
                System.arraycopy(buffer, size - keep, buffer, 0, keep);
            }
            size = keep;
        }

        private void discard(int count) {
            if (count >= size) {
                size = 0;
                return;
            }
            System.arraycopy(buffer, count, buffer, 0, size - count);
            size -= count;
        }
    }

    private static void putU16Le(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
    }

    private static void putU32Le(byte[] out, int offset, long value) {
        for (int i = 0; i < 4; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void putU64Le(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static int readU16Le(byte[] in, int offset) {
        return (in[offset] & 0xFF) | ((in[offset + 1] & 0xFF) << 8);
    }

    private static long readU32Le(byte[] in, int offset) {
        return (in[offset] & 0xFFL)
                | ((in[offset + 1] & 0xFFL) << 8)
                | ((in[offset + 2] & 0xFFL) << 16)
                | ((in[offset + 3] & 0xFFL) << 24);
    }

    private static int readU32LeAsInt(byte[] in, int offset) {
        return (int) readU32Le(in, offset);
    }
}
