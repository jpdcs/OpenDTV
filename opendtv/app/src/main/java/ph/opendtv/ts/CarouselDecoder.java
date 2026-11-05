package ph.opendtv.ts;

import static ph.opendtv.ts.TsUtil.i32;
import static ph.opendtv.ts.TsUtil.u16;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * DSM-CC data carousel decoder for one PID (ISO 13818-6 / ARIB STD-B24 Vol.3).
 *
 * Sections with table_id 0x3B carry DSI (0x1006) and DII (0x1002) messages that announce the modules;
 * table_id 0x3C carries DDB (0x1003) messages with the module blocks. Blocks that arrive before their
 * DII (common, the carousel is cyclic) are kept until the DII shows up. Complete modules are
 * decompressed (zlib) when flagged and split into their MIME multipart resources.
 *
 * Not thread-safe by itself; TsDemux calls it under its own lock.
 */
public final class CarouselDecoder {
    private static final int MAX_MODULE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 3 * 1024 * 1024;
    private static final int MAX_MODULES = 2000;
    /**
     * Memory for all modules of this PID together (block buffers, inflated content and its parts). The sizes come
     * from the signal: one DII can announce 2000 modules of 4 MB, or a 16-byte module that "inflates" to 2 GB, and
     * an OutOfMemoryError there killed the app as soon as the channel was tuned.
     */
    private static final long MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    /** Heap cost of a stashed block beyond its payload (array header, map entry, boxed key). */
    private static final int PENDING_OVERHEAD = 64;

    private static final class Pending {
        int version;
        final Map<Integer, byte[]> blocks = new HashMap<>();
    }

    private final int pid;
    private final Map<Long, CarouselModule> modules = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private int pendingBytes = 0;
    private long totalBytes = 0;   // charged against MAX_TOTAL_BYTES
    private int diiCount, dsiCount, ddbCount, blockSize;
    private long bytesReceived;

    public CarouselDecoder(int pid) {
        this.pid = pid;
    }

    public void onSection(byte[] s, int n) {
        int tid = s[0] & 0xFF;
        if (tid == 0x3B) onControl(s, n);
        else if (tid == 0x3C) onDdb(s, n);
    }

    private static long key(int downloadId, int moduleId) {
        return ((downloadId & 0xFFFFFFFFL) << 16) | moduleId;
    }

    // ---------------------------------------------------------------- DSI / DII

    private void onControl(byte[] s, int n) {
        int m = 8, end = n - 4;
        if (end - m < 12 || s[m] != 0x11 || s[m + 1] != 0x03) return;
        int messageId = u16(s, m + 2);
        if (messageId == 0x1006) {
            dsiCount++;
            return;
        }
        if (messageId != 0x1002) return;
        diiCount++;

        int p = m + 12 + (s[m + 9] & 0xFF);
        if (p + 18 > end) return;
        int downloadId = i32(s, p);
        blockSize = u16(s, p + 4);
        p += 16; // downloadId, blockSize, windowSize, ackPeriod, tCDownloadWindow, tCDownloadScenario
        int compat = u16(s, p);
        p += 2 + compat;
        if (p + 2 > end) return;
        int count = u16(s, p);
        p += 2;

        while (count-- > 0 && p + 8 <= end) {
            int moduleId = u16(s, p);
            int size = i32(s, p + 2);
            int version = s[p + 6] & 0xFF;
            int infoLen = s[p + 7] & 0xFF;
            int infoStart = p + 8;
            p += 8 + infoLen;
            if (p > end) return;

            long k = key(downloadId, moduleId);
            CarouselModule cur = modules.get(k);
            if (cur != null && cur.version == version && cur.size == size) continue; // unchanged, keep progress
            if (modules.size() >= MAX_MODULES) return;
            if (cur != null) totalBytes -= charged(cur);   // replaced by the new version below

            CarouselModule mod = new CarouselModule();
            mod.downloadId = downloadId;
            mod.moduleId = moduleId;
            mod.version = version;
            mod.size = size;
            mod.blockSize = blockSize;
            parseModuleInfo(mod, s, infoStart, infoStart + infoLen);
            if (size <= 0 || size > MAX_MODULE_BYTES) {
                mod.error = "module too large or empty (" + size + " bytes)";
            } else if (blockSize <= 0) {
                mod.error = "invalid block size";
            } else if (totalBytes + size > MAX_TOTAL_BYTES) {
                mod.error = "carousel over " + (MAX_TOTAL_BYTES >> 20) + " MB, module skipped";
            } else {
                mod.blocksTotal = (size + blockSize - 1) / blockSize;
                mod.data = new byte[size];
                mod.received = new BitSet(mod.blocksTotal);
                totalBytes += size;
            }
            modules.put(k, mod);
            ingestPending(k, mod);
        }
    }

    /** Module info descriptors: 0x01 type, 0x02 name, 0x09/0xC2 compression (method, original size). */
    private static void parseModuleInfo(CarouselModule mod, byte[] s, int p, int end) {
        while (p + 2 <= end) {
            int tag = s[p] & 0xFF, len = s[p + 1] & 0xFF;
            int d = p + 2;
            if (d + len > end) return;
            if ((tag == 0x09 || tag == 0xC2) && len >= 5) {
                mod.compressed = true;
                mod.originalSize = i32(s, d + 1);
            } else if (tag == 0x01) {
                mod.typeHint = TsUtil.text(s, d, len);
            } else if (tag == 0x02) {
                mod.name = TsUtil.text(s, d, len);
            }
            p = d + len;
        }
    }

    // ---------------------------------------------------------------- DDB

    private void onDdb(byte[] s, int n) {
        int m = 8, end = n - 4;
        if (end - m < 12 || s[m] != 0x11 || s[m + 1] != 0x03) return;
        if (u16(s, m + 2) != 0x1003) return;
        ddbCount++;
        int downloadId = i32(s, m + 4);
        int messageEnd = Math.min(end, m + 12 + u16(s, m + 10));
        int h = m + 12 + (s[m + 9] & 0xFF);
        if (h + 6 > messageEnd) return;
        int moduleId = u16(s, h);
        int version = s[h + 2] & 0xFF;
        int block = u16(s, h + 4);
        int dataStart = h + 6;
        int dataLen = messageEnd - dataStart;
        if (dataLen <= 0) return;

        long k = key(downloadId, moduleId);
        CarouselModule mod = modules.get(k);
        if (mod == null) {
            stash(k, version, block, s, dataStart, dataLen);
        } else if (mod.version == version) {
            addBlock(mod, block, s, dataStart, dataLen);
        } // else: block of another version, ignore
    }

    private void stash(long k, int version, int block, byte[] s, int off, int len) {
        Pending p = pending.get(k);
        if (p == null || p.version != version) {
            if (p != null) for (byte[] b : p.blocks.values()) pendingBytes -= b.length + PENDING_OVERHEAD;
            p = new Pending();
            p.version = version;
            pending.put(k, p);
        }
        if (p.blocks.containsKey(block)) return;
        // Counted with their overhead: 1-byte blocks under ever new ids cost ~100 B of heap each, and counting only
        // the payload let millions of them pile up before the cap.
        if (pendingBytes + len + PENDING_OVERHEAD > MAX_PENDING_BYTES) { // too much waiting for a DII that never comes
            pending.clear();
            pendingBytes = 0;
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(s, off, copy, 0, len);
        p.blocks.put(block, copy);
        pendingBytes += len + PENDING_OVERHEAD;
    }

    private void ingestPending(long k, CarouselModule mod) {
        Pending p = pending.remove(k);
        if (p == null) return;
        for (byte[] b : p.blocks.values()) pendingBytes -= b.length + PENDING_OVERHEAD;
        if (p.version != mod.version) return;
        for (Map.Entry<Integer, byte[]> e : p.blocks.entrySet()) {
            addBlock(mod, e.getKey(), e.getValue(), 0, e.getValue().length);
        }
    }

    private void addBlock(CarouselModule mod, int block, byte[] src, int off, int len) {
        if (mod.complete || mod.data == null || block >= mod.blocksTotal || mod.received.get(block)) return;
        int dest = block * mod.blockSize;
        int n = Math.min(len, mod.size - dest);
        if (n <= 0) return;
        System.arraycopy(src, off, mod.data, dest, n);
        mod.received.set(block);
        mod.blocksReceived++;
        bytesReceived += n;
        if (mod.blocksReceived == mod.blocksTotal) finish(mod);
    }

    private void finish(CarouselModule mod) {
        byte[] content = mod.data;
        totalBytes -= mod.data.length;   // the block buffer gives way to the content below
        long room = MAX_TOTAL_BYTES - totalBytes;
        if (mod.compressed) {
            byte[] inflated = inflate(mod.data, mod.originalSize, Math.min(MAX_MODULE_BYTES * 4L, room));
            if (inflated == null) {
                mod.error = "zlib inflate failed";
            } else {
                content = inflated;
            }
        }
        mod.complete = true;
        mod.data = null; // content holds the final bytes
        mod.received = null;
        // The parts are copied out of the content, so a complete module holds its bytes about twice.
        if (2L * content.length > room) {
            mod.error = "carousel over " + (MAX_TOTAL_BYTES >> 20) + " MB, module dropped";
        } else {
            mod.content = content;
            mod.resources = MimeParts.parse(content);
            totalBytes += charged(mod);
        }
    }

    /** Bytes a module holds: its block buffer while assembling, then its content and the parts copied out of it. */
    private static long charged(CarouselModule mod) {
        long n = mod.data != null ? mod.data.length : 0;   // still assembling
        if (mod.content != null) n += mod.content.length;
        for (CarouselResource r : mod.resources) n += r.length;
        return n;
    }

    private static byte[] inflate(byte[] in, int expected, long limit) {
        if (limit <= 0) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            // Pre-size from the signal's original_size only when it is within the limit: 0x7FFFFFFF there was a
            // 2 GB allocation for a 16-byte module.
            int initial = expected > 0 && expected <= limit ? expected : (int) Math.min(in.length * 3L, limit);
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > limit) return null;
            }
            return inf.finished() ? out.toByteArray() : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // ---------------------------------------------------------------- snapshot

    public CarouselInfo snapshot() {
        CarouselInfo info = new CarouselInfo();
        info.pid = pid;
        info.blockSize = blockSize;
        info.diiCount = diiCount;
        info.dsiCount = dsiCount;
        info.ddbCount = ddbCount;
        info.bytesReceived = bytesReceived;
        List<CarouselModule> list = new ArrayList<>();
        for (CarouselModule m : modules.values()) list.add(m.snapshot());
        Collections.sort(list, new Comparator<CarouselModule>() {
            @Override
            public int compare(CarouselModule a, CarouselModule b) {
                int c = Integer.compare(a.downloadId, b.downloadId);
                return c != 0 ? c : Integer.compare(a.moduleId, b.moduleId);
            }
        });
        info.modules.addAll(list);
        return info;
    }
}
