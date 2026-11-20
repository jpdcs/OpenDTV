package ph.opendtv.source;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB tuner source for the ITE IT9135/IT9175-family Pad TV / GMA Now dongles: PT275 (VID 048d, PID 275a) and
 * PT275C (PID e275). Both use the same protocol; firmware and init scripts come from {@link It9175Profile}.
 *
 * Transport (from libpt275aDrv.so in GMA Now 1.47; the vendor driver does raw USBDEVFS_BULK on these):
 *   bulk OUT 0x02  command frames        (see {@link AfaCmd})
 *   bulk IN  0x81  command responses
 *   bulk IN  0x84  MPEG-TS data
 * The vendor app opens the device, claims the interface(s) with force=true and then talks to the chip.
 *
 * STATUS
 *   done:      endpoint selection, register I/O, chip probe, firmware download, full post-firmware init, tune,
 *              lock wait, signal registers. The init/tune logic is {@link It9175Tuner}, ported from the vendor
 *              library and diff-tested against it (tools/ph/opendtv/source/TunerDiffTest).
 *   not done:  ALPU authentication (vendor sends it at the end of init; its result only gates the vendor's
 *              own ReadTS in software), clock indexes other than 0/1, EEPROM-configured variants.
 *   unverified on hardware: all of it. Run UsbProbeActivity first and check the probe report.
 */
public final class UsbTunerSource implements TsSource {
    public static final int VID = 0x048d;
    /** PT275 / GMA Now. */
    public static final int PID = It9175Profile.PID_PT275;
    /** PT275C (USB-C). The vendor app drives it with libPTx1Drv.so instead of libpt275aDrv.so. */
    public static final int PID_PT275C = It9175Profile.PID_PT275C;

    /** True for every dongle this driver supports. */
    public static boolean isSupported(UsbDevice d) {
        return d != null && d.getVendorId() == VID && It9175Profile.forProductId(d.getProductId()) != null;
    }

    /** "048d:275a / 048d:e275", for status messages. */
    public static final String SUPPORTED_IDS = String.format(Locale.US, "%04x:%04x / %04x:%04x", VID, PID, VID,
            PID_PT275C);

    static final int EP_CMD_OUT = 0x02;
    static final int EP_CMD_IN = 0x81;
    static final int EP_TS_IN = 0x84;

    /** usbfs on older Android versions limits one bulkTransfer to 16 KiB; use a multiple of 188. */
    private static final int READ_SIZE = 188 * 87;
    private static final int CMD_TIMEOUT_MS = 1000;
    private int cmdTimeoutMs = CMD_TIMEOUT_MS;
    private boolean quiet;
    /** Max data bytes per register write (vendor loader batches at most 20 consecutive registers). */
    private static final int MAX_WRITE_RUN = 20;

    private final UsbManager manager;
    private final UsbDevice device;
    private final It9175Profile profile;
    private volatile UsbDeviceConnection connection;
    private final List<UsbInterface> claimed = new ArrayList<UsbInterface>();
    private UsbEndpoint epCmdOut;
    private UsbEndpoint epCmdIn;
    private UsbEndpoint epTs;
    private volatile boolean running;
    private Thread thread;
    private int seq;
    private final Object cmdLock = new Object();
    private volatile SignalStatus cachedStatus;
    private Thread statusThread;
    private Thread consumerThread;
    private volatile boolean tuning;
    private volatile UsbRequest[] urbs;
    private final StringBuilder notes = new StringBuilder();
    private It9175Tuner tuner;
    private volatile boolean locked;
    /** Channel tuned right after init (kHz). Default: TV5 multiplex, Philippines UHF channel 14 + 4 steps. */
    public int initialFrequencyKhz = 497143;

    public UsbTunerSource(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
        It9175Profile p = It9175Profile.forProductId(device.getProductId());
        this.profile = p != null ? p : It9175Profile.PT275A;
    }

    @Override
    public String name() {
        return "USB tuner " + String.format("%04x:%04x", device.getVendorId(), device.getProductId());
    }

    @Override
    public synchronized void start(final Listener l) {
        if (running) return;
        if (!open()) {
            l.onError(notes.toString().trim());
            return;
        }
        if (!initTuner()) {
            l.onError("Tuner init failed:\n" + notes.toString().trim());
        } else {
            boolean lk = tune(initialFrequencyKhz);
            l.onError((lk ? "LOCKED on " : "NO LOCK on ") + initialFrequencyKhz + " kHz (TS reading continues)\n"
                    + notes.toString().trim());
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(l);
            }
        }, "UsbTunerSource");
        thread.start();
        statusThread = new Thread(new Runnable() {
            @Override
            public void run() {
                statusLoop();
            }
        }, "UsbSignal");
        statusThread.setDaemon(true);
        statusThread.start();
    }

    /** Polls the demodulator once a second so the UI never blocks on USB register reads. */
    private void statusLoop() {
        while (running) {
            It9175Tuner t = tuner;
            if (t != null && !tuning && connection != null) {
                try {
                    SignalStatus st = t.getStatus();
                    st.locked = locked && st.locked;
                    cachedStatus = st;
                } catch (RuntimeException ignored) {
                    // keep the last value
                }
            }
            sleep(1000);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Open / endpoints
    // ---------------------------------------------------------------------------------------------

    /** Opens the device, finds the three endpoints by address and claims their interfaces (force). */
    private boolean open() {
        notes.setLength(0);
        connection = manager.openDevice(device);
        if (connection == null) {
            notes.append("openDevice() failed (no USB permission?)\n");
            return false;
        }
        UsbInterface bestTsIntf = null;
        UsbEndpoint largestOther = null;
        UsbInterface largestOtherIntf = null;
        List<UsbInterface> need = new ArrayList<UsbInterface>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            notes.append(String.format(Locale.US, "intf %d alt %d class 0x%02x:", intf.getId(),
                    intf.getAlternateSetting(), intf.getInterfaceClass()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                notes.append(String.format(Locale.US, " 0x%02x(%s,%d)", ep.getAddress(),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out", ep.getMaxPacketSize()));
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                int a = ep.getAddress();
                if (a == EP_CMD_OUT) { epCmdOut = ep; addUnique(need, intf); }
                else if (a == EP_CMD_IN) { epCmdIn = ep; addUnique(need, intf); }
                else if (a == EP_TS_IN) { epTs = ep; bestTsIntf = intf; addUnique(need, intf); }
                else if (ep.getDirection() == UsbConstants.USB_DIR_IN
                        && (largestOther == null || ep.getMaxPacketSize() > largestOther.getMaxPacketSize())) {
                    largestOther = ep;
                    largestOtherIntf = intf;
                }
            }
            notes.append('\n');
        }
        if (epTs == null && largestOther != null) {
            // Unexpected layout: fall back to the largest bulk-IN that is not the command-response pipe.
            epTs = largestOther;
            addUnique(need, largestOtherIntf);
            notes.append("EP 0x84 not found, using 0x").append(Integer.toHexString(epTs.getAddress()))
                    .append(" for TS\n");
        }
        if (epCmdOut == null || epCmdIn == null || epTs == null) {
            notes.append("Missing endpoint(s): cmdOut=").append(epCmdOut != null)
                    .append(" cmdIn=").append(epCmdIn != null).append(" ts=").append(epTs != null).append('\n');
            close();
            return false;
        }
        for (UsbInterface intf : need) {
            if (connection.claimInterface(intf, true)) {
                claimed.add(intf);
            } else {
                notes.append("claimInterface(").append(intf.getId()).append(") failed\n");
            }
        }
        if (claimed.isEmpty()) {
            close();
            return false;
        }
        return true;
    }

    private static void addUnique(List<UsbInterface> list, UsbInterface intf) {
        if (intf != null && !list.contains(intf)) list.add(intf);
    }

    private void close() {
        if (connection != null) {
            for (UsbInterface intf : claimed) connection.releaseInterface(intf);
            claimed.clear();
            connection.close();
            connection = null;
        }
        epCmdOut = epCmdIn = epTs = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Command channel
    // ---------------------------------------------------------------------------------------------

    /** Sends one frame and returns the response data ({@code expectData} bytes) or null on any failure. */
    private byte[] transact(byte[] frame, int expectData, String what) {
        synchronized (cmdLock) {
            return transactLocked(frame, expectData, what);
        }
    }

    private byte[] transactLocked(byte[] frame, int expectData, String what) {
        UsbDeviceConnection connection = this.connection;
        UsbEndpoint epCmdOut = this.epCmdOut, epCmdIn = this.epCmdIn;
        if (connection == null || epCmdOut == null || epCmdIn == null) return null;
        int w = connection.bulkTransfer(epCmdOut, frame, frame.length, cmdTimeoutMs);
        if (w != frame.length) {
            if (!quiet) notes.append(what).append(": write returned ").append(w).append('\n');
            return null;
        }
        byte[] rx = new byte[256];
        int n = connection.bulkTransfer(epCmdIn, rx, rx.length, cmdTimeoutMs);
        if (n <= 0) {
            if (!quiet) notes.append(what).append(": no response (").append(n).append(")\n");
            return null;
        }
        byte[] data = AfaCmd.parseResponse(rx, n, expectData);
        if (data == null && !quiet) {
            notes.append(what).append(": bad response len=").append(n).append(" status=")
                    .append(AfaCmd.status(rx, n)).append(" [").append(hex(rx, Math.min(n, 16))).append("]\n");
        }
        return data;
    }

    private int nextSeq() {
        synchronized (cmdLock) {
            return seq++ & 0xFF;
        }
    }

    /** Reads {@code count} registers (processor: AfaCmd.PROC_LINK or PROC_OFDM, chip 0). */
    byte[] readRegisters(int processor, long addr, int count) {
        return transact(AfaCmd.buildRead(nextSeq(), 0, processor, addr, count), count,
                String.format(Locale.US, "read 0x%x", addr));
    }

    boolean writeRegisters(int processor, long addr, byte[] data) {
        return transact(AfaCmd.buildWrite(nextSeq(), 0, processor, addr, data), 0,
                String.format(Locale.US, "write 0x%x", addr)) != null;
    }

    /** Generic command (see {@link AfaCmd#buildCommand}); returns the reply bytes or null. */
    byte[] sendCommand(int cmd, int processor, byte[] payload, int replyLen) {
        return transact(AfaCmd.buildCommand(cmd, nextSeq(), 0, processor, payload), replyLen,
                String.format(Locale.US, "cmd 0x%02x proc %d", cmd, processor));
    }

    /** Firmware version of a processor (vendor Standard_getFirmwareVersion), 4 raw bytes or null. */
    byte[] firmwareVersion(int processor) {
        return sendCommand(0x22, processor, new byte[] {1}, 4);
    }

    // ---------------------------------------------------------------------------------------------
    // Chip bring-up
    // ---------------------------------------------------------------------------------------------

    /**
     * Mirrors the first part of the vendor Device_init: read chip version (LINK reg 0x1222) and chip id
     * (LINK reg 0x1223..0x1224, little endian). Then loads the SAMBA register script into the OFDM
     * processor like Standard_loadScript does.
     *
     * TODO(protocol): the rest of Device_init (DL_SetBusTuner, DL_ApCtrl, EEPROM config at 0x461B/0x461D,
     * TS mode, NIM reset/suspend regs) is not ported. Capture the Windows driver or run the vendor app with
     * logging to confirm what is needed before TS flows.
     */
    private boolean initTuner() {
        byte[] ver = readRegisters(AfaCmd.PROC_LINK, 0x1222, 1);
        byte[] id = readRegisters(AfaCmd.PROC_LINK, 0x1223, 2);
        if (ver == null || id == null) return false;
        int chipId = (id[0] & 0xFF) | ((id[1] & 0xFF) << 8);
        notes.append(String.format(Locale.US, "chip version=%d id=0x%04x, profile %s\n", ver[0] & 0xFF, chipId,
                profile.name));

        byte[] fwLink = firmwareVersion(AfaCmd.PROC_LINK);
        notes.append("fw LINK before=").append(fwLink == null ? "none" : hex(fwLink, 4)).append('\n');
        if (fwLink == null) return false;
        if (isZero(fwLink)) {
            // The chip is in boot-ROM state: its RAM firmware must be downloaded by the host.
            if (!loadFirmware()) return false;
            fwLink = firmwareVersion(AfaCmd.PROC_LINK);
            notes.append("fw LINK after=").append(fwLink == null ? "none" : hex(fwLink, 4)).append('\n');
            if (fwLink == null || isZero(fwLink)) {
                notes.append("firmware did not start (version still 0)\n");
                return false;
            }
        }

        // Post-download steps from Standard_initialize (single chip): LINK 0xf103 = 0x1a, OFDM 0xec4c = 0xa8.
        if (!writeRegisters(AfaCmd.PROC_LINK, 0xf103, new byte[] {0x1a})) return false;
        quiet = true;
        cmdTimeoutMs = 400;
        boolean alive = writeRegisters(AfaCmd.PROC_OFDM, 0xec4c, new byte[] {(byte) 0xa8});
        sleep(10);
        if (!alive) alive = ofdmAlive();
        if (!alive) {
            notes.append("OFDM silent after 0xec4c; running diagnostics\n");
            alive = diagnoseOfdm();
        }
        quiet = false;
        cmdTimeoutMs = CMD_TIMEOUT_MS;
        if (!alive) {
            notes.append("OFDM processor does not answer; skipping script load\n");
            return false;
        }
        byte[] fwOfdm = firmwareVersion(AfaCmd.PROC_OFDM);
        notes.append("fw OFDM=").append(fwOfdm == null ? "none" : hex(fwOfdm, 4)).append('\n');
        notes.append(diagRegs("before tuner init"));
        tuner = new It9175Tuner(new TunerIo(), notes, profile);
        boolean ok = tuner.init();
        notes.append(tuner.describe()).append('\n');
        notes.append(diagRegs("after tuner init"));
        return ok;
    }

    /** Adapter so the pure-Java tuner logic can use this class's command channel. */
    private final class TunerIo implements It9175Tuner.Io {
        @Override
        public byte[] read(int proc, long addr, int n) {
            return readRegisters(proc, addr, n);
        }

        @Override
        public boolean write(int proc, long addr, byte[] data) {
            return writeRegisters(proc, addr, data);
        }

        @Override
        public boolean fwVersion(int proc) {
            return firmwareVersion(proc) != null;
        }

        @Override
        public void sleep(int ms) {
            UsbTunerSource.sleep(ms);
        }
    }

    /** One line with the registers that decide the vendor's code paths; paste it back if something fails. */
    private String diagRegs(String when) {
        StringBuilder sb = new StringBuilder("regs ").append(when).append(':');
        int[][] regs = {{0, 0x461b}, {0, 0x49ac}, {0, 0xd800}, {0, 0xd805}, {0, 0xdd11}, {8, 0x4d}, {8, 0x1c6},
                {8, 0x1dc}, {8, 0xec86}, {8, 0xed03}, {8, 0xed23}, {8, 0xed24}, {8, 0xed81}, {8, 0xec4c}};
        boolean q = quiet;
        quiet = true;
        for (int[] r : regs) {
            sb.append(String.format(Locale.US, " %s%04x=%s", r[0] == 0 ? "L" : "O", r[1], rd(r[0], r[1])));
        }
        quiet = q;
        return sb.append('\n').toString();
    }

    /** True if the OFDM processor answers a register read (vendor reads OFDM reg 0x46 right after init). */
    private boolean ofdmAlive() {
        return readRegisters(AfaCmd.PROC_OFDM, 0x46, 1) != null;
    }

    private String rd(int proc, long addr) {
        byte[] v = readRegisters(proc, addr, 1);
        return v == null ? "--" : String.format(Locale.US, "%02x", v[0] & 0xFF);
    }

    /** Sets bit 0 of a LINK register (read-modify-write). */
    private boolean setBit0(long addr) {
        byte[] v = readRegisters(AfaCmd.PROC_LINK, addr, 1);
        if (v == null) return false;
        return writeRegisters(AfaCmd.PROC_LINK, addr, new byte[] {(byte) (v[0] | 1)});
    }

    /**
     * Tries the likely reasons why the OFDM processor is silent and logs what it sees. Each step re-tests OFDM.
     * Returns true as soon as OFDM answers.
     */
    private boolean diagnoseOfdm() {
        StringBuilder sb = new StringBuilder("LINK regs:");
        int[] link = {0xd800, 0xf103, 0xd8c8, 0xd8c9, 0xd8bc, 0xd8bd, 0xd8bb, 0xe00c, 0xf641, 0x4bfb, 0xcfff};
        for (int r : link) sb.append(String.format(Locale.US, " %04x=%s", r, rd(AfaCmd.PROC_LINK, r)));
        notes.append(sb).append('\n');
        sb = new StringBuilder("OFDM regs:");
        int[] ofdm = {0xf84f, 0xfbb9, 0xec40, 0xec4c, 0x0043, 0x0046};
        for (int r : ofdm) sb.append(String.format(Locale.US, " %04x=%s", r, rd(AfaCmd.PROC_OFDM, r)));
        notes.append(sb).append('\n');
        if (ofdmAlive()) return true;

        // Step A: vendor 'DRV_ApCtrl on': five firmware-version dummy commands, then tuner/demod power on.
        for (int i = 0; i < 5; i++) {
            firmwareVersion(AfaCmd.PROC_LINK);
            sleep(1);
        }
        writeRegisters(AfaCmd.PROC_OFDM, 0xec40, new byte[] {1});
        sleep(10);
        notes.append("A (ApCtrl dummy + 0xec40=1): ").append(ofdmAlive() ? "OFDM ALIVE" : "silent").append('\n');
        if (ofdmAlive()) return true;

        // Step B: demod power-saving exit (Standard_controlPowerSaving, on): OFDM 0xfbb9 bit5=0, LINK 0xe00c=0,
        // OFDM 0xf84f = 0 then 1.
        writeRegisters(AfaCmd.PROC_LINK, 0xe00c, new byte[] {0});
        writeRegisters(AfaCmd.PROC_OFDM, 0xf84f, new byte[] {0});
        writeRegisters(AfaCmd.PROC_OFDM, 0xf84f, new byte[] {1});
        sleep(50);
        notes.append("B (power-saving exit): ").append(ofdmAlive() ? "OFDM ALIVE" : "silent").append('\n');
        if (ofdmAlive()) return true;

        // Step C: the LINK GPIO enables the vendor sets for a single USB2 chip (0xd8c8, 0xd8c9, 0xd8bc, 0xd8bd bit0).
        int[] gpio = {0xd8c8, 0xd8c9, 0xd8bc, 0xd8bd};
        for (int r : gpio) setBit0(r);
        sleep(50);
        notes.append("C (GPIO bits): ").append(ofdmAlive() ? "OFDM ALIVE" : "silent").append('\n');
        if (ofdmAlive()) return true;

        byte[] fwOfdm = firmwareVersion(AfaCmd.PROC_OFDM);
        notes.append("fw OFDM query: ").append(fwOfdm == null ? "none" : hex(fwOfdm, 4)).append('\n');
        return fwOfdm != null;
    }

    /**
     * Standard_loadFirmware for IT9175: LINK reg 0xf103 = 7, then every partition (all type 1) as a command 0x29
     * with the chunk as payload, then command 0x23 (start), 10 ms delay. Before that the vendor clears
     * LINK regs 0x4bfb and 0xcfff for a single-chip device.
     */
    private boolean loadFirmware() {
        if (!writeRegisters(AfaCmd.PROC_LINK, 0x4bfb, new byte[] {0})) return false;
        if (!writeRegisters(AfaCmd.PROC_LINK, 0xcfff, new byte[] {0})) return false;
        if (!writeRegisters(AfaCmd.PROC_LINK, 0xf103, new byte[] {7})) return false;
        byte[] image = profile.firmware();
        int off = 0;
        for (int i = 0; i < profile.fwChunks.length; i++) {
            int n = profile.fwChunks[i];
            byte[] chunk = new byte[n];
            System.arraycopy(image, off, chunk, 0, n);
            off += n;
            if (sendCommand(0x29, AfaCmd.PROC_LINK, chunk, 0) == null) {
                notes.append("firmware chunk ").append(i).append(" failed\n");
                return false;
            }
        }
        if (sendCommand(0x23, AfaCmd.PROC_LINK, null, 0) == null) {
            notes.append("firmware start command failed\n");
            return false;
        }
        sleep(10);
        notes.append("firmware downloaded (").append(off).append(" bytes)\n");
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes {addr, value} entries to the OFDM processor, batching runs of consecutive addresses. */
    private boolean loadScript(int[][] script) {
        int i = 0;
        while (i < script.length) {
            int start = script[i][0];
            byte[] buf = new byte[MAX_WRITE_RUN];
            int n = 0;
            while (i < script.length && n < MAX_WRITE_RUN && script[i][0] == start + n) {
                buf[n++] = (byte) script[i][1];
                i++;
            }
            byte[] run = new byte[n];
            System.arraycopy(buf, 0, run, 0, n);
            if (!writeRegisters(AfaCmd.PROC_OFDM, start, run)) {
                notes.append(String.format(Locale.US, "script write failed at 0x%x\n", start));
                return false;
            }
        }
        return true;
    }

    /**
     * Opens the device, runs the probe and a short TS read, and returns a multi-line report. Intended for
     * UsbProbeActivity so the real endpoint layout and chip id can be pasted back for the next step.
     */
    public synchronized String probeReport() {
        StringBuilder out = new StringBuilder();
        boolean opened = open();
        out.append("open: ").append(opened).append('\n');
        if (opened) {
            boolean ok = initTuner();
            out.append("initTuner: ").append(ok).append('\n');
            byte[] buf = new byte[READ_SIZE];
            int n = connection.bulkTransfer(epTs, buf, buf.length, 1000);
            out.append("TS read on 0x").append(Integer.toHexString(epTs.getAddress()))
                    .append(": ").append(n).append(" bytes");
            if (n > 0) out.append(", first byte 0x").append(Integer.toHexString(buf[0] & 0xFF))
                    .append(" (0x47 = sync)");
            out.append('\n');
        }
        out.append(notes);
        close();
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // TsSource
    // ---------------------------------------------------------------------------------------------

    /** One filled USB transfer travelling from the reader thread to the consumer thread. */
    private static final class Chunk {
        final byte[] data = new byte[URB_SIZE];
        int len;
    }

    private static final int URB_SIZE = 16384;       // 32 x 512: a whole number of high-speed packets
    private static final int URB_COUNT = 24;         // transfers kept in flight: ~190 ms of TS at 17 Mbit/s
    private static final int CHUNK_POOL = 400;       // ~6.5 MB between the USB thread and the consumer

    private final ArrayBlockingQueue<Chunk> filled = new ArrayBlockingQueue<Chunk>(CHUNK_POOL);
    private final ConcurrentLinkedQueue<Chunk> free = new ConcurrentLinkedQueue<Chunk>();
    private volatile long droppedChunks;

    /**
     * Reads the TS pipe. Many transfers stay queued at once (UsbRequest), so the dongle's small FIFO never waits
     * for Java; a second thread hands the data to the listener (demux/analysis can be slow without losing USB data).
     * Falls back to blocking bulkTransfer when UsbRequest cannot be set up.
     */
    private void readLoop(final Listener l) {
        free.clear();
        filled.clear();
        for (int i = 0; i < CHUNK_POOL; i++) free.add(new Chunk());
        consumerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                consume(l);
            }
        }, "UsbTsConsumer");
        consumerThread.start();
        try {
            if (!asyncReadLoop()) syncReadLoop();
        } finally {
            running = false;
        }
    }

    private void consume(Listener l) {
        while (running || !filled.isEmpty()) {
            Chunk c;
            try {
                c = filled.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (c == null) continue;
            try {
                l.onData(c.data, 0, c.len);
            } catch (RuntimeException e) {
                l.onError("TS consumer error: " + e);
            }
            free.add(c);
        }
    }

    private void deliver(byte[] src, int n) {
        Chunk c = free.poll();
        if (c == null) {
            droppedChunks++;
            return;
        }
        System.arraycopy(src, 0, c.data, 0, n);
        c.len = n;
        if (!filled.offer(c)) {
            droppedChunks++;
            free.add(c);
        }
    }

    private boolean asyncReadLoop() {
        UsbDeviceConnection c = connection;
        UsbEndpoint ep = epTs;
        if (c == null || ep == null) return false;
        UsbRequest[] reqs = new UsbRequest[URB_COUNT];
        ByteBuffer[] bufs = new ByteBuffer[URB_COUNT];
        for (int i = 0; i < URB_COUNT; i++) {
            UsbRequest r = new UsbRequest();
            if (!r.initialize(c, ep)) {
                for (int k = 0; k < i; k++) reqs[k].close();
                r.close();
                notes.append("UsbRequest.initialize failed; using blocking reads\n");
                return false;
            }
            bufs[i] = ByteBuffer.allocate(URB_SIZE);
            r.setClientData(Integer.valueOf(i));
            reqs[i] = r;
        }
        for (int i = 0; i < URB_COUNT; i++) {
            if (!queue(reqs[i], bufs[i])) {
                for (int k = 0; k < URB_COUNT; k++) reqs[k].close();
                notes.append("UsbRequest.queue failed; using blocking reads\n");
                return false;
            }
        }
        urbs = reqs;
        int failures = 0;
        while (running) {
            UsbRequest done = c.requestWait();
            if (done == null) {
                if (!running) break;
                if (++failures > 200) break;   // device gone
                sleep(5);
                continue;
            }
            Object tag = done.getClientData();
            if (!(tag instanceof Integer)) continue;
            int idx = (Integer) tag;
            ByteBuffer b = bufs[idx];
            int n = b.position();
            if (n > 0) {
                failures = 0;
                deliver(b.array(), n);
            } else if (++failures > 2000) {
                break;
            }
            if (!running) break;
            if (!queue(done, b)) {
                if (++failures > 200) break;
            }
        }
        urbs = null;
        for (UsbRequest r : reqs) {
            try {
                r.cancel();
            } catch (RuntimeException ignored) {
            }
            r.close();
        }
        return true;
    }

    private static boolean queue(UsbRequest r, ByteBuffer b) {
        b.clear();
        return Build.VERSION.SDK_INT >= 26 ? r.queue(b) : r.queue(b, URB_SIZE);
    }

    private void syncReadLoop() {
        byte[] buf = new byte[READ_SIZE];
        while (running) {
            UsbDeviceConnection c = connection;
            UsbEndpoint ep = epTs;
            if (c == null || ep == null) break;
            int n = c.bulkTransfer(ep, buf, buf.length, 500);
            if (n > 0) deliver(buf, Math.min(n, buf.length));
            // n < 0 is a timeout or error; keep polling until stop() is called.
        }
    }

    /** Number of 16 KiB transfers dropped because the consumer could not keep up (should stay 0). */
    public long getDroppedChunks() {
        return droppedChunks;
    }

    @Override
    public boolean isReady() {
        return running && tuner != null;
    }

    @Override
    public boolean supportsTuning() {
        return true;
    }

    /**
     * Tunes to a 6 MHz ISDB-T channel (centre frequency in kHz) and waits for demod lock (up to ~6 s).
     * Port of lib275a_SetPara + lib275a_IsLock; verified against the vendor library under emulation only.
     */
    @Override
    public boolean tune(int frequencyKhz) {
        return doTune(frequencyKhz, false);
    }

    @Override
    public boolean scanTune(int frequencyKhz) {
        return doTune(frequencyKhz, true);
    }

    private boolean doTune(int frequencyKhz, boolean quick) {
        tuning = true;
        try {
            return doTuneInner(frequencyKhz, quick);
        } finally {
            tuning = false;
        }
    }

    private boolean doTuneInner(int frequencyKhz, boolean quick) {
        if (tuner == null) {
            notes.append("tune: tuner not initialised\n");
            return false;
        }
        long t0 = System.currentTimeMillis();
        boolean ok = tuner.tune(frequencyKhz);
        long tTune = System.currentTimeMillis() - t0;
        if (!quick) notes.append("tune ").append(frequencyKhz).append(" kHz: ").append(ok ? "programmed" : "FAILED").append('\n');
        if (!ok) return false;
        sleep(50);
        locked = tuner.waitLock(quick);
        Log.i("OpenDTV", "tune " + frequencyKhz + " kHz: programmed in " + tTune + " ms, lock=" + locked
                + " after " + (System.currentTimeMillis() - t0) + " ms");
        SignalStatus fresh = tuner.getStatus();
        fresh.locked = locked && fresh.locked;
        cachedStatus = fresh;
        if (!quick) {
            notes.append("lock: ").append(locked).append('\n');
            notes.append(String.format(Locale.US, "signal raw: strength=%d quality=%d snr=%d dbm=%d berErr=%d berWin=%d%n",
                    fresh.rawStrength, fresh.rawQuality, fresh.rawSnr, fresh.dbm, fresh.berErrors, fresh.berWindow));
            notes.append(diagRegs("after tune"));
        }
        return locked;
    }

    @Override
    public SignalStatus getSignalStatus() {
        return connection == null ? null : cachedStatus;
    }

    @Override
    public synchronized void stop() {
        running = false;
        UsbRequest[] reqs = urbs;
        if (reqs != null) {
            for (UsbRequest r : reqs) {
                try {
                    r.cancel();   // wakes requestWait()
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (statusThread != null) {
            statusThread.interrupt();
            statusThread = null;
        }
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (consumerThread != null) {
            try {
                consumerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consumerThread = null;
        }
        close();
    }

    private static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format(Locale.US, "%02x ", b[i] & 0xFF));
        return s.toString().trim();
    }
}