# OpenDTV: ISDB-T analyzer and live viewer for the IT9175-based dongle (Pad TV / GMA Now, 048d:275a)

Basic Java Android app. Layout:

- **Top left:** video player
- **Top right:** Virtual Number, Channel name, Program ID (+ signal line, channel picker, source buttons)
- **Bottom:** Video ID, Audio ID, Codec, then tabs **Analyzer / EPG / Datacast**

## Build

Open this folder in Android Studio and let it sync (it creates the Gradle wrapper). If it suggests newer
AGP / Media3 versions, accept them. Run on a phone with USB host (OTG) support.

## Update (hardware confirmed)

The tuner locks and plays on a real PT275. Changes after the first hardware test:
- USB TS reads now use 24 queued `UsbRequest` transfers (16 KiB each) plus a separate consumer thread; the old
  one-at-a-time blocking `bulkTransfer` lost packets (audio timestamp discontinuities in logcat, choppy video).
  The signal line shows `USB drop N` if the consumer ever falls behind (should stay absent).
- Signal Strength / Quality bars, dBm, polled once a second off the UI thread.
- **Scan** (quick list from the vendor app, or full 473.143 to 695.143 MHz (every 6 MHz)) and **Manual** tune (MHz, kHz or UHF
  channel number); found multiplexes are saved and listed in the new spinner.
- ExoPlayer: live-friendly buffer sizes and `FLAG_ALLOW_NON_IDR_KEYFRAMES` for faster start.
- FFmpeg software decoders (NextLib `nextlib-media3ext`, GPL-3.0) are registered as extension renderers after MediaCodec, so formats the phone cannot decode fall back to FFmpeg. Media3 is now 1.7.1 (compileSdk 35).

## What works now

| Part | State |
|---|---|
| TS demux, PAT/PMT/SDT/NIT/EIT/TDT-TOT parser, PID stats, CC/TEI/sync-loss counters | Done and tested (`tools/TsSelfTest.java`) |
| Single-service filter (rewrites PAT so ExoPlayer sees one program) | Done and tested |
| Analyzer / EPG / Datacast tabs, Virtual Number, IDs, Codec | Written (Android layer not compiled here, see below) |
| ExoPlayer playback through `TsPipeDataSource` | Written, untested on a device |
| **Open .ts file** source (develop and test without the dongle) | Done |
| USB: open device, claim interface, read bulk-IN | Written |
| **Tuner init, tuning, lock, signal registers** | **Written, UNTESTED on hardware.** `It9175Tuner` is a port of the vendor library's init/tune/lock logic, diff-tested on a PC against the real `libpt275aDrv.so` run under a CPU emulator (80 random scenarios, identical register writes). `UsbTunerSource` auto-tunes 497143 kHz (TV5) after init. |

**First hardware test:** open the app (or UsbProbeActivity) with the dongle and paste back the whole probe report.
It lists the registers that select the vendor's code paths (`regs before/after tuner init`, `regs after tune`),
the tuner-firmware values (`ec86/ed03/ed23`), `lock: true/false`, the raw signal registers and the TS byte count.
Not ported: the ALPU authentication the vendor does at the end of init (only gates the vendor's own ReadTS in
software), crystal indexes other than 0/1, EEPROM-configured variants.

Without hardware, use a **.ts** capture to exercise everything else.

### Test the tuner port on a PC

```
javac -d out -sourcepath app/src/main/java tools/ph/opendtv/source/TunerDiffTest.java
java -cp out ph.opendtv.source.TunerDiffTest tools/golden/tuner_golden.txt
```
`tools/golden/tuner_golden.txt` holds the vendor library's register writes for each scenario; `tools/emu/` has the
emulator and generators that produced it and `It9175Data.java` (needs `libpt275aDrv.so`, not included).

## Test the library on a PC (no Android needed)

```
javac -d out app/src/main/java/ph/opendtv/ts/*.java \
      app/src/main/java/ph/opendtv/source/{TsSource,SignalStatus,FileTsSource}.java tools/TsSelfTest.java
java -cp out TsSelfTest
```

## Notes and known limits

- **Virtual Number** = `remote_control_key_id` from the NIT (TS information descriptor). When a multiplex has
  several TV services, `.N` is appended in service_id order. This is a heuristic; adjust to the NTC channel plan.
- **Times** (EPG, clock) are shown as broadcast. ARIB specifies JST (UTC+9); Philippine broadcasters may differ.
- **Text** decoding is simplified: printable ASCII only. Kanji/kana (ARIB 2-byte sets) are skipped.
- **Datacast**: DSM-CC data carousels (stream types 0x0B-0x0D) are decoded (DSI/DII/DDB): modules, block
  progress, and for ARIB BML modules the MIME multipart files inside (name, type, size). Verified on a real
  TV5 (497.143 MHz) capture: 14 modules / 40 files on PID 0x02F8 (twitter_*.btb, 23 JPEGs, cms_text_*.btb) and
  startup.bml + common.clt on PID 0x02EE. Files are listed, not rendered. zlib-compressed modules
  (descriptor 0x09 / 0xC2) are supported and unit-tested, but no real compressed module has been seen yet.
  The OTA "ssu" service (type 0x0C) is listed with its module sizes. Caption streams (0x06) are listed separately.
- **Tools**: `tools/TsDump.java` prints services, streams, EPG, carousels and the PID table for any .ts file.
  `tools/TsExtract.java` saves every file of every complete carousel module
  (`java -cp out TsExtract input.ts outDir`), with an `index.txt`. Incomplete modules are listed but not saved;
  a longer capture (one or more full carousel cycles, e.g. 60 s+) completes more of them.
- **Codecs** come from PMT stream types. MPEG-2 video playback depends on the phone having an MPEG-2 decoder.
- No descrambling and no B-CAS code is included or planned.
- Layout works in portrait and landscape; landscape matches the top-left / top-right design best.

## Layout of the code

```
ph.opendtv.ts       pure Java: TsDemux, SectionAssembler, ServiceFilter, TsPipe, models
ph.opendtv.source   TsSource, FileTsSource (pure Java), UsbTunerSource (Android)
ph.opendtv.player   TsPipeDataSource (Media3)
ph.opendtv          MainActivity, UsbProbeActivity (endpoint diagnostic)
```

## Live Channels (Android TV / TV box, Android 9+ / API 28+)

The same APK also registers a **TV input** ("OpenDTV ISDB-T") for the system *Live Channels* app (TV Input Framework).
`minSdk` is now 28.

Setup on the TV box:
1. Install the APK, plug in the dongle (048d:275a) and allow the USB permission dialog.
2. Open **Live Channels -> Settings -> Channel sources** (or the "OpenDTV channel scan" icon on the launcher),
   choose **OpenDTV ISDB-T** and run **Quick scan** or **Full scan**. Found services are written to the TV provider.
3. Watch from Live Channels; zapping inside one multiplex does not retune the dongle.

Code (package `ph.opendtv.tv`):
```
OpenDtvInputService  TvInputService entry point
TunerSession         one viewing session: tune -> wait PAT/PMT -> ServiceFilter -> TsPipe -> ExoPlayer -> TV surface,
                     audio-track menu, EPG sync, retry on player error
TunerHub             the one UsbTunerSource shared by sessions and scan; USB permission, ownership, idle power-down (30 s)
SetupActivity        D-pad friendly scan screen, writes channels (TvContract) and the EPG events seen during the scan
TvChannels           TV provider helpers (channels, programs, broadcast-clock -> UTC offset)
```
Notes: one tuner = one session at a time (`tunerCount=1`); recording and time-shift are not supported. EPG times use the
offset between the broadcast TDT/TOT and the device clock (rounded to 15 min, JST fallback), so the device clock must be right.
Channel numbers are the NIT `remote_control_key_id` (e.g. `5.1`); services without one get `100 + UHF channel` (e.g. `118.1`).
Like the rest of the tuner code, this is not yet tested on real hardware.

## Data broadcast (BML) viewer
Analyzer -> "Remote" tab -> DATA opens the full ARIB data-broadcast display over the video (video shrinks/moves as the page asks).
Keys: arrows, OK, BACK, Blue/Red/Green/Yellow, 0-11 (on screen, or hardware D-pad / number / colour keys).
Engine: web-bml by otya128 (MIT) bundled as `app/src/main/assets/bml/bml.js` (source: github.com/otya128/web-bml,
glue in `opendtv/glue.ts`, built with esbuild). Java only filters the data-carousel PIDs and feeds them in (`bml/BmlController`).

### Data broadcast in the main screen (landscape)
Landscape: no on-screen DATA button (use the DATA / F5 key of a remote or keyboard). Portrait: the floating remote button (bottom right) opens a panel with navigation, OK, the 4 colour keys, DATA and BACK.
Controls (only active in landscape):

| Action | Remote | Keyboard |
|---|---|---|
| Show / hide data broadcast | DATA key (`KEYCODE_TV_DATA_SERVICE`); remotes without it: TEXT key (`KEYCODE_TV_TELETEXT`) | F5 |
| Move | D-pad | Arrow keys or W A S D |
| Select | OK / Enter | Enter / numpad Enter |
| Back (page back) | BACK | F6 or Backspace |
| Colour keys | Red / Green / Yellow / Blue | F1 / F2 / F3 / F4 |
| Numbers | 0-9 | 0-9 and numpad 0-9 |
| Close | DATA again | F5 or Esc |

While the data broadcast is on screen these keys go to the page; otherwise the arrows keep changing channel / opening the side panel.

**Cache:** hiding the data broadcast does not destroy the page. The WebView is kept (timers paused, no packets fed, no CPU),
so DATA again is instant. A hidden page is destroyed after 3 minutes, on low memory, when the channel changes, a scan starts,
or the app goes to the background.

**Rotation:** MainActivity handles orientation changes itself (`onConfigurationChanged` re-inflates `layout` / `layout-land` /
`layout-sw600dp-land`) so the tuner and the player keep running. A plugged-in keyboard / remote no longer recreates the activity.

## Update: older WebView + CC / Audio language

- **BML on old WebView:** `bml.js` is now transpiled to Chrome 55-level syntax and `compat.js` (loaded first by `index.html`)
  polyfills `.at()`, `flatMap`, `replaceAll`, `matchAll`, `replaceChildren`, `Object.fromEntries`, ... To rebuild after updating
  web-bml: `tools/build_bml.sh path/to/original/bml.js`. If the device has no / a disabled WebView, `BmlController` no longer
  crashes: the status line says the data broadcast is unavailable, and an un-runnable page reports the WebView version.
  A crashed WebView renderer is handled too (page dropped, app keeps playing).
- **Settings tab:** new *Closed Captions (CC)* toggle, *Caption Language* (1 main / 2 second) and *Audio Language*
  (lists the audio tracks of the playing channel; the choice is remembered and re-applied on other channels).
  PMT language descriptors (0x0A, 0xC4) are parsed into `StreamInfo.language`.

## Update: portrait BML remote, faster start, best audio

- Portrait shows the data broadcast over the 16:9 video; a Material 3 floating button (bottom right) opens the remote panel.
- Main activity only: playback starts after 25 ms of buffered media (was 1.5 s), 1 s after an underrun; PMT polling 20 ms.
- Audio: with no language chosen in Settings, the channel's best audio track is picked (channels > sample rate > bitrate > codec).

## Captions, BML sounds and fonts (from OpenDTVph-MOD)

- **Settings > Caption Display**: *Simple text line* (default, `CaptionOverlay`) or *Styled* (`ui/CaptionView`): the caption PES
  is rendered by aribb24.js (`assets/bml/captions.html` + `captions.js`) in a transparent WebView over the video, with the
  broadcast layout and Latin / UCS coded captions. Styled mode needs a reasonably recent Android System WebView.
- **BML beeps**: ROM sounds 0..13 (`assets/bml/sounds/*.wav`) and AIFF sounds play natively (`BmlSoundPlayer`, called from
  `bml.js` through the `AndroidBml` bridge); if a sound is unavailable the page falls back to WebAudio as before.
- **BML fonts**: Kosugi / Kosugi Maru (`assets/bml/fonts`) are registered as the BML round / bold round / square gothic fonts.
- The BML engine itself (`BmlController`, web-bml in `bml.js`) is unchanged apart from these hooks.

## Update: over-the-air logic updates (no new APK)

- Like Expo's JavaScript updates: the APK is the shell (screens, layouts, images, permissions, Media3/FFmpeg, the USB
  driver), and logic that changes often lives in a **bundle**, compiled Java (`classes.dex` in a `.jar`) that implements
  `ota/AppLogic`. Bundle 1 ships inside the APK (`assets/ota`) and does exactly what the app did before.
- Two pieces of logic are in the bundle so far: the full scan's "is this the same channel" rule
  (`isSameService`, issue #1) and channel name to logo matching (`logoFor`, names in `assets/dtvlogo`; the images stay
  in the APK). More can move in later, one small step at a time.
- `ota/LogicBundles` checks GitHub (`releases/download/ota/bundle.json`) at most once a day, or from **Settings > App
  Updates (no APK)**. A newer bundle is downloaded, its SHA-256 and ECDSA P-256 signature are checked against the key
  built into the APK, and it is used from the **next start**. If the app dies within 5 s of starting a new bundle, the
  next start goes back to the previous one, and that version is never used again.
- **Off until a key is set**: with an empty `LogicBundles.PUBLIC_KEY` only bundle 1 runs and the app makes no network
  request. To turn it on: `tools/ota/make_key.sh` prints the public key for `PUBLIC_KEY` (release that APK) and writes
  the private `.pem`; put the `.pem` in the repository secret `OTA_SIGNING_KEY`. Never commit it.
- **Publishing**: change `bundle/src/ph/opendtv/bundle/Logic.java`, bump `VERSION`, and run the **OTA logic bundle**
  workflow (Actions tab). It builds with `tools/ota/build_bundle.sh`, signs, and uploads `bundle-N.jar` +
  `bundle.json` to the release `ota` (not marked latest, so APK downloads stay the same). Locally:
  `tools/ota/build_bundle.sh --embed` puts a bundle inside the APK, `--publish KEY.pem BASE_URL` signs one.
- A bundle says which shell it needs (`minShell` = `LogicBundles.SHELL_API`). Changing `AppLogic`, or anything only the
  APK has (a library, permission, layout, image), still needs a new APK.
- Adds the `INTERNET` permission (used only for this). Bundles are downloaded code, which Google Play does not allow;
  GitHub releases are fine.
