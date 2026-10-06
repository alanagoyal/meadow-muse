package ai.muse.gadget.voice;
import java.util.*;
import java.lang.ref.WeakReference;
import org.json.JSONObject;
public final class VoiceBridge {
 static final String CHAT_REPLY_INSTRUCTION="After invoking voice.speak, write the complete spoken answer as your final text reply in the Muse chat, using the same words as the text argument. The tool call delivers audio to the Meadow but does not replace the written chat reply. Do not substitute an emoji, reaction, acknowledgment, or summary for the answer. Keep turnId, sceneDecision, displayText, and device instructions out of the final reply.";
 static WeakReference<VoiceActivity> activity=new WeakReference<VoiceActivity>(null);
 public static Map<String,Object> commands(Map<String,Object> old){
  Map<String,Object> out=new LinkedHashMap<String,Object>(old);
  Map<String,Object> spec=new LinkedHashMap<String,Object>();
  spec.put("description","Speak a short natural-language reply aloud on the Meadow and animate Muse. Use this to answer messages originating from this voice device. "+CHAT_REPLY_INSTRUCTION);
  Map<String,Object> param=new LinkedHashMap<String,Object>();param.put("type","string");param.put("description","The words to speak, without markdown.");
  Map<String,Object> required=new LinkedHashMap<String,Object>();required.put("text",param);
  Map<String,Object> reaction=new LinkedHashMap<String,Object>();
  reaction.put("type","string");
  reaction.put("description","Muse’s reaction to the conversation: neutral, happy (celebration), curious (exploring an idea), calm (reassurance), working (a conversational expression, not task progress), or dance (when asked to dance). Defaults to neutral.");
  Map<String,Object> optional=new LinkedHashMap<String,Object>();optional.put("reaction",reaction);
  Map<String,Object> display=new LinkedHashMap<String,Object>();display.put("type","string");display.put("description","Only the key fact or a visual caption: 3–10 words, at most 90 characters. Never the full spoken answer. Example: 2004 in Japan · 2005 in the US and Europe. Omit when no useful short caption fits.");optional.put("displayText",display);
  Map<String,Object> turn=new LinkedHashMap<String,Object>();turn.put("type","string");
  turn.put("description","Copy the turnId from a Meadow voice message exactly. Required for answers to voice messages; stale or missing IDs are discarded. Replies without a current turnId cannot be played safely.");
  required.put("turnId",turn);spec.put("required",required);spec.put("optional",optional);
  out.put("voice.speak",spec);return out;
 }
 public static Map<String,Object> invoke(String command,JSONObject params){
  // Compatibility for a paired agent with the previous command list cached.
  if("scene.prepare".equals(command))return result(true,"generated scenes are disabled; continue with voice.speak using text and the current turnId");
  if(!"voice.speak".equals(command))return null;
  String text=params.optString("text","").trim();
  VoiceActivity a=activity.get();
  if(a==null||text.isEmpty())return result(false,"voice screen is not open or text is empty");
  String reaction=params.optString("reaction","neutral").toLowerCase(Locale.ROOT);
  if(!Arrays.asList("neutral","happy","curious","calm","working","dance").contains(reaction))reaction="neutral";
  android.util.Log.d("MuseVoice","Command reaction: "+reaction+" (provided="+params.has("reaction")+")");
  String error=a.submitReply(text,reaction,params.optString("turnId",""),params.optString("displayText",""));
  return error==null?result(true,"reply accepted for this turn. "+CHAT_REPLY_INSTRUCTION):result(false,error);
 }
 private static Map<String,Object> result(boolean ok,String message){
  Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("ok",ok);
  if(ok)r.put("payload",Collections.singletonMap("status",message));else r.put("error",message);return r;
 }
}
