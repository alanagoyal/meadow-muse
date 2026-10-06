"""Download and verify the dependencies used by the saved Meadow build."""
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    dependencies = json.loads((ROOT / "dependencies.json").read_text())
    declared = {d["path"] for d in dependencies}
    for dependency in dependencies:
        path = ROOT / dependency["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or digest(path) != dependency["sha256"]:
            print(f"Downloading {dependency['path']}", flush=True)
            temporary = path.with_suffix(path.suffix + ".download")
            urllib.request.urlretrieve(dependency["url"], temporary)
            if digest(temporary) != dependency["sha256"]:
                temporary.unlink()
                raise RuntimeError(f"Checksum mismatch: {dependency['path']}")
            temporary.replace(path)
    for name in ("vosk", "jna"):
        if f"tools/voice/{name}.aar" not in declared:
            continue
        with zipfile.ZipFile(ROOT / f"tools/voice/{name}.aar") as archive:
            archive.extractall(ROOT / f"tools/voice/{name}")
        if not (ROOT / "patched").exists():
            continue
        destination = ROOT / "patched/lib/arm64-v8a"
        destination.mkdir(parents=True, exist_ok=True)
        for library in (ROOT / f"tools/voice/{name}/jni/arm64-v8a").glob("*.so"):
            shutil.copy2(library, destination / library.name)
    if (ROOT / "patched").exists() and "tools/voice/model.zip" in declared:
        (ROOT / "patched/assets").mkdir(exist_ok=True)
        shutil.copy2(ROOT / "tools/voice/model.zip", ROOT / "patched/assets/voice-model.zip")
    for directory in ("tools/voice/classes", "tools/voice/dex", "signed-voice"):
        (ROOT / directory).mkdir(parents=True, exist_ok=True)
    print("Dependencies ready. Run python3 build_voice.py with Java 21 installed.")


if __name__ == "__main__":
    main()
