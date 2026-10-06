from pathlib import Path
import os
import json
import shutil
import subprocess,zipfile
root=Path(__file__).resolve().parent
os.chdir(root)
java_home=os.environ.get('JAVA_HOME')
local_java=next(iter(sorted((root/'tools').glob('jdk*/Contents/Home/bin/java'))),None)
java=str(Path(java_home)/'bin/java') if java_home else str(local_java) if local_java else shutil.which('java')
if not java:
    raise SystemExit('Java 21 is required. Set JAVA_HOME or install Java on PATH.')
def run(*args):subprocess.run(args,check=True)
cp=':'.join('tools/voice/'+p for p in ['android.jar','vosk/classes.jar','jna/classes.jar','kotlin.jar','json.jar'])
declared={entry['path'] for entry in json.loads(Path('dependencies.json').read_text())}
libs=[f'tools/voice/{name}/classes.jar' for name in ('vosk','jna') if f'tools/voice/{name}.aar' in declared]
sources = sorted(Path('voice-src/ai/muse/gadget/voice').glob('*.java'))
# Drop classes from removed methods and anonymous callbacks before rebuilding.
for compiled in Path('tools/voice/classes/ai/muse/gadget/voice').glob('*.class'):
    compiled.unlink()
run(java,'-jar','tools/voice/ecj.jar','-8','-cp',cp,'-d','tools/voice/classes',
    'voice-stubs/ai/muse/gadget/service/GadgetService.java', *map(str, sources))
with zipfile.ZipFile('tools/voice/voice-classes.jar','w') as z:
 for p in Path('tools/voice/classes/ai/muse/gadget/voice').glob('*.class'):z.write(p,str(p.relative_to('tools/voice/classes')))
run(java,'-cp','tools/voice/r8.jar','com.android.tools.r8.D8','--min-api','29','--lib','tools/voice/android.jar','--output','tools/voice/dex','tools/voice/voice-classes.jar',*libs)
run(java,'-Duser.home='+str(root/'tools/apktool-home'),'-jar','tools/apktool.jar','b','patched','-o','meadow-voice.apk')
with zipfile.ZipFile('meadow-voice.apk','a',compression=zipfile.ZIP_DEFLATED) as z:z.write('tools/voice/dex/classes.dex','classes2.dex')
Path('signed-voice/meadow-voice-aligned-debugSigned.apk').unlink(missing_ok=True)
Path('signed-voice/meadow-voice-aligned-debugSigned.apk.idsig').unlink(missing_ok=True)
run(java,'-Duser.home='+str(root/'tools/signer-home'),'-jar','tools/signer.jar','--apks','meadow-voice.apk','--out','signed-voice')

with zipfile.ZipFile("signed-voice/meadow-voice-aligned-debugSigned.apk") as z: assert z.read("classes2.dex")==Path("tools/voice/dex/classes.dex").read_bytes()
