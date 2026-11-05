package ph.opendtv.ts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for the MIME multipart bodies used by ARIB data-broadcast modules:
 * <pre>
 * Content-Type: multipart/mixed;boundary="VI_BOUNDARY"
 * Content-Length: 5554
 * Content-Location:
 *
 * --VI_BOUNDARY
 * Content-Type: text/X-arib-bml; charset="euc-jp"
 * Content-Length: 4243
 * Content-Location: startup.bml
 *
 * ...body (Content-Length bytes, may be binary)...
 * --VI_BOUNDARY--
 * </pre>
 * Bodies are cut by Content-Length, never by searching for the boundary, because JPEG/PNG data
 * could contain the boundary text.
 */
final class MimeParts {
    private static final int MAX_PARTS = 4000;

    private MimeParts() {}

    private static final class Headers {
        final Map<String, String> map = new HashMap<>();
        int bodyStart; // offset just after the blank line
    }

    static List<CarouselResource> parse(byte[] d) {
        List<CarouselResource> out = new ArrayList<>();
        if (d == null || d.length < 14) return out;
        Headers outer = readHeaders(d, 0);
        if (outer == null) return out;
        String ct = outer.map.get("content-type");
        if (ct == null) return out;

        String boundary = boundaryOf(ct);
        if (boundary == null) { // single, non-multipart resource
            CarouselResource r = new CarouselResource();
            r.contentType = ct;
            r.location = nz(outer.map.get("content-location"));
            r.body = Arrays.copyOfRange(d, outer.bodyStart, d.length);
            r.length = r.body.length;
            out.add(r);
            return out;
        }

        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(d, delim, outer.bodyStart);
        while (pos >= 0 && out.size() < MAX_PARTS) {
            int p = pos + delim.length;
            if (p + 1 < d.length && d[p] == '-' && d[p + 1] == '-') break; // closing delimiter
            Headers h = readHeaders(d, skipEol(d, p));
            if (h == null) break;
            CarouselResource r = new CarouselResource();
            r.contentType = nz(h.map.get("content-type"));
            r.location = nz(h.map.get("content-location"));
            int start = h.bodyStart;
            int end;
            int declared = parseInt(h.map.get("content-length"));
            if (declared >= 0 && declared <= d.length - start) {   // start + declared could overflow int
                end = start + declared;
                pos = indexOf(d, delim, end);
            } else {
                int next = indexOf(d, delim, start);
                end = next < 0 ? d.length : trimEol(d, start, next);
                pos = next;
            }
            r.body = Arrays.copyOfRange(d, start, end);
            r.length = r.body.length;
            out.add(r);
        }
        return out;
    }

    private static Headers readHeaders(byte[] d, int from) {
        int p = from;
        Headers h = new Headers();
        while (p < d.length) {
            int eol = indexOf(d, new byte[]{'\n'}, p);
            if (eol < 0) return null;
            int lineEnd = (eol > p && d[eol - 1] == '\r') ? eol - 1 : eol;
            if (lineEnd == p) { // blank line: end of headers
                h.bodyStart = eol + 1;
                return h;
            }
            String line = new String(d, p, lineEnd - p, StandardCharsets.ISO_8859_1);
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > 64) return null; // not a header line
            h.map.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            p = eol + 1;
            if (p - from > 8192) return null;
        }
        return null;
    }

    private static String boundaryOf(String contentType) {
        if (!contentType.toLowerCase(Locale.US).startsWith("multipart/")) return null;
        int i = contentType.toLowerCase(Locale.US).indexOf("boundary=");
        if (i < 0) return null;
        String b = contentType.substring(i + 9).trim();
        if (b.startsWith("\"")) {
            int q = b.indexOf('"', 1);
            if (q <= 0) return null;
            b = b.substring(1, q);
        } else {
            int semi = b.indexOf(';');
            if (semi >= 0) b = b.substring(0, semi).trim();
        }
        // RFC 2046 caps a boundary at 70 characters. A longer one made the plain search below take minutes
        // per module (under the demux lock, so video and the UI stalled with it).
        return b.length() <= 70 ? b : null;
    }

    private static int skipEol(byte[] d, int p) {
        if (p < d.length && d[p] == '\r') p++;
        if (p < d.length && d[p] == '\n') p++;
        return p;
    }

    private static int trimEol(byte[] d, int start, int end) {
        if (end - 1 >= start && d[end - 1] == '\n') end--;
        if (end - 1 >= start && d[end - 1] == '\r') end--;
        return end;
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static int indexOf(byte[] d, byte[] pat, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= d.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) if (d[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }
}
