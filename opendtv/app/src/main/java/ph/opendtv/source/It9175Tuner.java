package ph.opendtv.source;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Tuner / demodulator logic of the IT9175 ("SAMBA" tuner), ported from the vendor libpt275aDrv.so.
 * Pure Java: all chip access goes through {@link Io}, so it can be diff-tested on a JVM against traces of
 * the real vendor library (tools/emu, run under a CPU emulator against a fake device).
 *
 * Flow (same order as the vendor): {@link #init} after the firmware is running and LINK 0xf103 = 0x1a,
 * OFDM 0xec4c = 0xa8; then {@link #tune}, {@link #waitLock}, {@link #getStatus}.
 *
 * Table-driven parts (register scripts, bandwidth tables, per-channel band rows) come from an {@link It9175Profile}
 * ({@link It9175Data} for the PT275, {@link It9175DataPtx1} for the PT275C).
 * Everything that depends on values read back from the chip (crystal index, tuner firmware id / clock,
 * 0x1dc, 0x1c6, ed81) is computed here at run time.
 */
final class It9175Tuner {
    interface Io {
        /** Reads n registers, null on failure. proc 0 = LINK, 8 = OFDM. */
        byte[] read(int proc, long addr, int n);

        boolean write(int proc, long addr, byte[] data);

        /** Command 0x22 (firmware version) - the vendor sends it as a dummy "ping". */
        boolean fwVersion(int proc);

        void sleep(int ms);
    }

    private static final int LINK = 0;
    private static final int OFDM = 8;
    private static final int BW_KHZ = 6000;
    /** nv divider table (rodata 0x18360). */
    private static final int[] NV = {48, 32, 24, 16, 12, 8, 6, 4, 2};

    private final Io io;
    private final StringBuilder log;
    /** Firmware-specific scripts and band tables (they differ between the PT275 and PT275C vendor drivers). */
    private final It9175Profile profile;

    /** LINK 0xd800 low nibble (crystal index). */
    int clock = -1;
    /** SAMBA tuner firmware parameters read at init. */
    int tunerType, ed03, ed23;
    private long mulP, mulQ;
    /** samba band thresholds, index = byte offset / 4 within the vendor table (1..8 used). */
    private final long[] thr = {53000, 74000, 111000, 148000, 222000, 296000, 445000, 573000, 950000};
    private boolean initDone;
    /** Result of the last 'Q' op (OFDM 0x4d != 0), consumed by 'I' ops. */
    private boolean flag;
    int lastFreqKhz;

    It9175Tuner(Io io, StringBuilder log) {
        this(io, log, It9175Profile.PT275A);
    }

    It9175Tuner(Io io, StringBuilder log, It9175Profile profile) {
        this.io = io;
        this.log = log;
        this.profile = profile;
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private int rd(int proc, long addr) {
        byte[] b = io.read(proc, addr, 1);
        return b == null ? -1 : (b[0] & 0xFF);
    }

    private boolean wr(int proc, long addr, int v) {
        return io.write(proc, addr, new byte[] {(byte) v});
    }

    private static byte[] unhex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    /** Executes one generated op (see It9175Data for the syntax). */
    private boolean exec(String op) {
        char c = op.charAt(0);
        int proc = op.charAt(1) - '0';
        String[] p = (op.length() > 3 ? op.substring(3) : "").split(":");
        switch (c) {
            case 'W':
                return io.write(proc, Long.parseLong(p[0], 16), unhex(p[1]));
            case 'M': {
                long a = Long.parseLong(p[0], 16);
                int r = rd(proc, a);
                if (r < 0) return false;
                return wr(proc, a, (r & Integer.parseInt(p[1], 16)) | Integer.parseInt(p[2], 16));
            }
            case 'V':
                return io.fwVersion(proc);
            case 'Q': {
                int r = rd(proc, Long.parseLong(p[0], 16));
                if (r < 0) return false;
                flag = r != 0;
                return true;
            }
            case 'I':
                return !flag || io.write(proc, Long.parseLong(p[0], 16), unhex(p[1]));
            case 'P': {
                long a = Long.parseLong(p[0], 16);
                for (int i = 0; i < 150; i++) {
                    int r = rd(proc, a);
                    if (r < 0) return false;
                    if (r == 0) return true;
                    io.sleep(2);
                }
                log.append("poll 0x").append(Long.toHexString(a)).append(" timed out\n");
                return true; // the vendor ignores the timeout too
            }
            default:
                throw new IllegalStateException(op);
        }
    }

    private boolean run(String[] ops) {
        for (String op : ops) {
            if (!exec(op)) {
                log.append("op failed: ").append(op).append('\n');
                return false;
            }
        }
        return true;
    }

    private int clockIndex() {
        return clock == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------------------------------------
    // init
    // ---------------------------------------------------------------------------------------------

    boolean init() {
        initDone = false;
        int d800 = rd(LINK, 0xd800);
        if (d800 < 0) return false;
        clock = d800 & 0x0F;
        log.append(String.format(Locale.US, "LINK 0xd800=0x%02x -> clock index %d\n", d800, clock));
        if (clock > 1) {
            log.append("clock index > 1: vendor takes an unported path, falling back to the index-1 tables\n");
        }
        if (!run(clockIndex() == 0 ? profile.initA0 : profile.initA1)) return false;
        if (!sambaInit()) return false;
        if (!run(clockIndex() == 0 ? profile.initB0 : profile.initB1)) return false;
        // lib275a_PIDOnOff(0) (hardware PID filter off = pass the whole TS), lib275a_set_oneseg(0)
        if (!run(new String[] {"M8:f993:fe:00", "M8:f992:fe:01", "W8:1e7:00"})) return false;
        initDone = true;
        return true;
    }

    /** samba_init: read the tuner firmware's parameters and derive the band thresholds. */
    private boolean sambaInit() {
        int t = rd(OFDM, 0xec86);
        int d3 = rd(OFDM, 0xed03);
        byte[] c = io.read(OFDM, 0xed23, 2);
        if (t < 0 || d3 < 0 || c == null) return false;
        tunerType = t;
        ed03 = d3;
        int v = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
        int ed81;
        if (t == 0) {
            mulP = 2000;
            mulQ = 3;
            ed81 = 0x10;
        } else if (t == 1) {
            mulP = 20480;
            mulQ = 18;
            ed81 = 6;
        } else {
            mulP = 640;
            mulQ = 1;
            ed81 = 6;
        }
        for (int i = 1; v == 0 && i <= 49; i++) {
            io.sleep(2);
            c = io.read(OFDM, 0xed23, 2);
            if (c == null) return false;
            v = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
        }
        ed23 = v;
        log.append(String.format(Locale.US, "tuner fw: ec86=%d ed03=%d ed23=%d\n", t, d3, v));
        if (v == 0) {
            log.append("tuner firmware did not report its clock (ed23 stayed 0)\n");
            return false;
        }
        long nv = getNv(d3);
        long x5 = (long) v * mulP;
        long x8 = x5 / mulQ;
        long x7 = new BigInteger(Long.toString(x8)).multiply(new BigInteger("AAAAAAAAAAAAAAAB", 16))
                .shiftRight(64).longValue();
        thr[5] = (x8 >>> 3) & 0xFFFFFFFFL;
        thr[3] = (x8 >>> 4) & 0xFFFFFFFFL;
        thr[7] = (x5 / (nv * mulQ)) & 0xFFFFFFFFL;
        thr[1] = (x8 >>> 5) & 0xFFFFFFFFL;
        thr[6] = (x7 >>> 2) & 0xFFFFFFFFL;
        thr[4] = (x7 >>> 3) & 0xFFFFFFFFL;
        thr[2] = (x7 >>> 4) & 0xFFFFFFFFL;
        // chip 0x9175 rev 1 waits 50 ms; other chips poll OFDM 0xec82 (not ported: this chip is the target)
        int ver = rd(LINK, 0x1222);
        byte[] id = io.read(LINK, 0x1223, 2);
        if (id == null) return false;
        io.sleep(50);
        if (!wr(OFDM, 0xed81, ed81)) return false;
        int r1dc = rd(OFDM, 0x1dc);
        if (r1dc < 0) return false;
        if (r1dc == 1) {
            if (!run(new String[] {"M0:d8d0:fe:01", "M0:d8d1:fe:01"})) return false;
        }
        return true;
    }

    static int getNv(int idx) {
        return (idx >= 0 && idx <= 8) ? NV[idx] : 2;
    }

    // ---------------------------------------------------------------------------------------------
    // frequency math (samba_get_*)
    // ---------------------------------------------------------------------------------------------

    int getNc(long f) {
        if (thr[1] >= f) return 0;
        if (f <= thr[2]) return 1;
        if (f <= thr[3]) return 2;
        if (f <= thr[4]) return 3;
        if (f <= thr[5]) return 4;
        if (f <= thr[6]) return 5;
        if (f <= thr[7]) return 6;
        return f <= thr[8] ? 7 : 8;
    }

    /** returns {nc, nv, fcw}; code = ((nc & 7) << 13) + fcw. */
    long[] freqCode(long f) {
        int nc = getNc(f);
        long nv = getNv(nc);
        long prod = (f * mulQ) * nv; // vendor: umull(f, [0x328]) * nv
        long den = mulP;
        long q = prod / den;
        long rem = prod - q * den;
        if (rem >= (den >>> 1)) q++;
        return new long[] {nc, nv, q & 0xFFFFFFFFL};
    }

    int loFreq(long f) {
        long[] r = freqCode(f);
        return (int) ((((r[0] & 7) << 13) + r[2]) & 0xFFFFFFFFL);
    }

    int calFreqIqik(long f, int p) {
        long[] r = freqCode(f);
        long nc = r[0], nv = r[1], fcw = r[2];
        long x;
        if (tunerType == 0) {
            if (p <= 0x1f) x = fcw + ((9 * nv * p) >>> 5);
            else x = fcw - ((9 * nv * ((64 - p) & 0xFF)) >>> 5);
        } else if (p <= 0x1f && tunerType == 1) {
            x = fcw + ((p * nv) >>> 1);
        } else {
            x = fcw - ((((64 - p) & 0xFF) * nv) >>> 1);
        }
        return (int) ((x + ((nc & 7) << 13)) & 0xFFFFFFFFL);
    }

    static int lnaCapSel(long f) {
        if (f <= 0x6B6C0L) return 0;
        if (f <= 0x762A0L) return 1;
        if (f <= 0x82208L) return 2;
        if (f <= 0x8F4F8L) return 3;
        if (f <= 0x9D788L) return 4;
        if (f <= 0xAD570L) return 5;
        return ((f - 0xAD571L) & 0xFFFFFFFFL) <= 0x1193FL ? 6 : 7;
    }

    static int lpfBw(int bwKhz) {
        switch (bwKhz) {
            case 5000: return 0;
            case 5500: return 1;
            case 6000: return 2;
            case 6500: return 3;
            case 7000: return 4;
            case 7500: return 5;
            case 8000: return 6;
            case 8500: return 7;
            default: return 6;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // tune
    // ---------------------------------------------------------------------------------------------

    private static int[] bandRow(int[][] table, long f) {
        int[] row = table[0];
        for (int[] r : table) {
            if (r[0] <= f) row = r;
            else break;
        }
        return row;
    }

    /** lib275a_SetPara for a 6 MHz channel. */
    boolean tune(int freqKhz) {
        if (!initDone) {
            log.append("tune before init\n");
            return false;
        }
        long f = freqKhz & 0xFFFFFFFFL;
        // Standard_selectBandwidth (6 MHz) + start of Standard_setFrequency
        if (!run(clockIndex() == 0 ? profile.tunePre0 : profile.tunePre1)) return false;
        int v4b;
        if (f <= 29999) v4b = 0xFF;
        else if (f <= 300000) v4b = 0;
        else if (f <= 1000000) v4b = 1;
        else v4b = ((f - 1670000L) & 0xFFFFFFFFL) > 10000 ? 0xFF : 2;
        if (!wr(OFDM, 0x4b, v4b)) return false;
        int r1c6 = rd(OFDM, 0x1c6);
        int r1dc = rd(OFDM, 0x1dc);
        if (r1c6 < 0 || r1dc < 0) return false;
        if (r1c6 != 0) {
            int[] r = bandRow(r1dc == 1 ? profile.bands1 : profile.bands0, f);
            if (!wr(OFDM, 0xfb25, r[1]) || !wr(OFDM, 0xfbb5, r[2]) || !wr(OFDM, 0xfbb6, r[3])
                    || !wr(OFDM, 0xfbb7, r[4]) || !wr(OFDM, 0xfbb9, r[5]) || !wr(OFDM, 0xfbb9, r[6])
                    || !wr(OFDM, 0xfbba, r[7])) {
                return false;
            }
        }
        if (!sambaSetFreq(f, BW_KHZ)) return false;
        if (!wr(OFDM, 0, 0)) return false;
        lastFreqKhz = freqKhz;
        return true;
    }

    private boolean sambaSetFreq(long f, int bwKhz) {
        int ec4c = rd(OFDM, 0xec4c);
        int ed81 = rd(OFDM, 0xed81);
        if (ec4c < 0 || ed81 < 0) return false;
        int lna = lnaCapSel(f);
        int lpf = lpfBw(bwKhz);
        int b5a = (ec4c & 0xE7) | (f > 0x4C2BFL ? 8 : 0);
        int iq = calFreqIqik(f, ed81);
        int lo = loFreq(f);
        if (!wr(OFDM, 0x160, lna) || !wr(OFDM, 0xec56, lpf) || !wr(OFDM, 0xec4c, b5a)
                || !wr(OFDM, 0xec4d, iq & 0xFF) || !wr(OFDM, 0xec4e, (iq >> 8) & 0xFF)
                || !wr(OFDM, 0x15e, lo & 0xFF) || !wr(OFDM, 0x15f, (lo >> 8) & 0xFF)) {
            return false;
        }
        int r1dc = rd(OFDM, 0x1dc);
        if (r1dc < 0) return false;
        if (r1dc == 1) {
            // LINK 0xd8cf bit 0 = (f > 176142 kHz)
            int r = rd(LINK, 0xd8cf);
            if (r < 0) return false;
            if (!wr(LINK, 0xd8cf, (r & 0xFE) | (f > 0x2B00EL ? 1 : 0))) return false;
        }
        // samba_sethalffreq
        long half = f >>> 1;
        int hl = loFreq(half);
        return wr(OFDM, 0x1e3, lnaCapSel(half)) && wr(OFDM, 0x1e1, hl & 0xFF) && wr(OFDM, 0x1e2, (hl >> 8) & 0xFF);
    }

    // ---------------------------------------------------------------------------------------------
    // lock / status
    // ---------------------------------------------------------------------------------------------

    /** Standard_isLocked (single chip): 0x47 (1 = signal found, 2 = none) -> TPS lock -> MPEG2 lock. */
    boolean waitLock() {
        return waitLock(false);
    }

    /** @param quick single attempt (used by channel scan: an empty channel is rejected after ~1 s) */
    boolean waitLock(boolean quick) {
        for (int attempt = 0; attempt < (quick ? 1 : 2); attempt++) {
            int st = 0;
            for (int i = 0; i < (quick ? 60 : 40); i++) {
                st = rd(OFDM, 0x47);
                if (st == 1 || st == 2 || st < 0) break;
                io.sleep(25);
            }
            if (st == 1) {
                boolean tps = false;
                for (int i = 0; i < 50 && !tps; i++) {
                    tps = (rd(OFDM, 0xf5a9) & 1) != 0;
                    if (!tps) io.sleep(25);
                }
                if (tps) {
                    for (int i = 0; i < 40; i++) {
                        if (mpeg2Locked()) return true;
                        io.sleep(25);
                    }
                }
            }
            if (st == 2 && attempt == 1) break;
            // vendor resets and retries once
            wr(OFDM, 0x47, 0);
            wr(LINK, 0, 0);
        }
        return false;
    }

    private boolean mpeg2Locked() {
        int a = rd(OFDM, 0xf9f1), b = rd(OFDM, 0xf9f2), c = rd(OFDM, 0xf9f3);
        return a >= 0 && b >= 0 && c >= 0 && ((a | b | c) & 1) != 0;
    }

    SignalStatus getStatus() {
        SignalStatus s = new SignalStatus();
        int tps = rd(OFDM, 0xf5a9);
        s.locked = tps >= 0 && (tps & 1) != 0 && mpeg2Locked();
        int str = rd(OFDM, 0x13e);
        if (str >= 0) {
            s.rawStrength = str;
            s.strengthPercent = Math.min(str, 100);
        }
        int q = rd(OFDM, 0x49);
        if (q >= 0) {
            s.rawQuality = q;
            s.qualityPercent = Math.min(q, 100);
        }
        int dbm = rd(OFDM, 0x13f);
        if (dbm >= 0) s.dbm = dbm - 100;
        int snr = rd(OFDM, 0x1c9);
        if (snr >= 0) s.rawSnr = snr;
        byte[] b = io.read(OFDM, 0x32, 7);
        if (b != null) {
            int window = (b[5] & 0xFF) | ((b[6] & 0xFF) << 8);
            long err = (b[2] & 0xFF) | ((long) (b[3] & 0xFF) << 8) | ((long) (b[4] & 0xFF) << 16);
            s.berErrors = err;
            s.berWindow = window;
            if (window != 0) s.ber = (double) err / (window * 204.0 * 8.0);
        }
        return s;
    }

    String describe() {
        return String.format(Locale.US, "clock=%d tunerType=%d ed03=%d ed23=%d thr=[%d %d %d %d %d %d %d %d]",
                clock, tunerType, ed03, ed23, thr[1], thr[2], thr[3], thr[4], thr[5], thr[6], thr[7], thr[8]);
    }
}