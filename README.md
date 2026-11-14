# StudioSpark Pro (fresh rebuild) — debug build notes

applicationId `com.studiospark.app` · versionName `2.0.0-pro-debug` · versionCode 1 · minSdk 31 · targetSdk 34 · Kotlin + Jetpack Compose.
Built offline: `JAVA_HOME=/home/box/tools/jdk-17.0.21+9 ./gradlew --offline assembleDebug testDebugUnitTest`.
Debug APK only. Nothing has been committed, signed for release, or uploaded.

## Where things are
- **Tracking (Pro Tools-style):** the transport bar is always visible. Hide only collapses the optional row.
  - Transport: RTZ, rewind, stop, play, record and FF, plus a bars|beats counter.
  - Modes and timing: Rec mode (Normal / Punch / Loop), count-in, click, tempo and meter.
  - Studio → Edit window: track headers with R/I/S/M and meters; ruler (tap to locate, loop and punch bars); clips with waveforms; MIDI regions; playhead.
  - Every pass becomes a private take with Listen / Place / Discard. Nothing is auto-placed.
- **Upload + Rig:** Studio toolbar **Upload** (or Record → Tone…, or Stems → Send to Upload + Rig).
  - Steps: Source → Rights → Instrument → Tone (full amp/effects rig with audition) → Place.
  - For an existing track: select it, then tap the **Amp & effects rig** card or **Tone**. Its edits are live on that track and undoable.
- **Mixer:** Studio → **Mix** (console or small strips), with pan, solo/mute, fader, meters, rig summary and master.
- **Jam room:** the **Jam Room** tab.
  - Pinch to zoom; drag empty space to pan; Reset view.
  - Drag gear to move it; Patch cable connects gear (feedback loops are blocked).
  - Record jam makes a private take. Click is app-wide.
- **Drums:** select the Drums track for the playable kit.
  - **Drum station** has custom kits: add any piece, tune/level/size, choke groups and sticks, then save.
  - Pads mode has per-pad Play / Pause / Record-arm.
- **Guitar:** select a guitar or bass track for the playable fretboard and tuning.
  - **Workbench** sets name, tuning, string set and body, then saves to My instruments.
- **Instruments** (top right): one gallery of every instrument, amp, cab, pedal and rack unit. Each can go to a track, a track rig, or the room.
- **Settings** (gear icon): Gold theme (persisted), Hide panels, 8/16-track, meter, latency compensation, birthday banner, bounce, MIDI export, AI vocal, new session.

## Builder tabs (add-on): Drum Kit · Guitar · Bass

Top tab row (scrolls sideways): **Studio · Drum Kit · Guitar · Bass · Stems · Record · Jam Room**. Debug deep links: `--es open DRUMS|GUITAR|BASS`.

**Drum Kit tab** (`ui/DrumKitScreen.kt`, `ui/KitRender.kt`, `core/DrumCatalog.kt`)
- Start from 9 starter kits or a blank kit. Add, remove and drag-reposition (Arrange) cymbals, drums, pedals and hardware. Zones are linked: hi-hat edge/foot, ride bell, snare rim and the double-pedal left beater.
- Overhead and Behind-the-kit (drummer's view) renderers. Procedural photoreal shells (17 finishes), chrome hardware and bronze cymbals (6 finishes).
- Inspector: tune, level, **pan** (real stereo per piece), size, sound, choke group, finish and audition.
- Playable and recordable while building. It plays on a Drums track; Record arms that track, and takes land in the Listen / Place / Discard tray. Nothing is auto-placed.
- Catalog: Cymbals 39 · Drums 26 · Bass pedals 7 (single + double) · Hardware 18 · Starter kits 9 + blank.
- Sound: 62 sounding entries use 24 distinct in-house samples. 23 are direct and 39 are size-retuned. 7 pedal entries trigger the kick, and 18 hardware entries are silent.
- Gaps (silent, flagged): orchestral gong, bar chimes, timbales.

**Guitar / Bass tabs** (`ui/InstrumentBuilderScreen.kt`, `ui/GuitarRender.kt`, `core/StringCatalog.kt`)
- Start from starters (11 guitars, 7 basses) or from scratch. Catalog covers body styles, finishes, pickup configurations, necks/fretboards/inlays, bridges and hardware, string sets and tunings.
- Original procedural photoreal renderer covering bursts, metallic flake, wood grain, quilt, flame and road-worn finishes, plus pickguards, pickups, bridges and headstocks. The drawn neck is playable (tap to fret, drag to strum), and there is also a full fretboard.
- Tunings: standard, drop D, drop C, open G/D/E, DADGAD, Eb, 7-string and baritone. Bass: standard, drop D, drop C, open A, 5-string and 5-string drop A.
- Sound: owned CC0 guitar (36) and bass (24) recordings, voiced by the tone filter from body, pickups, selector position, board, bridge, strings and the tone knob. Acoustic bodies use the in-house modeled voice.
- Gaps (flagged in UI): twelve-string jumbo, fretless bass, acoustic bass, guitar piezo, bass piezo.
- **Amp & pedals, live:** each build carries a rig preset (stomps → amp → cab → rack) on its track. Blocks can be bypassed in place, and "Open full rig" edits the same live rig. In the Jam Room, "Jam Room" plugs the instrument into the cabled amp chain (adding an amp and cab if the room has none).

**Saved instruments are first-class.** Saving adds the kit/guitar/bass to every instrument picker: track Instrument dropdown, Instruments gallery (with Edit), Jam Room add dialog, upload/place instrument step, and the Record tab's "Play & record an instrument" panel. Saved builds and kits also go to a personal library that is merged into new sessions.

## DSP
All of the following is real-time DSP on a 48 kHz float engine, using a low-latency AudioTrack on an urgent-priority thread:
- Stomps
- Staged amp models: preamp, tone stack, sag, presence
- Cab with a movable mic (position, distance, type and room)
- Rack units
- Tuner (YIN)

Knob moves are read live by the audio thread. Rigs go idle 6 s after silence to save battery.

Stems use on-device Open-Unmix UMX-HQ (MIT weights, ONNX Runtime):
- STFT 4096/1024
- Four learned estimators, ratio masks, iSTFT
- 4 s chunks with a 1 s crossfade

## Remaining gaps (honest)
- On-device verification was limited.
  - The only Android emulator available here ran without hardware acceleration.
  - The app launched and rendered Studio, Jam Room and the amp controls with no crashes.
  - Latency, touch feel and audio quality still need checking on a real phone.
- Brand catalogs (Sabian etc.) are not included. The reuse-only / no-license-obligation rules rule them out, so gear uses original names and art.
- AI vocals use an on-device formant synth (labelled synthetic). Lyrics and suggestions are template/theory-based, not a large model.
- Clip stretch is varispeed (pitch follows), not time-stretch. Polish is normalize plus edge fades.
- Drum pieces have no per-piece pan; kits are mono into the track's pan.
- MIDI out sends to the first available device; there is no per-port routing UI.
- The APK is large (~446 MB) because it bundles 320 MB of owned samples and four 34 MB stem models.
