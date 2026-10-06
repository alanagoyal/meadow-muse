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
  spec.put("required",Collections.singletonMap("text",param));spec.put("optional",Collections.emptyMap());
  out.put("voice.speak",spec);return out;
 }
 public static Map<String,Object> invoke(String command,JSONObject params){
  if(!"voice.speak".equals(command))return null;
  String text=params.optString("text","").trim();
  VoiceActivity a=activity.get();
  if(a==null||text.isEmpty())return result(false,"voice screen is not open or text is empty");
  a.onChat(text);return result(true,"reply queued for speech");
 }
 private static Map<String,Object> result(boolean ok,String message){
  Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("ok",ok);
  if(ok)r.put("payload",Collections.singletonMap("status",message));else r.put("error",message);return r;
 }
}
