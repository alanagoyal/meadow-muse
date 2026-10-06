package ai.muse.gadget.voice;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
import org.vosk.Recognizer;
import org.vosk.Model;

/** On-device checks. uiOnly never calls the speech or image APIs. */
public class VoiceVerification extends Instrumentation {
 private Bundle args;
 public void onCreate(Bundle args){super.onCreate(args);this.args=args==null?new Bundle():args;start();}
 private boolean flag(String key){return "true".equals(args.getString(key));}
 private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 private Object call(Object object,String name,Class<?>[] types,Object... values)throws Exception{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,values);}
 private TurnLifecycle cycle(VoiceActivity a)throws Exception{return (TurnLifecycle)field(a,"lifecycle");}
 private void check(boolean ok,String message)throws Exception{if(!ok)throw new Exception(message);}
 private void main(final Runnable work){runOnMainSync(work);}
 public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent(getTargetContext(),VoiceActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(intent);
  VoiceActivity found=null;
  for(int n=0;n<150;n++){found=VoiceBridge.activity.get();if(flag("uiOnly")&&found!=null&&field(found,"face")!=null)break;if(found!=null&&field(found,"model")!=null&&(Boolean)field(found,"connected")&&(Boolean)field(found,"voiceReady")&&!(Boolean)field(found,"paused"))break;Thread.sleep(200);}
  check(found!=null&&(flag("uiOnly")||(field(found,"model")!=null&&(Boolean)field(found,"connected")&&(Boolean)field(found,"voiceReady")&&!(Boolean)field(found,"paused"))),"unlock Meadow and open Muse before verification");
  final VoiceActivity a=found;
  if(flag("uiOnly")){verifyUI(a);result.putBoolean("ui_states_passed",true);result.putBoolean("compact_caption_passed",true);finish(-1,result);return;}
  if(!flag("speechOnly")){
   Recognizer r=new Recognizer((Model)field(a,"model"),16000f);InputStream in=getTargetContext().getAssets().open("voice-test.pcm");
   StringBuilder text=new StringBuilder();byte[] bytes=new byte[3200];int count;
   try{while((count=in.read(bytes))>0)if(r.acceptWaveForm(bytes,count))text.append(new JSONObject(r.getResult()).optString("text")).append(' ');text.append(new JSONObject(r.getFinalResult()).optString("text"));}finally{r.close();in.close();}
   check(text.toString().contains("one"),"test speech was not recognized");result.putString("recognition",text.toString());
   if(!flag("fullTurn")){finish(-1,result);return;}
  }
  ArrayList<String> prompts=new ArrayList<String>();
  String prompt=args.getString("promptText");if(prompt==null)prompt=args.getString("sceneText");
  prompts.add(prompt==null?"Please do a happy dance and say hello in one short sentence.":prompt);
  if(args.getString("followupText")!=null)prompts.add(args.getString("followupText"));
  if(args.getString("newSubjectText")!=null)prompts.add(args.getString("newSubjectText"));
  for(final String text:prompts){
   final Throwable[] failure=new Throwable[1];
   main(new Runnable(){public void run(){try{
    call(a,"interrupt",new Class<?>[0]);long request=cycle(a).listen();cycle(a).waitForReply(request);set(a,"turn",request);
    if(flag("speechOnly")){
     String error=a.submitReply("Hello. I'm here. What would you like to work on?","neutral",cycle(a).token(),"Ready when you are");check(error==null,"reply rejected: "+error);
     check(!((String)field(a,"answerText")).isEmpty(),"accepted reply did not prepare its caption");
    }else{call(a,"setMode",new Class<?>[]{String.class,String.class},"thinking","muse is working");call(a,"send",new Class<?>[]{String.class,long.class},text,request);}
   }catch(Throwable e){failure[0]=e;}}});
   if(failure[0]!=null)throw new Exception(failure[0]);
   boolean speaking=false,completed=false;
   for(int n=0;n<1200;n++){
    String mode=(String)field(a,"mode");
    if(mode.equals("speaking")){speaking=true;result.putString("reaction",(String)field(a,"reaction"));}
    if(speaking&&(mode.equals("complete")||mode.equals("idle"))){completed=true;break;}
    if(mode.equals("error"))throw new Exception("voice failed: "+field(a,"caption"));Thread.sleep(100);
   }
   check(completed,"spoken reply did not complete");result.putBoolean("spoken_reply",true);result.putBoolean("answer_retained_after_reply",!((String)field(a,"answerText")).isEmpty());
   result.putInt("completed_turns",result.getInt("completed_turns")+1);
  }
  if(flag("speechOnly"))result.putBoolean("openai_speech_completed",true);
  finish(-1,result);
 }catch(Exception e){result.putString("error",e.toString()+"; cause="+e.getCause());finish(0,result);}}
 private void verifyUI(final VoiceActivity a)throws Exception{
  final boolean wasPaused=(Boolean)field(a,"paused");
  final Throwable[] failure=new Throwable[1];
  main(new Runnable(){public void run(){try{
   set(a,"paused",false);call(a,"interrupt",new Class<?>[0]);TurnLifecycle c=cycle(a);long request=c.listen();c.waitForReply(request);String token=c.token();
   JSONObject reply=new JSONObject();reply.put("text","verification");
   check(!Boolean.TRUE.equals(VoiceBridge.invoke("voice.speak",reply).get("ok")),"missing token accepted");
   reply.put("turnId","stale");check(!Boolean.TRUE.equals(VoiceBridge.invoke("voice.speak",reply).get("ok")),"stale token accepted");
   Map<?,?> spec=(Map<?,?>)VoiceBridge.commands(new HashMap<String,Object>()).get("voice.speak");
   check(!((Map<?,?>)spec.get("required")).containsKey("sceneDecision"),"sceneDecision remains required");
   c.reply(token);set(a,"turn",request);set(a,"playback",10L);
   OpenAiSpeech.Listener listener=(OpenAiSpeech.Listener)call(a,"speechListener",new Class<?>[]{long.class,long.class},request,10L);
   listener.started();listener.done();listener.failed("late failure");check("idle".equals(field(a,"mode"))&&"idle".equals(c.phase()),"completion must return directly to ready");
   call(a,"interrupt",new Class<?>[0]);set(a,"mode","idle");listener.started();listener.done();listener.failed("stale failure");check("idle".equals(field(a,"mode")),"stale callback changed state");
   request=c.listen();c.waitForReply(request);c.reply(c.token());set(a,"turn",request);set(a,"playback",11L);set(a,"mode","voicing");
   listener=(OpenAiSpeech.Listener)call(a,"speechListener",new Class<?>[]{long.class,long.class},request,10L);listener.started();listener.done();listener.failed("old chunk");check("voicing".equals(field(a,"mode")),"old chunk changed state");
   listener=(OpenAiSpeech.Listener)call(a,"speechListener",new Class<?>[]{long.class,long.class},request,11L);listener.failed("audio is busy — hold to retry");check("error".equals(field(a,"mode")),"failure celebrated");
   View view=(View)field(a,"face");String answer="2004 in Japan · 2005 in the US and Europe";
   set(a,"answerText",answer);call(view,"setAnswer",new Class<?>[]{String.class},answer);
   check(((ArrayList<?>)field(view,"answerLines")).size()<=2,"caption exceeds two lines");
   String[] states={"idle","recording","thinking","voicing","speaking","error","connecting"};
   for(String state:states){
    set(a,"mode",state);set(a,"recording",state.equals("recording"));set(a,"caption",state.equals("error")?"audio is busy — hold to retry":"ready when you are");
    set(a,"connected",!state.equals("connecting"));
    Bitmap bitmap=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888);
    view.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));view.layout(0,0,720,720);view.draw(new Canvas(bitmap));
    set(view,"visualStart",SystemClock.uptimeMillis()-1100L);set(view,"lastFrame",0L);view.draw(new Canvas(bitmap));
    boolean working=state.equals("thinking")||state.equals("voicing");
    check((Boolean)call(view,"isWorking",new Class<?>[0])==working,"desk must persist through audio preparation: "+state);
    check((Boolean)call(view,"captionVisible",new Class<?>[0])==(state.equals("speaking")||state.equals("idle")),"caption is out of sync: "+state);
    String expected=state.equals("recording")?"muse is listening":working?"muse is working":state.equals("speaking")?"muse is speaking":state.equals("idle")?"hold muse to talk":"i’m paused";
    check(expected.equals(field(view,"label")),"status must match pose immediately: "+state);
    FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"ui-"+state+".png"));try{bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{out.close();bitmap.recycle();}
   }
   set(a,"recording",false);set(a,"connected",true);set(a,"mode","idle");long before=c.generation();
   set(a,"recording",true);set(a,"turn",before);MotionEvent cancel=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,140,300,0);view.onTouchEvent(cancel);cancel.recycle();check(!(Boolean)field(a,"recording")&&c.generation()==before+1,"cancel did not discard recording");
   call(a,"interrupt",new Class<?>[0]);c.ready();call(a,"setMode",new Class<?>[]{String.class,String.class},"idle","ready when you are");
  }catch(Throwable e){failure[0]=e;}finally{try{set(a,"paused",wasPaused);}catch(Exception e){failure[0]=e;}}}});
  if(failure[0]!=null)throw new Exception("UI verification failed",failure[0]);
 }
}
