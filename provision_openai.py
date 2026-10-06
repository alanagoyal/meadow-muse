"""Provision a personal Meadow over USB without printing or embedding its API key."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
adb = os.environ.get("ADB") or shutil.which("adb")
if not adb:
    raise SystemExit("Set ADB to your platform-tools/adb path.")
key = None
for line in (ROOT / ".env.local").read_text().splitlines():
    name, separator, value = line.partition("=")
    if separator and name.strip() == "OPENAI_API_KEY":
        key = value.strip().strip("\"'")
if not key or not key.startswith("sk-") or "\n" in key or len(key)>1024:
    raise SystemExit("A valid OPENAI_API_KEY is required in .env.local.")
subprocess.run([adb, "shell", "am", "force-stop", "ai.muse.gadget.fixed"], check=True, stdout=subprocess.DEVNULL)
directory = "/sdcard/Android/data/ai.muse.gadget.fixed/files"
subprocess.run([adb, "shell", "mkdir", "-p", directory], check=True)
subprocess.run([adb, "shell", "cat > " + directory + "/openai-key.pending"], input=key.encode(), check=True)
subprocess.run([adb, "shell", "am", "start", "-W", "-n", "ai.muse.gadget.fixed/ai.muse.gadget.voice.VoiceActivity"], check=True, stdout=subprocess.DEVNULL)
# The app imports into private internal storage on startup and deletes the staging file.
check = subprocess.run([adb,"shell","test","!","-f",directory+"/openai-key.pending"])
if check.returncode:
    raise SystemExit("Provisioning file is still pending. Open Muse to complete the import.")
print("OpenAI key provisioned; temporary transfer file removed.")
