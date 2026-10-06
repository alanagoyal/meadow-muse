package ai.muse.gadget.voice;
import java.util.*;
import java.lang.ref.WeakReference;
import org.json.JSONObject;
public final class VoiceBridge {
    static WeakReference<VoiceActivity> activity=new WeakReference<VoiceActivity>(null);
    public static Map<String,Object> commands(Map<String,Object> old){
        Map<String,Object> out=new LinkedHashMap<String,Object>(old),spec=new LinkedHashMap<String,Object>(),required=new LinkedHashMap<String,Object>();
        spec.put("description","Reply to the current Meadow chat message. Then write the same complete answer in Muse chat.");
        for(String name:Arrays.asList("text","turnId")){Map<String,Object> p=new LinkedHashMap<String,Object>();p.put("type","string");p.put("description",name.equals("text")?"The complete plain-text answer.":"Copy the latest Meadow turnId exactly.");required.put(name,p);}
        spec.put("required",required);out.put("voice.speak",spec);return out;
    }
    public static Map<String,Object> invoke(String command,JSONObject params){
        if(!"voice.speak".equals(command))return null;
        VoiceActivity a=activity.get();String error=a==null?"Open the Meadow chat screen.":a.submitReply(params.optString("text",""),params.optString("turnId",""));
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("ok",error==null);
        if(error==null)result.put("payload",Collections.singletonMap("status","Reply accepted for speech. Write the same complete answer in Muse chat."));else result.put("error",error);return result;
    }
}
