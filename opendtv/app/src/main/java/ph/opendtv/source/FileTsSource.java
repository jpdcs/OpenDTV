package ph.opendtv.source;

import java.io.IOException;
import java.io.InputStream;

/**
 * Plays a recorded .ts file as if it were a tuner. Lets you develop and test the UI, analyzer and
 * player without the dongle.
 *
 * Recordings come in several packet sizes; the rest of the app only understands 188-byte packets,
 * so the first 64 KiB is probed and the stream is normalised on the fly:
 *   188  plain TS
 *   192  BDAV / m2ts (4-byte timestamp in front of each packet)
 *   204  TS + 16 bytes Reed-Solomon parity (typical ISDB-T / DVB capture from a modulator or analyser)
 *
 * bytesPerSecond = 0 reads as fast as the consumer accepts it (the analyzer wants this; playback is
 * paced by the TsPipe back-pressure anyway).
 */
public final class FileTsSource implements TsSource {

    public interface Opener {
        InputStream open() throws IOException;
    }

    private static final int OUT_PACKETS = 1024;            // 192 KiB per onData() call
    private static final int PROBE_BYTES = 64 * 1024;

    private final String name;
    private final Opener opener;
    private final long bytesPerSecond;
    private final boolean loop;
    private volatile boolean running;
    private volatile int detectedPacketSize = 188;
    private Thread thread;

    public FileTsSource(String name, Opener opener, long bytesPerSecond, boolean loop) {
        this.name = name;
        this.opener = opener;
        this.bytesPerSecond = bytesPerSecond;
        this.loop = loop;
    }

    @Override
    public String name() {
        return name;
    }

    /** 188, 192 or 204 once the file has been probed (188 before that). */
    public int packetSize() {
        return detectedPacketSize;
    }

    @Override
    public synchronized void start(final Listener l) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(l);
            }
        }, "FileTsSource");
        thread.start();
    }

    /**
     * Finds (packetSize, offset of the first 0x47) such that the most sync bytes line up.
     * @return {size, offset} or null if nothing looks like TS
     */
    static int[] detect(byte[] b, int n) {
        int[] sizes = {188, 192, 204};
        int bestScore = 0;
        int[] best = null;
        for (int size : sizes) {
            for (int off = 0; off < size && off < n; off++) {
                int hits = 0, tot = 0;
                for (int i = off; i < n; i += size) {
                    tot++;
                    if ((b[i] & 0xFF) == 0x47) hits++;
                }
                // require nearly every slot to be a sync byte
                if (tot >= 8 && hits * 100 >= tot * 95 && hits > bestScore) {
                    bestScore = hits;
                    best = new int[] {size, off};
                }
            }
        }
        return best;
    }

    /**
     * Real-time rate of a capture in output bytes (188-byte packets) per second, from the first and the last PCR of
     * one PID, so a looping playback can be paced like a live tuner. 0 when it cannot tell (no PCR, odd values):
     * then the file is read as fast as the consumer accepts it.
     */
    public static long estimateBytesPerSecond(java.io.File f) {
        final int window = 4 * 1024 * 1024;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            byte[] head = new byte[(int) Math.min(window, len)];
            raf.readFully(head);
            int[] fmt = detect(head, Math.min(head.length, PROBE_BYTES));
            if (fmt == null) return 0;
            int size = fmt[0], sync = fmt[1];   // sync = file offset of the first 0x47
            int pcrPid = -1;
            long pcr0 = -1, pos0 = -1;
            for (int p = sync; p + 188 <= head.length; p += size) {
                long pcr = pcrAt(head, p, -1);
                if (pcr >= 0) {
                    pcrPid = ((head[p + 1] & 0x1F) << 8) | (head[p + 2] & 0xFF);
                    pcr0 = pcr;
                    pos0 = p;
                    break;
                }
            }
            if (pcrPid < 0) return 0;
            long tailStart = Math.max(sync, len - window);
            tailStart = sync + ((tailStart - sync + size - 1) / size) * size;   // back on the packet grid
            byte[] tail = new byte[(int) (len - tailStart)];
            raf.seek(tailStart);
            raf.readFully(tail);
            long pcr1 = -1, pos1 = -1;
            for (int p = 0; p + 188 <= tail.length; p += size) {
                long pcr = pcrAt(tail, p, pcrPid);
                if (pcr >= 0) {
                    pcr1 = pcr;
                    pos1 = tailStart + p;
                }
            }
            if (pcr1 <= pcr0 || pos1 <= pos0) return 0;
            long rate = (pos1 - pos0) / size * 188L * 27_000_000L / (pcr1 - pcr0);
            return rate >= 50_000 && rate <= 10_000_000 ? rate : 0;   // 0.4 .. 80 Mbit/s, else do not trust it
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }

    /** PCR (27 MHz units) of the packet whose sync byte is at {@code p}, or -1 (no PCR, or another PID). */
    private static long pcrAt(byte[] b, int p, int pid) {
        if ((b[p] & 0xFF) != 0x47) return -1;
        if (pid >= 0 && (((b[p + 1] & 0x1F) << 8) | (b[p + 2] & 0xFF)) != pid) return -1;
        if (((b[p + 3] >> 4) & 2) == 0) return -1;                       // no adaptation field
        if ((b[p + 4] & 0xFF) < 7 || (b[p + 5] & 0x10) == 0) return -1;  // no PCR in it
        long base = ((long) (b[p + 6] & 0xFF) << 25) | ((long) (b[p + 7] & 0xFF) << 17)
                | ((long) (b[p + 8] & 0xFF) << 9) | ((long) (b[p + 9] & 0xFF) << 1) | ((b[p + 10] & 0xFF) >> 7);
        return base * 300 + (((b[p + 10] & 1) << 8) | (b[p + 11] & 0xFF));
    }

    private void pump(Listener l) {
        try {
            do {
                try (InputStream in = opener.open()) {
                    if (in == null) throw new IOException("cannot open file");
                    streamOnce(in, l);
                }
            } while (running && loop);
            if (running) l.onEnd();
        } catch (IOException e) {
            if (running) l.onError("File error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    private void streamOnce(InputStream in, Listener l) throws IOException, InterruptedException {
        byte[] probe = new byte[PROBE_BYTES];
        int have = 0;
        while (have < probe.length) {
            int r = in.read(probe, have, probe.length - have);
            if (r <= 0) break;
            have += r;
        }
        int[] fmt = detect(probe, have);
        int size = fmt != null ? fmt[0] : 188;
        int skip = fmt != null ? fmt[1] : 0;
        // 192-byte (m2ts): the TS packet follows a 4-byte header, so the sync byte is at +4.
        int pktOff = (size == 192) ? 4 : 0;
        // If detect() found the sync at offset 'skip' inside a 192 layout, packet starts there anyway.
        if (size == 192) skip = Math.max(0, skip - 4);
        detectedPacketSize = size;

        byte[] raw = new byte[size * OUT_PACKETS];
        byte[] out = new byte[188 * OUT_PACKETS];
        int rawLen = 0;
        long start = System.nanoTime();
        long sent = 0;

        // Seed with the probed bytes, dropping anything before the first packet.
        System.arraycopy(probe, skip, raw, 0, Math.min(have - skip, raw.length));
        rawLen = Math.min(have - skip, raw.length);
        int remainingProbe = have - skip - rawLen;     // bytes of the probe that did not fit (normally 0)
        int probePos = skip + rawLen;

        boolean eof = false;
        while (running) {
            // top up the raw buffer
            while (rawLen < raw.length && !eof) {
                int r;
                if (remainingProbe > 0) {
                    r = Math.min(remainingProbe, raw.length - rawLen);
                    System.arraycopy(probe, probePos, raw, rawLen, r);
                    probePos += r;
                    remainingProbe -= r;
                } else {
                    r = in.read(raw, rawLen, raw.length - rawLen);
                    if (r <= 0) {
                        eof = true;
                        break;
                    }
                }
                rawLen += r;
            }
            int npk = rawLen / size;
            if (npk == 0) break;
            int o = 0;
            for (int i = 0; i < npk; i++) {
                System.arraycopy(raw, i * size + pktOff, out, o, 188);
                o += 188;
            }
            l.onData(out, 0, o);
            sent += o;
            int used = npk * size;
            System.arraycopy(raw, used, raw, 0, rawLen - used);
            rawLen -= used;
            if (bytesPerSecond > 0) {
                long dueNs = sent * 1_000_000_000L / bytesPerSecond;
                long aheadMs = (dueNs - (System.nanoTime() - start)) / 1_000_000L;
                if (aheadMs > 0) Thread.sleep(aheadMs);
            }
            if (eof && rawLen < size) break;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public boolean supportsTuning() {
        return false;
    }

    @Override
    public boolean tune(int frequencyKhz) {
        return false;
    }

    @Override
    public SignalStatus getSignalStatus() {
        return null;
    }
}
