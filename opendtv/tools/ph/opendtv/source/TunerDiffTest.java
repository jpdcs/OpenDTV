package ph.opendtv.source;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Differential test of {@link It9175Tuner} against traces of the real vendor library (libpt275aDrv.so run under
 * a CPU emulator against a fake device, see tools/emu). For every scenario both sides get the same register
 * read values and must produce the identical sequence of register writes.
 *
 * Run: javac -d out -sourcepath app/src/main/java tools/ph/opendtv/source/TunerDiffTest.java
 *      java -cp out ph.opendtv.source.TunerDiffTest tools/golden/tuner_golden.txt
 *      java -cp out ph.opendtv.source.TunerDiffTest tools/golden/tuner_golden_ptx1.txt ptx1   (PT275C, libPTx1Drv.so)
 */
public final class TunerDiffTest {
    static int pseudo(int p, long a) {
        return (int) ((a * 131 + p * 17 + 7) & 0xff);
    }

    static final class FakeIo implements It9175Tuner.Io {
        final Map<Long, Integer> expl = new HashMap<Long, Integer>();
        final Set<Long> targets;
        final List<String> ops = new ArrayList<String>();

        FakeIo(Set<Long> targets) {
            this.targets = targets;
        }

        static long key(int p, long a) {
            return ((long) p << 32) | a;
        }

        public byte[] read(int proc, long addr, int n) {
            byte[] r = new byte[n];
            for (int i = 0; i < n; i++) {
                long k = key(proc, addr + i);
                Integer v = expl.get(k);
                if (v == null) v = targets.contains(k) ? pseudo(proc, addr + i) : 0;
                r[i] = (byte) (int) v;
            }
            return r;
        }

        public boolean write(int proc, long addr, byte[] d) {
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            ops.add("W " + proc + " " + Long.toHexString(addr) + " " + sb);
            return true;
        }

        public boolean fwVersion(int proc) {
            ops.add("C " + proc + " 22");
            return true;
        }

        public void sleep(int ms) {}
    }

    public static void main(String[] args) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(args[0]));
        It9175Profile profile = args.length > 1 && args[1].equals("ptx1") ? It9175Profile.PT275C : It9175Profile.PT275A;
        System.out.println("profile: " + profile.name);
        Set<Long> targets = new HashSet<Long>();
        String line;
        int scenarios = 0, failed = 0;
        String[] sc = null;
        List<String> exp = new ArrayList<String>();
        while ((line = br.readLine()) != null) {
            if (line.startsWith("T ")) {
                String[] p = line.split(" ");
                targets.add(FakeIo.key(Integer.parseInt(p[1]), Long.parseLong(p[2], 16)));
            } else if (line.startsWith("S ")) {
                sc = line.substring(2).split(" ");
                exp.clear();
            } else if (line.startsWith("O ")) {
                exp.add(line.substring(2));
            } else if (line.equals("E")) {
                scenarios++;
                int clk = Integer.parseInt(sc[0]), d1 = Integer.parseInt(sc[1]), ec86 = Integer.parseInt(sc[2]);
                int ed03 = Integer.parseInt(sc[3]), ed23 = Integer.parseInt(sc[4]), ed81 = Integer.parseInt(sc[5]);
                int ec4c = Integer.parseInt(sc[6]), r4d = Integer.parseInt(sc[7]), freq = Integer.parseInt(sc[8]);
                FakeIo io = new FakeIo(targets);
                Set<Long> t2 = new HashSet<Long>(targets);
                for (long k : new long[] {FakeIo.key(0, 0xd800), FakeIo.key(8, 0x1dc), FakeIo.key(8, 0xec86),
                        FakeIo.key(8, 0xec4c), FakeIo.key(8, 0xed81), FakeIo.key(8, 0x4d), FakeIo.key(8, 0x1c6)}) {
                    t2.remove(k);
                }
                io = new FakeIo(t2);
                io.expl.put(FakeIo.key(0, 0xd800), clk);
                io.expl.put(FakeIo.key(0, 0x1222), 1);
                io.expl.put(FakeIo.key(0, 0x1223), 0x75);
                io.expl.put(FakeIo.key(0, 0x1224), 0x91);
                io.expl.put(FakeIo.key(8, 0xec86), ec86);
                io.expl.put(FakeIo.key(8, 0xed03), ed03);
                io.expl.put(FakeIo.key(8, 0xed23), ed23 & 0xff);
                io.expl.put(FakeIo.key(8, 0xed24), ed23 >> 8);
                io.expl.put(FakeIo.key(8, 0xed81), ed81);
                io.expl.put(FakeIo.key(8, 0xec4c), ec4c);
                io.expl.put(FakeIo.key(8, 0x1dc), d1);
                io.expl.put(FakeIo.key(8, 0x1c6), 1);
                io.expl.put(FakeIo.key(8, 0x4d), r4d);
                StringBuilder log = new StringBuilder();
                It9175Tuner t = new It9175Tuner(io, log, profile);
                boolean ok = t.init() && t.tune(freq);
                boolean same = ok && io.ops.equals(exp);
                if (!same) {
                    failed++;
                    System.out.println("FAIL scenario " + scenarios + " " + String.join(" ", sc) + " ok=" + ok);
                    int n = Math.min(exp.size(), io.ops.size());
                    int i = 0;
                    while (i < n && exp.get(i).equals(io.ops.get(i))) i++;
                    System.out.println("  first diff at op " + i + " (exp " + exp.size() + ", got " + io.ops.size() + ")");
                    if (i < exp.size()) System.out.println("  exp: " + exp.get(i));
                    if (i < io.ops.size()) System.out.println("  got: " + io.ops.get(i));
                    if (failed > 5) break;
                }
            }
        }
        System.out.println(scenarios + " scenarios, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
