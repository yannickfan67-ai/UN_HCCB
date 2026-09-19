package io.github.yannickfan67.unhccb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Clean-room Java port of the reverse-engineered Microsoft Tag / HCCB 5x10 codec. */
public final class HccbCodec {
    private HccbCodec() {}

    private static final byte[] XOR_TABLE = hex(
            "0f4d9417483a861c02f8e3e896e8074c" +
            "d1f7183b18cfc97723630430fdc82fcb" +
            "2104c488055a43d3e2886a8a70d19be6" +
            "996910475f80830624b253fd6478905a" +
            "6250c64a49df7d2c9b08860c7a612b6a" +
            "a5d107123046ce404b7786ea2bcb42fe" +
            "8a8a3dc1");

    private static final int GF_M = 5;
    private static final int GF_N = 31;
    private static final int GF_PRIMITIVE = 0x25;
    private static final int RS_PARITY_SYMBOLS = 4;
    private static final int RS_DATA_CAPACITY = 27;
    private static final int[] GF_ALPHA = new int[GF_N];
    private static final int[] GF_INDEX = new int[1 << GF_M];
    private static final int[] RS_GENERATOR_LOGS = {10, 29, 19, 24, 0};

    static {
        Arrays.fill(GF_INDEX, GF_N);
        GF_ALPHA[0] = 1;
        for (int i = 1; i < GF_N; i++) {
            int x = GF_ALPHA[i - 1] << 1;
            if ((x & (1 << GF_M)) != 0) x ^= GF_PRIMITIVE;
            GF_ALPHA[i] = x & GF_N;
        }
        for (int i = 0; i < GF_ALPHA.length; i++) GF_INDEX[GF_ALPHA[i]] = i;
        GF_INDEX[0] = GF_N;
    }

    public static final class DecodeResult {
        public final int prefix;
        public final long code;
        public final String tid;
        public final boolean crcOk;
        public final boolean parityOk;
        public final boolean fillerOk;
        public final boolean calibrationOk;
        public final int correctedCells;

        DecodeResult(int prefix, long code, boolean crcOk, boolean parityOk,
                     boolean fillerOk, boolean calibrationOk, int correctedCells) {
            this.prefix = prefix;
            this.code = code;
            this.tid = String.format(java.util.Locale.US, "%05d_%010d", prefix, code);
            this.crcOk = crcOk;
            this.parityOk = parityOk;
            this.fillerOk = fillerOk;
            this.calibrationOk = calibrationOk;
            this.correctedCells = correctedCells;
        }

        public boolean strong() { return crcOk && fillerOk && calibrationOk; }
    }

    public static int[] encode(int prefix, long code) {
        if (prefix < 0 || prefix > 0xFFFF) throw new IllegalArgumentException("prefix out of range");
        if (code < 0 || code > 0xFFFFFFFFL) throw new IllegalArgumentException("code out of range");
        byte[] raw8 = buildRaw8(prefix, code);
        byte[] parity = rsParityBytes(raw8);
        byte[] rsbuf = new byte[11];
        System.arraycopy(parity, 0, rsbuf, 0, 3);
        System.arraycopy(raw8, 0, rsbuf, 3, 8);
        byte[] xored = whiten(rsbuf);
        int[] data = bytesToColorSymbols(xored);
        int[] cells = Arrays.copyOf(data, 50);
        cells[44] = 0;
        cells[45] = 1;
        cells[46] = 0;
        cells[47] = 1;
        cells[48] = 2;
        cells[49] = 3;
        return cells;
    }

    public static DecodeResult decode(int[] cells) {
        return decodeInternal(cells, 0);
    }

    public static DecodeResult decodeResilient(int[] cells, int maxErrors) {
        DecodeResult d = decodeInternal(cells, 0);
        if (d.strong()) return d;
        if (maxErrors <= 0) throw new IllegalArgumentException("CRC/trailer validation failed");
        for (int p = 0; p < 44; p++) {
            int old = cells[p];
            for (int alt = 0; alt < 4; alt++) {
                if (alt == old) continue;
                int[] trial = cells.clone();
                trial[p] = alt;
                DecodeResult r = decodeInternal(trial, 1);
                if (r.strong()) return r;
            }
        }
        if (maxErrors <= 1) throw new IllegalArgumentException("No valid 1-cell correction");
        for (int p1 = 0; p1 < 44; p1++) {
            for (int p2 = p1 + 1; p2 < 44; p2++) {
                int o1 = cells[p1], o2 = cells[p2];
                for (int a1 = 0; a1 < 4; a1++) {
                    if (a1 == o1) continue;
                    for (int a2 = 0; a2 < 4; a2++) {
                        if (a2 == o2) continue;
                        int[] trial = cells.clone();
                        trial[p1] = a1;
                        trial[p2] = a2;
                        DecodeResult r = decodeInternal(trial, 2);
                        if (r.strong()) return r;
                    }
                }
            }
        }
        throw new IllegalArgumentException("No valid 2-cell correction");
    }

    private static DecodeResult decodeInternal(int[] cells, int corrected) {
        if (cells == null || cells.length != 50) throw new IllegalArgumentException("need 50 cells");
        for (int c : cells) if (c < 0 || c > 3) throw new IllegalArgumentException("cell out of range");
        int[] first44 = Arrays.copyOf(cells, 44);
        byte[] rsbuf = whiten(colorSymbolsToBytes(first44));
        byte[] parity = Arrays.copyOfRange(rsbuf, 0, 3);
        byte[] raw8 = Arrays.copyOfRange(rsbuf, 3, 11);
        int storedCrc = u8(raw8[0]) | (u8(raw8[1]) << 8);
        byte[] body = Arrays.copyOfRange(raw8, 2, 8);
        int calcCrc = crc16X25(body);
        int prefix = u8(body[0]) | (u8(body[1]) << 8);
        long code = (long)u8(body[2]) | ((long)u8(body[3]) << 8) |
                ((long)u8(body[4]) << 16) | ((long)u8(body[5]) << 24);
        boolean parityOk = Arrays.equals(parity, rsParityBytes(raw8));
        boolean fillerOk = cells[44] == 0 && cells[45] == 1;
        boolean calOk = cells[46] == 0 && cells[47] == 1 && cells[48] == 2 && cells[49] == 3;
        return new DecodeResult(prefix, code, storedCrc == calcCrc, parityOk, fillerOk, calOk, corrected);
    }

    public static int crc16X25(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= u8(b);
            for (int i = 0; i < 8; i++) crc = (crc >>> 1) ^ (((crc & 1) != 0) ? 0x8408 : 0);
        }
        return (crc ^ 0xFFFF) & 0xFFFF;
    }

    private static byte[] buildRaw8(int prefix, long code) {
        byte[] body = new byte[6];
        putLe16(body, 0, prefix);
        putLe32(body, 2, code);
        int crc = crc16X25(body);
        byte[] raw = new byte[8];
        putLe16(raw, 0, crc);
        System.arraycopy(body, 0, raw, 2, 6);
        return raw;
    }

    private static byte[] rsParityBytes(byte[] raw8) {
        int[] data = bytesTo5BitSymbols(raw8);
        int[] parity = new int[RS_PARITY_SYMBOLS];
        for (int i = RS_DATA_CAPACITY - 1; i >= 0; i--) {
            int datum = i < data.length ? data[i] : 0;
            int feedbackValue = datum ^ parity[RS_PARITY_SYMBOLS - 1];
            int feedback = GF_INDEX[feedbackValue];
            if (feedback != GF_N) {
                for (int j = RS_PARITY_SYMBOLS - 1; j > 0; j--) {
                    parity[j] = parity[j - 1] ^ GF_ALPHA[(feedback + RS_GENERATOR_LOGS[j]) % GF_N];
                }
                parity[0] = GF_ALPHA[(feedback + RS_GENERATOR_LOGS[0]) % GF_N];
            } else {
                for (int j = RS_PARITY_SYMBOLS - 1; j > 0; j--) parity[j] = parity[j - 1];
                parity[0] = 0;
            }
        }
        return pack5BitSymbols(parity);
    }

    private static int[] bytesTo5BitSymbols(byte[] data) {
        List<Integer> out = new ArrayList<>();
        long acc = 0;
        int bits = 0;
        for (byte value : data) {
            acc = (acc << 8) | u8(value);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.add((int)((acc >> bits) & 0x1F));
                acc &= bits == 0 ? 0 : ((1L << bits) - 1);
            }
        }
        if (bits > 0) out.add((int)((acc << (5 - bits)) & 0x1F));
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static byte[] pack5BitSymbols(int[] symbols) {
        int acc = 0, bits = 0;
        byte[] tmp = new byte[(symbols.length * 5 + 7) / 8];
        int n = 0;
        for (int sym : symbols) {
            acc = (acc << 5) | sym;
            bits += 5;
            while (bits >= 8) {
                bits -= 8;
                tmp[n++] = (byte)((acc >> bits) & 0xFF);
                acc &= bits == 0 ? 0 : ((1 << bits) - 1);
            }
        }
        if (bits > 0) tmp[n++] = (byte)((acc << (8 - bits)) & 0xFF);
        return Arrays.copyOf(tmp, n);
    }

    private static byte[] whiten(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte)(u8(data[i]) ^ u8(XOR_TABLE[i % XOR_TABLE.length]));
        return out;
    }

    private static int[] bytesToColorSymbols(byte[] data) {
        int[] out = new int[data.length * 4];
        int n = 0;
        for (int pos = 0; pos < data.length * 8; pos += 2) {
            int b0 = (u8(data[pos / 8]) >> (pos % 8)) & 1;
            int p1 = pos + 1;
            int b1 = (u8(data[p1 / 8]) >> (p1 % 8)) & 1;
            out[n++] = (b0 << 1) | b1;
        }
        return out;
    }

    private static byte[] colorSymbolsToBytes(int[] symbols) {
        byte[] out = new byte[(symbols.length * 2 + 7) / 8];
        int bitPos = 0;
        for (int s : symbols) {
            int[] bits = {(s >> 1) & 1, s & 1};
            for (int bit : bits) {
                if (bit != 0) out[bitPos / 8] |= (byte)(1 << (bitPos % 8));
                bitPos++;
            }
        }
        return out;
    }

    private static int u8(byte b) { return b & 0xFF; }
    private static void putLe16(byte[] a, int o, int v) { a[o]=(byte)v; a[o+1]=(byte)(v>>>8); }
    private static void putLe32(byte[] a, int o, long v) {
        a[o]=(byte)v; a[o+1]=(byte)(v>>>8); a[o+2]=(byte)(v>>>16); a[o+3]=(byte)(v>>>24);
    }
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length()/2];
        for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return out;
    }
}
