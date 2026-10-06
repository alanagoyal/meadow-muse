"""Prepare a local APK workspace. The upstream APK and artwork are never vendored."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
BASE_SHA256 = 'd2e9d91abbcc3bf2032473b9c9a79fbbbc177e6e2476013bf9fbf2ff34a58062'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path, help='Your authorized, unmodified meadow-gadget.apk')
    parser.add_argument('--assets', type=Path, help='Optional local asset directory; see THIRD_PARTY.md')
    args = parser.parse_args()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != BASE_SHA256:
        raise SystemExit('Unsupported base APK. This importer is pinned to the SHA-256 documented in THIRD_PARTY.md.')
    stage = json.loads((ROOT / 'stage.json').read_text())
    required = stage['assets']
    missing = [name for name in required if not args.assets or not (args.assets / name).is_file()]
    if missing:
        raise SystemExit('Supply authorized local assets with --assets: ' + ', '.join(missing))
    if (ROOT / 'patched').exists():
        raise SystemExit('patched/ already exists. Rename it or use a fresh checkout before preparing another stage.')
    java_home = os.environ.get('JAVA_HOME')
    local = next((ROOT / 'tools').glob('jdk*/Contents/Home/bin/java'), None)
    java = str(Path(java_home) / 'bin/java') if java_home else str(local) if local else shutil.which('java')
    if not java:
        raise SystemExit('Java 21 required; set JAVA_HOME.')
    subprocess.run([java, '-Duser.home=' + str(ROOT / 'tools/apktool-home'), '-jar', str(ROOT / 'tools/apktool.jar'), 'd', str(args.apk.resolve()), '-o', str(ROOT / 'patched')], check=True)
    for script in ('patch_apk.py', 'patch_connection.py'):
        subprocess.run([os.sys.executable, str(ROOT / script)], cwd=ROOT, check=True)
    net = ROOT / 'patched/smali/ai/muse/gadget/net'
    shutil.copy2(ROOT / 'patches/SignalQueue.smali', net / 'SignalQueue.smali')
    path = net / 'LinkSession.smali'
    code = path.read_text()
    # Attach only our command extensions to the existing service.
    a = code.index('.method private final commandSpecs(')
    b = code.index('.end method', a)
    section = code[a:b]
    marker = '    return-object v0'
    assert section.count(marker) == 1
    section = section.replace(marker, '    invoke-static {v0}, Lai/muse/gadget/voice/VoiceBridge;->commands(Ljava/util/Map;)Ljava/util/Map;\n    move-result-object v0\n' + marker)
    code = code[:a] + section + code[b:]
    a = code.index('.method private final runCommand(')
    b = code.index('.end annotation', a) + len('.end annotation')
    code = code[:b] + '\n\n    invoke-static {p1, p2}, Lai/muse/gadget/voice/VoiceBridge;->invoke(Ljava/lang/String;Lorg/json/JSONObject;)Ljava/util/Map;\n    move-result-object v0\n    if-eqz v0, :not_voice_command\n    return-object v0\n    :not_voice_command\n' + code[b:]
    path.write_text(code)
    shutil.copy2(ROOT / 'app/AndroidManifest.xml', ROOT / 'patched/AndroidManifest.xml')
    assets = ROOT / 'patched/assets'
    assets.mkdir(exist_ok=True)
    for name in required:
        target = assets / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(args.assets / name, target)
    # An optional test fixture and chat-header avatar are local inputs too.
    if args.assets:
        for name in ('voice-test.pcm', 'muse/idle.gif'):
            src = args.assets / name
            if src.is_file():
                target = assets / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, target)
    subprocess.run([os.sys.executable, str(ROOT / 'setup.py')], check=True)
    print('Local workspace ready. Run python3 build_voice.py.')

if __name__ == '__main__':
    main()
