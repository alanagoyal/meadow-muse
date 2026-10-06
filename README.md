# meadow muse

A voice interface for Muse on Meadow: hold to talk, hear a spoken reply, and
see an animated character with compact captions. Speech recognition runs
locally with Vosk; speech output uses OpenAI TTS.

## prerequisites

- An ARM64 Meadow with internet access and USB debugging enabled.
- A phone with Muse installed and a working Muse account.
- Python 3, Java 21, and Android platform-tools (`adb`) on your computer’s `PATH`.
  You can also select Java with `JAVA_HOME`.
- An OpenAI API key with billing enabled.
- The original `meadow-gadget.apk` and two character animations you are
  authorized to use. **These are not included; this repo cannot build without
  them.** See [external inputs](THIRD_PARTY.md) for the exact APK version and
  animation sources.

## build

```sh
git clone https://github.com/alanagoyal/meadow-muse.git
cd meadow-muse
```

Arrange the animations in a local directory:

```text
assets/
  muse/
    idle.gif
    working.gif
```

Both GIFs should be 320 × 320. Replace the paths below with your files:

```sh
python3 setup.py
python3 prepare.py --apk /path/to/meadow-gadget.apk --assets /path/to/assets
python3 build_voice.py
```

Setup downloads checksum-verified build tools and the recognition model.
Preparation imports and patches the supplied APK into ignored `patched/`.

## install and connect

Connect and unlock Meadow, approve USB debugging, and confirm `adb devices`
lists it as `device`. Then run:

```sh
adb install --no-incremental -r signed-voice/meadow-voice-aligned-debugSigned.apk
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_CONNECT
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant ai.muse.gadget.fixed android.permission.RECORD_AUDIO
cp .env.example .env.local
```

Set `OPENAI_API_KEY` in `.env.local`, then provision it over USB:

```sh
python3 provision_openai.py
```

This opens the app and imports the key into private app storage. `.env.local`
is ignored by Git. Speech uses `gpt-4o-mini-tts` with the `marin` voice and
incurs OpenAI API charges.

In the phone’s Muse app, open the developer/gadget settings and pair the
Meadow. Wait for **connected** on Meadow, then hold the character to talk and
release to send. Hold again to interrupt a reply. Bluetooth handles pairing;
Meadow also needs internet to connect to Muse and generate speech.

To reopen the app:

```sh
adb shell am start -n ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceActivity
```

## checks

Use a fresh checkout when switching versions; generated build files must
match the source.

UI and speech code lives in `voice-src/`; `prepare.py`, `patch_apk.py`, and
`patch_connection.py` integrate it with the supplied app.

```sh
python3 tests/run_pcm.py
python3 tests/run_lifecycle.py
adb shell am instrument -w -e uiOnly true ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceVerification
```

Speech buffers at least half a second of audio before playback to protect the
opening word from gaps between network chunks. PCM tests cover startup buffering,
short replies, cancellation, and simulated network gaps. The fix passed live
speech and full-turn tests on Meadow, with listening confirmation that the
opening sounded clean.

The device check renders UI states without API calls. Test a spoken question
on the paired Meadow to verify the microphone and complete conversation.

Our code is [MIT licensed](LICENSE). The supplied APK, artwork, and downloaded
dependencies retain their own terms; see [external inputs](THIRD_PARTY.md).
