# meadow muse

a voice interface for muse on meadow: hold to talk, hear a spoken reply, and
see an animated character with compact captions. speech recognition runs
locally with vosk; speech output uses openai tts.

## prerequisites

- an arm64 meadow with internet access and usb debugging enabled.
- a phone with muse installed and a working muse account.
- python 3, java 21, and android platform-tools (`adb`) on your computer’s `PATH`.
  you can also select java with `JAVA_HOME`.
- an openai api key with billing enabled.
- the original `meadow-gadget.apk` and two character animations you are
  authorized to use. **these are not included; this repo cannot build without
  them.** see [external inputs](THIRD_PARTY.md) for the exact apk version and
  animation sources.

## build

```sh
git clone https://github.com/alanagoyal/meadow-muse.git
cd meadow-muse
```

arrange the animations in a local directory:

```text
assets/
  muse/
    idle.gif
    working.gif
```

both gifs should be 320 × 320. replace the paths below with your files:

```sh
python3 setup.py
python3 prepare.py --apk /path/to/meadow-gadget.apk --assets /path/to/assets
python3 build_voice.py
```

setup downloads checksum-verified build tools and the recognition model.
preparation imports and patches the supplied apk into ignored `patched/`.

## install and connect

connect and unlock meadow, approve usb debugging, and confirm `adb devices`
lists it as `device`. then run:

```sh
adb install --no-incremental -r signed-voice/meadow-voice-aligned-debugSigned.apk
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_CONNECT
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant ai.muse.gadget.fixed android.permission.RECORD_AUDIO
cp .env.example .env.local
```

set `OPENAI_API_KEY` in `.env.local`, then provision it over usb:

```sh
python3 provision_openai.py
```

this opens the app and imports the key into private app storage. `.env.local`
is ignored by git. speech uses `gpt-4o-mini-tts` with the `marin` voice and
incurs openai api charges.

in the phone’s muse app, open the developer/gadget settings and pair the
meadow. wait for **connected** on meadow, then hold the character to talk and
release to send. hold again to interrupt a reply. bluetooth handles pairing;
meadow also needs internet to connect to muse and generate speech.

to reopen the app:

```sh
adb shell am start -n ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceActivity
```

## checks

use a fresh checkout when switching versions; generated build files must
match the source.

ui and speech code lives in `voice-src/`; `prepare.py`, `patch_apk.py`, and
`patch_connection.py` integrate it with the supplied app.

```sh
python3 tests/run_pcm.py
python3 tests/run_lifecycle.py
adb shell am instrument -w -e uiOnly true ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceVerification
```

speech buffers at least half a second of audio before playback to protect the
opening word from gaps between network chunks. pcm tests cover startup buffering,
short replies, cancellation, and simulated network gaps. the fix passed live
speech and full-turn tests on meadow, with listening confirmation that the
opening sounded clean.

the device check renders ui states without api calls. test a spoken question
on the paired meadow to verify the microphone and complete conversation.

our code is [mit licensed](LICENSE). the supplied apk, artwork, and downloaded
dependencies retain their own terms; see [external inputs](THIRD_PARTY.md).
