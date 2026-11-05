package ph.opendtv.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import ph.opendtv.source.SignalStatus;
import ph.opendtv.source.TsSource;
import ph.opendtv.source.UsbTunerSource;
import ph.opendtv.ts.TsDemux;

/**
 * Process-wide owner of the one USB tuner. The Live Channels session and the channel-scan screen both go
 * through here, so there is only ever one {@link UsbTunerSource}, one demux and one "current owner".
 *
 * Threading: all device operations ({@link #ensureTuned}, {@link #ensurePermission}) block and must be called
 * from a background thread. {@link #acquire}, {@link #release} and the getters never block (safe on the UI thread).
 */
public final class TunerHub {
    private static final String TAG = "OpenDTV-TV";
    private static final String ACTION_USB_PERMISSION = "ph.opendtv.tv.USB_PERMISSION";
    /** Keep the dongle powered this long after the last user went away (Live Channels re-opens sessions often). */
    private static final long IDLE_STOP_MS = 30_000;

    /** Whoever currently uses the tuner. Called when somebody else takes it over. */
    public interface Owner {
        void onPreempted();
    }

    /**
     * Asked between tune attempts whether to give up. Our own interface, not java.util.function.BooleanSupplier:
     * that one only exists from Android 7.0 (API 24), so every tune crashed on Android 5.0 to 6.0.
     */
    public interface CancelCheck {
        boolean isCancelled();
    }

    private static TunerHub instance;

    public static synchronized TunerHub get(Context c) {
        if (instance == null) instance = new TunerHub(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final UsbManager usb;
    private final Object devLock = new Object();
    private final Object idleLock = new Object();
    private final java.util.concurrent.atomic.AtomicReference<Owner> owner =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "TunerHubIdle");
            t.setDaemon(true);
            return t;
        }
    });
    private ScheduledFuture<?> idleStop;

    // guarded by devLock (source/demux are volatile so getters can read them without blocking)
    private volatile UsbTunerSource source;
    private String sourceDeviceName;
    private int currentKhz = -1;
    private boolean locked;
    private volatile TsDemux demux = new TsDemux();
    private volatile String lastMessage = "";

    private TunerHub(Context app) {
        this.app = app;
        this.usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
    }

    private final TsSource.Listener listener = new TsSource.Listener() {
        @Override
        public void onData(byte[] buf, int off, int len) {
            demux.feed(buf, off, len);   // read the field each time: a retune swaps in a fresh demux
        }

        @Override
        public void onError(String message) {
            lastMessage = message;
            Log.i(TAG, "tuner: " + message);
        }

        @Override
        public void onEnd() {
        }
    };

    // ------------------------------------------------------------------ device / permission

    public UsbDevice findDevice() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == UsbTunerSource.VID && d.getProductId() == UsbTunerSource.PID) return d;
        }
        return null;
    }

    public boolean isTunerPresent() {
        return findDevice() != null;
    }

    /** Last status line from the tuner driver (init result, lock, errors). */
    public String lastMessage() {
        return lastMessage;
    }

    /**
     * Makes sure we may open the dongle; shows the system "Allow access to USB device?" dialog if needed and
     * waits for the answer. Blocks, so call it off the UI thread.
     */
    public boolean ensurePermission(long timeoutMs) {
        UsbDevice d = findDevice();
        if (d == null) {
            lastMessage = "Tuner 048d:275a not found (plug it in; OTG adapter on a phone).";
            return false;
        }
        if (usb.hasPermission(d)) return true;

        final CountDownLatch done = new CountDownLatch(1);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                done.countDown();
            }
        };
        ContextCompat.registerReceiver(app, r, new IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            // Mutable (the system adds the result extras) but explicit through setPackage: required on Android 14.
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName());
            usb.requestPermission(d, PendingIntent.getBroadcast(app, 0, i, flags));
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            try {
                app.unregisterReceiver(r);
            } catch (RuntimeException ignored) {
            }
        }
        boolean ok = usb.hasPermission(d);
        if (!ok) lastMessage = "USB permission for the tuner was not granted.";
        return ok;
    }

    // ------------------------------------------------------------------ ownership (never blocks)

    public void acquire(Owner o) {
        synchronized (idleLock) {
            if (idleStop != null) {
                idleStop.cancel(false);
                idleStop = null;
            }
        }
        Owner old = owner.getAndSet(o);
        if (old != null && old != o) old.onPreempted();
    }

    public void release(Owner o) {
        if (owner.compareAndSet(o, null)) {
            demux.setPacketSink(null);
            synchronized (idleLock) {
                if (idleStop != null) idleStop.cancel(false);
                idleStop = timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        stopIfIdle();
                    }
                }, IDLE_STOP_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public boolean isOwner(Owner o) {
        return owner.get() == o;
    }

    /** Detaches the packet sink, but only if {@code o} is still the owner (a newer owner has its own). */
    public void detachSink(Owner o) {
        if (owner.get() == o) demux.setPacketSink(null);
    }

    private void stopIfIdle() {
        synchronized (devLock) {
            if (owner.get() != null) return;
            Log.i(TAG, "tuner idle, powering down");
            teardownLocked();
        }
    }

    // ------------------------------------------------------------------ tuning (blocks)

    public TsDemux demux() {
        return demux;
    }

    public SignalStatus signal() {
        UsbTunerSource s = source;
        return s == null ? null : s.getSignalStatus();
    }

    /**
     * Brings the dongle up if needed (firmware, init) and tunes to {@code khz}. Returns true when the demodulator
     * is locked. Staying on the same multiplex is free (no retune, demux kept), so zapping between services of one
     * multiplex is fast. A fresh demux is installed after every real retune.
     */
    public boolean ensureTuned(int khz, CancelCheck cancelled) {
        synchronized (devLock) {
            if (!openLocked(khz)) return false;
            if (locked && currentKhz == khz && source.isReady()) return true;
            return tuneLocked(khz, cancelled);
        }
    }

    private boolean openLocked(int khz) {
        UsbDevice d = findDevice();
        if (d == null) {
            teardownLocked();
            lastMessage = "Tuner not connected.";
            return false;
        }
        UsbTunerSource s = source;
        if (s != null && (!s.isReady() || !d.getDeviceName().equals(sourceDeviceName))) {
            Log.i(TAG, "tuner restarted (dead source or re-plugged)");
            teardownLocked();
        }
        if (source != null) return true;
        if (!usb.hasPermission(d)) {
            lastMessage = "No USB permission for the tuner.";
            return false;
        }
        s = new UsbTunerSource(usb, d);
        s.initialFrequencyKhz = khz;       // start() tunes once right after init
        demux = new TsDemux();
        s.start(listener);                 // blocks: firmware download, init, first tune
        if (!s.isReady()) {
            s.stop();
            return false;
        }
        source = s;
        sourceDeviceName = d.getDeviceName();
        SignalStatus st = s.getSignalStatus();
        currentKhz = khz;
        locked = st != null && st.locked;
        if (locked) demux = new TsDemux(); // drop whatever arrived during init
        return true;
    }

    private boolean tuneLocked(int khz, CancelCheck cancelled) {
        UsbTunerSource s = source;
        boolean ok = s.tune(khz);
        if (!ok && !cancelled.isCancelled()) {
            sleep(300);   // the first attempt right after a frequency change sometimes misses a weak signal
            ok = s.tune(khz);
        }
        currentKhz = khz;
        locked = ok;
        demux = new TsDemux();
        Log.i(TAG, "tune " + khz + " kHz -> " + (ok ? "locked" : "no lock"));
        return ok;
    }

    private void teardownLocked() {
        UsbTunerSource s = source;
        source = null;
        if (s != null) s.stop();
        sourceDeviceName = null;
        currentKhz = -1;
        locked = false;
    }

    /** Powers the dongle down right now (e.g. app exit). */
    public void shutdown() {
        synchronized (devLock) {
            teardownLocked();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
