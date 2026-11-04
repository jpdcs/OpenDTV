package ph.opendtv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Environment;

import ph.opendtv.bml.BmlController;
import ph.opendtv.ui.EwbsBanner;
import ph.opendtv.ts.RecordingPacketSink;
import ph.opendtv.ui.CaptionOverlay;
import ph.opendtv.ui.CaptionView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ph.opendtv.player.TsPipeDataSource;
import ph.opendtv.source.SignalStatus;
import ph.opendtv.source.TsSource;
import ph.opendtv.ts.EpgEvent;
import ph.opendtv.ts.ServiceFilter;
import ph.opendtv.ts.ServiceInfo;
import ph.opendtv.ts.StreamInfo;
import ph.opendtv.ts.TsDemux;
import ph.opendtv.ts.TsPipe;
import ph.opendtv.tv.TunerHub;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity implements TunerHub.Owner {

    private static final String TAG = "OpenDTV-Main";
    private static final String ACTION_USB_PERMISSION = "ph.opendtv.USB_PERMISSION";
    private static final String PREFS = "opendtv_prefs";
    private static final String PREF_FAV = "favorites";
    private static final String PREF_CHANNELS = "saved_channels";
    private static final String PREF_ASPECT = "aspect_mode";
    private static final String PREF_LAST_SID = "last_service_id";
    private static final String PREF_LAST_KHZ = "last_khz";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TsPipe pipe = new TsPipe(4 * 1024 * 1024);   // service-filtered TS only (a few Mbit/s): ~10 s of video

    private TunerHub hub;
    private UsbManager usbManager;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView tvCurrentChannel;
    private TabLayout tabLayout;
    private RecyclerView recyclerView;
    private RecordingPacketSink currentPacketSink;
    private MenuItem recordMenuItem;
    private CaptionOverlay captionOverlay;
    private CaptionView captionView;          // styled (WebView) caption display, null while the simple text line is selected
    private static final String PREF_HW_DECODER = "hardware_decoder";   // false (default) = FFmpeg first, true = MediaCodec first
    private static final String PREF_CC = "closed_captions";
    private static final String PREF_CC_STYLE = "caption_style";        // 0 = simple text line, 1 = styled (aribb24 WebView)
    private static final String PREF_CC_LANG = "caption_language";      // 1 = first (main), 2 = second
    private static final String PREF_AUDIO_LANG = "audio_language";     // ISO 639 code, "" = automatic
    private MenuItem captionsMenuItem;

    // Data broadcast (BML), landscape and portrait. Landscape: keys only. Portrait: floating remote button + panel.
    private BmlController bml;
    private final EwbsBanner ewbsBanner = new EwbsBanner(this);
    private final Runnable ewbsTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            TsDemux d = hub != null ? hub.demux() : null;
            ewbsBanner.update(d);
            ui.postDelayed(this, 1000);
        }
    };
    private FrameLayout bmlFrame;
    private View fabRemote;                // portrait only: opens / closes the remote panel
    private View remotePanel;              // portrait only
    private boolean remotePanelOpen;
    private float[] bmlVideoFrac;          // where the page wants the video (fractions), null = full screen
    private int layoutKey = -1;            // which layout set (portrait / landscape / tablet landscape) is inflated

    // Landscape HUD & Overlay Views
    private View sidePanel;
    private View infoBanner;
    private android.widget.ImageView hudIvLogo;
    private TextView hudTvVirtual;
    private TextView hudTvChannelName;
    private TextView hudTvNetworkName;
    private TextView hudTvNowEvent;
    private TextView hudTvNextEvent;
    private android.widget.ImageButton btnOpenSidePanel;

    private final Runnable hideHudRunnable = this::hideInfoBanner;
    private final Runnable hideSidePanelRunnable = this::hideSidePanel;

    private volatile TsDemux demux = new TsDemux();
    private int currentTab = 0; // 0: Channels, 1: EPG, 2: Favorites
    private ChannelEntry currentChannelEntry;
    private static final int MAX_RETRIES = 3;
    /** Single worker for tune/play: one tune at a time, no thread churn, cancelled in onDestroy. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PlayChannel"));
    /** Created once and reused for every channel change. */
    private final DataSource.Factory pipeFactory = () -> new TsPipeDataSource(pipe);
    private final MediaSource.Factory mediaSourceFactory = new ProgressiveMediaSource.Factory(pipeFactory,
            new DefaultExtractorsFactory().setTsExtractorFlags(
                    // Same flags as the known-good OpenDTVph-MOD: non-IDR keyframes + access-unit detection, which
                    // lets H.264 streams without AUDs (some broadcasters) start and stay in sync with the FFmpeg decoder.
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS));
    private int retries;
    private final AtomicInteger playGen = new AtomicInteger();
    private volatile boolean tunerReady;
    private volatile boolean initializing;

    private final List<ChannelEntry> allChannels = new ArrayList<>();
    /** Favorite channels as "khz:serviceId", one per list entry: service IDs repeat across multiplexes (issue #1). */
    private final Set<String> favorites = new HashSet<>();

    private volatile boolean scanning;
    private volatile boolean scanCancel;

    public static final class ChannelEntry {
        public int khz;
        public ServiceInfo service;
        /** Network ID / TS ID of the multiplex the scan found this service in (-1 = unknown, e.g. loaded from prefs). */
        public int networkId = -1;
        public int tsId = -1;

        public ChannelEntry(int khz, ServiceInfo service) {
            this.khz = khz;
            this.service = service;
        }
    }

    /**
     * A service ID is only unique inside one multiplex: broadcasters reuse small IDs (Solar's services are 1, 2, 3 ...),
     * so the same ID on another multiplex is another channel. It is a duplicate only when it is the same multiplex
     * received again on a second frequency: same TS ID, and the same network ID when both are known (issue #1).
     */
    private static boolean isSameService(ChannelEntry ce, int networkId, int tsId, int serviceId) {
        if (ce.service.serviceId != serviceId || ce.tsId != tsId) return false;
        return ce.networkId < 0 || networkId < 0 || ce.networkId == networkId;
    }

    /** One list entry is one service on one frequency (service IDs repeat across multiplexes, issue #1). */
    private static boolean isSameEntry(ChannelEntry a, ChannelEntry b) {
        return a == b || (a.khz == b.khz && a.service.serviceId == b.service.serviceId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        hub = TunerHub.get(this);

        loadChannels();
        loadFavorites();   // after the channels: favorites saved by older versions are matched to a list entry

        // Live TV buffering, tuned for a fast channel start: playback begins as soon as 25 ms of media are queued
        // (a tiny safety margin over "instantly"; the first keyframe is the real floor), keeps 2-10 s buffered, and
        // after an underrun waits 1 s so a short stall does not turn into a stutter loop. Time (not bytes) decides,
        // with a byte cap so a high-bitrate HD service cannot grow the heap.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 10000, 1500, 3000)
                .setTargetBufferBytes(6 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        // Hardware decoders first (MPEG-2 / H.264 / H.265 / AAC / MP2 / AC-3 as far as the device supports them),
        // fall back to the next decoder (incl. software) instead of failing, and keep frames flowing on late decode.
        // FFmpeg renderers (NextLib) are added after MediaCodec: used when the phone has no decoder for a stream
        // or the hardware decoder fails. Switch to EXTENSION_RENDERER_MODE_PREFER to try FFmpeg first.
        boolean hwFirst = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HW_DECODER, false);
        DefaultRenderersFactory renderers = new NextRenderersFactory(this)
                .setExtensionRendererMode(hwFirst ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(playerListener);
        applyAudioLanguagePref();

        // Data broadcast engine: created once, survives rotation (the page is cached, see BmlController).
        bml = new BmlController(this, null, new BmlController.Host() {
            @Override
            public void onVideoRect(float l, float t, float r, float b, boolean invisible) {
                bmlVideoFrac = (invisible || (l <= 0.001f && t <= 0.001f && r >= 0.999f && b >= 0.999f))
                        ? null : new float[] {l, t, r, b};
                applyBmlVideoRect();
            }

            @Override
            public void onStatus(String text) {
                if (text.startsWith("BML:") || text.contains("waiting") || text.contains("unavailable")
                        || text.contains("not supported") || text.contains("stopped"))
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClosed() {
                bmlVideoFrac = null;
                applyBmlVideoRect();
                updateBmlButton();
            }
        });

        layoutKey = layoutKeyOf(getResources().getConfiguration());
        bindLayout();

        currentChannelEntry = pickStartChannel();
        showCurrentChannel();

        initTuner();
    }

    /** 0/1 = portrait/landscape, +2 on a tablet-sized screen: the three layout sets we ship. */
    private static int layoutKeyOf(Configuration c) {
        return (c.orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 0) | (c.smallestScreenWidthDp >= 600 ? 2 : 0);
    }

    /**
     * The manifest keeps the activity alive on rotation (a recreate would restart the tuner), so Android does NOT
     * pick layout-land by itself: inflate the right layout here. Player, tuner and the cached BML page are untouched.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int key = layoutKeyOf(newConfig);
        if (key == layoutKey) return;       // keyboard / navigation / density change: nothing to redo
        layoutKey = key;
        ui.removeCallbacks(hideHudRunnable);
        ui.removeCallbacks(hideSidePanelRunnable);
        if (playerView != null) playerView.setPlayer(null);   // release the old surface before the views go
        bindLayout();
        showCurrentChannel();
    }

    /** Inflates the layout for the current orientation and wires every view in it (used by onCreate and rotation). */
    private void bindLayout() {
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setOverflowIcon(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_more_vert));
        } else if (getSupportActionBar() != null) {
            setSupportActionBar(null);      // landscape has no toolbar: drop the one of the portrait layout
        }

        playerView = findViewById(R.id.player_view);
        tvCurrentChannel = findViewById(R.id.tv_current_channel);
        tabLayout = findViewById(R.id.tab_layout);
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && tabLayout != null) {
                tabLayout.clearFocus();
            }
        });
        recyclerView.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                child.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && tabLayout != null) {
                        tabLayout.clearFocus();
                    }
                });
            }
            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });

        sidePanel = findViewById(R.id.side_panel);
        infoBanner = findViewById(R.id.info_banner);
        hudIvLogo = findViewById(R.id.hud_iv_logo);
        hudTvVirtual = findViewById(R.id.hud_tv_virtual);
        hudTvChannelName = findViewById(R.id.hud_tv_channel_name);
        hudTvNetworkName = findViewById(R.id.hud_tv_network_name);
        hudTvNowEvent = findViewById(R.id.hud_tv_now_event);
        hudTvNextEvent = findViewById(R.id.hud_tv_next_event);
        btnOpenSidePanel = findViewById(R.id.btn_open_side_panel);

        if (btnOpenSidePanel != null) {
            btnOpenSidePanel.setOnClickListener(v -> toggleSidePanel());
        }

        // Data broadcast container: in both layouts (portrait: inside the 16:9 video frame).
        bmlFrame = findViewById(R.id.bml_frame);
        bmlVideoFrac = null;
        if (bmlFrame != null) {
            bmlFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (bmlVideoFrac != null && (r - l != or - ol || b - t != ob - ot)) applyBmlVideoRect();
            });
        }
        bindRemote();
        bml.attachTo(bmlFrame);             // moves the cached page into the new layout's frame

        hideSystemBars();

        AspectRatioFrameLayout frame = findViewById(R.id.player_frame);
        if (frame != null) {
            frame.setAspectRatio(16f / 9f);   // fixed 16:9 display area in portrait
        }

        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(false);
        // Default: fill the whole 16:9 area (4:3 channels are stretched). Change it in the Settings tab.
        applyAspectMode(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, 0), false);
        playerView.setOnClickListener(v -> {
            if (sidePanel != null) {
                if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                    hideInfoBanner();
                } else {
                    showInfoBanner(6000);
                }
            }
            // Portrait: tapping the video does nothing (aspect mode is changed from the Settings tab only).
        });
        playerView.setPlayer(player);
        if (captionOverlay == null) {
            captionOverlay = new CaptionOverlay(playerView);
            captionOverlay.setEnabled(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_CC, true));
        } else {
            captionOverlay.attachTo(playerView);
        }
        if (captionView != null) captionView.attachTo(playerView);
        else applyCaptionStyle();

        tabLayout.addTab(tabLayout.newTab().setText("Channels"));
        tabLayout.addTab(tabLayout.newTab().setText("EPG"));
        tabLayout.addTab(tabLayout.newTab().setText("Favorites"));
        tabLayout.addTab(tabLayout.newTab().setText("Settings"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                updateContentAdapter();
                schedulePanelAutoHide();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        TabLayout.Tab wanted = tabLayout.getTabAt(currentTab);
        if (wanted != null && !wanted.isSelected()) wanted.select();

        updateContentAdapter();
        invalidateOptionsMenu();            // portrait toolbar menu (re)built
        updateBmlButton();
    }

    // ------------------------------------------------------------------ data broadcast (BML), landscape

    /** The tuned service as the demux sees it now (saved channels are stubs without streams). */
    private ServiceInfo liveService(TsDemux d) {
        if (d == null || currentChannelEntry == null) return null;
        int sid = currentChannelEntry.service.serviceId;
        for (ServiceInfo s : d.getServices()) {
            if (s.serviceId == sid && s.pmtSeen) return s;
        }
        return null;
    }

    /** DATA: show the data broadcast, or hide it again. A hidden page stays cached, so showing it again is instant. */
    private void toggleBml() {
        if (bml == null || bmlFrame == null) return;
        if (bml.isVisible()) {
            bml.hide();
            updateBmlButton();
            return;
        }
        TsDemux d = hub != null ? hub.demux() : null;
        ServiceInfo svc = liveService(d);
        if (svc == null) {
            Toast.makeText(this, "Tune to a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasData = false;
        for (ph.opendtv.ts.StreamInfo st : svc.streams) hasData |= st.isDatacast();
        if (!hasData) {
            Toast.makeText(this, "This channel has no data broadcast", Toast.LENGTH_SHORT).show();
            return;
        }
        hideSidePanel();
        ui.removeCallbacks(hideHudRunnable);
        hideInfoBanner();
        if (bml.open(d, svc)) bml.tap(BmlController.DATA);   // brand-new page: ask it to show itself once loaded
        updateBmlButton();
    }

    /** Portrait remote: the DATA key lights up while the data broadcast is on screen. */
    private void updateBmlButton() {
        View d = findViewById(R.id.rm_data);
        if (d != null) d.setAlpha(bml != null && bml.isVisible() ? 1f : 0.7f);
    }

    /** Wires the portrait remote (floating button + panel). Absent in the landscape layouts. */
    private void bindRemote() {
        fabRemote = findViewById(R.id.fab_remote);
        remotePanel = findViewById(R.id.remote_panel);
        if (fabRemote == null || remotePanel == null) {
            fabRemote = null;
            remotePanel = null;
            return;
        }
        setRemotePanelOpen(remotePanelOpen);
        fabRemote.setOnClickListener(v -> setRemotePanelOpen(!remotePanelOpen));
        remoteKey(R.id.rm_up, BmlController.UP);
        remoteKey(R.id.rm_down, BmlController.DOWN);
        remoteKey(R.id.rm_left, BmlController.LEFT);
        remoteKey(R.id.rm_right, BmlController.RIGHT);
        remoteKey(R.id.rm_ok, BmlController.ENTER);
        remoteKey(R.id.rm_back, BmlController.BACK);
        remoteKey(R.id.rm_blue, BmlController.BLUE);
        remoteKey(R.id.rm_red, BmlController.RED);
        remoteKey(R.id.rm_green, BmlController.GREEN);
        remoteKey(R.id.rm_yellow, BmlController.YELLOW);
        View data = findViewById(R.id.rm_data);
        if (data != null) data.setOnClickListener(v -> toggleBml());
    }

    private void remoteKey(int viewId, final int code) {
        View b = findViewById(viewId);
        if (b == null) return;
        b.setOnClickListener(v -> {
            if (bml == null || !bml.isVisible()) {
                Toast.makeText(this, "Press DATA to open the data broadcast first", Toast.LENGTH_SHORT).show();
                return;
            }
            bml.tap(code);
        });
    }

    private void setRemotePanelOpen(boolean open) {
        remotePanelOpen = open;
        if (remotePanel == null || fabRemote == null) return;
        remotePanel.setVisibility(open ? View.VISIBLE : View.GONE);
        ((com.google.android.material.floatingactionbutton.FloatingActionButton) fabRemote)
                .setImageResource(open ? R.drawable.ic_close : R.drawable.ic_remote);
        updateBmlButton();
    }

    /** BML moves / shrinks the video: place the PlayerView inside the landscape root accordingly. */
    private void applyBmlVideoRect() {
        if (playerView == null || bmlFrame == null) return;
        int w = bmlFrame.getWidth(), h = bmlFrame.getHeight();
        FrameLayout.LayoutParams lp;
        if (bmlVideoFrac == null || w == 0 || h == 0) {
            lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            lp = new FrameLayout.LayoutParams(Math.max(1, Math.round((bmlVideoFrac[2] - bmlVideoFrac[0]) * w)),
                    Math.max(1, Math.round((bmlVideoFrac[3] - bmlVideoFrac[1]) * h)));
            lp.leftMargin = Math.round(bmlVideoFrac[0] * w);
            lp.topMargin = Math.round(bmlVideoFrac[1] * h);
        }
        playerView.setLayoutParams(lp);
    }

    /**
     * Keys (landscape, or a keyboard / remote in portrait). DATA (remote DATA / teletext key, or F5) always toggles the data broadcast; while it is on
     * screen the keys below go to the page instead of the TV UI:
     * arrows or WASD = move, Enter / OK = select, F6 / Back = BACK, F1-F4 / colour keys = red green yellow blue,
     * 0-9 and numpad 0-9 = number keys, Esc = close.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (bml != null && bmlFrame != null && handleBmlKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private boolean handleBmlKey(android.view.KeyEvent e) {
        int kc = e.getKeyCode();
        int scan = e.getScanCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0) {
            // diagnostics: adb logcat -s OpenDTV-Keys   (shows what the keyboard / remote really sends)
            Log.d("OpenDTV-Keys", "key=" + android.view.KeyEvent.keyCodeToString(kc) + " (" + kc + ") scan=" + scan
                    + " dev=" + e.getDeviceId() + " bmlVisible=" + bml.isVisible());
        }
        if (kc == android.view.KeyEvent.KEYCODE_F5
                || scan == 63                                                // Linux KEY_F5, whatever keycode the layout maps it to
                || kc == android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE      // the DATA button of a TV remote
                || kc == android.view.KeyEvent.KEYCODE_TV_TELETEXT) {       // remotes without it: the TEXT button
            if (down && e.getRepeatCount() == 0) toggleBml();
            return true;
        }
        if (!bml.isVisible()) return false;
        if (kc == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (down && e.getRepeatCount() == 0) toggleBml();
            return true;
        }
        int code = bmlCodeFor(kc);
        if (code < 0 && scan == 64) code = BmlController.BACK;               // Linux KEY_F6
        if (code < 0) return false;
        boolean arrow = code >= BmlController.UP && code <= BmlController.RIGHT;
        if (down && (e.getRepeatCount() == 0 || arrow)) bml.tap(code);   // holding an arrow keeps moving
        return true;
    }

    private static int bmlCodeFor(int kc) {
        switch (kc) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
            case android.view.KeyEvent.KEYCODE_W: return BmlController.UP;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
            case android.view.KeyEvent.KEYCODE_S: return BmlController.DOWN;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
            case android.view.KeyEvent.KEYCODE_A: return BmlController.LEFT;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
            case android.view.KeyEvent.KEYCODE_D: return BmlController.RIGHT;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER: return BmlController.ENTER;
            case android.view.KeyEvent.KEYCODE_BACK:
            case android.view.KeyEvent.KEYCODE_DEL:                          // Backspace
            case android.view.KeyEvent.KEYCODE_F6: return BmlController.BACK;
            case android.view.KeyEvent.KEYCODE_F1:
            case android.view.KeyEvent.KEYCODE_PROG_RED: return BmlController.RED;
            case android.view.KeyEvent.KEYCODE_F2:
            case android.view.KeyEvent.KEYCODE_PROG_GREEN: return BmlController.GREEN;
            case android.view.KeyEvent.KEYCODE_F3:
            case android.view.KeyEvent.KEYCODE_PROG_YELLOW: return BmlController.YELLOW;
            case android.view.KeyEvent.KEYCODE_F4:
            case android.view.KeyEvent.KEYCODE_PROG_BLUE: return BmlController.BLUE;
            default:
        }
        if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9)
            return BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_0);
        if (kc >= android.view.KeyEvent.KEYCODE_NUMPAD_0 && kc <= android.view.KeyEvent.KEYCODE_NUMPAD_9)
            return BmlController.DIGIT0 + (kc - android.view.KeyEvent.KEYCODE_NUMPAD_0);
        return -1;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // a hidden (cached) data broadcast page is the first thing to give back when memory gets low
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && bml != null) bml.trimMemory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        recordMenuItem = menu.findItem(R.id.action_record);
        MenuItem hw = menu.findItem(R.id.action_hardware_decoder);
        if (hw != null) hw.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HW_DECODER, false));
        MenuItem cc = menu.findItem(R.id.action_captions);
        captionsMenuItem = cc;
        if (cc != null) cc.setChecked(captionOverlay != null && captionOverlay.isEnabled());
        if (currentPacketSink != null && currentPacketSink.isRecording()) {
            if (recordMenuItem != null) recordMenuItem.setTitle("Stop Recording");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_scan) {
            startFullScan();
            return true;
        } else if (id == R.id.action_manual_scan) {
            showManualScanDialog();
            return true;
        } else if (id == R.id.action_licenses) {
            startActivity(new Intent(this, LicensesActivity.class));
            return true;
        } else if (id == R.id.action_analyzer) {
            openAnalyzer();
            return true;
        } else if (id == R.id.action_record) {
            toggleChannelRecording();
            return true;
        } else if (id == R.id.action_captions) {
            setCaptionsEnabled(!item.isChecked());
            return true;
        } else if (id == R.id.action_hardware_decoder) {
            boolean hw = !item.isChecked();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HW_DECODER, hw).apply();
            item.setChecked(hw);
            Toast.makeText(this, hw ? "Hardware decoder selected" : "FFmpeg software decoder selected", Toast.LENGTH_SHORT).show();
            recreate();   // the player is built in onCreate
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ closed captions / audio language

    private int captionStyle() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CC_STYLE, 0) == 1 ? 1 : 0;
    }

    /** Creates / frees the styled caption WebView to match the Settings choice, and routes the captions to it. */
    private void applyCaptionStyle() {
        boolean styled = captionStyle() == 1;
        if (styled && captionView == null) {
            captionView = new CaptionView(playerView);
            captionView.setEnabled(captionOverlay == null || captionOverlay.isEnabled());
            captionView.setLanguage(captionLanguage());
        } else if (!styled && captionView != null) {
            captionView.destroy();
            captionView = null;
        }
        bindCaptionSink();
    }

    /** Sends the channel's captions to whichever display is selected. */
    private void bindCaptionSink() {
        if (currentPacketSink == null) return;
        currentPacketSink.setCaptionSink(captionView != null ? null : captionOverlay);
        currentPacketSink.setRawCaptionSink(captionView);
        currentPacketSink.setCaptionLanguage(captionLanguage());
    }

    private void resetCaptions() {
        if (captionOverlay != null) captionOverlay.reset();
        if (captionView != null) captionView.clear();
    }

    private void cycleCaptionStyle() {
        int next = captionStyle() == 1 ? 0 : 1;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_CC_STYLE, next).apply();
        resetCaptions();
        applyCaptionStyle();
        Toast.makeText(this, next == 1 ? "Caption display: styled (broadcast layout)" : "Caption display: simple text line",
                Toast.LENGTH_SHORT).show();
        if (currentTab == 3) updateContentAdapter();
    }

    private void setCaptionsEnabled(boolean on) {
        captionOverlay.setEnabled(on);
        if (captionView != null) captionView.setEnabled(on);
        if (captionsMenuItem != null) captionsMenuItem.setChecked(on);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CC, on).apply();
        Toast.makeText(this, on ? "Closed captions ON (shown when the channel broadcasts them)" : "Closed captions OFF",
                Toast.LENGTH_SHORT).show();
        if (currentTab == 3) updateContentAdapter();
    }

    private int captionLanguage() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CC_LANG, 1) == 2 ? 2 : 1;
    }

    private void cycleCaptionLanguage() {
        int next = captionLanguage() == 1 ? 2 : 1;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_CC_LANG, next).apply();
        if (currentPacketSink != null) currentPacketSink.setCaptionLanguage(next);
        if (captionView != null) captionView.setLanguage(next);
        resetCaptions();
        Toast.makeText(this, next == 1 ? "Caption language: 1 (main)" : "Caption language: 2 (second language, if broadcast)",
                Toast.LENGTH_SHORT).show();
        if (currentTab == 3) updateContentAdapter();
    }

    /** "English", "Filipino", ... from an ISO 639 code; the upper-cased code when Android does not know it. */
    private static String languageName(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return null;
        try {
            String n = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault());
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(code)) return n;
        } catch (RuntimeException ignored) { }
        return code.toUpperCase(Locale.ROOT);
    }

    /** ISO 639 language the user picked ("" = automatic), shown in the Settings tab. */
    private String audioLanguageSummary() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "");
        String n = languageName(code);
        return n == null ? "Automatic (best quality)" : n;
    }

    /** Applies the saved audio language preference to the player (it picks the matching track when a channel starts). */
    private void applyAudioLanguagePref() {
        String code = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "");
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(code.isEmpty() ? null : code)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build());
    }

    /** One audio track the channel offers (an elementary stream of the current service). */
    private String audioTrackLabel(int index, androidx.media3.common.Format f) {
        String lang = languageName(f.language);
        if (lang == null && currentChannelEntry != null && currentChannelEntry.service != null && f.id != null) {
            // Format id is "<program>/<pid>": fall back to the language our own PMT parser found for that PID
            try {
                String idPart = f.id.contains("/") ? f.id.substring(f.id.lastIndexOf('/') + 1) : f.id;
                int pid = Integer.parseInt(idPart);
                ServiceInfo live = demux != null ? findLiveService(currentChannelEntry.service.serviceId) : null;
                if (live != null) for (StreamInfo st : live.streams) if (st.pid == pid) { lang = languageName(st.language); break; }
            } catch (RuntimeException ignored) { }
        }
        StringBuilder sb = new StringBuilder(lang != null ? lang : "Audio " + (index + 1));
        String codec = f.sampleMimeType == null ? null
                : f.sampleMimeType.contains("mp4a") ? "AAC" : f.sampleMimeType.contains("ac3") ? "AC-3"
                : f.sampleMimeType.contains("mpeg") ? "MP2" : null;
        if (codec != null || f.channelCount > 0) {
            sb.append("  (");
            if (codec != null) sb.append(codec);
            if (f.channelCount > 0) sb.append(codec != null ? " " : "").append(f.channelCount).append("ch");
            sb.append(')');
        }
        return sb.toString();
    }

    private ServiceInfo findLiveService(int sid) {
        try {
            for (ServiceInfo si : demux.getServices()) if (si.serviceId == sid) return si;
        } catch (RuntimeException ignored) { }
        return null;
    }

    /**
     * Automatic audio choice = the best quality track of the channel: most channels, then highest sample rate,
     * then highest bitrate (when the stream says), then codec (AAC > AC-3 > MPEG audio). Skipped when the user picked a
     * language / track in Settings (then the saved language or the override decides).
     */
    private androidx.media3.common.TrackGroup autoAudioGroup;   // the group pickBestAudioTrack chose, not a manual pick

    private void pickBestAudioTrack(androidx.media3.common.Tracks tracks) {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUDIO_LANG, "").isEmpty()) return;
        androidx.media3.common.TrackSelectionParameters tsp = player.getTrackSelectionParameters();
        androidx.media3.common.Tracks.Group best = null, selected = null;
        int audioGroups = 0;
        for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0 || !g.isTrackSupported(0)) continue;
            audioGroups++;
            if (g.isTrackSelected(0)) selected = g;
            if (tsp.overrides.containsKey(g.getMediaTrackGroup()) && g.getMediaTrackGroup() != autoAudioGroup) return;   // a manual pick
            if (best == null || audioQuality(g.getTrackFormat(0)) > audioQuality(best.getTrackFormat(0))) best = g;
        }
        if (audioGroups < 2 || best == null || best == selected) return;
        autoAudioGroup = best.getMediaTrackGroup();
        player.setTrackSelectionParameters(tsp.buildUpon()
                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(best.getMediaTrackGroup(), 0))
                .build());
    }

    private static long audioQuality(androidx.media3.common.Format f) {
        long bitrate = f.bitrate > 0 ? Math.min(f.bitrate / 1000, 4095) : 0;       // kbit/s
        long rate = f.sampleRate > 0 ? Math.min(f.sampleRate / 1000, 255) : 0;      // kHz
        long ch = f.channelCount > 0 ? Math.min(f.channelCount, 15) : 0;
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        long codec = m.contains("mp4a") ? 3 : m.contains("ac3") ? 2 : m.contains("mpeg") ? 1 : 0;
        return (ch << 40) | (rate << 28) | (bitrate << 8) | codec;
    }

    /** Settings > Audio Language: lists the audio streams of the playing channel and switches between them. */
    private void showAudioLanguageDialog() {
        final List<androidx.media3.common.Tracks.Group> groups = new ArrayList<>();
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO && g.length > 0) groups.add(g);
        }
        if (groups.isEmpty()) {
            Toast.makeText(this, "No audio tracks yet - play a channel first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[groups.size() + 1];
        labels[0] = "Automatic (best quality)";
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            androidx.media3.common.Tracks.Group g = groups.get(i);
            labels[i + 1] = audioTrackLabel(i, g.getTrackFormat(0)) + (g.isTrackSupported(0) ? "" : "  [not supported]");
            if (g.isTrackSelected(0) && g.getMediaTrackGroup() != autoAudioGroup
                    && player.getTrackSelectionParameters().overrides.containsKey(g.getMediaTrackGroup())) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Audio language")
                .setSingleChoiceItems(labels, checked, (dlg, which) -> {
                    android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                    if (which == 0) {
                        ed.putString(PREF_AUDIO_LANG, "");
                        b.setPreferredAudioLanguage(null).clearOverridesOfType(C.TRACK_TYPE_AUDIO);
                    } else {
                        androidx.media3.common.Tracks.Group g = groups.get(which - 1);
                        String lang = g.getTrackFormat(0).language;
                        boolean known = lang != null && !lang.isEmpty() && !lang.equals("und");
                        // remember the language (so it is picked again on other channels), the override switches now
                        ed.putString(PREF_AUDIO_LANG, known ? lang : "");
                        b.setPreferredAudioLanguage(known ? lang : null)
                                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(g.getMediaTrackGroup(), 0));
                    }
                    ed.apply();
                    player.setTrackSelectionParameters(b.build());
                    dlg.dismiss();
                    if (currentTab == 3) updateContentAdapter();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleChannelRecording() {
        if (currentPacketSink == null) {
            Toast.makeText(this, "No channel playing to record", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!currentPacketSink.isRecording()) {
            if (!checkAndRequestStoragePermission(this)) return;
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "OpenDTV/Record");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String chNum = (currentChannelEntry != null && currentChannelEntry.service != null) ? currentChannelEntry.service.virtualNumber : "0";
                String chName = (currentChannelEntry != null && currentChannelEntry.service != null) ? currentChannelEntry.service.name : "Channel";
                chNum = chNum.replaceAll("[^a-zA-Z0-9_-]", "_");
                chName = chName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String timeStamp = new SimpleDateFormat("MMddyyyy-HHmmss", Locale.US).format(new Date());
                String fileName = chNum + "-" + chName + "-" + timeStamp + ".ts";
                File file = new File(dir, fileName);
                currentPacketSink.startRecord(file);
                if (recordMenuItem != null) recordMenuItem.setTitle("Stop Recording");
                Toast.makeText(this, "Started recording: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                Toast.makeText(this, "Failed to start recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            currentPacketSink.stopRecord();
            if (recordMenuItem != null) recordMenuItem.setTitle("Record Channel");
            Toast.makeText(this, "Stopped recording. Saved in OpenDTV/Record", Toast.LENGTH_SHORT).show();
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

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            retries = 0;
        }

        @Override
        public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            pickBestAudioTrack(tracks);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "player error: " + error.getErrorCodeName() + " " + error.getMessage());
            final ChannelEntry e = currentChannelEntry;
            if (e != null && !isFinishing() && retries++ < MAX_RETRIES) {
                // Same multiplex: no retune, just a fresh filter + prepare.
                ui.postDelayed(() -> {
                    if (!isFinishing() && !scanning && currentChannelEntry == e) tuneAndPlay(e);
                }, 1000);
            } else {
                Toast.makeText(MainActivity.this, "Cannot play this channel (" + error.getErrorCodeName() + ")",
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        // Came back from the background / Analyzer: resume the channel (same multiplex = instant).
        if (tunerReady && currentChannelEntry != null && !scanning) {
            retries = 0;
            tuneAndPlay(currentChannelEntry);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Nothing to watch in the background: stop decoding and stop feeding the pipe (CPU, battery, heap).
        playGen.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        resetCaptions();
        if (bml != null) bml.close();        // background: no data-broadcast page kept alive unseen
    }

    /**
     * The Analyzer opens the USB dongle on its own (not through TunerHub). Two owners of one device break each
     * other, so release the tuner completely first; onResume re-initialises it when we come back.
     */
    private void openAnalyzer() {
        playGen.incrementAndGet();
        tunerReady = false;
        ui.removeCallbacksAndMessages(null);
        player.stop();
        player.clearMediaItems();
        hub.detachSink(this);
        pipe.clear();
        try {
            worker.execute(() -> {
                hub.shutdown();   // blocks until the dongle is closed (off the UI thread)
                ui.post(() -> {
                    if (!isFinishing()) startActivity(new Intent(MainActivity.this, AnalyzerActivity.class));
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // USB_DEVICE_ATTACHED re-delivers to this singleTop activity
        if (!tunerReady) initTuner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ewbsTick);
        ui.post(ewbsTick);
        hideSystemBars();
        if (!tunerReady && !scanning) initTuner();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ewbsTick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        if (sidePanel != null || getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().hide();
            }
        } else {
            // portrait (also after rotating back from landscape): normal status bar + toolbar
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (getSupportActionBar() != null) {
                getSupportActionBar().show();
            }
        }
    }

    private static String favoriteKey(ChannelEntry ce) {
        return ce.khz + ":" + ce.service.serviceId;
    }

    /** The list entry a row shows: the adapters are given each entry's own ServiceInfo. */
    private ChannelEntry entryFor(ServiceInfo service) {
        for (ChannelEntry ce : allChannels) {
            if (ce.service == service) return ce;
        }
        return null;
    }

    /** Stars in the channel lists, stored per list entry (frequency + service ID). */
    private final ph.opendtv.ui.ChannelAdapter.Favorites favoriteStars = new ph.opendtv.ui.ChannelAdapter.Favorites() {
        @Override
        public boolean contains(ServiceInfo service) {
            ChannelEntry ce = entryFor(service);
            return ce != null && favorites.contains(favoriteKey(ce));
        }

        @Override
        public boolean toggle(ServiceInfo service) {
            ChannelEntry ce = entryFor(service);
            if (ce == null) return false;
            String key = favoriteKey(ce);
            if (favorites.remove(key)) return false;
            favorites.add(key);
            return true;
        }
    };

    private void loadFavorites() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> favs = sp.getStringSet(PREF_FAV, new HashSet<>());
        favorites.clear();
        boolean upgraded = false;
        for (String s : favs) {
            if (s.indexOf(':') > 0) {
                favorites.add(s);
                continue;
            }
            // Saved by an older version as the service ID alone. Those versions listed only the first channel with a
            // given ID (issue #1), so the star belongs to the first list entry with that ID.
            upgraded = true;
            try {
                int sid = Integer.parseInt(s);
                for (ChannelEntry ce : allChannels) {
                    if (ce.service.serviceId == sid) {
                        favorites.add(favoriteKey(ce));
                        break;
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
        if (upgraded) saveFavorites();
    }

    private void saveFavorites() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(PREF_FAV, new HashSet<>(favorites)).apply();
    }

    private void saveChannels() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder();
        for (ChannelEntry ce : allChannels) {
            sb.append(ce.khz).append('|')
                    .append(ce.service.serviceId).append('|')
                    .append(ce.service.name).append('|')
                    .append(ce.service.provider).append('|')
                    .append(ce.service.virtualNumber).append('\n');
        }
        editor.putString(PREF_CHANNELS, sb.toString()).apply();
    }

    private void loadChannels() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString(PREF_CHANNELS, "");
        allChannels.clear();
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 5) continue;
            try {
                int khz = Integer.parseInt(p[0]);
                int sid = Integer.parseInt(p[1]);
                ServiceInfo svc = new ServiceInfo(sid);
                svc.name = p[2];
                svc.provider = p[3];
                svc.virtualNumber = p[4];
                svc.pmtSeen = true;
                allChannels.add(new ChannelEntry(khz, svc));
            } catch (Exception ignored) {}
        }
    }

    /** Last tuned channel if it is still in the list, otherwise the first one. */
    private ChannelEntry pickStartChannel() {
        if (allChannels.isEmpty()) return null;
        int sid = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_SID, -1);
        int khz = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_KHZ, -1);
        for (ChannelEntry ce : allChannels) {
            if (ce.service.serviceId == sid && (khz < 0 || ce.khz == khz)) return ce;
        }
        return allChannels.get(0);
    }

    /** 0 = Stretch (fill 16:9), 1 = Fit (keeps ratio, black bars), 2 = Zoom (keeps ratio, crops). */
    private void applyAspectMode(int mode, boolean announce) {
        int rm;
        String name;
        switch (mode) {
            case 1: rm = AspectRatioFrameLayout.RESIZE_MODE_FIT; name = "Fit (original ratio)"; break;
            case 2: rm = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; name = "Zoom (crop to 16:9)"; break;
            default: mode = 0; rm = AspectRatioFrameLayout.RESIZE_MODE_FILL; name = "Stretch (full 16:9)";
        }
        playerView.setResizeMode(rm);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ASPECT, mode).apply();
        if (announce) Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
    }

    private void saveLastChannel(ChannelEntry e) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_LAST_SID, e.service.serviceId)
                .putInt(PREF_LAST_KHZ, e.khz).apply();
    }

    private void showCurrentChannel() {
        if (currentChannelEntry == null) {
            if (tvCurrentChannel != null) tvCurrentChannel.setText("No channel tuned");
            return;
        }
        ServiceInfo svc = currentChannelEntry.service;
        if (tvCurrentChannel != null) {
            tvCurrentChannel.setText(svc.virtualNumber + "  " + (svc.name.isEmpty() ? "Service " + svc.serviceId : svc.name));
        }
        showInfoBanner();
    }

    /** Finds the tuner, gets USB permission, takes ownership, then starts the (last) channel automatically. */
    private void initTuner() {
        if (initializing) return;
        if (!hub.isTunerPresent()) {
            tunerReady = false;
            return; // onResume / USB-attach will retry
        }
        initializing = true;
        new Thread(() -> {
            boolean ok = hub.ensurePermission(60_000);
            if (ok) hub.acquire(MainActivity.this);
            tunerReady = ok;
            initializing = false;
            ui.post(() -> {
                if (isFinishing()) return;
                if (!ok) {
                    Toast.makeText(MainActivity.this, "USB permission required", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (currentChannelEntry != null && !scanning) tuneAndPlay(currentChannelEntry);
            });
        }, "InitTuner").start();
    }

    private void updateContentAdapter() {
        if (currentTab == 1) {
            // EPG tab
            if (currentChannelEntry == null) {
                recyclerView.setAdapter(new ph.opendtv.ui.EpgAdapter(this, new ArrayList<>(), demux.getClockMs()));
            } else {
                List<EpgEvent> events = demux.getEvents(currentChannelEntry.service.serviceId);
                recyclerView.setAdapter(new ph.opendtv.ui.EpgAdapter(this, events, demux.getClockMs()));
            }
        } else if (currentTab == 2) {
            // Favorites tab
            List<ServiceInfo> favServices = new ArrayList<>();
            List<ChannelEntry> favEntries = new ArrayList<>();
            for (ChannelEntry ce : allChannels) {
                if (favorites.contains(favoriteKey(ce))) {
                    favServices.add(ce.service);
                    favEntries.add(ce);
                }
            }
            recyclerView.setAdapter(new ph.opendtv.ui.ChannelAdapter(this, favServices, favoriteStars, new ph.opendtv.ui.ChannelAdapter.OnChannelClickListener() {
                @Override
                public void onChannelClick(ServiceInfo service) {
                    // the adapter hands back the entry's own ServiceInfo; the service ID alone can match two channels
                    for (ChannelEntry ce : favEntries) {
                        if (ce.service == service) {
                            selectAndPlayChannel(ce);
                            break;
                        }
                    }
                }
                @Override
                public void onFavoriteToggle(ServiceInfo service, boolean isFavorite) {
                    saveFavorites();
                    updateContentAdapter();
                    Toast.makeText(MainActivity.this,
                            (isFavorite ? "Added to Favorites: " : "Removed from Favorites: ") +
                                    (service.name.isEmpty() ? "Service " + service.serviceId : service.name),
                            Toast.LENGTH_SHORT).show();
                }
            }));
        } else if (currentTab == 3) {
            // Settings tab
            List<ph.opendtv.ui.SettingAdapter.SettingItem> items = new ArrayList<>();
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Auto Channel Scan",
                    "Perform full UHF band scan for channels",
                    R.drawable.ic_scan,
                    this::startFullScan));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Manual Frequency Scan",
                    "Scan specific UHF channel frequency with signal meter",
                    R.drawable.ic_tune,
                    this::showManualScanDialog));
            boolean isRecording = currentPacketSink != null && currentPacketSink.isRecording();
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    isRecording ? "Stop Channel Recording" : "Record Channel",
                    isRecording ? "Stop saving transport stream to storage" : "Save TS stream to OpenDTV/Record folder",
                    R.drawable.ic_more_vert,
                    this::toggleChannelRecording));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Signal & TS Analyzer",
                    "Inspect bitrates, PIDs, tables, and raw packets",
                    R.drawable.ic_analytics,
                    this::openAnalyzer));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Video Aspect Ratio",
                    "Cycle display ratio (Stretch 16:9 / Fit / Zoom)",
                    android.R.drawable.ic_menu_crop,
                    () -> {
                        int next = (getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_ASPECT, 0) + 1) % 3;
                        applyAspectMode(next, true);
                    }));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Closed Captions (CC)",
                    captionOverlay != null && captionOverlay.isEnabled()
                            ? "ON - tap to turn off (shown when the channel broadcasts captions)"
                            : "OFF - tap to turn on",
                    R.drawable.ic_captions,
                    () -> setCaptionsEnabled(captionOverlay == null || !captionOverlay.isEnabled())));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Caption Display",
                    captionStyle() == 1
                            ? "Styled (broadcast layout, Latin / Japanese) - tap to switch to the simple text line"
                            : "Simple text line - tap to switch to styled (broadcast layout)",
                    R.drawable.ic_captions,
                    this::cycleCaptionStyle));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Caption Language",
                    captionLanguage() == 1 ? "Language 1 (main) - tap to switch to language 2"
                            : "Language 2 - tap to switch to language 1 (main)",
                    R.drawable.ic_captions,
                    this::cycleCaptionLanguage));
            items.add(new ph.opendtv.ui.SettingAdapter.SettingItem(
                    "Audio Language",
                    audioLanguageSummary() + " - choose among the channel's audio tracks",
                    R.drawable.ic_audio,
                    this::showAudioLanguageDialog));
            recyclerView.setAdapter(new ph.opendtv.ui.SettingAdapter(this, items));
        } else {
            // Channels tab
            List<ServiceInfo> services = new ArrayList<>();
            for (ChannelEntry ce : allChannels) services.add(ce.service);
            recyclerView.setAdapter(new ph.opendtv.ui.ChannelAdapter(this, services, favoriteStars, new ph.opendtv.ui.ChannelAdapter.OnChannelClickListener() {
                @Override
                public void onChannelClick(ServiceInfo service) {
                    // the adapter hands back the entry's own ServiceInfo; the service ID alone can match two channels
                    for (ChannelEntry ce : allChannels) {
                        if (ce.service == service) {
                            selectAndPlayChannel(ce);
                            break;
                        }
                    }
                }
                @Override
                public void onFavoriteToggle(ServiceInfo service, boolean isFavorite) {
                    saveFavorites();
                    updateContentAdapter();
                    Toast.makeText(MainActivity.this,
                            (isFavorite ? "Added to Favorites: " : "Removed from Favorites: ") +
                                    (service.name.isEmpty() ? "Service " + service.serviceId : service.name),
                            Toast.LENGTH_SHORT).show();
                }
            }));
        }
    }

    private void selectAndPlayChannel(ChannelEntry entry) {
        if (bml != null) bml.close();        // the cached page belongs to the old channel
        currentChannelEntry = entry;
        saveLastChannel(entry);
        showCurrentChannel();
        if (tunerReady) {
            tuneAndPlay(entry);
        } else if (!hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found. Plug in USB dongle.", Toast.LENGTH_LONG).show();
        } else {
            initTuner(); // plays currentChannelEntry once ready
        }
    }

    private void tuneAndPlay(final ChannelEntry entry) {
        final int g = playGen.incrementAndGet();
        Runnable task = () -> {
            if (g != playGen.get()) return;   // superseded while queued
            if (!hub.isTunerPresent()) {
                tunerReady = false;
                return;
            }
            hub.acquire(MainActivity.this);
            ui.post(() -> {
                if (g != playGen.get()) return;
                hub.detachSink(MainActivity.this);
                player.stop();
                player.clearMediaItems();
                pipe.clear();
            });
            boolean locked = hub.ensureTuned(entry.khz, () -> g != playGen.get());
            if (g != playGen.get()) return;
            if (!locked) {
                ui.post(() -> Toast.makeText(MainActivity.this, "Failed to lock frequency", Toast.LENGTH_SHORT).show());
                return;
            }
            // Saved channels are stubs: take the live service (PMT PID, streams) from the demux.
            final ServiceInfo live = waitForService(entry.service.serviceId, g);
            if (live == null) {
                if (g == playGen.get())
                    ui.post(() -> Toast.makeText(MainActivity.this, "Channel not found on this frequency", Toast.LENGTH_SHORT).show());
                return;
            }
            final TsDemux d = hub.demux();
            ui.post(() -> {
                if (g != playGen.get() || isFinishing()) return;
                demux = d;
                pipe.clear();
                if (currentPacketSink != null && currentPacketSink.isRecording()) {
                    currentPacketSink.stopRecord();
                    if (recordMenuItem != null) recordMenuItem.setTitle("Record Channel");
                    Toast.makeText(MainActivity.this, "Recording stopped due to channel change", Toast.LENGTH_SHORT).show();
                }
                currentPacketSink = new RecordingPacketSink(pipe, d.getTsId(), live);
                resetCaptions();
                bindCaptionSink();
                applyAudioLanguagePref();
                d.setPacketSink(currentPacketSink);

                MediaSource ms = mediaSourceFactory.createMediaSource(MediaItem.fromUri("tspipe://live"));
                player.setMediaSource(ms);
                player.prepare();
                player.setPlayWhenReady(true);
                armBufferingWatchdog(entry, g);
                if (currentTab == 1) updateContentAdapter();
                showInfoBanner();
            });
        };
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // activity is being destroyed
        }
    }

    /** If nothing is rendered 12 s after prepare (endless "loading"), rebuild the filter + player once or twice. */
    private void armBufferingWatchdog(final ChannelEntry entry, final int g) {
        ui.postDelayed(() -> {
            if (g != playGen.get() || isFinishing() || scanning || currentChannelEntry != entry) return;
            if (player.getPlaybackState() == Player.STATE_READY) return;
            Log.w(TAG, "still buffering after 12 s (state " + player.getPlaybackState() + "), retry " + retries);
            if (retries++ < MAX_RETRIES) tuneAndPlay(entry);
            else Toast.makeText(this, "No playable video on this channel (signal or stream problem)", Toast.LENGTH_SHORT).show();
        }, 12_000);
    }

    /** Waits up to 12 s for PAT/PMT of the wanted service to be parsed. */
    private ServiceInfo waitForService(int sid, int g) {
        long start = System.currentTimeMillis();
        while (g == playGen.get() && System.currentTimeMillis() - start < 12_000) {
            for (ServiceInfo s : hub.demux().getServices()) {
                if (s.serviceId == sid && s.pmtSeen && s.hasVideo()) return s;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { return null; }   // poll fast: PMT usually arrives within ~100 ms
        }
        return null;
    }

    private void updateSideMenuButtonPosition() {
        if (btnOpenSidePanel == null) return;
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
            btnOpenSidePanel.setVisibility(View.VISIBLE);
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_right);
            int sideWidthPx = sidePanel.getWidth();
            if (sideWidthPx <= 0) {
                float density = getResources().getDisplayMetrics().density;
                sideWidthPx = (int) ((getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 420 : 380) * density);
            }
            int marginEndPx = sideWidthPx + (int) (18 * getResources().getDisplayMetrics().density);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
        } else {
            btnOpenSidePanel.setImageResource(R.drawable.ic_chevron_left);
            int marginEndPx = (int) (8 * getResources().getDisplayMetrics().density);
            if (btnOpenSidePanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) btnOpenSidePanel.getLayoutParams();
                params.setMarginEnd(marginEndPx);
                btnOpenSidePanel.setLayoutParams(params);
            }
            if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                btnOpenSidePanel.setVisibility(View.VISIBLE);
            } else {
                btnOpenSidePanel.setVisibility(View.GONE);
            }
        }
    }

    private void toggleSidePanel() {
        if (sidePanel == null) return;
        if (sidePanel.getVisibility() == View.VISIBLE) {
            hideSidePanel();
        } else {
            showSidePanel();
        }
    }

    private void showSidePanel() {
        if (sidePanel != null) {
            sidePanel.setVisibility(View.VISIBLE);
            recyclerView.requestFocus();
            schedulePanelAutoHide();
        }
        updateSideMenuButtonPosition();
        showInfoBanner();
    }

    private void hideSidePanel() {
        if (sidePanel != null) {
            sidePanel.setVisibility(View.GONE);
            ui.removeCallbacks(hideSidePanelRunnable);
        }
        updateSideMenuButtonPosition();
    }

    private void schedulePanelAutoHide() {
        ui.removeCallbacks(hideSidePanelRunnable);
        if (sidePanel != null && sidePanel.getVisibility() == View.VISIBLE) {
            ui.postDelayed(hideSidePanelRunnable, 10000);
        }
    }

    private void showInfoBanner() {
        showInfoBanner(6000);
    }

    private void showInfoBanner(int durationMs) {
        ui.removeCallbacks(hideHudRunnable);
        if (infoBanner != null && currentChannelEntry != null) {
            ServiceInfo svc = currentChannelEntry.service;
            if (hudTvVirtual != null) hudTvVirtual.setText(svc.virtualNumber.isEmpty() ? String.valueOf(svc.serviceId) : svc.virtualNumber);
            if (hudTvChannelName != null) hudTvChannelName.setText(svc.name.isEmpty() ? "Service " + svc.serviceId : svc.name);
            if (hudTvNetworkName != null) hudTvNetworkName.setText(svc.provider.isEmpty() ? "ISDB-T Broadcast" : svc.provider);

            if (hudIvLogo != null) {
                android.graphics.drawable.Drawable logo = ph.opendtv.ui.LogoHelper.getLogo(this, svc.name, svc.provider);
                if (logo != null) {
                    hudIvLogo.setImageDrawable(logo);
                    hudIvLogo.setVisibility(View.VISIBLE);
                } else {
                    hudIvLogo.setVisibility(View.GONE);
                }
            }

            updateEpgBannerInfo(svc.serviceId);

            infoBanner.setVisibility(View.VISIBLE);
            updateSideMenuButtonPosition();
            updateBmlButton();

            if (durationMs > 0) {
                ui.postDelayed(hideHudRunnable, durationMs);
            }
        }
    }

    private void updateEpgBannerInfo(int serviceId) {
        if (hudTvNowEvent == null || hudTvNextEvent == null) return;
        List<EpgEvent> events = demux.getEvents(serviceId);
        long nowMs = demux.getClockMs() > 0 ? demux.getClockMs() : System.currentTimeMillis();

        EpgEvent nowEvent = null;
        EpgEvent nextEvent = null;

        if (events != null) {
            for (EpgEvent ev : events) {
                if (ev.startMs <= nowMs && ev.endMs() > nowMs) {
                    nowEvent = ev;
                } else if (ev.startMs > nowMs) {
                    if (nextEvent == null || ev.startMs < nextEvent.startMs) {
                        nextEvent = ev;
                    }
                }
            }
        }

        hudTvNowEvent.setText("Now: " + (nowEvent != null && !nowEvent.name.isEmpty() ? nowEvent.name : "No program info"));
        hudTvNextEvent.setText("Next: " + (nextEvent != null && !nextEvent.name.isEmpty() ? nextEvent.name : "No program info"));
    }

    private void hideInfoBanner() {
        if (infoBanner != null) {
            infoBanner.setVisibility(View.GONE);
        }
        updateSideMenuButtonPosition();
        updateBmlButton();
    }

    private void tuneNextChannel() {
        if (allChannels.isEmpty()) return;
        int currentIdx = -1;
        if (currentChannelEntry != null) {
            for (int i = 0; i < allChannels.size(); i++) {
                if (isSameEntry(allChannels.get(i), currentChannelEntry)) {
                    currentIdx = i;
                    break;
                }
            }
        }
        int nextIdx = (currentIdx + 1) % allChannels.size();
        selectAndPlayChannel(allChannels.get(nextIdx));
    }

    private void tunePreviousChannel() {
        if (allChannels.isEmpty()) return;
        int currentIdx = -1;
        if (currentChannelEntry != null) {
            for (int i = 0; i < allChannels.size(); i++) {
                if (isSameEntry(allChannels.get(i), currentChannelEntry)) {
                    currentIdx = i;
                    break;
                }
            }
        }
        int prevIdx = (currentIdx - 1 + allChannels.size()) % allChannels.size();
        selectAndPlayChannel(allChannels.get(prevIdx));
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (sidePanel != null) { // Landscape mode
            schedulePanelAutoHide();
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (sidePanel.getVisibility() == View.VISIBLE) {
                        if (tabLayout.hasFocus()) {
                            int pos = tabLayout.getSelectedTabPosition();
                            if (pos > 0) {
                                TabLayout.Tab tab = tabLayout.getTabAt(pos - 1);
                                if (tab != null) tab.select();
                            } else {
                                hideSidePanel();
                            }
                            return true;
                        }
                    } else {
                        showSidePanel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (sidePanel.getVisibility() == View.VISIBLE) {
                        if (tabLayout.hasFocus()) {
                            int pos = tabLayout.getSelectedTabPosition();
                            if (pos < tabLayout.getTabCount() - 1) {
                                TabLayout.Tab tab = tabLayout.getTabAt(pos + 1);
                                if (tab != null) tab.select();
                            }
                            return true;
                        }
                    } else {
                        showSidePanel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        tuneNextChannel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        tunePreviousChannel();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (sidePanel.getVisibility() != View.VISIBLE) {
                        showInfoBanner();
                        return true;
                    }
                    break;

                case android.view.KeyEvent.KEYCODE_BACK:
                    if (sidePanel.getVisibility() == View.VISIBLE) {
                        hideSidePanel();
                        return true;
                    } else if (infoBanner != null && infoBanner.getVisibility() == View.VISIBLE) {
                        hideInfoBanner();
                        return true;
                    }
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private AlertDialog scanDialog;
    private TextView scanStatusTv, scanFoundTv;
    private ProgressBar scanBar;

    private void showScanProgress(int max) {
        ui.post(() -> {
            if (scanDialog != null) {
                scanDialog.dismiss();
                scanDialog = null;
            }
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad);

            scanStatusTv = new TextView(this);
            scanStatusTv.setTextSize(15);
            scanStatusTv.setText("Starting Full UHF Scan ...");
            scanStatusTv.setPadding(0, 0, 0, pad / 2);

            scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            scanBar.setMax(max);
            scanBar.setProgress(0);

            scanFoundTv = new TextView(this);
            scanFoundTv.setTextSize(13);
            scanFoundTv.setPadding(0, pad / 2, 0, 0);
            scanFoundTv.setText("Found: none yet");

            box.addView(scanStatusTv);
            box.addView(scanBar);
            box.addView(scanFoundTv);

            scanDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle("Full Channel Scan")
                    .setView(box)
                    .setCancelable(false)
                    .setNegativeButton("Stop", (dialog, which) -> {
                        scanCancel = true;
                        if (scanStatusTv != null) scanStatusTv.setText("Stopping scan ...");
                    })
                    .create();
            scanDialog.show();
        });
    }

    private void updateScanProgress(String status, int done, String foundText) {
        ui.post(() -> {
            if (scanDialog == null || scanStatusTv == null) return;
            scanStatusTv.setText(status);
            if (scanBar != null) scanBar.setProgress(done);
            if (scanFoundTv != null && foundText != null) scanFoundTv.setText(foundText);
        });
    }

    private void dismissScanDialog() {
        ui.post(() -> {
            if (scanDialog != null) {
                scanDialog.dismiss();
                scanDialog = null;
            }
        });
    }

    private void showManualScanDialog() {
        if (hub == null || !hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found. Plug in USB dongle.", Toast.LENGTH_LONG).show();
            return;
        }

        Context context = this;
        android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView tvFreqLabel = new TextView(context);
        tvFreqLabel.setText("Select UHF Channel & Exact Frequency:");
        tvFreqLabel.setTextSize(14);
        tvFreqLabel.setPadding(0, 0, 0, pad / 2);
        box.addView(tvFreqLabel);

        Spinner spUhf = new Spinner(context);
        List<String> items = new ArrayList<>();
        List<Integer> khzList = new ArrayList<>();
        for (int ch = 14; ch <= 69; ch++) {
            int khz = 473143 + (ch - 14) * 6000;
            items.add(String.format(Locale.US, "UHF %d  (%.3f MHz)", ch, khz / 1000.0));
            khzList.add(khz);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUhf.setAdapter(adapter);
        box.addView(spUhf);

        TextView tvExactFreq = new TextView(context);
        tvExactFreq.setTextSize(13);
        tvExactFreq.setPadding(0, pad / 2, 0, pad / 2);
        box.addView(tvExactFreq);

        // Signal Indicator
        TextView tvManualSignal = new TextView(context);
        tvManualSignal.setText("Signal: --");
        tvManualSignal.setTextSize(13);
        box.addView(tvManualSignal);

        android.widget.LinearLayout strRow = new android.widget.LinearLayout(context);
        strRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        strRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        strRow.setPadding(0, 4, 0, 4);
        TextView tvStrLabel = new TextView(context);
        tvStrLabel.setText("Strength");
        tvStrLabel.setTextSize(11);
        tvStrLabel.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int)(60 * getResources().getDisplayMetrics().density), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        ProgressBar barStrength = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        barStrength.setMax(100);
        barStrength.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        strRow.addView(tvStrLabel);
        strRow.addView(barStrength);
        box.addView(strRow);

        android.widget.LinearLayout qualRow = new android.widget.LinearLayout(context);
        qualRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        qualRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        qualRow.setPadding(0, 4, 0, pad);
        TextView tvQualLabel = new TextView(context);
        tvQualLabel.setText("Quality");
        tvQualLabel.setTextSize(11);
        tvQualLabel.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int)(60 * getResources().getDisplayMetrics().density), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        ProgressBar barQuality = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        barQuality.setMax(100);
        barQuality.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        qualRow.addView(tvQualLabel);
        qualRow.addView(barQuality);
        box.addView(qualRow);

        Button btnScanThis = new Button(context);
        btnScanThis.setText("SCAN");
        box.addView(btnScanThis);

        final AtomicInteger currentSelectedKhz = new AtomicInteger(khzList.get(0));

        spUhf.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int khz = khzList.get(position);
                currentSelectedKhz.set(khz);
                tvExactFreq.setText(String.format(Locale.US, "Frequency: %.3f MHz (%d kHz)", khz / 1000.0, khz));
                worker.execute(() -> {
                    if (hub != null && hub.isTunerPresent()) {
                        hub.ensureTuned(khz, () -> false);
                    }
                });
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> {
            if (hub == null) return;
            SignalStatus st = hub.signal();
            ui.post(() -> {
                if (scanDialog != null && scanDialog.isShowing() && manualScanDialog != null && !manualScanDialog.isShowing()) return;
                if (manualScanDialog == null || !manualScanDialog.isShowing()) return;
                if (st == null) {
                    tvManualSignal.setText("Signal: no source / waiting");
                    barStrength.setProgress(0);
                    barQuality.setProgress(0);
                } else {
                    tvManualSignal.setText(String.format(Locale.US, "Locked: %b | Strength: %d%% | Quality: %d%%", st.locked, Math.max(st.strengthPercent, 0), Math.max(st.qualityPercent, 0)));
                    barStrength.setProgress(Math.max(st.strengthPercent, 0));
                    barQuality.setProgress(st.locked ? Math.max(st.qualityPercent, 0) : 0);
                }
            });
        }, 0, 1, TimeUnit.SECONDS);

        manualScanDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Manual Channel Scan")
                .setView(box)
                .setNegativeButton("Close", (d, w) -> {
                    poller.shutdownNow();
                    d.dismiss();
                })
                .create();

        btnScanThis.setOnClickListener(v -> {
            int khz = currentSelectedKhz.get();
            manualScanDialog.dismiss();
            poller.shutdownNow();
            runManualScanForFrequency(khz);
        });

        manualScanDialog.setOnDismissListener(d -> poller.shutdownNow());
        manualScanDialog.show();
    }

    private AlertDialog manualScanDialog;

    private void runManualScanForFrequency(final int khz) {
        if (bml != null) bml.close();
        if (scanning) return;
        scanning = true;
        playGen.incrementAndGet();
        player.stop();
        player.clearMediaItems();

        showScanProgress(1);
        updateScanProgress(String.format(Locale.US, "Scanning frequency %.3f MHz ...", khz / 1000.0), 0, "Finding services ...");

        new Thread(() -> {
            if (!hub.ensurePermission(60_000)) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "USB permission required", Toast.LENGTH_SHORT).show());
                return;
            }
            hub.acquire(MainActivity.this);
            boolean locked = hub.ensureTuned(khz, () -> false);
            if (!locked) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "Failed to lock frequency " + (khz/1000.0) + " MHz", Toast.LENGTH_SHORT).show());
                return;
            }

            TsDemux d = hub.demux();
            long start = System.currentTimeMillis();
            long firstSeen = 0;
            while (System.currentTimeMillis() - start < 18000) {
                int n = countServices(d);
                long now = System.currentTimeMillis();
                if (n > 0 && firstSeen == 0) firstSeen = now;
                if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1000) || now - firstSeen > 10000)) break;
                updateScanProgress(String.format(Locale.US, "Scanning frequency %.3f MHz ... reading names (%d services)", khz / 1000.0, n), 0,
                        "Finding services ...");
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }

            List<ChannelEntry> newEntries = new ArrayList<>();
            for (ServiceInfo s : d.getServices()) {
                if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                newEntries.add(new ChannelEntry(khz, s.copy()));
            }

            // Remove existing entries for this frequency only, and add new ones
            allChannels.removeIf(ce -> ce.khz == khz);
            allChannels.addAll(newEntries);

            scanning = false;
            dismissScanDialog();
            saveChannels();
            ui.post(() -> {
                Toast.makeText(MainActivity.this, "Manual scan complete. Found " + newEntries.size() + " channel(s) on frequency " + (khz/1000.0) + " MHz.", Toast.LENGTH_LONG).show();
                updateContentAdapter();
                if (!newEntries.isEmpty()) {
                    currentChannelEntry = newEntries.get(0);
                    saveLastChannel(currentChannelEntry);
                    showCurrentChannel();
                    tunerReady = true;
                    tuneAndPlay(currentChannelEntry);
                } else if (currentChannelEntry == null) {
                    currentChannelEntry = pickStartChannel();
                    showCurrentChannel();
                    if (currentChannelEntry != null) {
                        saveLastChannel(currentChannelEntry);
                        tunerReady = true;
                        tuneAndPlay(currentChannelEntry);
                    }
                }
            });
        }, "ManualScanThread").start();
    }

    private void startFullScan() {
        if (bml != null) bml.close();
        if (scanning) {
            scanCancel = true;
            return;
        }
        if (hub == null || !hub.isTunerPresent()) {
            Toast.makeText(this, "Tuner not found. Plug in USB dongle.", Toast.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        scanCancel = false;
        playGen.incrementAndGet();
        player.stop();
        player.clearMediaItems();
        allChannels.clear();

        List<Integer> freqs = new ArrayList<>();
        for (int f = 473143; f <= 695143; f += 6000) freqs.add(f);

        showScanProgress(freqs.size());

        new Thread(() -> {
            if (!hub.ensurePermission(60_000)) {
                scanning = false;
                dismissScanDialog();
                ui.post(() -> Toast.makeText(MainActivity.this, "USB permission required", Toast.LENGTH_SHORT).show());
                return;
            }
            hub.acquire(MainActivity.this);
            for (int i = 0; i < freqs.size() && !scanCancel; i++) {
                int khz = freqs.get(i);
                int uhfCh = 14 + (khz - 473143) / 6000;
                String freqStr = String.format(Locale.US, "UHF %d (%.3f MHz)", uhfCh, khz / 1000.0);
                updateScanProgress(String.format(Locale.US, "Scanning channel %d of %d: %s", i + 1, freqs.size(), freqStr), i,
                        "Found: " + allChannels.size() + " channel(s)");

                boolean locked = hub.ensureTuned(khz, () -> scanCancel);
                if (!locked || scanCancel) continue;

                TsDemux d = hub.demux();
                long start = System.currentTimeMillis();
                long firstSeen = 0;
                while (System.currentTimeMillis() - start < 22000 && !scanCancel) {
                    int n = countServices(d);
                    long now = System.currentTimeMillis();
                    if (n > 0 && firstSeen == 0) firstSeen = now;
                    // names (SDT) AND the virtual number (NIT, repeats slowly) must be in before leaving the channel
                    if (firstSeen != 0 && ((allNamed(d) && d.hasRemoteKey() && now - firstSeen > 1500) || now - firstSeen > 12000)) break;
                    updateScanProgress(String.format(Locale.US, "Scanning channel %d of %d: %s ... reading names (%d services)", i + 1, freqs.size(), freqStr, n), i,
                            "Found: " + allChannels.size() + " channel(s)");
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                }

                int networkId = d.getNetworkId(), tsId = d.getTsId();
                for (ServiceInfo s : d.getServices()) {
                    if (!s.pmtSeen || s.streams.isEmpty() || !s.hasVideo()) continue;
                    boolean exists = false;
                    for (ChannelEntry ce : allChannels) {
                        if (isSameService(ce, networkId, tsId, s.serviceId)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        ChannelEntry entry = new ChannelEntry(khz, s.copy());
                        entry.networkId = networkId;
                        entry.tsId = tsId;
                        allChannels.add(entry);
                    }
                }
            }
            scanning = false;
            dismissScanDialog();
            saveChannels();
            ui.post(() -> {
                Toast.makeText(MainActivity.this, "Scan complete. Found " + allChannels.size() + " channel(s).", Toast.LENGTH_LONG).show();
                updateContentAdapter();
                currentChannelEntry = pickStartChannel();
                showCurrentChannel();
                if (currentChannelEntry != null) {
                    saveLastChannel(currentChannelEntry);
                    tunerReady = true;
                    tuneAndPlay(currentChannelEntry);
                }
            });
        }, "FullScanThread").start();
    }

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

    @Override
    public void onPreempted() {
        // Live Channels (or another screen) took the tuner: stop cleanly, re-acquire on next start.
        scanCancel = true;
        tunerReady = false;
        playGen.incrementAndGet();
        ui.post(() -> {
            player.stop();
            player.clearMediaItems();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanCancel = true;
        playGen.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (currentPacketSink != null) {
            currentPacketSink.stopRecord();
        }
        if (scanDialog != null) {
            scanDialog.dismiss();
            scanDialog = null;
        }
        if (manualScanDialog != null) {
            manualScanDialog.dismiss();
            manualScanDialog = null;
        }
        if (bml != null) bml.close();
        if (captionView != null) { captionView.destroy(); captionView = null; }
        player.removeListener(playerListener);
        playerView.setPlayer(null);
        player.release();
        if (hub != null) hub.release(this);   // detaches the sink, powers the dongle down after the idle delay
        recyclerView.setAdapter(null);
        pipe.close();
    }
}
