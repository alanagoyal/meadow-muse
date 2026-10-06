package ai.muse.gadget.voice;
import java.util.*;
import java.lang.ref.WeakReference;
import org.json.JSONObject;
public final class VoiceBridge {
 static WeakReference<VoiceActivity> activity=new WeakReference<VoiceActivity>(null);
 public static Map<String,Object> commands(Map<String,Object> old){
  Map<String,Object> out=new LinkedHashMap<String,Object>(old);
  Map<String,Object> spec=new LinkedHashMap<String,Object>();
  spec.put("description","Speak a short natural-language reply aloud on the Meadow and animate Muse. Use this to answer messages originating from this voice device.");
  Map<String,Object> param=new LinkedHashMap<String,Object>();param.put("type","string");param.put("description","The words to speak, without markdown.");
  spec.put("required",Collections.singletonMap("text",param));
  Map<String,Object> reaction=new LinkedHashMap<String,Object>();
  reaction.put("type","string");
  reaction.put("description","Muse’s reaction to the conversation: neutral, happy (celebration), curious (exploring an idea), calm (reassurance), working (doing a task), or dance (when asked to dance). Defaults to neutral.");
  spec.put("optional",Collections.singletonMap("reaction",reaction));
  out.put("voice.speak",spec);return out;
 }
 public static Map<String,Object> invoke(String command,JSONObject params){
  if(!"voice.speak".equals(command))return null;
  String text=params.optString("text","").trim();
  VoiceActivity a=activity.get();
  if(a==null||text.isEmpty())return result(false,"voice screen is not open or text is empty");
  String reaction=params.optString("reaction","neutral").toLowerCase(Locale.ROOT);
  if(!Arrays.asList("neutral","happy","curious","calm","working","dance").contains(reaction))reaction="neutral";
  android.util.Log.d("MuseVoice","Command reaction: "+reaction+" (provided="+params.has("reaction")+")");
  a.onReply(text,reaction);return result(true,"reply queued for speech");
 }
 private static Map<String,Object> result(boolean ok,String message){
  Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("ok",ok);
  if(ok)r.put("payload",Collections.singletonMap("status",message));else r.put("error",message);return r;
 }
}
