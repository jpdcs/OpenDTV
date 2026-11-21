package ph.opendtv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ph.opendtv.player.TsPipeDataSource;
import ph.opendtv.source.FileTsSource;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.source.TsSource;
import ph.opendtv.source.UsbTunerSource;
import ph.opendtv.ts.AnalyzerSnapshot;
import ph.opendtv.ts.CarouselInfo;
import ph.opendtv.ts.CarouselModule;
import ph.opendtv.ts.CarouselResource;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.bml.BmlController;
import ph.opendtv.bml.RemotePanel;
import ph.opendtv.ui.EwbsBanner;
import ph.opendtv.ts.ServiceFilter;
import ph.opendtv.ui.CaptionOverlay;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;
import ph.opendtv.ts.TsDemux;
import ph.opendtv.ts.TsPipe;
import ph.opendtv.ts.TsUtil;

/**
 * Diagnostic analyzer activity (formerly MainActivity).
 * Top: video (left) + Virtual Number / Channel name / Program ID (right).
 * Bottom: Video ID, Audio ID, Codec, then Analyzer / EPG / Datacast tabs.
 *
 * Data flow:  TsSource thread -> TsDemux (analysis) -> ServiceFilter -> TsPipe -> ExoPlayer.
 */
@OptIn(markerClass = UnstableApi.class)
public class AnalyzerActivity extends AppCompatActivity {

    private static final String TAG = "OpenDTV-Analyzer";
    private static final String ACTION_USB_PERMISSION = "ph.opendtv.USB_PERMISSION";
    private static final int TAB_ANALYZER = 0, TAB_EPG = 1, TAB_DATACAST = 2, TAB_GRAPH = 3, TAB_LOGS = 4, TAB_REMOTE = 5;
    private static final long TICK_MS = 1000;
    private final StringBuilder logBuffer = new StringBuilder();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TsPipe pipe = new TsPipe(8 * 1024 * 1024);

    private volatile TsDemux demux = new TsDemux();
    private TsSource source;
    private ExoPlayer player;
    private UsbManager usbManager;

    private PlayerView playerView;
    private CaptionOverlay captionOverlay;

    // ---- data broadcast (BML) viewer + on-screen remote ----
    private BmlController bml;
    private final EwbsBanner ewbsBanner = new EwbsBanner(this);
    private RemotePanel remotePanel;
    private android.widget.FrameLayout playerFrame;
    private int bmlServiceId = -1;
    private float[] videoFrac = null;      // null = video fills the frame
    private TextView tvVirtual, tvChannel, tvProgramId, tvSignal, tvNetwork, tvStrengthVal, tvQualityVal;
    private TextView tvVideoId, tvAudioId, tvCodec, tvContent, tvStatus;
    private LinearLayout layoutGraphContainer;
    private TextView tvGraphBitrate, tvGraphPackets, tvGraphDemux, tvGraphLoss, tvGraphSignal;
    private ProgressBar barGraphBitrate, barGraphErrors;
    private Spinner spChannels, spMux;
    private ProgressBar barStrength, barQuality;
    private Button btnRecordWhole;
    private FileOutputStream wholeStreamOutput;
    private volatile boolean isRecordingWhole;

    /** A multiplex (one RF channel) found by a scan or tuned by hand. */
    private static final class Mux {
        final int khz;
        final String label;

        Mux(int khz, String label) {
            this.khz = khz;
            this.label = label;
        }
    }

    private final List<Mux> muxes = new ArrayList<>();
    private int currentMuxIndex = -1;
    private int currentTunedKhz = 0;
    private volatile boolean scanning;
    private volatile boolean scanCancel;
    private volatile String lastScanNote = "";
    private static final String PREFS = "opendtv";
    /** Quick-scan list of the vendor app (known active Philippine channels). */
    private static final int[] QUICK_SCAN_KHZ = {479143, 497143, 503143, 509143, 515143, 545143, 551143, 563143,
            569143, 581143, 587143, 593143, 599143, 611143, 617143, 623143, 629143, 641143, 647143, 659143, 683143,
            689143, 695143};

    private int currentTab = TAB_ANALYZER;
    private int selectedServiceId = -1;
    private String serviceListKey = "";
    private final List<Integer> spinnerSids = new ArrayList<>();
    private AnalyzerSnapshot lastSnapshot = new AnalyzerSnapshot();

    private final ActivityResultLauncher<String[]> openFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), new androidx.activity.result.ActivityResultCallback<Uri>() {
                @Override
                public void onActivityResult(Uri uri) {
                    if (uri != null) startFile(uri);
                }
            });

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && dev != null) {
                startSource(new UsbTunerSource(usbManager, dev), false);
            } else {
                setStatus("USB permission denied.");
            }
        }
    };

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbTunerSource.isSupported(dev)
                    && source instanceof UsbTunerSource) {
                scanCancel = true;
                stopSource();
                player.stop();
                setStatus("Tuner unplugged.");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analyzer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // never sleep while watching

        playerView = findViewById(R.id.player_view);
        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) frame.setAspectRatio(16f / 9f);

        tvVirtual = findViewById(R.id.tv_virtual);
        tvChannel = findViewById(R.id.tv_channel);
        tvProgramId = findViewById(R.id.tv_program_id);
        tvNetwork = findViewById(R.id.tv_network);
        tvStrengthVal = findViewById(R.id.tv_strength_val);
        tvQualityVal = findViewById(R.id.tv_quality_val);
        tvSignal = findViewById(R.id.tv_signal);
        tvVideoId = findViewById(R.id.tv_video_id);
        tvAudioId = findViewById(R.id.tv_audio_id);
        tvCodec = findViewById(R.id.tv_codec);
        tvContent = findViewById(R.id.tv_content);
        tvStatus = findViewById(R.id.tv_status);
        layoutGraphContainer = findViewById(R.id.layout_graph_container);
        tvGraphBitrate = findViewById(R.id.tv_graph_bitrate);
        barGraphBitrate = findViewById(R.id.bar_graph_bitrate);
        tvGraphPackets = findViewById(R.id.tv_graph_packets);
        tvGraphDemux = findViewById(R.id.tv_graph_demux);
        tvGraphLoss = findViewById(R.id.tv_graph_loss);
        barGraphErrors = findViewById(R.id.bar_graph_errors);
        tvGraphSignal = findViewById(R.id.tv_graph_signal);
        spChannels = findViewById(R.id.sp_channels);
        spMux = findViewById(R.id.sp_mux);
        barStrength = findViewById(R.id.bar_strength);
        barQuality = findViewById(R.id.bar_quality);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Live TV: start after ~1.5 s of data, keep at most 15 s, and refill 2.5 s after an underrun.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 15000, 1500, 2500)
                .build();
        player = new ExoPlayer.Builder(this, new NextRenderersFactory(this)
                .setExtensionRendererMode(getSharedPreferences("opendtv_prefs", MODE_PRIVATE).getBoolean("hardware_decoder", false)
                        ? androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        : androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
                .setLoadControl(loadControl).build();
        playerView.setPlayer(player);
        captionOverlay = new CaptionOverlay(playerView);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                setStatus("Player: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        // Tuner unplugged: stop cleanly. (Plugging in is handled through onNewIntent / the launch intent.)
        IntentFilter detach = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDetachReceiver, detach);

        ((Button) findViewById(R.id.btn_file)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFile.launch(new String[]{"*/*"});
            }
        });
        ((Button) findViewById(R.id.btn_usb)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectUsb();
            }
        });
        ((Button) findViewById(R.id.btn_probe)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AnalyzerActivity.this, UsbProbeActivity.class));
            }
        });
        btnRecordWhole = findViewById(R.id.btn_record_whole);
        btnRecordWhole.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWholeStreamRecording();
            }
        });
        ((Button) findViewById(R.id.btn_scan)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScanDialog();
            }
        });
        ((Button) findViewById(R.id.btn_manual)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualDialog();
            }
        });
        spMux.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= muxes.size() || position == currentMuxIndex) return;
                currentMuxIndex = position;
                tuneTo(muxes.get(position).khz);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        loadMuxes();
        bindTab(R.id.tab_analyzer, TAB_ANALYZER);
        bindTab(R.id.tab_epg, TAB_EPG);
        bindTab(R.id.tab_datacast, TAB_DATACAST);
        bindTab(R.id.tab_graph, TAB_GRAPH);
        bindTab(R.id.tab_logs, TAB_LOGS);
        setupRemote();

        spChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= spinnerSids.size()) return;
                int sid = spinnerSids.get(position);
                if (sid == selectedServiceId) return; // programmatic selection
                ServiceInfo svc = findService(demux.getServices(), sid);
                if (svc != null) selectService(svc);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ui.post(tick);

        // The tuner is the main source: start USB initialisation as soon as the app opens.
        connectUsb();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) connectUsb();
    }

    /** Close the dongle BEFORE the previous screen resumes, so the main player can re-open it cleanly. */
    @Override
    public void finish() {
        scanCancel = true;
        stopWholeStreamRecording();
        stopSource();
        super.finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bml != null) bml.close();      // background: no data-broadcast engine running unseen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bml != null) bml.close();
        scanCancel = true;
        ui.removeCallbacks(tick);
        stopWholeStreamRecording();
        stopSource();
        unregisterReceiver(usbPermissionReceiver);
        unregisterReceiver(usbDetachReceiver);
        dismissScanDialog();
        player.release();
        pipe.close();
    }

    private synchronized void toggleWholeStreamRecording() {
        if (!isRecordingWhole) {
            if (!checkAndRequestStoragePermission(this)) return;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "OpenDTV/wholets");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                int freq = currentTunedKhz > 0 ? currentTunedKhz : (currentMuxIndex >= 0 && currentMuxIndex < muxes.size() ? muxes.get(currentMuxIndex).khz : 497143);
                String netName = demux.getNetworkName();
                if (netName == null || netName.trim().isEmpty()) {
                    netName = "Network";
                }
                netName = netName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                String fileName = freq + "-" + netName + "-" + timeStamp + ".ts";
                File file = new File(dir, fileName);
                wholeStreamOutput = new FileOutputStream(file);
                isRecordingWhole = true;
                btnRecordWhole.setText("Stop Rec");
                Toast.makeText(this, "Started recording whole stream: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                setStatus("Recording whole stream to " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start whole stream recording", e);
                Toast.makeText(this, "Failed to start recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            stopWholeStreamRecording();
            Toast.makeText(this, "Stopped recording whole stream. Saved in OpenDTV/wholets", Toast.LENGTH_SHORT).show();
            setStatus("Whole stream recording saved.");
        }
    }

    public static boolean checkAndRequestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                    Toast.makeText(activity, "Please grant All Files Access to record to OpenDTV folder", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivity(intent);
                    Toast.makeText(activity, "Please grant All Files Access to record to OpenDTV folder", Toast.LENGTH_LONG).show();
                }
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, 1001);
                Toast.makeText(activity, "Please grant Storage permission to record", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private synchronized void stopWholeStreamRecording() {
        isRecordingWhole = false;
        if (wholeStreamOutput != null) {
            try {
                wholeStreamOutput.flush();
                wholeStreamOutput.close();
            } catch (Exception ignored) {}
            wholeStreamOutput = null;
        }
        if (btnRecordWhole != null) {
            btnRecordWhole.setText("Rec Whole");
        }
    }

    // ------------------------------------------------------------------ data broadcast + remote

    private void setupRemote() {
        playerFrame = (android.widget.FrameLayout) findViewById(R.id.player_frame);
        bml = new BmlController(this, playerFrame, new BmlController.Host() {
            @Override
            public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
                videoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                        ? null : new float[] {l, t, r, b};
                applyVideoRect();
            }

            @Override
            public void onStatus(String text) { setStatus(text); }

            @Override
            public void onClosed() {
                videoFrac = null;
                applyVideoRect();
            }
        });
        playerFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (videoFrac != null && (r - l != or - ol || b - t != ob - ot)) applyVideoRect();
            }
        });

        Button logs = (Button) findViewById(R.id.tab_logs);
        android.view.ViewGroup tabRow = (android.view.ViewGroup) logs.getParent();
        Button tab = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        tab.setLayoutParams(logs.getLayoutParams());
        tab.setText("Remote");
        tab.setTextSize(10);
        tab.setPadding(logs.getPaddingLeft(), logs.getPaddingTop(), logs.getPaddingRight(), logs.getPaddingBottom());
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = TAB_REMOTE;
                refresh();
            }
        });
        tabRow.addView(tab);

        remotePanel = new RemotePanel(this, new RemotePanel.Listener() {
            @Override
            public void onKey(int code) { remoteKey(code); }

            @Override
            public void onExit() { bml.close(); }
        });
        remotePanel.setVisibility(View.GONE);
        android.view.ViewGroup holder = (android.view.ViewGroup) tvContent.getParent();
        holder.addView(remotePanel, holder.indexOfChild(tvContent) + 1);
    }

    private void remoteKey(int code) {
        if (!bml.isActive()) {
            if (code != BmlController.DATA) {
                setStatus("Press DATA first to open the data broadcast.");
                return;
            }
            ServiceInfo svc = findService(demux.getServices(), selectedServiceId);
            if (svc == null || !svc.pmtSeen) {
                setStatus("No service selected yet.");
                return;
            }
            boolean hasData = false;
            for (ph.opendtv.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
            if (!hasData) {
                setStatus("This service has no data broadcast.");
                return;
            }
            bmlServiceId = svc.serviceId;
            bml.open(demux, svc);
        }
        bml.tap(code);
    }

    /** BML moves/shrinks the video: place the PlayerView inside the frame accordingly. */
    private void applyVideoRect() {
        android.widget.FrameLayout.LayoutParams lp;
        int w = playerFrame.getWidth(), h = playerFrame.getHeight();
        if (videoFrac == null || w == 0 || h == 0) {
            lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            lp = new android.widget.FrameLayout.LayoutParams(Math.max(1, Math.round((videoFrac[2] - videoFrac[0]) * w)),
                    Math.max(1, Math.round((videoFrac[3] - videoFrac[1]) * h)));
            lp.leftMargin = Math.round(videoFrac[0] * w);
            lp.topMargin = Math.round(videoFrac[1] * h);
        }
        playerView.setLayoutParams(lp);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (bml != null && bml.isActive() && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = -1;
            int kc = e.getKeyCode();
            switch (kc) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP: code = BmlController.UP; break;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: code = BmlController.DOWN; break;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT: code = BmlController.LEFT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: code = BmlController.RIGHT; break;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER: code = BmlController.ENTER; break;
                case android.view.KeyEvent.KEYCODE_PROG_RED: code = BmlController.RED; break;
                case android.view.KeyEvent.KEYCODE_PROG_GREEN: code = BmlController.GREEN; break;
                case android.view.KeyEvent.KEYCODE_PROG_YELLOW: code = BmlController.YELLOW; break;
                case android.view.KeyEvent.KEYCODE_PROG_BLUE: code = BmlController.BLUE; break;
                case 230: code = BmlController.DATA; break;   // KEYCODE_TV_DATA_SERVICE
                default:
                    if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9)
                        code = BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_0);
            }
            if (code >= 0) {
                bml.tap(code);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void bindTab(int id, final int tab) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = tab;
                refresh();
            }
        });
    }

    // ------------------------------------------------------------------ sources

    private void startFile(Uri uri) {
        final Uri u = uri;
        FileTsSource.Opener opener = new FileTsSource.Opener() {
            @Override
            public java.io.InputStream open() throws java.io.IOException {
                return getContentResolver().openInputStream(u);
            }
        };
        // Unthrottled: analysis should finish as fast as the CPU allows. When a service is playing,
        // back-pressure from the player (TsPipe.awaitSpace) still paces the file. Stops at EOF so the
        // counters and EWBS log stay readable. FileTsSource auto-detects 188/192/204-byte packets.
        startSource(new FileTsSource("File: " + uri.getLastPathSegment(), opener, 0L, false), true);
    }

    private void connectUsb() {
        if (source instanceof UsbTunerSource) return;   // already running or initialising
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (UsbTunerSource.isSupported(d)) {
                if (usbManager.hasPermission(d)) {
                    startSource(new UsbTunerSource(usbManager, d), false);
                } else {
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                    usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
                }
                return;
            }
        }
        setStatus("Tuner " + UsbTunerSource.SUPPORTED_IDS
                + " not found. Plug it in (OTG); the app starts it automatically.");
    }

    /** @param backpressure true for files (pace by the player), false for live sources (drop oldest). */
    private void startSource(TsSource s, final boolean backpressure) {
        stopSource();
        final TsDemux d = new TsDemux();
        demux = d;
        selectedServiceId = -1;
        serviceListKey = "";
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        source = s;
        setStatus(s.name() + ": starting ...");
        final TsSource starting = s;
        // Tuner bring-up (firmware download, init, first tune) takes seconds: never on the UI thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                starting.start(sourceListener(backpressure));
            }
        }, "SourceStart").start();
    }

    private TsSource.Listener sourceListener(final boolean backpressure) {
        return new TsSource.Listener() {
            @Override
            public void onData(byte[] buf, int off, int len) {
                if (backpressure) pipe.awaitSpace(len, 500);
                demux.feed(buf, off, len);   // read the field each time: a retune swaps in a fresh demux
                if (isRecordingWhole && wholeStreamOutput != null) {
                    synchronized (AnalyzerActivity.this) {
                        if (wholeStreamOutput != null) {
                            try {
                                wholeStreamOutput.write(buf, off, len);
                            } catch (Exception e) {
                                Log.e(TAG, "Whole stream write error", e);
                            }
                        }
                    }
                }
            }

            @Override
            public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(message);
                    }
                });
            }

            @Override
            public void onEnd() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("End of file.");
                    }
                });
            }
        };
    }

    private void stopSource() {
        if (source != null) {
            source.stop();
            source = null;
        }
    }

    // ------------------------------------------------------------------ playback

    private void selectService(ServiceInfo svc) {
        selectedServiceId = svc.serviceId;
        player.stop();
        pipe.clear();
        captionOverlay.reset();
        demux.setPacketSink(new ServiceFilter(pipe, demux.getTsId(), svc).setCaptionSink2(captionOverlay));

        DataSource.Factory factory = new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return new TsPipeDataSource(pipe);
            }
        };
        MediaSource ms = new ProgressiveMediaSource.Factory(factory, new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS))
                .createMediaSource(MediaItem.fromUri("tspipe://live"));
        player.setMediaSource(ms);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // ------------------------------------------------------------------ periodic UI refresh

    private void refresh() {
        TsDemux d = demux;
        List<ServiceInfo> services = d.getServices();
        lastSnapshot = d.sample();
        updateServiceList(services);

        ServiceInfo sel = findService(services, selectedServiceId);
        updateInfoPanel(sel);
        updateSignal();
        ewbsBanner.update(d);

        if (remotePanel != null) remotePanel.setVisibility(currentTab == TAB_REMOTE ? View.VISIBLE : View.GONE);
        if (bml != null && bml.isActive() && (bml.demux() != d || bmlServiceId != selectedServiceId)) bml.close();

        switch (currentTab) {
            case TAB_REMOTE:
                tvContent.setVisibility(View.GONE);
                layoutGraphContainer.setVisibility(View.GONE);
                break;
            case TAB_EPG:
                tvContent.setVisibility(View.VISIBLE);
                layoutGraphContainer.setVisibility(View.GONE);
                tvContent.setText(epgText(d, sel));
                break;
            case TAB_DATACAST:
                tvContent.setVisibility(View.VISIBLE);
                layoutGraphContainer.setVisibility(View.GONE);
                tvContent.setText(datacastText(d, services, sel));
                break;
            case TAB_GRAPH:
                tvContent.setVisibility(View.GONE);
                layoutGraphContainer.setVisibility(View.VISIBLE);
                updateGraphDashboard(d, sel);
                break;
            case TAB_LOGS:
                tvContent.setVisibility(View.VISIBLE);
                layoutGraphContainer.setVisibility(View.GONE);
                tvContent.setText(logBuffer.length() == 0 ? "No logs captured yet." : logBuffer.toString());
                break;
            default:
                tvContent.setVisibility(View.VISIBLE);
                layoutGraphContainer.setVisibility(View.GONE);
                tvContent.setText(analyzerText(d));
        }
    }

    private void updateServiceList(List<ServiceInfo> services) {
        StringBuilder key = new StringBuilder();
        List<ServiceInfo> shown = new ArrayList<>();
        for (ServiceInfo s : services) {
            if (!s.pmtSeen || s.streams.isEmpty()) continue;
            shown.add(s);
            key.append(s.serviceId).append(':').append(s.name).append(':').append(s.virtualNumber).append(';');
        }
        if (key.toString().equals(serviceListKey)) return;
        serviceListKey = key.toString();

        List<String> labels = new ArrayList<>();
        spinnerSids.clear();
        int selIndex = -1;
        for (ServiceInfo s : shown) {
            String name = s.name.isEmpty() ? "Service " + s.serviceId : s.name;
            labels.add(s.virtualNumber + "  " + name + (s.hasVideo() ? "" : "  (no video)"));
            spinnerSids.add(s.serviceId);
            if (s.serviceId == selectedServiceId) selIndex = labels.size() - 1;
        }
        spChannels.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));

        if (selIndex >= 0) {
            spChannels.setSelection(selIndex, false);
        } else if (selectedServiceId == -1) {
            for (int i = 0; i < shown.size(); i++) { // auto-select the first TV service
                if (shown.get(i).hasVideo()) {
                    spChannels.setSelection(i, false);
                    selectService(shown.get(i));
                    break;
                }
            }
        }
    }

    private void updateInfoPanel(ServiceInfo sel) {
        String net = demux.getNetworkName();
        if (net == null || net.isEmpty()) net = (sel != null && !sel.provider.isEmpty()) ? sel.provider : "--";
        if (tvNetwork != null) tvNetwork.setText(net);

        if (sel == null) {
            tvVirtual.setText("--");
            tvChannel.setText("--");
            tvProgramId.setText("--");
            tvVideoId.setText("Video ID: --");
            tvAudioId.setText("Audio ID: --");
            tvCodec.setText("Codec: --");
            return;
        }
        tvVirtual.setText(sel.virtualNumber);
        tvChannel.setText(sel.name.isEmpty() ? "Service " + sel.serviceId : sel.name);
        tvProgramId.setText(String.format(Locale.US, "%d (0x%04X)", sel.serviceId, sel.serviceId));

        StreamInfo v = sel.firstVideo(), a = sel.firstAudio();
        tvVideoId.setText(v == null ? "Video ID: --" : String.format(Locale.US, "Video ID: 0x%04X (%d)", v.pid, v.pid));
        tvAudioId.setText(a == null ? "Audio ID: --" : String.format(Locale.US, "Audio ID: 0x%04X (%d)", a.pid, a.pid));
        tvCodec.setText("Codec: " + (v == null ? "--" : v.shortCodec()) + " / " + (a == null ? "--" : a.shortCodec()));
    }

    private void updateSignal() {
        if (source == null) {
            tvSignal.setText("Signal: no source");
            barStrength.setProgress(0);
            barQuality.setProgress(0);
            if (tvStrengthVal != null) tvStrengthVal.setText("0%");
            if (tvQualityVal != null) tvQualityVal.setText("0%");
            return;
        }
        SignalStatus st = source.getSignalStatus();
        if (st == null) {
            tvSignal.setText("Signal: n/a (" + (source.supportsTuning() ? "waiting for tuner" : "file") + ")");
            barStrength.setProgress(0);
            barQuality.setProgress(0);
            if (tvStrengthVal != null) tvStrengthVal.setText("0%");
            if (tvQualityVal != null) tvQualityVal.setText("0%");
            return;
        }
        int strength = Math.max(st.strengthPercent, 0);
        int quality = st.locked ? Math.max(st.qualityPercent, 0) : 0;
        barStrength.setProgress(strength);
        barQuality.setProgress(quality);
        if (tvStrengthVal != null) tvStrengthVal.setText(strength + "%");
        if (tvQualityVal != null) tvQualityVal.setText(quality + "%");

        String dropped = source instanceof UsbTunerSource && ((UsbTunerSource) source).getDroppedChunks() > 0
                ? "  USB drop " + ((UsbTunerSource) source).getDroppedChunks() : "";
        tvSignal.setText(String.format(Locale.US, "%s  %s  Str %d%%  Qual %d%%  BER %s%s",
                st.locked ? "LOCK" : "no lock",
                st.dbm == Integer.MIN_VALUE ? "-- dBm" : st.dbm + " dBm",
                strength, quality,
                Double.isNaN(st.ber) ? "n/a" : String.format(Locale.US, "%.1e", st.ber), dropped)
                + playerDiag());
    }

    /** One line about what ExoPlayer is doing: helps tell "no data" from "no decoder" from "decoder too slow". */
    private String playerDiag() {
        if (selectedServiceId == -1) return "";
        String[] states = {"idle", "buffering", "ready", "ended"};
        int ps = player.getPlaybackState();
        androidx.media3.common.Format vf = player.getVideoFormat();
        androidx.media3.exoplayer.DecoderCounters dc = player.getVideoDecoderCounters();
        StringBuilder sb = new StringBuilder("\nPlayer ").append(states[Math.max(0, Math.min(3, ps - 1))]);
        if (vf == null) {
            sb.append("  video: none yet");
        } else {
            sb.append("  video: ").append(vf.sampleMimeType).append(' ').append(vf.width).append('x').append(vf.height);
        }
        if (dc != null) {
            dc.ensureUpdated();
            sb.append("  rendered ").append(dc.renderedOutputBufferCount).append(" dropped ").append(dc.droppedBufferCount);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tuning: scan / manual

    private static String channelLabel(int khz) {
        if (khz >= 473143 && (khz - 473143) % 6000 == 0) {
            return "UHF " + (14 + (khz - 473143) / 6000) + "  " + String.format(Locale.US, "%.3f MHz", khz / 1000.0);
        }
        return String.format(Locale.US, "%.3f MHz", khz / 1000.0);
    }

    private boolean tunerReady() {
        if (source == null || !source.supportsTuning()) {
            setStatus("Connect the USB tuner first (USB button).");
            return false;
        }
        if (!source.isReady()) {
            setStatus("Tuner is still initialising, please wait ...");
            return false;
        }
        if (scanning) {
            setStatus("Scan in progress.");
            return false;
        }
        return true;
    }

    /** Switches to a multiplex: fresh demux and player, then tune (off the UI thread; lock wait takes seconds). */
    private void tuneTo(final int khz) {
        if (!tunerReady()) return;
        currentTunedKhz = khz;
        final TsSource src = source;
        selectedServiceId = -1;
        serviceListKey = "";
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        demux = new TsDemux();
        setStatus("Tuning " + channelLabel(khz) + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = src.tune(khz);
                if (ok) {
                    pipe.clear();
                    demux = new TsDemux();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(ok ? "Locked " + channelLabel(khz) : "No lock on " + channelLabel(khz));
                    }
                });
            }
        }, "Tune").start();
    }

    private void showScanDialog() {
        if (scanning) {
            scanCancel = true;
            setStatus("Stopping scan ...");
            return;
        }
        if (!tunerReady()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Channel scan")
                .setItems(new String[]{
                                "Quick scan (" + QUICK_SCAN_KHZ.length + " known channels)",
                                "Full scan (473.143 to 695.143 MHz, 38 channels)"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                startScan(which == 1);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // scan progress dialog
    private AlertDialog scanDialog;
    private TextView scanStatusTv, scanFoundTv;
    private ProgressBar scanBar;

    private void dismissScanDialog() {
        if (scanDialog != null) {
            scanDialog.dismiss();
            scanDialog = null;
        }
    }

    private void showScanProgress(int max) {
        dismissScanDialog();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        scanStatusTv = new TextView(this);
        scanStatusTv.setTextSize(15);
        scanStatusTv.setText("Starting scan ...");
        scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanBar.setMax(max);
        scanFoundTv = new TextView(this);
        scanFoundTv.setTextSize(12);
        scanFoundTv.setPadding(0, pad / 2, 0, 0);
        scanFoundTv.setText("Found: none yet");
        box.addView(scanStatusTv);
        box.addView(scanBar);
        box.addView(scanFoundTv);
        scanDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Channel scan")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Stop", null)
                .create();
        scanDialog.show();
        // override so the dialog stays open and shows "Stopping ..." until the current channel finishes
        scanDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) {
                    scanCancel = true;
                    scanStatusTv.setText("Stopping after this channel ...");
                } else {
                    dismissScanDialog();
                }
            }
        });
    }

    private void scanProgress(final String status, final int done, final String foundText) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (scanDialog == null) return;
                scanStatusTv.setText(status);
                scanBar.setProgress(done);
                if (foundText != null) scanFoundTv.setText(foundText);
                setStatus(status);
            }
        });
    }

    private static String foundText(List<Mux> found) {
        if (found.isEmpty()) return "Found: none yet";
        StringBuilder sb = new StringBuilder("Found " + found.size() + ":\n");
        for (Mux m : found) sb.append("  ").append(m.label).append('\n');
        return sb.toString();
    }

    /**
     * Scan = for every frequency: full tune with the vendor's complete lock sequence (not the quick one), then
     * wait for PAT/PMT/SDT so each multiplex is listed with its channel names. Unlocked channels get a second
     * try because the first attempt right after a frequency change sometimes misses a weak signal.
     */
    private void startScan(boolean full) {
        if (!tunerReady()) return;
        final TsSource src = source;
        final List<Integer> freqs = new ArrayList<>();
        if (full) {
            for (int f = 473143; f <= 695143; f += 6000) freqs.add(f);
        } else {
            for (int f : QUICK_SCAN_KHZ) freqs.add(f);
        }
        scanning = true;
        scanCancel = false;
        selectedServiceId = -1;
        serviceListKey = "";
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        ((Button) findViewById(R.id.btn_scan)).setText("Scan...");
        showScanProgress(freqs.size());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Mux> found = new ArrayList<>();
                for (int i = 0; i < freqs.size() && !scanCancel; i++) {
                    final int khz = freqs.get(i);
                    String head = String.format(Locale.US, "Channel %d of %d:  %s\n", i + 1, freqs.size(), channelLabel(khz));
                    scanProgress(head + "tuning ...", i, null);
                    demux = new TsDemux();
                    long t0 = System.currentTimeMillis();
                    boolean lockedNow = src.tune(khz);
                    if (!lockedNow && !scanCancel) {
                        scanProgress(head + "no lock, trying again ...", i, null);
                        sleepMs(300);
                        lockedNow = src.tune(khz);
                    }
                    SignalStatus ss = src.getSignalStatus();
                    Log.i("OpenDTV", String.format(Locale.US, "scan %d kHz: lock=%b in %d ms  str=%d dbm=%d",
                            khz, lockedNow, System.currentTimeMillis() - t0,
                            ss == null ? -1 : ss.strengthPercent, ss == null ? 0 : ss.dbm));
                    if (!lockedNow) {
                        scanProgress(head + "no signal", i + 1, null);
                        continue;
                    }
                    scanProgress(head + "LOCKED, reading channel list ...", i, null);
                    TsDemux d = new TsDemux();   // drop whatever the previous channel left in the USB buffers
                    demux = d;
                    long start = System.currentTimeMillis();
                    long firstSeen = 0;
                    while (System.currentTimeMillis() - start < 15000 && !scanCancel) {
                        int n = countServices(d);
                        long now = System.currentTimeMillis();
                        if (n > 0 && firstSeen == 0) firstSeen = now;
                        // done when every TV service has its name, or 6 s after the first service appeared
                        if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1500) || now - firstSeen > 12000)) break;
                        scanProgress(head + String.format(Locale.US, "LOCKED, reading channel list ... %d service(s)", n), i, null);
                        sleepMs(250);
                    }
                    String names = serviceNames(d);
                    Log.i("OpenDTV", "scan " + khz + " kHz: services=" + countServices(d) + " " + names);
                    found.add(new Mux(khz, channelLabel(khz) + (names.isEmpty() ? "  (locked, no TV service seen)" : "  " + names)));
                    scanProgress(head + "found " + (names.isEmpty() ? "a multiplex" : names), i + 1, foundText(found));
                }
                final boolean cancelled = scanCancel;
                final int count = found.size();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        ((Button) findViewById(R.id.btn_scan)).setText("Scan");
                        String summary = (cancelled ? "Scan stopped: " : "Scan done: ") + count + " multiplex(es) found.";
                        if (scanDialog != null) {
                            scanStatusTv.setText(summary);
                            scanBar.setProgress(scanBar.getMax());
                            scanFoundTv.setText(foundText(found));
                            scanDialog.setTitle("Channel scan finished");
                            scanDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
                        }
                        if (!found.isEmpty()) {
                            muxes.clear();
                            muxes.addAll(found);
                            saveMuxes();
                            refreshMuxSpinner(0);
                            currentMuxIndex = 0;
                            tuneTo(found.get(0).khz);
                        }
                        setStatus(summary);
                    }
                });
            }
        }, "ScanThread").start();
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private static String serviceNames(TsDemux d) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ServiceInfo s : d.getServices()) {
            if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
            if (n++ > 0) sb.append(", ");
            sb.append(s.name.isEmpty() ? "sid " + s.serviceId : s.name);
        }
        return n == 0 ? "" : n + " TV: " + sb;
    }

    private void showManualDialog() {
        if (!tunerReady()) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("497.143 (MHz), 497143 (kHz) or UHF channel 18");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Manual tune")
                .setView(input)
                .setPositiveButton("Tune", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int khz = parseFrequency(input.getText().toString());
                        if (khz <= 0) {
                            setStatus("Not a valid frequency.");
                            return;
                        }
                        manualTune(khz);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "497.143" = MHz, "497143" = kHz, "497" = MHz (+0.143 ISDB-T offset), "18" = UHF channel number. */
    static int parseFrequency(String text) {
        String t = text.trim().replace(',', '.').toLowerCase(Locale.US).replace("mhz", "").replace("khz", "").trim();
        try {
            if (t.contains(".")) return (int) Math.round(Double.parseDouble(t) * 1000.0);
            long v = Long.parseLong(t);
            if (v >= 7 && v <= 13) return (int) (177143 + 6000 * (v - 7));    // VHF 7-13 (ISDB-T)
            if (v >= 14 && v <= 69) return (int) (473143 + 6000 * (v - 14));   // UHF channel number
            if (v >= 100 && v < 1000) return (int) (v * 1000 + 143);
            if (v >= 100000 && v <= 900000) return (int) v;
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    private void manualTune(final int khz) {
        // reuse the multiplex list: remember a manually tuned channel once it locks
        currentTunedKhz = khz;
        selectedServiceId = -1;
        serviceListKey = "";
        final TsSource src = source;
        player.stop();
        player.clearMediaItems();
        pipe.clear();
        demux = new TsDemux();
        setStatus("Tuning " + channelLabel(khz) + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = src.tune(khz);
                if (ok) {
                    pipe.clear();
                    demux = new TsDemux();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            setStatus("No lock on " + channelLabel(khz));
                            return;
                        }
                        setStatus("Locked " + channelLabel(khz));
                        int at = -1;
                        for (int i = 0; i < muxes.size(); i++) if (muxes.get(i).khz == khz) at = i;
                        if (at < 0) {
                            muxes.add(new Mux(khz, channelLabel(khz) + "  (manual)"));
                            at = muxes.size() - 1;
                            saveMuxes();
                        }
                        currentMuxIndex = at;
                        refreshMuxSpinner(at);
                    }
                });
            }
        }, "ManualTune").start();
    }

    private void refreshMuxSpinner(int select) {
        List<String> labels = new ArrayList<>();
        for (Mux m : muxes) labels.add(m.label);
        if (labels.isEmpty()) labels.add("(no channels yet: press Scan)");
        spMux.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (select >= 0 && select < muxes.size()) spMux.setSelection(select, false);
    }

    private void saveMuxes() {
        StringBuilder sb = new StringBuilder();
        for (Mux m : muxes) sb.append(m.khz).append('\t').append(m.label.replace('\n', ' ')).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("muxes", sb.toString()).apply();
    }

    private void loadMuxes() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString("muxes", "");
        muxes.clear();
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                muxes.add(new Mux(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        currentMuxIndex = muxes.isEmpty() ? -1 : 0;   // the spinner shows item 0 without firing a tune
        refreshMuxSpinner(-1);
    }

    // ------------------------------------------------------------------ tab content

    private String analyzerText(TsDemux d) {
        AnalyzerSnapshot s = lastSnapshot;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Network: %s (0x%04X)   TS id: 0x%04X\n",
                d.getNetworkName().isEmpty() ? "--" : d.getNetworkName(), Math.max(d.getNetworkId(), 0), Math.max(d.getTsId(), 0)));
        sb.append("Broadcast clock (JST): ").append(TsUtil.formatTime(d.getClockMs())).append('\n');
        sb.append(String.format(Locale.US, "Total %.2f Mbps | pkts %d | null %d | CC err %d | TEI %d | sync loss %d\n",
                s.totalKbps / 1000.0, s.totalPackets, s.nullPackets, s.ccErrors, s.teiPackets, s.syncLosses));
        String ewbs = d.ewbsReport();
        sb.append(ewbs.isEmpty() ? "\n" : ewbs + "\n");
        sb.append("PID     kbps    pkts   CC  Description\n");
        int shown = 0;
        for (AnalyzerSnapshot.Row r : s.rows) {
            if (shown++ >= 40) break;
            sb.append(String.format(Locale.US, "0x%04X %7.0f %7d %4d  %s\n", r.pid, r.kbps, r.packets, r.ccErrors, r.label));
        }
        return sb.toString();
    }

    private String epgText(TsDemux d, ServiceInfo sel) {
        if (sel == null) return "Select a channel.";
        List<EpgEvent> events = d.getEvents(sel.serviceId);
        if (events.isEmpty()) return "No EIT data yet for this service (can take several seconds).";
        long now = d.getClockMs();
        StringBuilder sb = new StringBuilder("Times as broadcast (JST per ARIB)\n\n");
        int shown = 0;
        for (EpgEvent e : events) {
            if (shown++ >= 40) break;
            boolean current = now >= 0 && e.startMs >= 0 && now >= e.startMs && now < e.endMs();
            sb.append(current ? "> " : "  ")
                    .append(TsUtil.formatTime(e.startMs)).append(" +")
                    .append(String.format(Locale.US, "%d:%02d", e.durationSec / 3600, (e.durationSec / 60) % 60))
                    .append("  ").append(e.name.isEmpty() ? "(no title)" : e.name).append('\n');
            if (!e.text.isEmpty()) sb.append("      ").append(e.text).append('\n');
        }
        return sb.toString();
    }

    private String datacastText(TsDemux d, List<ServiceInfo> services, ServiceInfo sel) {
        List<CarouselInfo> carousels = d.getCarousels();
        StringBuilder sb = new StringBuilder();
        sb.append("Datacast: DSM-CC data carousels are decoded into modules and files (names, types, sizes).\n")
                .append("BML pages and images are listed, not rendered.\n\n");
        if (sel != null) {
            sb.append("Selected service ").append(sel.serviceId).append(":\n");
            boolean any = false;
            for (StreamInfo s : sel.streams) {
                if (s.isDatacast()) {
                    any = true;
                    sb.append(String.format(Locale.US, "  PID 0x%04X  %s  data_component_id %s  tag %s\n",
                            s.pid, s.codecName(),
                            s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--",
                            s.componentTag >= 0 ? String.format(Locale.US, "0x%02X", s.componentTag) : "--"));
                    appendCarousel(sb, carouselFor(carousels, s.pid), "    ", true);
                }
            }
            if (!any) sb.append("  no data-carousel stream in this service\n");
            for (StreamInfo s : sel.streams) {
                if (s.streamType == 0x06) {
                    sb.append(String.format(Locale.US, "  PID 0x%04X  PES private (captions/text)  data_component_id %s\n",
                            s.pid, s.dataComponentId >= 0 ? String.format(Locale.US, "0x%04X", s.dataComponentId) : "--"));
                }
            }
            sb.append('\n');
        }
        sb.append("Data-broadcast services in this multiplex (service_type 0xC0 / 0x0C):\n");
        boolean found = false;
        for (ServiceInfo s : services) {
            if (!s.isDataBroadcastService()) continue;
            found = true;
            sb.append(String.format(Locale.US, "  sid %d  %s  PMT 0x%04X\n", s.serviceId, s.name, s.pmtPid));
            for (StreamInfo st : s.streams) {
                if (st.isDatacast()) {
                    sb.append(String.format(Locale.US, "      PID 0x%04X  %s\n", st.pid, st.codecName()));
                    appendCarousel(sb, carouselFor(carousels, st.pid), "      ", false);
                }
            }
        }
        if (!found) sb.append("  none seen\n");
        return sb.toString();
    }

    private static CarouselInfo carouselFor(List<CarouselInfo> list, int pid) {
        for (CarouselInfo c : list) if (c.pid == pid) return c;
        return null;
    }

    /** @param files list the files of each module (true), or only module id/size/progress (false). */
    private static void appendCarousel(StringBuilder sb, CarouselInfo ci, String indent, boolean files) {
        if (ci == null) {
            sb.append(indent).append("(no carousel data received yet)\n");
            return;
        }
        sb.append(String.format(Locale.US, "%s%d/%d modules complete, %d files, %d KB received (DII %d, DDB %d)\n",
                indent, ci.completeModules(), ci.modules.size(), ci.totalResources(),
                ci.bytesReceived / 1024, ci.diiCount, ci.ddbCount));
        int shownModules = 0;
        for (CarouselModule m : ci.modules) {
            if (shownModules++ >= 30) {
                sb.append(indent).append("  ... ").append(ci.modules.size() - 30).append(" more modules\n");
                break;
            }
            sb.append(String.format(Locale.US, "%s  module 0x%04X v%d  %d B  %s%s%s\n", indent, m.moduleId, m.version,
                    m.size, m.complete ? "complete" : m.percent() + "%", m.compressed ? "  (zlib)" : "",
                    m.error.isEmpty() ? "" : "  " + m.error));
            if (!files) continue;
            int shown = 0;
            for (CarouselResource r : m.resources) {
                if (r.location.isEmpty()) continue; // the resource-list part has no name
                if (shown++ >= 6) {
                    sb.append(indent).append("      ... more files\n");
                    break;
                }
                int semi = r.contentType.indexOf(';');
                String type = semi > 0 ? r.contentType.substring(0, semi) : r.contentType;
                sb.append(String.format(Locale.US, "%s      %s  %s  %d B\n", indent, r.location, type, r.length));
            }
        }
    }

    private void updateGraphDashboard(TsDemux d, ServiceInfo sel) {
        AnalyzerSnapshot s = lastSnapshot;
        SignalStatus st = source != null ? source.getSignalStatus() : null;

        // 1. Data Received
        double bitrateMbps = s.totalKbps / 1000.0;
        if (tvGraphBitrate != null) {
            tvGraphBitrate.setText(String.format(Locale.US, "Bitrate: %.2f Mbps (Total pkts: %d)", bitrateMbps, s.totalPackets));
        }
        if (barGraphBitrate != null) {
            int prog = (int) Math.min(3000, Math.round(bitrateMbps * 100.0));
            barGraphBitrate.setProgress(prog);
        }
        if (tvGraphPackets != null) {
            tvGraphPackets.setText(String.format(Locale.US, "Null Packets: %d | Total Packets: %d", s.nullPackets, s.totalPackets));
        }

        // 2. Demuxed
        if (tvGraphDemux != null) {
            String netName = d.getNetworkName().isEmpty() ? "--" : d.getNetworkName();
            int serviceCount = d.getServices().size();
            String selSvcStr = sel != null ? String.format(Locale.US, " | Selected: %s (SID %d)", sel.name.isEmpty() ? "Svc " + sel.serviceId : sel.name, sel.serviceId) : "";
            tvGraphDemux.setText(String.format(Locale.US, "Network: %s | TS ID: 0x%04X | Services: %d%s", netName, Math.max(d.getTsId(), 0), serviceCount, selSvcStr));
        }

        // 3. Loss & Errors
        long usbDrops = source instanceof UsbTunerSource ? ((UsbTunerSource) source).getDroppedChunks() : 0L;
        if (tvGraphLoss != null) {
            tvGraphLoss.setText(String.format(Locale.US, "CC Errors: %d | TEI Packets: %d | Sync Losses: %d | USB Drops: %d", s.ccErrors, s.teiPackets, s.syncLosses, usbDrops));
        }
        if (barGraphErrors != null) {
            long totalErrors = s.ccErrors + s.teiPackets + s.syncLosses + usbDrops;
            int errProg = (int) Math.min(100, totalErrors);
            barGraphErrors.setProgress(errProg);
        }

        // 4. Signal
        if (tvGraphSignal != null) {
            if (st == null) {
                tvGraphSignal.setText("Status: No signal status available");
            } else {
                tvGraphSignal.setText(String.format(Locale.US, "Status: %s | Power: %s | Strength: %d%% | Quality: %d%% | BER: %s",
                        st.locked ? "LOCKED" : "UNLOCKED",
                        st.dbm == Integer.MIN_VALUE ? "-- dBm" : st.dbm + " dBm",
                        Math.max(st.strengthPercent, 0),
                        Math.max(st.qualityPercent, 0),
                        Double.isNaN(st.ber) ? "n/a" : String.format(Locale.US, "%.1e", st.ber)));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ServiceInfo findService(List<ServiceInfo> list, int sid) {
        for (ServiceInfo s : list) if (s.serviceId == sid) return s;
        return null;
    }

    private void setStatus(String text) {
        if (text == null) text = "";
        String firstLine = text.trim();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline).trim();
        }
        tvStatus.setText(firstLine);

        if (!text.isEmpty()) {
            String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logBuffer.append("[").append(timeStamp).append("] ").append(text).append("\n\n");
        }
        if (currentTab == TAB_LOGS) {
            tvContent.setText(logBuffer.length() == 0 ? "No logs captured yet." : logBuffer.toString());
        }
    }
}