# OpenDTV

**An open source Android ISDB-T player application built for ISDB-T compliant TV dongle tuners.**

OpenDTV lets you watch free-to-air digital TV on your Android phone, tablet, or TV box by plugging in a USB ISDB-T dongle. It talks to the tuner directly over USB host (OTG), so no vendor app is needed. It also includes a transport stream analyzer, EPG, closed captions, a full ARIB data broadcast (BML) viewer, and a multi-country EWBS emergency alert system.

![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green)
![Min SDK](https://img.shields.io/badge/minSdk-21-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)
![Release](https://img.shields.io/github/v/release/OWNER/REPO)

<!-- Add screenshots here: main screen, channel list, EWBS banner, Analyzer -->

---

## Compatible Hardware

| Device | USB ID | Status |
|---|---|---|
| Mygica PadTV PT275 | `048d:275a` | Supported (confirmed on hardware) |
| GMA Now Dongle | `048d:275a` | Supported |
| IT9175 Tuner (ITE/Afatech) | `048d:275a` | Supported |
| Mygica PadTV PT275C (USB-C) | `048d:e275` | Supported, **needs testing** |
| ABS-CBN TV Plus GO (Tolka, IT9308) | `048d:9308` | Supported, **needs testing** |

> The PT275, GMA Now and IT9175 dongles share the same chip family and driver. The PT275C uses the same chip and protocol but a different firmware image and init script (the vendor app drives it with a separate driver build), so it gets its own data set. The ABS-CBN TV Plus GO (IT9308) uses its own driver path and has not been widely tested yet, so please report your results (see [Contributing](#contributing)). More ISDB-T dongles may be added over time.

Your Android device must support **USB Host (OTG)**.

---

## Features

### Viewing
- Live ISDB-T TV playback (ExoPlayer / Media3) with fast channel start
- Hardware decoding via MediaCodec, with an **FFmpeg software fallback** for formats your device cannot decode
- Channel list with **virtual channel numbers**, station logos, and **favorites**
- **Auto Scan** (quick list or full UHF sweep, 473.143 to 695.143 MHz) and **Manual Scan** (MHz, kHz, or UHF channel number)
- **Electronic Program Guide (EPG)** from broadcast EIT data
- **Audio track and language selection**. When no language is chosen, the best track is picked automatically (AAC is preferred over MP2)
- **Closed captions** (ARIB), as a simple text line or styled, broadcast-layout captions
- **Channel recording** to a transport stream file
- Portrait and landscape layouts for phones and tablets, plus a TV layout

### EWBS (Emergency Warning Broadcast System)
OpenDTV detects emergency signalling in the broadcast and warns you on screen and with an alarm tone.

- **Detection sources:** the emergency start-up flag (TMCC / IIP), the PMT emergency information descriptor (`0xFC`), and BML event messages
- **Multi-country profiles** in **Settings > EWBS**:

| Country | Code | Area codes | Default language |
|---|---|---|---|
| Philippines | `PHL` (`50 48 4C`) | 17 regions (PSGC) | Tagalog |
| Brazil | `BRA` (`42 52 41`) | 27 states (IBGE) | Português |
| Peru | `PER` (`50 45 52`) | 25 departments (UBIGEO) | Español |
| Japan | `JPN` (`4A 50 4E`) | 47 prefectures (JIS X 0401) | 日本語 |

- **Location / Area code:** choose your area from a *code - area* list, pick *All areas / nationwide*, or enter a custom 12-bit hex code. Alerts for other areas are shown differently and do not sound the alarm.
- **Message languages:** English, Tagalog, Português, 日本語, Español (Auto uses the country's language)
- **Event types:** earthquake, tsunami, typhoon / severe weather, flood, volcanic eruption, and test / maintenance
- **Signal level:** level 1 = emergency / mandatory alert, level 0 = test / non-urgent
- **On-screen banner** with event type, level, affected areas, and a short safety instruction:
  - Red = emergency for your area
  - Blue = test signal (drills)
  - Amber = alert for another area, or "EWBS was signalled" after it ends (so finished recordings still show it)
- **Emergency tone:** alternating 640 Hz / 1024 Hz alarm. Loops during a real alert, one quiet pass for tests. Tap the banner to mute the current alert, or turn the tone off in Settings > EWBS. The tone never changes your system volume.
- **Analyzer EWBS tab** with the decoded report and a timestamped event log

> EWBS area matching uses each country's national 2-digit code. If your broadcaster uses a different numbering, use **Custom code** in Location / Area. The event type is read from the reserved bits after `signal_level` in the `0xFC` descriptor, so reports from real broadcasts are very welcome.

### Data Broadcast (BML)
- Full ARIB data broadcast display over the video, with built-in BML sounds and fonts
- On-screen remote panel in portrait; D-pad, number, and colour-key support in landscape
- Keyboard and TV remote control support (see [Controls](#controls))

### Android TV / TV Boxes
- Registers an **OpenDTV ISDB-T TV input** for the system *Live Channels* app (Android 9+ / API 28+)
- Channel scan writes channels and EPG data to the TV provider

### Analyzer and Developer Tools
- Signal strength and quality bars with dBm readout
- Transport stream analyzer: PAT / PMT / SDT / NIT / EIT / TDT-TOT, PID statistics, CC / TEI / sync-loss counters
- DSM-CC data carousel inspector
- Open a `.ts` capture file to test and develop without a dongle
- USB probe diagnostic screen

---

## Getting Started

### Requirements
- Android 5.0 (API 21) or newer
- A device with USB Host (OTG) support and an OTG cable or adapter
- A compatible ISDB-T dongle (see above)
- An ISDB-T antenna and signal coverage in your area
- Recommended: a recent Android System WebView (for BML and styled captions)

### Installation
1. Download the latest APK from the [Releases](../../releases) page.
2. Plug the dongle into your device with an OTG adapter.
3. Open OpenDTV and allow the USB permission prompt.
4. Open the menu and choose **Auto Scan** to find channels.
5. Pick a channel and start watching.
6. Go to **Settings > EWBS** and set your country and area so alerts are matched to your location.

### Using with Live Channels (Android TV / TV box)
1. Install the APK, plug in the dongle, and allow USB permission.
2. Open **Live Channels > Settings > Channel sources** (or the *OpenDTV channel scan* icon on the launcher).
3. Choose **OpenDTV ISDB-T** and run a **Quick scan** or **Full scan**.
4. Watch from Live Channels.

Notes: one tuner means one viewing session at a time. EPG times are based on the offset between the broadcast clock and your device clock, so make sure your device date and time are correct.

---

## Controls

Data broadcast controls (landscape):

| Action | Remote | Keyboard |
|---|---|---|
| Show / hide data broadcast | DATA key (or TEXT key) | F5 |
| Move | D-pad | Arrow keys or W A S D |
| Select | OK / Enter | Enter / numpad Enter |
| Back | BACK | F6 or Backspace |
| Colour keys | Red / Green / Yellow / Blue | F1 / F2 / F3 / F4 |
| Numbers | 0-9 | 0-9 and numpad 0-9 |
| Close | DATA again | F5 or Esc |

In portrait, use the floating remote button (bottom right) to open the on-screen remote panel.

---

## Building from Source

Open the project in Android Studio and let Gradle sync, then run on a device with USB host support.

The pure Java transport stream and EWBS logic can be tested without Android:

```
javac -encoding UTF-8 -d out <pure-java files in app/src/main/java/ph/opendtv/ts> tools/EwbsSelfTest.java
java -Dfile.encoding=UTF-8 -cp out EwbsSelfTest
```

See `tools/TsSelfTest.java` for the transport stream parser test.

---

## Project Structure

```
ph.opendtv          MainActivity, AnalyzerActivity, UsbProbeActivity
ph.opendtv.ts       Pure Java: TS demux, section assembler, service filter, carousel decoder, EWBS codes and models
ph.opendtv.source   Tuner and TS sources: UsbTunerSource, It9175Tuner, It9308TunerSource, FileTsSource
ph.opendtv.it9308   IT9308 (ABS-CBN TV Plus GO) device driver layer
ph.opendtv.player   TsPipeDataSource (Media3), audio renderers
ph.opendtv.bml      BML data broadcast controller, remote panel, sound player
ph.opendtv.tv       Live Channels TV input, session, hub, setup/scan
ph.opendtv.ui       Adapters, caption views, EWBS banner / settings / tone, logos
```

---

## Known Limitations

- **No descrambling.** No B-CAS code is included or planned, so encrypted services will not play.
- Virtual channel numbers come from the NIT `remote_control_key_id`. This is a heuristic and may need adjusting for the local channel plan.
- Broadcast times are shown as received. ARIB specifies JST (UTC+9), but local broadcasters may differ.
- MPEG-2 playback with hardware decoding depends on your device having an MPEG-2 decoder. Otherwise the FFmpeg fallback is used.
- Not ported from the vendor library: ALPU authentication, crystal indexes other than 0/1, and EEPROM-configured variants.
- Tuner support has been confirmed on a real PT275. The IT9308 and other listed dongles may behave differently.
- EWBS area lists are region / state / department / prefecture level only (no province or city level), and the alert tone is an audible alarm, not a decoded FSK signal. The device does not auto-wake from standby.

---

## Contributing

Contributions are welcome. Bug reports, new dongle support, channel logos, translations, and pull requests all help.

When reporting a tuner issue, please include the full report from the **USB Probe** screen and, if possible, a short `.ts` capture. For EWBS issues, include the Analyzer **EWBS** tab report and your country / area settings.

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Open a pull request

---

## Credits and Licenses

- **web-bml** by [otya128](https://github.com/otya128/web-bml) (MIT): BML data broadcast engine
- **aribb24.js**: ARIB caption rendering
- **NextLib** by [anilbeesetti](https://github.com/anilbeesetti/nextlib) (GPL-3.0): FFmpeg decoders for Media3
- **AndroidX Media3 / ExoPlayer** (Apache-2.0)
- **Kosugi / Kosugi Maru** fonts: BML fonts
- Other libraries: fflate, crc-32, fast-xml-parser, css (see the in-app *Open source licenses* screen)

OpenDTV's own code is released under the **MIT License**. See [`LICENSE`](LICENSE).

> **Note:** The app bundles NextLib (GPL-3.0) for FFmpeg decoding, which may affect how compiled builds can be redistributed. Review the licenses of all bundled components before distributing.
>
> OpenDTV contains no code or data from the vendor drivers (libpt275aDrv, GMA Now, Pad TV). The tuner logic was written from analysis of observable behavior.

---

## Disclaimer

OpenDTV is an independent project and is not affiliated with or endorsed by Mygica, Geniatech, GMA Network Inc., ABS-CBN, ITE Tech, or any broadcaster. All trademarks and station logos belong to their respective owners. EWBS alerts in this app are an aid only and must not be relied on as your only source of emergency information. Make sure you comply with the laws and broadcast regulations in your country.
