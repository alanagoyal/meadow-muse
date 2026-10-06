# External inputs

This repository’s MIT license covers our code, not the supplied APK or Muse
artwork. Those files are not included in any commit.

- **Base APK:** supply your authorized `meadow-gadget.apk`. The importer expects
  SHA-256 `d2e9d91abbcc3bf2032473b9c9a79fbbbc177e6e2476013bf9fbf2ff34a58062`.
  It provides pairing and the Muse connection. A clone cannot build without it.
  The resulting APK contains third-party code; do not redistribute it without permission.
- **Optional chat-header image:** supply local `muse/idle.gif`. Without
  it, the header just says Muse. This artwork is not included, and no
  redistribution permission is granted here.
- **Recognition test:** optional `voice-test.pcm`, 16 kHz mono signed-16-bit
  PCM containing “one”. Supply a recording you may use.

Pass these assets to `prepare.py --assets /path/to/assets`. Imported code and
assets stay in ignored `patched/`. Downloads in `dependencies.json` are pinned
by SHA-256 and retain their own licenses. Muse and OpenAI require their respective accounts.
