# Meadow Muse

Type a question or tap the microphone to dictate. Review the text and press
Send. Muse replies in text and speaks with OpenAI’s Marin voice.

## Build

Requires Python 3, Java 21, `adb`, an ARM64 Meadow, a Muse account, and the
[separately supplied APK/assets](THIRD_PARTY.md).

```sh
python3 setup.py
python3 prepare.py --apk /path/to/meadow-gadget.apk
python3 build_voice.py
```

`--assets /path/to/assets` optionally supplies the chat-header image.
Java comes from `JAVA_HOME` or `PATH`. Preparation creates ignored `patched/`;
use a fresh checkout when switching versions.

```sh
adb install --no-incremental -r signed-voice/meadow-voice-aligned-debugSigned.apk
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_CONNECT
adb shell pm grant ai.muse.gadget.fixed android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant ai.muse.gadget.fixed android.permission.RECORD_AUDIO
adb shell am start -n ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceActivity
```

Pair in the Muse phone app’s developer/gadget settings. Updates preserve
pairing. This uses a separate package and a development signing key.

Put `OPENAI_API_KEY` in ignored `.env.local`, then run
`python3 provision_openai.py` with Meadow attached. The app stores the key
privately. Speech uses `gpt-4o-mini-tts` / `marin` and incurs API usage.

## Code and checks

`voice-src/` contains the UI and speech code. `VoiceBridge` registers reply
commands with the supplied app. `prepare.py` applies the Bluetooth, X25519,
and disconnect fixes; `patches/` contains our queue fix.

```sh
adb shell am instrument -w -e uiOnly true ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceVerification
```

`uiOnly` renders native UI states without speech/image API calls. `fullTurn`
sends a live question and requires an unlocked, paired device. Fixture
recognition does not test the physical microphone; test dictation on Meadow.
Use `-e dictationOnly true` to test the local recognition fixture.
Run `python3 tests/run_pcm.py` for streaming playback checks.

Our code is MIT; external code, artwork, and services retain their own terms.
