// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
/*
 * The QRC decryption is ported from HyperLyrics Enhanced (juren233/HyperLyrics-Enhanced,
 * online/utils/TripleDesCustom.kt and QmCryptoUtils in CryptoUtils.kt), GPL-3.0, and is used here
 * under this project's AGPL-3.0 (GPLv3 section 13). Rewritten in Java; the tables are theirs.
 */
package btm.m.os4.systemuihook.hypermusiccover.next.lyrics;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * QQ Music's lyric encryption: hex, then a Triple-DES of QQ's own - the DES round function and
 * tables are standard, the bit order they read the block in is not, so no platform cipher will
 * do - then zlib. Pure Java, so it can be checked off the device.
 */
final class QrcCrypt {

    private QrcCrypt() {
    }

    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.UTF_8);

    private static final int ENCRYPT = 1;
    private static final int DECRYPT = 0;

    /** The plain text of one of QQ's encrypted lyric fields, or "" when it does not decrypt. */
    static String decrypt(String rawHex) {
        if (rawHex == null) return "";
        StringBuilder hex = new StringBuilder(rawHex.length());
        for (int i = 0; i < rawHex.length(); i++) {
            char c = rawHex.charAt(i);
            if (Character.digit(c, 16) >= 0) hex.append(c);
        }
        if (hex.length() == 0) return "";
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        if (data.length % 8 != 0) return "";
        byte[] plain = new byte[data.length];
        byte[] block = new byte[8];
        for (int i = 0; i + 8 <= data.length; i += 8) {
            System.arraycopy(data, i, block, 0, 8);
            byte[] t = block;
            for (int k = 0; k < 3; k++) t = cryptBlock(t, SCHEDULES[k]);
            System.arraycopy(t, 0, plain, i, 8);
        }
        return inflate(plain);
    }

    private static String inflate(byte[] data) {
        Inflater inflater = new Inflater(false);
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
        try {
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            inflater.end();
        }
    }

    private static byte[] slice(byte[] a, int from) {
        byte[] out = new byte[8];
        System.arraycopy(a, from, out, 0, 8);
        return out;
    }

    private static final int[][] SBOX = {
            {14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                    0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                    4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                    15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13},
            {15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                    3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                    0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                    13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9},
            {10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                    13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                    13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                    1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12},
            {7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                    13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                    10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                    3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14},
            {2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                    14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                    4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                    11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3},
            {12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                    10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                    9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                    4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13},
            {4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                    13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                    1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                    6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12},
            {13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                    1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                    7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                    2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11},
    };

    private static int bitnum(byte[] a, int b, int c) {
        int byteIndex = (b / 32) * 4 + 3 - (b % 32) / 8;
        if (byteIndex >= a.length) return 0;
        int bit = ((a[byteIndex] & 0xFF) >> (7 - (b % 8))) & 1;
        return bit << c;
    }

    private static int bitnumIntr(int a, int b, int c) {
        return ((a >>> (31 - b)) & 1) << c;
    }

    private static int bitnumIntl(int a, int b, int c) {
        return ((a << b) & 0x80000000) >>> c;
    }

    private static int sboxBit(int a) {
        return (a & 32) | ((a & 31) >>> 1) | ((a & 1) << 4);
    }

    private static byte[] cryptBlock(byte[] in, int[][] key) {
        // The initial permutation: s0 takes the odd source bits 57, 49 .. 1, then 59 .. 3,
        // 61 .. 5, 63 .. 7 into bits 31 .. 0; s1 the even ones from 56, 58, 60, 62.
        int s0 = 0, s1 = 0;
        int pos = 31;
        for (int row = 0; row < 4; row++) {
            for (int k = 0; k < 8; k++, pos--) {
                s0 |= bitnum(in, 57 + 2 * row - 8 * k, pos);
                s1 |= bitnum(in, 56 + 2 * row - 8 * k, pos);
            }
        }
        for (int idx = 0; idx < 15; idx++) {
            int prev = s1;
            s1 = f(s1, key[idx]) ^ s0;
            s0 = prev;
        }
        s0 = f(s1, key[15]) ^ s0;
        // The inverse permutation. Byte BYTE_OF[k] gathers bits k, k+8, k+16, k+24 of both
        // halves, s1 above s0 at each.
        byte[] out = new byte[8];
        for (int k = 0; k < 8; k++) {
            int v = 0;
            for (int m = 0; m < 4; m++) {
                v |= bitnumIntr(s1, k + 8 * m, 7 - 2 * m) | bitnumIntr(s0, k + 8 * m, 6 - 2 * m);
            }
            out[BYTE_OF[k]] = (byte) v;
        }
        return out;
    }

    private static final int[] BYTE_OF = {4, 5, 6, 7, 0, 1, 2, 3};

    /** Where each bit of the S-box output goes in the round function's result. */
    private static final int[] P = {15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
            1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24};

    private static int f(int state, int[] key) {
        int t1 = bitnumIntl(state, 31, 0) | ((state & 0xF0000000) >>> 1) | bitnumIntl(state, 4, 5)
                | bitnumIntl(state, 3, 6) | ((state & 0x0f000000) >>> 3) | bitnumIntl(state, 8, 11)
                | bitnumIntl(state, 7, 12) | ((state & 0x00f00000) >>> 5) | bitnumIntl(state, 12, 17)
                | bitnumIntl(state, 11, 18) | ((state & 0x000f0000) >>> 7) | bitnumIntl(state, 16, 23);
        int t2 = bitnumIntl(state, 15, 0) | ((state & 0x0000f000) << 15) | bitnumIntl(state, 20, 5)
                | bitnumIntl(state, 19, 6) | ((state & 0x00000f00) << 13) | bitnumIntl(state, 24, 11)
                | bitnumIntl(state, 23, 12) | ((state & 0x000000f0) << 11) | bitnumIntl(state, 28, 17)
                | bitnumIntl(state, 27, 18) | ((state & 0x0000000f) << 9) | bitnumIntl(state, 0, 23);
        int[] l = {
                ((t1 >>> 24) & 0xff) ^ key[0], ((t1 >>> 16) & 0xff) ^ key[1],
                ((t1 >>> 8) & 0xff) ^ key[2], ((t2 >>> 24) & 0xff) ^ key[3],
                ((t2 >>> 16) & 0xff) ^ key[4], ((t2 >>> 8) & 0xff) ^ key[5],
        };
        int res = (SBOX[0][sboxBit(l[0] >>> 2)] << 28)
                | (SBOX[1][sboxBit(((l[0] & 0x03) << 4) | (l[1] >>> 4))] << 24)
                | (SBOX[2][sboxBit(((l[1] & 0x0f) << 2) | (l[2] >>> 6))] << 20)
                | (SBOX[3][sboxBit(l[2] & 0x3f)] << 16)
                | (SBOX[4][sboxBit(l[3] >>> 2)] << 12)
                | (SBOX[5][sboxBit(((l[3] & 0x03) << 4) | (l[4] >>> 4))] << 8)
                | (SBOX[6][sboxBit(((l[4] & 0x0f) << 2) | (l[5] >>> 6))] << 4)
                | SBOX[7][sboxBit(l[5] & 0x3f)];
        int out = 0;
        for (int i = 0; i < 32; i++) out |= bitnumIntl(res, P[i], i);
        return out;
    }

    private static final int[] KEY_RND_SHIFT = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
    private static final int[] KEY_PERM_C = {56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
            9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35};
    private static final int[] KEY_PERM_D = {62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
            13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3};
    private static final int[] KEY_COMPRESSION = {13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22,
            18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32,
            47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31};

    private static int[][] keySchedule(byte[] key, int mode) {
        int[][] schedule = new int[16][6];
        int c = 0, d = 0;
        for (int i = 0; i < 28; i++) c += bitnum(key, KEY_PERM_C[i], 31 - i);
        for (int i = 0; i < 28; i++) d += bitnum(key, KEY_PERM_D[i], 31 - i);
        for (int i = 0; i < 16; i++) {
            int s = KEY_RND_SHIFT[i];
            c = ((c << s) | (c >>> (28 - s))) & 0xFFFFFFF0;
            d = ((d << s) | (d >>> (28 - s))) & 0xFFFFFFF0;
            int togen = mode == DECRYPT ? 15 - i : i;
            for (int j = 0; j < 6; j++) schedule[togen][j] = 0;
            for (int j = 0; j < 24; j++) {
                schedule[togen][j / 8] |= bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8));
            }
            for (int j = 24; j < 48; j++) {
                schedule[togen][j / 8] |= bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8));
            }
        }
        return schedule;
    }

    /**
     * The schedules for decryption, built once: the key never changes. Last in the class because
     * static initialisers run in order, and these read every table above.
     */
    private static final int[][][] SCHEDULES = {
            keySchedule(slice(KEY, 16), DECRYPT),
            keySchedule(slice(KEY, 8), ENCRYPT),
            keySchedule(slice(KEY, 0), DECRYPT),
    };
}
