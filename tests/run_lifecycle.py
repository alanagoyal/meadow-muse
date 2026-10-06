"""Run the Android-independent event lifecycle tests with the existing build tools."""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
local_java = next(iter(sorted((root / 'tools').glob('jdk*/Contents/Home/bin/java'))), None)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else str(local_java) if local_java else shutil.which('java')
if not java:
    raise SystemExit('Java 21 required; run setup.py and configure JAVA_HOME.')
output = root / 'tools/lifecycle-tests'
output.mkdir(parents=True, exist_ok=True)
subprocess.run([java, '-jar', str(root / 'tools/voice/ecj.jar'), '-8', '-d', str(output),
                str(root / 'voice-src/ai/muse/gadget/voice/TurnLifecycle.java'),
                str(root / 'tests/TurnLifecycleTest.java'),
                str(root / 'voice-src/ai/muse/gadget/voice/MuseMatte.java'),
                str(root / 'tests/MuseMatteTest.java'),
                str(root / 'voice-src/ai/muse/gadget/voice/SceneStage.java'),
                str(root / 'tests/SceneStageTest.java')], check=True)
subprocess.run([java, '-cp', str(output), 'ai.muse.gadget.voice.TurnLifecycleTest'], check=True)

subprocess.run([java, '-cp', str(output), 'ai.muse.gadget.voice.MuseMatteTest'], check=True)

subprocess.run([java, '-cp', str(output), 'ai.muse.gadget.voice.SceneStageTest'], check=True)
