package ph.opendtv.ts;

import static ph.opendtv.ts.TsUtil.bcd;
import static ph.opendtv.ts.TsUtil.u12;
import static ph.opendtv.ts.TsUtil.u13;
import static ph.opendtv.ts.TsUtil.u16;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * MPEG-TS demultiplexer and ISDB-T (ARIB STD-B10) PSI/SI analyzer.
 *
 * Call feed() with raw bytes in any chunking; it resynchronises on 0x47, tracks per-PID statistics,
 * parses PAT, PMT, SDT, NIT, EIT and TDT/TOT, and forwards good packets to an optional PacketSink.
 * All public methods are synchronized, so the UI thread can read while a source thread feeds.
 */
public final class TsDemux {
    public static final int PID_PAT = 0x0000, PID_NIT = 0x0010, PID_SDT = 0x0011,
            PID_EIT = 0x0012, PID_TDT = 0x0014, PID_IIP = 0x1FF0, PID_NULL = 0x1FFF;

    private static final int PKT = 188;
    private static final int MAX_EVENTS = 4000;

    private static final class PidStats {
        long packets;
        long ccErrors;
        int lastCc = -1;
    }

    /**
     * Rolling history of the raw packets of one PID. Lets the data-broadcast viewer start from what the
     * carousel already delivered (a recorded file is read once, a live carousel repeats only every N seconds).
     * Grows on demand up to its maximum, so unused PIDs cost nothing.
     */
    private static final class Ring {
        final boolean psi;
        final int maxPkts;
        byte[] buf;   // first packet allocates: a PID that is only announced (never carries data) costs nothing
        int cap = 0, next = 0, count = 0;

        Ring(int maxPkts, boolean psi) {
            this.maxPkts = maxPkts;
            this.psi = psi;
        }

        void add(byte[] b, int o) {
            if (buf == null) {
                cap = Math.min(64, maxPkts);
                buf = new byte[cap * PKT];
            }
            if (count == cap && cap < maxPkts) {            // not wrapped yet: grow in place
                int ncap = Math.min(maxPkts, cap * 2);
                byte[] nb = new byte[ncap * PKT];
                System.arraycopy(buf, 0, nb, 0, count * PKT);
                buf = nb;
                cap = ncap;
                next = count;
            }
            System.arraycopy(b, o, buf, next * PKT, PKT);
            next = (next + 1) % cap;
            if (count < cap) count++;
        }

        int copyTo(byte[] out, int o) {
            int first = count < cap ? 0 : next;             // oldest packet
            for (int i = 0; i < count; i++) {
                System.arraycopy(buf, ((first + i) % cap) * PKT, out, o, PKT);
                o += PKT;
            }
            return o;
        }
    }

    private static final int PSI_RING_PKTS = 512, EIT_RING_PKTS = 1024, DATA_RING_PKTS = 12000;
    /**
     * PIDs we assemble sections on, and data carousels we decode. A real multiplex has a handful of each, but one
     * crafted PAT can name 8000 PMT PIDs and one PMT hundreds of carousels, and their buffers ran the heap out.
     */
    private static final int MAX_SECTION_PIDS = 256, MAX_DATA_PIDS = 16;
    private final Ring[] rings = new Ring[0x2000];
    private long eventSections, pmtEmergencyDescs;
    private boolean bmlEwbs = false;
    private String iipLastHex = "";

    private void ensureRing(int pid, int max, boolean psi) {
        if (pid >= 0 && pid < rings.length && rings[pid] == null) rings[pid] = new Ring(max, psi);
    }

    private final byte[] carry = new byte[PKT];
    private int carryLen = 0;

    private final PidStats[] stats = new PidStats[0x2000];   // indexed by PID: no boxing in the per-packet path
    private final Map<Integer, SectionAssembler> assemblers = new HashMap<>();
    private final Map<Integer, ServiceInfo> services = new TreeMap<>();
    private final Map<Integer, Integer> tsRemoteKey = new HashMap<>();
    private final Map<Long, EpgEvent> events = new HashMap<>();
    private final Map<Integer, CarouselDecoder> carousels = new TreeMap<>();

    private PacketSink sink;
    private volatile PacketSink tap;
    private long totalPackets, nullPackets, teiPackets, syncLosses;
    private int tsId = -1, networkId = -1;
    private String networkName = "";
    private long clockMs = -1;

    // EWBS / emergency signalling: TMCC start flag (IIP), PMT 0xFC descriptor, BML event messages
    private static final int MAX_EWBS_LOG = 200;
    private boolean iipFlag = false;
    private long iipPackets = 0, iipBadCrc = 0;
    private final List<String> ewbsLog = new ArrayList<>();
    private final Map<Integer, String> lastEventMsg = new TreeMap<>();

    private long lastSampleNs = 0;
    private long lastSampleTotal = 0;
    private final Map<Integer, Long> lastSamplePackets = new HashMap<>();

    public TsDemux() {
        SectionAssembler.Sink s = this::onSection;
        for (int pid : new int[]{PID_PAT, PID_NIT, PID_SDT, PID_EIT, PID_TDT}) {
            assemblers.put(pid, new SectionAssembler(pid, s));
            ensureRing(pid, pid == PID_EIT ? EIT_RING_PKTS : PSI_RING_PKTS, true);
        }
    }

    /**
     * Atomically installs {@code t} as the tap and returns the buffered history (PSI first, then data PIDs)
     * of every PID flagged in {@code want}, so a consumer that starts late still sees what already went by.
     * Packets arriving after this call go to the tap as usual.
     */
    public synchronized byte[] attachTapWithHistory(PacketSink t, boolean[] want) {
        int total = 0;
        for (int pid = 0; pid < rings.length && pid < want.length; pid++) {
            if (want[pid] && rings[pid] != null) total += rings[pid].count * PKT;
        }
        byte[] out = new byte[total];
        int o = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int pid = 0; pid < rings.length && pid < want.length; pid++) {
                Ring r = rings[pid];
                if (want[pid] && r != null && r.psi == (pass == 0)) o = r.copyTo(out, o);
            }
        }
        this.tap = t;
        return out;
    }

    /** Second, independent consumer of every good packet (used by the data-broadcast viewer). */
    public void setTapSink(PacketSink s) {
        this.tap = s;
    }

    public synchronized void setPacketSink(PacketSink s) {
        this.sink = s;
    }

    // ------------------------------------------------------------------ packet layer

    public synchronized void feed(byte[] b, int off, int len) {
        // Clamp: a source must never make the demux read past its buffer.
        int p = Math.max(off, 0), end = Math.min(off + len, b.length);
        if (end <= p) return;

        if (carryLen > 0) {
            int take = Math.min(PKT - carryLen, end - p);
            System.arraycopy(b, p, carry, carryLen, take);
            carryLen += take;
            p += take;
            if (carryLen < PKT) return;
            if ((carry[0] & 0xFF) == 0x47) handlePacket(carry, 0);
            else syncLosses++;
            carryLen = 0;
        }

        while (end - p >= PKT) {
            if ((b[p] & 0xFF) != 0x47) {
                syncLosses++;
                int q = p + 1;
                while (q < end && !((b[q] & 0xFF) == 0x47 && (q + PKT >= end || (b[q + PKT] & 0xFF) == 0x47))) q++;
                p = q;
                continue;
            }
            handlePacket(b, p);
            p += PKT;
        }

        if (p < end) {
            carryLen = end - p;
            System.arraycopy(b, p, carry, 0, carryLen);
        }
        if (sink != null) sink.flush();
        PacketSink tp = tap;
        if (tp != null) tp.flush();
    }

    private void handlePacket(byte[] b, int o) {
        boolean tei = (b[o + 1] & 0x80) != 0;
        boolean pusi = (b[o + 1] & 0x40) != 0;
        int pid = ((b[o + 1] & 0x1F) << 8) | (b[o + 2] & 0xFF);
        int afc = (b[o + 3] >> 4) & 3;
        int cc = b[o + 3] & 0x0F;

        totalPackets++;
        if (pid == PID_NULL) {
            nullPackets++;
            return;
        }
        if (tei) {
            teiPackets++;
            return;
        }

        PidStats st = stats[pid];
        if (st == null) {
            st = new PidStats();
            stats[pid] = st;
        }
        st.packets++;

        int p = o + 4;
        int end = o + PKT;
        boolean hasPayload = (afc & 1) != 0;
        boolean discontinuity = false;
        if ((afc & 2) != 0) {
            int afl = b[p] & 0xFF;
            if (afl > 0 && p + 1 < end) discontinuity = (b[p + 1] & 0x80) != 0;
            p += 1 + afl;
        }
        if (hasPayload) {
            if (st.lastCc >= 0 && !discontinuity && cc != st.lastCc && cc != ((st.lastCc + 1) & 0x0F)) {
                st.ccErrors++;
            }
            st.lastCc = cc;
        } else if (discontinuity) {
            st.lastCc = -1;
        }

        if (pid == PID_IIP && hasPayload && pusi && end - p >= 22) parseIip(b, p);

        if (hasPayload && p < end) {
            SectionAssembler a = assemblers.get(pid);
            if (a != null) a.push(b, p, end, pusi);
        }
        Ring rg = rings[pid];
        if (rg != null) rg.add(b, o);
        PacketSink t = tap;
        if (t != null) t.onPacket(b, o);
        if (sink != null) sink.onPacket(b, o);
    }

    // ------------------------------------------------------------------ section layer

    private void onSection(int pid, byte[] s, int n) {
        int tid = s[0] & 0xFF;
        try {
            if (tid == 0x3D) parseStreamEvent(pid, s, n);   // DSM-CC stream-event section (BML event messages)
            CarouselDecoder cd = carousels.get(pid);
            if (cd != null) { // PID announced as DSM-CC data (stream types 0x0B-0x0D)
                cd.onSection(s, n);
                return;
            }
            switch (tid) {
                case 0x00: parsePat(s, n); break;
                case 0x02: parsePmt(pid, s, n); break;
                case 0x40: parseNit(s, n); break;
                case 0x42: parseSdt(s, n); break;
                case 0x70: case 0x73: parseTime(s, n); break;
                default:
                    if (pid == PID_EIT && (tid == 0x4E || (tid >= 0x50 && tid <= 0x5F))) parseEit(s, n);
            }
        } catch (RuntimeException e) {
            // Malformed table (length fields out of range): ignore it, keep running.
        }
    }

    private ServiceInfo svc(int sid) {
        ServiceInfo si = services.get(sid);
        if (si == null) {
            si = new ServiceInfo(sid);
            services.put(sid, si);
        }
        return si;
    }

    private void parsePat(byte[] s, int n) {
        tsId = u16(s, 3);
        for (int p = 8; p + 4 <= n - 4; p += 4) {
            int program = u16(s, p);
            int pid = u13(s, p + 2);
            if (program == 0) continue; // NIT PID entry
            svc(program).pmtPid = pid;
            ensureRing(pid, PSI_RING_PKTS, true);
            if (!assemblers.containsKey(pid) && assemblers.size() < MAX_SECTION_PIDS) {
                assemblers.put(pid, new SectionAssembler(pid, this::onSection));
            }
        }
    }

    private void parsePmt(int pid, byte[] s, int n) {
        int sid = u16(s, 3);
        ServiceInfo si = services.get(sid);
        if (si == null || si.pmtPid != pid) return;
        si.pcrPid = u13(s, 8);
        int p = 12 + u12(s, 10);
        int end = n - 4;
        parseEmergencyDescriptor(si, s, 12, Math.min(p, end));
        List<StreamInfo> list = new ArrayList<>();
        while (p + 5 <= end) {
            StreamInfo st = new StreamInfo();
            st.streamType = s[p] & 0xFF;
            st.pid = u13(s, p + 1);
            int dEnd = Math.min(p + 5 + u12(s, p + 3), end);
            int d = p + 5;
            while (d + 2 <= dEnd) {
                int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
                if (tag == 0x52 && dl >= 1) st.componentTag = s[d + 2] & 0xFF;
                else if (tag == 0xFD && dl >= 2) st.dataComponentId = u16(s, d + 2);
                else if (tag == 0x0A && dl >= 3) st.language = iso639(s, d + 2);            // ISO_639_language_descriptor
                else if (tag == 0xC4 && dl >= 9) {                                           // audio_component_descriptor
                    st.language = iso639(s, d + 2 + 6);
                    if ((s[d + 2 + 5] & 0x80) != 0 && dl >= 12) st.language2 = iso639(s, d + 2 + 9);
                }
                d += 2 + dl;
            }
            list.add(st);
            // A carousel never takes over a PID that already carries tables (PAT, EIT, a PMT ...): those would
            // stop being parsed for the rest of the tune.
            boolean newCarousel = st.isDatacast() && !carousels.containsKey(st.pid) && st.pid >= 0x20
                    && !assemblers.containsKey(st.pid) && carousels.size() < MAX_DATA_PIDS;
            if (newCarousel || carousels.containsKey(st.pid)) ensureRing(st.pid, DATA_RING_PKTS, false);
            if (newCarousel) {
                carousels.put(st.pid, new CarouselDecoder(st.pid));
                assemblers.put(st.pid, new SectionAssembler(st.pid, this::onSection));
            } else if (st.streamType == 0x05 && !assemblers.containsKey(st.pid) && assemblers.size() < MAX_SECTION_PIDS) {
                // private sections: could carry DSM-CC stream events (EWBS event messages) on their own PID
                assemblers.put(st.pid, new SectionAssembler(st.pid, this::onSection));
            }
            p = dEnd;
        }
        si.streams = list;
        si.pmtSeen = true;
    }

    private static String iso639(byte[] s, int p) {
        String l = new String(new char[] {(char) (s[p] & 0x7F), (char) (s[p + 1] & 0x7F), (char) (s[p + 2] & 0x7F)}).trim();
        return l.length() == 3 ? l.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private void parseSdt(byte[] s, int n) {
        tsId = u16(s, 3);
        int p = 11, end = n - 4;
        while (p + 5 <= end) {
            int sid = u16(s, p);
            int dEnd = Math.min(p + 5 + u12(s, p + 3), end);
            ServiceInfo si = svc(sid);
            int d = p + 5;
            while (d + 2 <= dEnd) {
                int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
                if (tag == 0x48 && dl >= 3) { // service_descriptor
                    si.serviceType = s[d + 2] & 0xFF;
                    int provLen = s[d + 3] & 0xFF;
                    si.provider = TsUtil.text(s, d + 4, provLen);
                    int nameLen = s[d + 4 + provLen] & 0xFF;
                    si.name = TsUtil.text(s, d + 5 + provLen, nameLen);
                }
                d += 2 + dl;
            }
            p = dEnd;
        }
    }

    private void parseNit(byte[] s, int n) {
        networkId = u16(s, 3);
        int p = 8;
        int ndl = u12(s, p);
        p += 2;
        int dEnd = p + ndl;
        for (int d = p; d + 2 <= dEnd; ) {
            int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
            if (tag == 0x40) networkName = TsUtil.text(s, d + 2, dl);
            d += 2 + dl;
        }
        p = dEnd;
        int loopEnd = Math.min(p + 2 + u12(s, p), n - 4);
        p += 2;
        while (p + 6 <= loopEnd) {
            int tid = u16(s, p);
            int tdEnd = Math.min(p + 6 + u12(s, p + 4), loopEnd);
            for (int d = p + 6; d + 2 <= tdEnd; ) {
                int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
                if (tag == 0xCD && dl >= 2) { // TS information descriptor
                    tsRemoteKey.put(tid, s[d + 2] & 0xFF);
                }
                d += 2 + dl;
            }
            p = tdEnd;
        }
    }

    private void parseTime(byte[] s, int n) {
        if (n >= 8) clockMs = TsUtil.mjdToMillis(s, 3);
    }

    private void parseEit(byte[] s, int n) {
        int sid = u16(s, 3);
        int p = 14, end = n - 4;
        while (p + 12 <= end) {
            EpgEvent e = new EpgEvent();
            e.serviceId = sid;
            e.eventId = u16(s, p);
            e.startMs = (u16(s, p + 2) == 0xFFFF) ? -1 : TsUtil.mjdToMillis(s, p + 2);
            e.durationSec = bcd(s[p + 7]) * 3600 + bcd(s[p + 8]) * 60 + bcd(s[p + 9]);
            e.runningStatus = (s[p + 10] >> 5) & 7;
            int dEnd = Math.min(p + 12 + u12(s, p + 10), end);
            for (int d = p + 12; d + 2 <= dEnd; ) {
                int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
                if (tag == 0x4D && dl >= 5) { // short_event_descriptor
                    int nl = s[d + 5] & 0xFF;
                    e.name = TsUtil.text(s, d + 6, nl);
                    int tl = s[d + 6 + nl] & 0xFF;
                    e.text = TsUtil.text(s, d + 7 + nl, tl);
                }
                d += 2 + dl;
            }
            if (events.size() > MAX_EVENTS) events.clear();
            events.put(((long) sid << 16) | e.eventId, e);
            p = dEnd;
        }
    }


    // ------------------------------------------------------------------ EWBS / emergency signalling

    private void logEwbs(String what) {
        if (ewbsLog.size() >= MAX_EWBS_LOG) ewbsLog.remove(0);
        ewbsLog.add(String.format(Locale.US, "%s  pkt %d  %s",
                clockMs >= 0 ? TsUtil.formatTime(clockMs) : "--", totalPackets, what));
    }

    /**
     * ISDB-T Information Packet (ARIB STD-B31, PID 0x1FF0). Payload: IIP_packet_pointer(16), then the
     * 16-byte modulation_control_configuration_information and its CRC32. Inside it, byte 2 holds
     * system_identifier(2) count_down_index(4) switch_on_control_flag_for_emergency_alerting(1)
     * partial_reception(1): the emergency start-up flag is bit 1 of payload byte 4.
     */
    private void parseIip(byte[] b, int p) {
        if (Crc32Mpeg.calc(b, p + 2, 20) != 0) {   // 16 bytes + CRC32 must give 0
            iipBadCrc++;
            return;
        }
        boolean f = ((b[p + 4] >> 1) & 1) != 0;
        StringBuilder hx = new StringBuilder();
        for (int i = 2; i < 18; i++) hx.append(String.format(Locale.US, "%02X", b[p + i] & 0xFF));
        iipLastHex = hx.toString();
        iipPackets++;
        if (iipPackets == 1) {
            iipFlag = f;
            if (f) logEwbs("IIP: emergency start-up flag already ON at first IIP");
        } else if (f != iipFlag) {
            iipFlag = f;
            logEwbs(f ? "IIP: emergency start-up flag 0 -> 1 (EWBS START)" : "IIP: emergency start-up flag 1 -> 0 (EWBS END)");
        }
    }

    /** PMT program-level emergency_information_descriptor (tag 0xFC). */
    private void parseEmergencyDescriptor(ServiceInfo si, byte[] s, int from, int to) {
        boolean active = false;
        StringBuilder info = new StringBuilder();
        for (int d = from; d + 2 <= to; ) {
            int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
            if (tag == 0xFC && d + 2 + dl <= to) {
                pmtEmergencyDescs++;
                int q = d + 2, qEnd = q + dl;
                while (q + 4 <= qEnd) {
                    int esid = u16(s, q);
                    boolean start = (s[q + 2] & 0x80) != 0;
                    int level = (s[q + 2] >> 6) & 1;
                    int al = s[q + 3] & 0xFF;
                    q += 4;
                    if (start) active = true;
                    if (info.length() > 0) info.append("; ");
                    info.append(String.format(Locale.US, "service 0x%04X %s level %d areas", esid, start ? "START" : "END", level));
                    for (int a = 0; a + 2 <= al && q + a + 2 <= qEnd; a += 2) {
                        info.append(String.format(Locale.US, " 0x%03X", u16(s, q + a) >> 4));
                    }
                    q += al;
                }
            }
            d += 2 + dl;
        }
        String now = info.toString();
        if (active != si.emergencyActive || !now.equals(si.emergencyInfo)) {
            if (!now.isEmpty()) logEwbs("PMT " + si.serviceId + " (0x" + Integer.toHexString(si.serviceId).toUpperCase(Locale.ROOT) + "): emergency descriptor 0xFC: " + now);
            else logEwbs("PMT " + si.serviceId + ": emergency descriptor removed");
        }
        si.emergencyActive = active;
        si.emergencyInfo = now;
    }

    /**
     * DSM-CC stream-event section (table_id 0x3D). Looks for the general event message descriptor
     * (0x40): the first 12 bits are event_msg_group_id, the private data is the trailing printable
     * ASCII run (this PTV BML tests only its last character: '1' = EWBS start, '2' = end).
     */
    private void parseStreamEvent(int pid, byte[] s, int n) {
        eventSections++;
        for (int d = 8; d + 2 <= n - 4; ) {
            int tag = s[d] & 0xFF, dl = s[d + 1] & 0xFF;
            if (d + 2 + dl > n - 4) break;
            if (tag == 0x40 && dl >= 3) {
                int group = ((s[d + 2] & 0xFF) << 4) | ((s[d + 3] & 0xF0) >> 4);
                int e = d + 2 + dl, st = e;
                while (st > d + 2 && s[st - 1] >= 0x20 && s[st - 1] < 0x7F) st--;
                String priv = new String(s, st, e - st, java.nio.charset.StandardCharsets.US_ASCII);
                String key = group + "|" + priv;
                if (!key.equals(lastEventMsg.get(pid))) {
                    lastEventMsg.put(pid, key);
                    char last = priv.isEmpty() ? ' ' : priv.charAt(priv.length() - 1);
                    if (last == '1') bmlEwbs = true;
                    else if (last == '2') bmlEwbs = false;
                    logEwbs(String.format(Locale.US, "EVENT MSG pid 0x%04X group %d private '%s'%s", pid, group, priv,
                            last == '1' ? "  <- BML EWBS START" : last == '2' ? "  (BML: end / normal)" : ""));
                }
            }
            d += 2 + dl;
        }
    }

    /** true while any EWBS signal (IIP flag or a PMT start descriptor) is active. */
    public synchronized boolean isEwbsActive() {
        if (iipFlag || bmlEwbs) return true;
        for (ServiceInfo si : services.values()) if (si.emergencyActive) return true;
        return false;
    }

    /** true if any EWBS signal has been seen at all (even if it has ended again): useful for finished recordings. */
    public synchronized boolean ewbsEverSeen() {
        return !ewbsLog.isEmpty();
    }

    /** Short one-line description of what is signalling EWBS right now / last time (for the on-screen banner). */
    public synchronized String ewbsBannerText() {
        StringBuilder sb = new StringBuilder();
        if (iipFlag) sb.append("IIP start flag");
        for (ServiceInfo si : services.values()) {
            if (si.emergencyActive) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("PMT ").append(si.serviceId);
            }
        }
        if (bmlEwbs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("BML event");
        }
        return sb.toString();
    }

    /** Multi-line text for the analyzer. Always non-empty so the user can see that EWBS was looked for. */
    public synchronized String ewbsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(isEwbsActive() ? "!! EWBS ACTIVE !!\n" : ewbsLog.isEmpty() ? "EWBS: no signal seen\n" : "EWBS: idle (was signalled, see log)\n");
        sb.append(String.format(Locale.US, "  IIP 0x1FF0: %d ok, %d bad CRC, emergency start-up flag %s\n",
                iipPackets, iipBadCrc, iipPackets == 0 ? "n/a (no IIP in this TS)" : iipFlag ? "ON" : "off"));
        if (!iipLastHex.isEmpty()) sb.append("  last IIP MCCI: ").append(iipLastHex).append('\n');
        sb.append(String.format(Locale.US, "  PMT 0xFC descriptors seen: %d | DSM-CC event sections (0x3D): %d | BML EWBS flag: %s\n",
                pmtEmergencyDescs, eventSections, bmlEwbs ? "ON" : "off"));
        for (ServiceInfo si : services.values()) {
            if (!si.emergencyInfo.isEmpty()) sb.append("  PMT ").append(si.serviceId).append(": ").append(si.emergencyInfo).append('\n');
        }
        for (Map.Entry<Integer, String> e : lastEventMsg.entrySet()) {
            sb.append(String.format(Locale.US, "  event msg on 0x%04X: %s\n", e.getKey(), e.getValue().replace("|", " / ")));
        }
        int from = Math.max(0, ewbsLog.size() - 12);
        for (int i = from; i < ewbsLog.size(); i++) sb.append("  ").append(ewbsLog.get(i)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ queries (snapshots)

    /** Services sorted by service_id. virtualNumber is remote_control_key_id, with ".N" appended when the TS has several TV services (heuristic). */
    /** true once the NIT's TS-information descriptor (remote_control_key_id = virtual channel number) was received. */
    public synchronized boolean hasRemoteKey() {
        return tsRemoteKey.containsKey(tsId) || tsRemoteKey.size() == 1;
    }

    public synchronized List<ServiceInfo> getServices() {
        Integer rk = tsRemoteKey.get(tsId);
        if (rk == null && tsRemoteKey.size() == 1) rk = tsRemoteKey.values().iterator().next();

        int tvCount = 0;
        for (ServiceInfo si : services.values()) if (isTv(si)) tvCount++;

        List<ServiceInfo> out = new ArrayList<>();
        int idx = 0;
        for (ServiceInfo si : services.values()) {
            ServiceInfo c = si.copy();
            c.remoteKey = rk == null ? -1 : rk;
            if (rk != null && isTv(si)) {
                idx++;
                c.virtualNumber = tvCount > 1 ? rk + "." + idx : String.valueOf(rk);
            }
            out.add(c);
        }
        return out;
    }

    private static boolean isTv(ServiceInfo si) {
        return si.serviceType == 0x01 || si.hasVideo();
    }

    /** Snapshots of every DSM-CC data carousel found in the PMTs, sorted by PID. */
    public synchronized List<CarouselInfo> getCarousels() {
        List<CarouselInfo> out = new ArrayList<>();
        for (CarouselDecoder cd : carousels.values()) out.add(cd.snapshot());
        return out;
    }

    public synchronized int getTsId() { return tsId; }

    public synchronized int getNetworkId() { return networkId; }

    public synchronized String getNetworkName() { return networkName; }

    /** Broadcast clock from TDT/TOT (ARIB: JST wall-clock), -1 if not received. */
    public synchronized long getClockMs() { return clockMs; }

    public synchronized List<EpgEvent> getEvents(int serviceId) {
        List<EpgEvent> out = new ArrayList<>();
        for (EpgEvent e : events.values()) if (e.serviceId == serviceId) out.add(e);
        Collections.sort(out, new Comparator<EpgEvent>() {
            @Override
            public int compare(EpgEvent a, EpgEvent b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return out;
    }

    public synchronized AnalyzerSnapshot sample() {
        long now = System.nanoTime();
        double dt = lastSampleNs == 0 ? 0 : (now - lastSampleNs) / 1e9;
        AnalyzerSnapshot snap = new AnalyzerSnapshot();
        snap.totalPackets = totalPackets;
        snap.nullPackets = nullPackets;
        snap.teiPackets = teiPackets;
        snap.syncLosses = syncLosses;
        snap.totalKbps = dt > 0 ? (totalPackets - lastSampleTotal) * PKT * 8 / dt / 1000.0 : 0;

        for (int pidIdx = 0; pidIdx < stats.length; pidIdx++) {
            PidStats st = stats[pidIdx];
            if (st == null) continue;
            Long prev = lastSamplePackets.get(pidIdx);
            AnalyzerSnapshot.Row r = new AnalyzerSnapshot.Row();
            r.pid = pidIdx;
            r.label = describePidLocked(r.pid);
            r.packets = st.packets;
            r.ccErrors = st.ccErrors;
            r.kbps = (dt > 0 && prev != null) ? (st.packets - prev) * PKT * 8 / dt / 1000.0 : 0;
            snap.ccErrors += st.ccErrors;
            snap.rows.add(r);
            lastSamplePackets.put(pidIdx, st.packets);
        }
        Collections.sort(snap.rows, new Comparator<AnalyzerSnapshot.Row>() {
            @Override
            public int compare(AnalyzerSnapshot.Row a, AnalyzerSnapshot.Row b) {
                int c = Double.compare(b.kbps, a.kbps);
                return c != 0 ? c : Integer.compare(a.pid, b.pid);
            }
        });
        lastSampleNs = now;
        lastSampleTotal = totalPackets;
        return snap;
    }

    public synchronized String describePid(int pid) {
        return describePidLocked(pid);
    }

    private String describePidLocked(int pid) {
        switch (pid) {
            case PID_PAT: return "PAT";
            case PID_NIT: return "NIT";
            case PID_SDT: return "SDT/BAT";
            case PID_EIT: return "EIT";
            case PID_TDT: return "TDT/TOT";
            case PID_IIP: return "IIP (TMCC / EWBS start flag)";
            default:
        }
        for (ServiceInfo si : services.values()) {
            if (si.pmtPid == pid) return "PMT sid " + si.serviceId;
            for (StreamInfo st : si.streams) {
                if (st.pid == pid) {
                    String kind = st.isVideo() ? "Video" : st.isAudio() ? "Audio"
                                                           : st.isDatacast() ? "Datacast" : "Data";
                    return kind + " " + st.shortCodec() + " (sid " + si.serviceId + ")";
                }
            }
        }
        return "";
    }
}