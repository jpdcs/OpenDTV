package ph.opendtv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ph.opendtv.ts.EpgEvent;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.TsDemux;

/**
 * Channel setup for Live Channels: scans the UHF band with the USB tuner, then writes the found services
 * into the TV provider. Launched by the TV app ("Set up" / "Rescan") or from the launcher.
 * Plain focusable buttons only, so it works with a remote's D-pad.
 */
public final class SetupActivity extends AppCompatActivity implements TunerHub.Owner {
    private static final String TAG = "OpenDTV-TV";

    /** Quick-scan list of the vendor app (known active Philippine channels), kHz. */
    private static final int[] QUICK_SCAN_KHZ = {479143, 497143, 503143, 509143, 515143, 545143, 551143, 563143,
            569143, 581143, 587143, 593143, 599143, 611143, 617143, 623143, 629143, 641143, 647143, 659143, 683143,
            689143, 695143};

    private static final class Found {
        int khz, tsId, onid;
        long offsetMs;
        final List<ServiceInfo> tv = new ArrayList<>();
        final Map<Integer, List<EpgEvent>> events = new HashMap<>();
    }

    private TunerHub hub;
    private String inputId;
    private TextView statusTv, foundTv;
    private ProgressBar bar;
    private Button btnQuick, btnFull, btnCancel;
    private volatile boolean scanning;
    private volatile boolean scanCancel;
    /** Set when the scan cannot run at all (no tuner, no permission, init failure); shown instead of "no channels". */
    private volatile String scanError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hub = TunerHub.get(this);
        inputId = getIntent().getStringExtra(TvInputInfo.EXTRA_INPUT_ID);
        if (inputId == null) inputId = TvChannels.inputId(this);
        buildUi();
        setResult(Activity.RESULT_CANCELED);
        if (!hub.isTunerPresent()) setStatus("Tuner not found. Plug in the dongle (048d:275a), then choose a scan.");
        else setStatus("Choose Quick scan (known channels) or Full scan (all UHF channels).");
    }

    private void buildUi() {
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#14181F"));

        TextView title = text("OpenDTV  -  ISDB-T channel scan", 28, "#FFFFFF");
        statusTv = text("", 18, "#D0D6E0");
        statusTv.setPadding(0, dp(16), 0, dp(16));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1);
        foundTv = text("", 15, "#9FB3C8");
        foundTv.setPadding(0, dp(16), 0, dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnQuick = button("Quick scan");
        btnFull = button("Full scan");
        btnCancel = button("Close");
        row.addView(btnQuick);
        row.addView(btnFull);
        row.addView(btnCancel);

        root.addView(title);
        root.addView(statusTv);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
        root.addView(foundTv);
        root.addView(row);
        setContentView(root);

        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(false);
            }
        });
        btnFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(true);
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) {
                    scanCancel = true;
                    setStatus("Stopping after this channel ...");
                } else {
                    finish();
                }
            }
        });
        btnQuick.requestFocus();
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setFocusable(true);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
            }
        });
    }

    @Override
    public void onPreempted() {
        scanCancel = true;   // a viewing session took the tuner
    }

    @Override
    public void onBackPressed() {
        if (scanning) {
            scanCancel = true;
            setStatus("Stopping after this channel ...");
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        scanCancel = true;
        hub.release(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ scan

    private static String channelLabel(int khz) {
        if (khz >= 473143 && (khz - 473143) % 6000 == 0) {
            return "UHF " + (14 + (khz - 473143) / 6000) + "  " + String.format(Locale.US, "%.3f MHz", khz / 1000.0);
        }
        return String.format(Locale.US, "%.3f MHz", khz / 1000.0);
    }

    private void startScan(boolean full) {
        if (scanning) return;
        final List<Integer> freqs = new ArrayList<>();
        if (full) {
            for (int f = 473143; f <= 695143; f += 6000) freqs.add(f);
        } else {
            for (int f : QUICK_SCAN_KHZ) freqs.add(f);
        }
        scanning = true;
        scanCancel = false;
        scanError = null;
        btnQuick.setEnabled(false);
        btnFull.setEnabled(false);
        btnCancel.setText("Stop");
        btnCancel.requestFocus();
        bar.setMax(freqs.size());
        bar.setProgress(0);
        foundTv.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Found> found = new ArrayList<>();
                int channels = 0;
                try {
                    channels = scan(freqs, found);
                } catch (RuntimeException e) {
                    Log.e(TAG, "scan failed", e);
                    scanError = "Scan failed: " + e;
                } finally {
                    hub.release(SetupActivity.this);
                }
                finishScan(channels, found.size());
            }
        }, "OpenDtvScan").start();
    }

    /** @return number of channels written */
    private int scan(List<Integer> freqs, List<Found> found) {
        setStatus("Waiting for USB permission ...");
        if (!hub.ensurePermission(120_000)) {
            scanError = hub.lastMessage();
            return 0;
        }
        hub.acquire(this);
        for (int i = 0; i < freqs.size() && !scanCancel; i++) {
            final int khz = freqs.get(i);
            final int idx = i;
            String head = String.format(Locale.US, "Channel %d of %d:  %s\n", i + 1, freqs.size(), channelLabel(khz));
            setStatus(head + "tuning ... (the first one also loads the tuner firmware)");
            boolean locked = hub.ensureTuned(khz, new TunerHub.CancelCheck() {
                @Override
                public boolean isCancelled() {
                    return scanCancel;
                }
            });
            if (!locked) {
                if (!hub.isTunerPresent() || hub.lastMessage().startsWith("Tuner init failed")) {
                    scanError = "Tuner problem: " + hub.lastMessage();
                    break;
                }
                progress(idx + 1);
                continue;
            }
            setStatus(head + "LOCKED, reading channel list ...");
            TsDemux d = hub.demux();
            long start = System.currentTimeMillis();
            long firstSeen = 0;
            while (System.currentTimeMillis() - start < 15000 && !scanCancel) {
                int n = countServices(d);
                long now = System.currentTimeMillis();
                if (n > 0 && firstSeen == 0) firstSeen = now;
                // done when every TV service has its name, or 6 s after the first service appeared
                if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1500) || now - firstSeen > 12000)) break;
                setStatus(head + String.format(Locale.US, "LOCKED, reading channel list ... %d service(s)", n));
                sleep(250);
            }
            Found f = new Found();
            f.khz = khz;
            f.tsId = d.getTsId();
            f.onid = d.getNetworkId();
            f.offsetMs = TvChannels.clockOffsetMs(d);
            for (ServiceInfo s : d.getServices()) {
                if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                f.tv.add(s);
                f.events.put(s.serviceId, d.getEvents(s.serviceId));
            }
            Log.i(TAG, "scan " + khz + " kHz: " + f.tv.size() + " TV service(s)");
            progress(idx + 1);
            if (!f.tv.isEmpty()) {
                found.add(f);
                final String list = foundText(found);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        foundTv.setText(list);
                    }
                });
            }
        }
        if (scanCancel && found.isEmpty()) return 0;
        return found.isEmpty() ? 0 : writeChannels(found);
    }

    private int writeChannels(List<Found> found) {
        setStatus("Saving channels ...");
        List<TvChannels.Spec> specs = new ArrayList<>();
        Map<String, Found> byKey = new HashMap<>();
        for (Found f : found) {
            int idx = 0;
            for (ServiceInfo s : f.tv) {
                idx++;
                TvChannels.Spec sp = new TvChannels.Spec();
                sp.khz = f.khz;
                sp.sid = s.serviceId;
                sp.tsId = f.tsId;
                sp.onid = f.onid;
                sp.name = s.name.isEmpty() ? "Service " + s.serviceId : s.name;
                if (s.virtualNumber != null && !s.virtualNumber.equals("--")) {
                    sp.number = s.virtualNumber;
                } else if (f.khz >= 473143 && (f.khz - 473143) % 6000 == 0) {
                    // no remote_control_key_id: 100 + UHF channel, so it never collides with real numbers
                    sp.number = (100 + 14 + (f.khz - 473143) / 6000) + "." + idx;
                } else {
                    sp.number = (f.khz / 1000) + "." + idx;
                }
                specs.add(sp);
                byKey.put(sp.key(), f);
            }
        }
        Map<String, Long> ids = TvChannels.replaceChannels(this, inputId, specs);
        for (TvChannels.Spec sp : specs) {
            Long id = ids.get(sp.key());
            Found f = byKey.get(sp.key());
            if (id == null || f == null) continue;
            List<EpgEvent> ev = f.events.get(sp.sid);
            if (ev != null && !ev.isEmpty()) TvChannels.syncPrograms(this, id, ev, f.offsetMs);
        }
        return ids.size();
    }

    private void progress(final int done) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bar.setProgress(done);
            }
        });
    }

    private void finishScan(final int channels, final int muxes) {
        final boolean cancelled = scanCancel;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                scanning = false;
                btnQuick.setEnabled(true);
                btnFull.setEnabled(true);
                if (channels > 0) {
                    setResult(Activity.RESULT_OK, new Intent());
                    statusTv.setText((cancelled ? "Scan stopped: " : "Scan done: ") + channels + " channel(s) in "
                            + muxes + " multiplex(es) added to Live Channels.");
                    btnCancel.setText("Finish");
                } else {
                    btnCancel.setText("Close");
                    String err = scanError;
                    statusTv.setText(err != null ? err : cancelled ? "Scan stopped, nothing saved."
                            : "No channels found. Check the antenna and try a Full scan.");
                }
                btnCancel.requestFocus();
            }
        });
    }

    private static String foundText(List<Found> found) {
        StringBuilder sb = new StringBuilder();
        for (Found f : found) {
            sb.append(channelLabel(f.khz)).append(":  ");
            int n = 0;
            for (ServiceInfo s : f.tv) {
                if (n++ > 0) sb.append(", ");
                sb.append(s.name.isEmpty() ? "sid " + s.serviceId : s.name);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** True when every service that has a PMT and video also has a name (SDT received). */
    private static boolean allNamed(TsDemux d) {
        boolean any = false;
        for (ServiceInfo s : d.getServices()) {
            if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
            any = true;
            if (s.name.isEmpty()) return false;
        }
        return any;
    }

    private static int countServices(TsDemux d) {
        int n = 0;
        for (ServiceInfo s : d.getServices()) if (s.pmtSeen && !s.streams.isEmpty()) n++;
        return n;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
