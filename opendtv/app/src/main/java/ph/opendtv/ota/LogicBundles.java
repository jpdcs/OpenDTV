package ph.opendtv.ota;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

import dalvik.system.DexClassLoader;

/**
 * Over-the-air updates for the app's logic, the way Expo updates its JavaScript: no new APK.
 *
 * The APK is the shell: screens, layouts, images, permissions, libraries (Media3, FFmpeg) and the USB driver. The
 * logic that changes often lives in a bundle: a .jar holding compiled Java (classes.dex) that implements
 * {@link AppLogic}, built by tools/ota/build_bundle.sh. One bundle ships inside the APK (assets/ota). Newer ones are
 * published as bundle.json + bundle-N.jar; the app downloads them, checks the signature against {@link #PUBLIC_KEY}
 * and uses the new bundle from the next start. If the app dies while starting a new bundle, it goes back to the
 * previous one.
 *
 * A bundle says which shell it needs (minShell). Anything only the shell can provide (a new library, permission,
 * layout or image, or a change to AppLogic) needs a new APK, like adding a native package in Expo.
 */
public final class LogicBundles {
    /** Version of {@link AppLogic} this APK provides. Bump it whenever AppLogic changes. */
    public static final int SHELL_API = 1;
    /** Where the latest bundle.json is published (a fixed "ota" release on GitHub). */
    public static final String MANIFEST_URL = "https://github.com/jpdcs/OpenDTV/releases/download/ota/bundle.json";
    /** Public half of the bundle signing key (ECDSA P-256, X.509 DER, base64). The private half signs bundles. */
    private static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3cBFPG9+Q2fGoA8uJ0/aBfrVNGKhjYMaoPx7yfRCkayi6bqKSdyaUDw1GQR8fz6Q/lt83gVdbtt1kk5B44MTsg==";

    private static final String TAG = "OpenDTV-OTA";
    private static final String ENTRY_CLASS = "ph.opendtv.bundle.Logic";
    private static final String PREFS = "opendtv_ota";
    private static final String K_CURRENT = "current";     // downloaded bundle in use (0 = the one in the APK)
    private static final String K_PREVIOUS = "previous";   // what to go back to if "current" fails
    private static final String K_PENDING = "pending";     // downloaded and verified, used from the next start
    private static final String K_TRYING = "trying";       // started but not yet confirmed healthy
    private static final String K_BAD = "bad";             // failed to start; never used again
    private static final int MAX_BUNDLE_BYTES = 8 * 1024 * 1024;

    private static LogicBundles instance;

    public static synchronized LogicBundles get(Context c) {
        if (instance == null) instance = new LogicBundles(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private AppLogic logic;
    private int loadedVersion;
    private boolean loadedDownloaded;
    private volatile boolean checking;

    private LogicBundles(Context app) {
        this.app = app;
    }

    // ------------------------------------------------------------------ loading

    /** The logic in use (loaded once per process). Null only if even the bundle inside the APK can't load. */
    public synchronized AppLogic logic() {
        if (logic == null) load();
        return logic;
    }

    /** E.g. "Bundle 2 (downloaded update)". */
    public synchronized String describe() {
        logic();
        if (logic == null) return "No bundle loaded (built-in rules)";
        return "Bundle " + loadedVersion + (loadedDownloaded ? " (downloaded update)" : " (built into the app)");
    }

    /** Call once the app has been running fine for a while: the bundle that started it is kept. */
    public void markHealthy() {
        prefs().edit().remove(K_TRYING).apply();
    }

    private void load() {
        SharedPreferences sp = prefs();
        int embedded = embeddedVersion();
        int trying = sp.getInt(K_TRYING, 0);
        if (trying > 0) {
            // The last start with this bundle never reached markHealthy(): treat it as broken and go back.
            Log.w(TAG, "bundle " + trying + " did not start cleanly, going back to " + sp.getInt(K_PREVIOUS, 0));
            sp.edit().putInt(K_BAD, trying).putInt(K_CURRENT, sp.getInt(K_PREVIOUS, 0)).remove(K_TRYING).commit();
        }
        int pending = sp.getInt(K_PENDING, 0);
        if (pending > 0 && pending != sp.getInt(K_BAD, 0)) {
            sp.edit().putInt(K_PREVIOUS, sp.getInt(K_CURRENT, 0)).putInt(K_CURRENT, pending)
                    .putInt(K_TRYING, pending).remove(K_PENDING).commit();
        } else if (pending > 0) {
            sp.edit().remove(K_PENDING).commit();
        }

        int current = sp.getInt(K_CURRENT, 0);
        if (current > embedded) {
            File f = downloadedFile(current);
            try {
                logic = open(f);
                loadedVersion = current;
                loadedDownloaded = true;
                Log.i(TAG, "logic: downloaded bundle " + current);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "downloaded bundle " + current + " failed to load: " + t);
                sp.edit().putInt(K_BAD, current).putInt(K_CURRENT, 0).remove(K_TRYING).commit();
            }
        }
        try {
            logic = open(embeddedFile(embedded));
            loadedVersion = embedded;
            loadedDownloaded = false;
            Log.i(TAG, "logic: bundle " + embedded + " from the APK");
        } catch (Throwable t) {
            Log.e(TAG, "the bundle inside the APK failed to load", t);
            logic = null;
        }
    }

    private AppLogic open(File jar) throws Exception {
        if (!jar.isFile()) throw new IOException("missing " + jar.getName());
        jar.setReadOnly();   // Android 14+ refuses to load code from a writable file
        DexClassLoader cl = new DexClassLoader(jar.getPath(), app.getCodeCacheDir().getPath(), null,
                app.getClassLoader());
        return (AppLogic) cl.loadClass(ENTRY_CLASS).getDeclaredConstructor().newInstance();
    }

    private int embeddedVersion() {
        try (InputStream in = app.getAssets().open("ota/bundle.json")) {
            return new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8)).getInt("version");
        } catch (Exception e) {
            return 0;
        }
    }

    /** Copies the APK's bundle to files/ota (code must be loaded from a file), once per version. */
    private File embeddedFile(int version) throws IOException {
        File f = new File(dir(), "embedded-" + version + ".jar");
        if (!f.isFile()) {
            File tmp = new File(dir(), "embedded.tmp");
            try (InputStream in = app.getAssets().open("ota/bundle.jar"); OutputStream out = new FileOutputStream(tmp)) {
                out.write(readAll(in, MAX_BUNDLE_BYTES));
            }
            if (!tmp.renameTo(f)) throw new IOException("can't store the bundle");
        }
        return f;
    }

    private File downloadedFile(int version) {
        return new File(dir(), "bundle-" + version + ".jar");
    }

    private File dir() {
        File d = new File(app.getFilesDir(), "ota");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ checking and downloading

    /**
     * Looks for a newer bundle and downloads it in the background. Quiet (manual = false): a short toast when one was
     * downloaded. Manual (Settings): always answers, and offers to restart right away.
     */
    public void checkForUpdate(final Activity act, final boolean manual) {
        if (checking) return;
        checking = true;
        if (manual) Toast.makeText(act, "Checking for app updates ...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            int got = 0;
            try {
                JSONObject m = new JSONObject(new String(fetch(manifestUrl(), 256 * 1024), StandardCharsets.UTF_8));
                int version = m.getInt("version");
                int minShell = m.optInt("minShell", 1);
                SharedPreferences sp = prefs();
                int have = Math.max(Math.max(logicVersion(), sp.getInt(K_PENDING, 0)), embeddedVersion());
                if (minShell > SHELL_API) {
                    result = "Bundle " + version + " needs a newer OpenDTV app (APK). Install the latest release.";
                } else if (version <= have || version == sp.getInt(K_BAD, 0)) {
                    result = "You're up to date (bundle " + have + ").";
                } else {
                    byte[] jar = fetch(m.getString("url"), MAX_BUNDLE_BYTES);
                    String sha = sha256(jar);
                    if (!sha.equalsIgnoreCase(m.getString("sha256"))) throw new IOException("bundle file is damaged");
                    String signed = "opendtv-bundle:" + version + ":" + minShell + ":" + sha;
                    if (!verify(signed, m.getString("signature"))) throw new SecurityException("bad signature");
                    File f = downloadedFile(version);
                    if (f.exists()) {
                        f.setWritable(true);
                        f.delete();
                    }
                    try (OutputStream out = new FileOutputStream(f)) {
                        out.write(jar);
                    }
                    f.setReadOnly();
                    sp.edit().putInt(K_PENDING, version).commit();
                    got = version;
                    String notes = m.optString("notes", "");
                    result = "Bundle " + version + " is ready" + (notes.isEmpty() ? "." : ": " + notes);
                }
            } catch (SecurityException e) {
                result = "The update was not signed by OpenDTV, so it was ignored.";
                Log.w(TAG, "rejected bundle: " + e);
            } catch (Exception e) {
                result = "Couldn't check for app updates: " + e.getMessage();
                Log.i(TAG, "check failed: " + e);
            }
            final String text = result;
            final int ready = got;
            act.runOnUiThread(() -> {
                checking = false;
                if (act.isFinishing()) return;
                if (ready > 0 && manual) {
                    new AlertDialog.Builder(act)
                            .setTitle("App update downloaded")
                            .setMessage(text + "\n\nIt's used the next time OpenDTV starts. No new APK needed.")
                            .setPositiveButton("Restart now", (d, w) -> restart(act))
                            .setNegativeButton("Later", null)
                            .show();
                } else if (ready > 0) {
                    Toast.makeText(act, "App update downloaded. It's used the next time OpenDTV starts.",
                            Toast.LENGTH_LONG).show();
                } else if (manual) {
                    new AlertDialog.Builder(act).setTitle("App updates").setMessage(text)
                            .setPositiveButton("OK", null).show();
                }
            });
        }, "LogicBundleCheck").start();
    }

    private synchronized int logicVersion() {
        return logic == null ? 0 : loadedVersion;
    }

    /** Starts OpenDTV again in a fresh process, which loads the newest bundle. */
    public static void restart(Activity act) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(act.getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        act.startActivity(i);
        Runtime.getRuntime().exit(0);
    }

    private String manifestUrl() {
        if ((app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            File f = new File(app.getFilesDir(), "ota_url.txt");   // debug builds: test against another server
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    String url = new String(readAll(in, 4096), StandardCharsets.UTF_8).trim();
                    if (!url.isEmpty()) return url;
                } catch (IOException ignored) {
                }
            }
        }
        return MANIFEST_URL;
    }

    private static boolean verify(String signed, String signatureB64) {
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(signed.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.decode(signatureB64, Base64.DEFAULT));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "OpenDTV (logic bundle updater)");
        if (c instanceof HttpURLConnection) {
            int code = ((HttpURLConnection) c).getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
        }
        try (InputStream in = c.getInputStream()) {
            return readAll(in, maxBytes);
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("file too large");
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
