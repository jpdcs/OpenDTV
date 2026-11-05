package ph.opendtv.bml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.util.Log;

import java.util.Arrays;

/**
 * Native BML sounds. ROM sounds 0..13 (assets/bml/sounds/N.wav) go through a SoundPool and AIFF sounds sent by a
 * broadcaster (audio/x-arib-aiff) through an AudioTrack. Both are played natively because a WebView's
 * AudioContext often stays suspended until a user gesture, which silences BML beeps.
 */
final class BmlSoundPlayer {
    private static final String TAG = "OpenDTV-BML";
    private static final int COUNT = 14;

    private final int[] soundIds = new int[COUNT];
    private final SoundPool pool;
    private AudioTrack aiffTrack;
    private boolean released;

    BmlSoundPlayer(Context context) {
        Context app = context.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        for (int i = 0; i < COUNT; i++) {
            try (AssetFileDescriptor fd = app.getAssets().openFd("bml/sounds/" + i + ".wav")) {
                soundIds[i] = pool.load(fd, 1);
            } catch (Exception e) {
                Log.w(TAG, "ROM sound " + i + " load failed", e);
            }
        }
    }

    /** @return false when the sound is not available (the page then falls back to WebAudio). */
    synchronized boolean play(int id) {
        if (released || id < 0 || id >= COUNT || soundIds[id] == 0) return false;
        pool.play(soundIds[id], 1f, 1f, 1, 0, 1f);
        return true;
    }

    /** Plays a mono 16-bit AIFF / AIFC file. @return false if it cannot be played natively. */
    synchronized boolean playAiff(byte[] d) {
        if (released || d == null || d.length < 12) return false;
        // AudioTrack.Builder is Android 6.0+. On 5.x its NoClassDefFoundError is an Error, which the catch below
        // does not take, so it crashed the app; let the page fall back to WebAudio there instead.
        if (android.os.Build.VERSION.SDK_INT < 23) return false;
        try {
            if (!"FORM".equals(ascii(d, 0, 4))) return false;
            String form = ascii(d, 8, 4);
            if (!"AIFF".equals(form) && !"AIFC".equals(form)) return false;
            int channels = 0, bits = 0;
            long frames = 0;
            double rate = 0;
            byte[] pcm = null;
            int pos = 12;
            while (pos + 8 <= d.length) {
                String id = ascii(d, pos, 4);
                long size = be32(d, pos + 4);
                int body = pos + 8;
                if ("COMM".equals(id) && body + 18 <= d.length) {
                    channels = be16(d, body);
                    frames = be32(d, body + 2);
                    bits = be16(d, body + 6);
                    rate = extended(d, body + 8);
                } else if ("SSND".equals(id) && body + 8 <= d.length) {
                    int start = body + 8 + (int) be32(d, body);
                    int end = (int) Math.min(d.length, body + size);
                    if (start < end) pcm = Arrays.copyOfRange(d, start, end);
                }
                long next = body + size + (size & 1);
                if (next <= pos || next > d.length) break;
                pos = (int) next;
            }
            if (channels != 1 || bits != 16 || rate <= 0 || pcm == null) return false;
            int n = (int) Math.min(frames, pcm.length / 2);
            if (n <= 0) return false;
            short[] s = new short[n];
            for (int i = 0; i < n; i++) s[i] = (short) (((pcm[i * 2] & 0xFF) << 8) | (pcm[i * 2 + 1] & 0xFF));
            int sr = (int) Math.round(rate);
            s = trimEdgeSilence(s, sr);
            stopAiffLocked();
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(sr,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), s.length * 2))
                    .build();
            t.write(s, 0, s.length);
            t.play();
            aiffTrack = t;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "AIFF sound failed", e);
            return false;
        }
    }

    synchronized void stopAiff() { stopAiffLocked(); }

    synchronized void release() {
        released = true;
        stopAiffLocked();
        pool.release();
    }

    private void stopAiffLocked() {
        if (aiffTrack == null) return;
        try { aiffTrack.stop(); } catch (Exception ignored) { }
        try { aiffTrack.release(); } catch (Exception ignored) { }
        aiffTrack = null;
    }

    /** Cuts near-silent lead-in / tail so short UI clicks start at once (keeps 5 ms before and 15 ms after). */
    private static short[] trimEdgeSilence(short[] s, int rate) {
        int peak = 0;
        for (short v : s) peak = Math.max(peak, Math.abs(v));
        if (peak < 8) return s;
        int thr = Math.max(8, peak / 50);
        int start = 0;
        while (start < s.length && Math.abs(s[start]) < thr) start++;
        int end = s.length - 1;
        while (end > start && Math.abs(s[end]) < thr) end--;
        if (start == 0 && end == s.length - 1) return s;
        int from = Math.max(0, start - rate * 5 / 1000);
        int to = Math.min(s.length - 1, end + rate * 15 / 1000);
        return to > from ? Arrays.copyOfRange(s, from, to + 1) : s;
    }

    private static String ascii(byte[] b, int o, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) (b[o + i] & 0xFF));
        return sb.toString();
    }

    private static int be16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }

    private static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    /** 80-bit IEEE extended (AIFF sample rate). */
    private static double extended(byte[] b, int o) {
        int exp = (((b[o] & 0x7F) << 8) | (b[o + 1] & 0xFF)) - 16383;
        long mant = 0;
        for (int i = 2; i < 10; i++) mant = (mant << 8) | (b[o + i] & 0xFF);
        return mant * Math.pow(2, exp - 63);
    }
}
