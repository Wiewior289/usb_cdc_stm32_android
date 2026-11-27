package com.example.stm32usbprobe;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CzarProtocolTest {
    @Test
    public void crcMatchesIsoHdlcCheckVector() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xCBF43926L, CzarProtocol.crc32(input, 0, input.length));
    }

    @Test
    public void requestHasFrozenOffsetsAndLittleEndianFields() {
        int session = 0x78563412;
        int sequence = 0x0C0B0A09;
        long timestamp = 0x0807060504030201L;
        byte[] payload = new byte[] {0x55, (byte) 0xAA};
        byte[] frame = CzarProtocol.encodeRequest(session, sequence, timestamp, payload);

        assertEquals(30, frame.length);
        assertArrayEquals(new byte[] {0x43, 0x5A, 0x41, 0x52},
                Arrays.copyOfRange(frame, 0, 4));
        assertEquals(1, frame[4] & 0xFF);
        assertEquals(1, frame[5] & 0xFF);
        assertArrayEquals(new byte[] {0x12, 0x34, 0x56, 0x78},
                Arrays.copyOfRange(frame, 6, 10));
        assertArrayEquals(new byte[] {0x09, 0x0A, 0x0B, 0x0C},
                Arrays.copyOfRange(frame, 10, 14));
        assertArrayEquals(new byte[] {0x02, 0x00},
                Arrays.copyOfRange(frame, 14, 16));
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08},
                Arrays.copyOfRange(frame, 16, 24));
        assertArrayEquals(payload, Arrays.copyOfRange(frame, 24, 26));
        assertArrayEquals(hex("43 5A 41 52 01 01 12 34 56 78 09 0A 0B 0C "
                + "02 00 01 02 03 04 05 06 07 08 29 3A B9 9E B1 38"), frame);
        assertEquals(CzarProtocol.crc32(frame, 4, 22), readU32(frame, 26));
    }

    @Test
    public void payloadUsesModulo256Formula() {
        byte[] payload = CzarProtocol.createPayload(250, 3, 4);
        assertArrayEquals(new byte[] {(byte) 87, (byte) 104, (byte) 121, (byte) 138}, payload);
    }

    @Test
    public void responseMatchesSharedGoldenVector() {
        byte[] frame = hex("43 5A 41 52 01 02 12 34 56 78 09 0A 0B 0C "
                + "02 00 44 33 22 11 99 33 22 11 00 29 3A 07 DA 90 10");
        CzarProtocol.Response response = decode(frame);
        assertEquals(0x78563412, response.sessionId);
        assertEquals(0x0C0B0A09, response.sequence);
        assertEquals(0x11223344, response.stm32RxUs);
        assertEquals(0x11223399, response.stm32TxUs);
        assertEquals(CzarProtocol.STATUS_OK, response.status);
        assertArrayEquals(new byte[] {0x29, 0x3A}, response.payload);
        assertEquals(0x55L, CzarProtocol.stm32ProcessingTimeUs(response));
    }

    @Test
    public void decoderHandlesFragmentationAndPreservesMagicInsidePayload() {
        CzarProtocol.StreamDecoder decoder = new CzarProtocol.StreamDecoder();
        byte[] payload = new byte[] {1, 0x43, 0x5A, 0x41, 0x52, 2, 3};
        byte[] frame = response(0x11223344, 7, payload, 0, 100, 145);

        assertTrue(decoder.feed(frame, 0, 3).isEmpty());
        assertTrue(decoder.feed(frame, 3, 11).isEmpty());
        List<CzarProtocol.Response> decoded =
                decoder.feed(frame, 14, frame.length - 14);

        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.get(0).payload);
        assertEquals(0x11223344, decoded.get(0).sessionId);
        assertEquals(7, decoded.get(0).sequence);
        assertEquals(45L, CzarProtocol.stm32ProcessingTimeUs(decoded.get(0)));
    }

    @Test
    public void decoderHandlesConcatenatedFrames() {
        byte[] a = response(1, 10, new byte[] {10}, 0, 1, 3);
        byte[] b = response(1, 11, new byte[] {11, 12}, 0, 4, 9);
        byte[] both = concat(a, b);

        List<CzarProtocol.Response> decoded =
                new CzarProtocol.StreamDecoder().feed(both, 0, both.length);
        assertEquals(2, decoded.size());
        assertEquals(10, decoded.get(0).sequence);
        assertEquals(11, decoded.get(1).sequence);
    }

    @Test
    public void decoderRejectsBadCrcThenResynchronizesToNextFrame() {
        byte[] bad = response(1, 1, new byte[] {1, 2, 3}, 0, 5, 8);
        bad[bad.length - 1] ^= 0x40;
        byte[] good = response(1, 2, new byte[] {4, 5}, 0, 9, 14);
        byte[] stream = concat(bad, good);

        CzarProtocol.StreamDecoder decoder = new CzarProtocol.StreamDecoder();
        List<CzarProtocol.Response> decoded = decoder.feed(stream, 0, stream.length);
        assertEquals(1, decoded.size());
        assertEquals(2, decoded.get(0).sequence);
        assertTrue(decoder.getCrcErrorCount() >= 1);
    }

    @Test
    public void decoderRejectsOversizedLengthAndFindsNextMagic() {
        byte[] invalidHeader = new byte[25];
        System.arraycopy(CzarProtocol.MAGIC, 0, invalidHeader, 0, 4);
        invalidHeader[4] = 1;
        invalidHeader[5] = 2;
        invalidHeader[14] = (byte) 0x01;
        invalidHeader[15] = (byte) 0x08; // 2049
        byte[] good = response(2, 3, new byte[] {9}, 0, 10, 11);

        byte[] stream = concat(invalidHeader, good);
        CzarProtocol.StreamDecoder decoder = new CzarProtocol.StreamDecoder();
        List<CzarProtocol.Response> decoded = decoder.feed(stream, 0, stream.length);
        assertEquals(1, decoded.size());
        assertEquals(3, decoded.get(0).sequence);
        assertTrue(decoder.getInvalidLengthCount() >= 1);
    }

    @Test
    public void diagnosticCrcErrorMustHaveEmptyPayload() {
        CzarProtocol.Response response = decode(response(7, 8, new byte[0],
                CzarProtocol.STATUS_CRC_ERROR, 20, 25));
        assertNull(CzarProtocol.validateResponse(response, 7, 8, new byte[] {1}));
        CzarProtocol.Response invalid = decode(response(7, 8, new byte[] {1},
                CzarProtocol.STATUS_CRC_ERROR, 20, 25));
        assertNotNull(CzarProtocol.validateResponse(invalid, 7, 8, new byte[] {1}));
    }

    @Test
    public void responseValidationChecksSessionSequenceAndEcho() {
        byte[] payload = new byte[] {7, 8, 9};
        CzarProtocol.Response response = decode(response(0x1234, 99, payload, 0, 1, 2));
        assertNull(CzarProtocol.validateResponse(response, 0x1234, 99, payload));
        assertNotNull(CzarProtocol.validateResponse(response, 0x1235, 99, payload));
        assertNotNull(CzarProtocol.validateResponse(response, 0x1234, 100, payload));
        assertNotNull(CzarProtocol.validateResponse(response, 0x1234, 99,
                new byte[] {7, 8, 0}));
    }

    @Test
    public void payloadLengthBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> CzarProtocol.createPayload(1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CzarProtocol.createPayload(1, 1, 2049));
        assertEquals(2076, CzarProtocol.encodeRequest(1, 2, 3,
                new byte[2048]).length);
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    private static CzarProtocol.Response decode(byte[] frame) {
        List<CzarProtocol.Response> decoded =
                new CzarProtocol.StreamDecoder().feed(frame, 0, frame.length);
        assertEquals(1, decoded.size());
        return decoded.get(0);
    }

    private static byte[] response(int session, int sequence, byte[] payload,
                                   int status, int rxUs, int txUs) {
        int crcOffset = 25 + payload.length;
        byte[] frame = new byte[crcOffset + 4];
        System.arraycopy(CzarProtocol.MAGIC, 0, frame, 0, 4);
        frame[4] = 1;
        frame[5] = 2;
        put32(frame, 6, session);
        put32(frame, 10, sequence);
        frame[14] = (byte) payload.length;
        frame[15] = 0;
        put32(frame, 16, rxUs);
        put32(frame, 20, txUs);
        frame[24] = (byte) status;
        System.arraycopy(payload, 0, frame, 25, payload.length);
        put32(frame, crcOffset, (int) CzarProtocol.crc32(frame, 4, 21 + payload.length));
        return frame;
    }

    private static void put32(byte[] out, int offset, int value) {
        for (int i = 0; i < 4; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
