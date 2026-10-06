package ai.muse.gadget.voice;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.io.*;
public class VoiceVerification extends Instrumentation {
 private boolean speechOnly, uiOnly, fullTurn, sceneOnly, sceneFixture;
 private String sceneText, followupText, newSubjectText;
 public void onCreate(Bundle args){super.onCreate(args);newSubjectText=args==null?null:args.getString("newSubjectText");followupText=args==null?null:args.getString("followupText");sceneFixture=args!=null&&"true".equals(args.getString("sceneFixture"));sceneOnly=args!=null&&"true".equals(args.getString("sceneOnly"));sceneText=args==null?null:args.getString("sceneText");speechOnly=args!=null&&"true".equals(args.getString("speechOnly"));uiOnly=args!=null&&"true".equals(args.getString("uiOnly"));fullTurn=args!=null&&"true".equals(args.getString("fullTurn"));start();}
 private void verifyReplyBridge(final VoiceActivity activity)throws Exception{
  final Throwable[] failure=new Throwable[1];
  runOnMainSync(new Runnable(){public void run(){
   boolean paused=true,connected=false,ready=false;
   try{
    paused=(Boolean)field(activity,"paused");connected=(Boolean)field(activity,"connected");ready=(Boolean)field(activity,"voiceReady");
    setField(activity,"paused",false);setField(activity,"connected",true);setField(activity,"voiceReady",true);
    String previous="";
    for(int step=0;step<2;step++){
     Method interrupt=VoiceActivity.class.getDeclaredMethod("interrupt");interrupt.setAccessible(true);interrupt.invoke(activity);
     TurnLifecycle cycle=activityCycle(activity);long request=cycle.listen();cycle.waitForReply(request);String token=cycle.token();
     setField(activity,"turn",request);cycle.prepareScene(token);setField(activity,"scenePending",true);
     JSONObject reply=new JSONObject();reply.put("text","Verification reply");reply.put("sceneDecision","keep");
     if(Boolean.TRUE.equals(VoiceBridge.invoke("voice.speak",reply).get("ok")))throw new Exception("missing turnId accepted");
     if(step>0){reply.put("turnId",previous);if(Boolean.TRUE.equals(VoiceBridge.invoke("voice.speak",reply).get("ok")))throw new Exception("previous turnId accepted");}
     reply.put("turnId",token);java.util.Map<String,Object> accepted=VoiceBridge.invoke("voice.speak",reply);
     if(!Boolean.TRUE.equals(accepted.get("ok")))throw new Exception("current reply rejected: "+accepted);
     if(Boolean.TRUE.equals(VoiceBridge.invoke("voice.speak",reply).get("ok")))throw new Exception("duplicate reply accepted");previous=token;
    }
   }catch(Throwable e){failure[0]=e;}
   finally{try{Method interrupt=VoiceActivity.class.getDeclaredMethod("interrupt");interrupt.setAccessible(true);interrupt.invoke(activity);setField(activity,"paused",paused);setField(activity,"connected",connected);setField(activity,"voiceReady",ready);}catch(Exception e){failure[0]=e;}}
  }});
  if(failure[0]!=null)throw new Exception("Reply bridge verification failed",failure[0]);
 }
 private Object field(VoiceActivity a,String name)throws Exception{Field f=VoiceActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
 public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent(getTargetContext(),VoiceActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(i);
  VoiceActivity a=null;
  for(int n=0;n<150;n++){a=VoiceBridge.activity.get();if(uiOnly&&a!=null&&field(a,"face")!=null)break;if(a!=null&&field(a,"model")!=null&&(Boolean)field(a,"connected")&&(Boolean)field(a,"voiceReady")&&(sceneOnly||uiOnly||!(Boolean)field(a,"paused")))break;Thread.sleep(200);}
  if(a==null||(!uiOnly&&(field(a,"model")==null||!(Boolean)field(a,"connected")||!(Boolean)field(a,"voiceReady")||(!sceneOnly&&(Boolean)field(a,"paused")))))throw new Exception("unlock Meadow and open Muse before verification");
  if(sceneOnly){
   final VoiceActivity activity=a;
   final java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> generated=new java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap>();
   final java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<String>();
   final OpenAiScene[] client=new OpenAiScene[1];
   final String prompt=sceneText==null?"Can you help me pick a cozy restaurant for dinner in San Francisco?":sceneText;
   long started=android.os.SystemClock.uptimeMillis();
   if(sceneFixture)generated.set(android.graphics.BitmapFactory.decodeFile(new File(activity.getExternalFilesDir(null),"scene-preview.png").getAbsolutePath()));
   else runOnMainSync(new Runnable(){public void run(){
    client[0]=new OpenAiScene(activity);
    client[0].generate(prompt,"",new OpenAiScene.Listener(){
     public void image(android.graphics.Bitmap image){generated.set(image);}
     public void failed(String hint){error.set(hint);}
    });
   }});
   for(int n=0;n<1000&&generated.get()==null&&error.get()==null;n++)Thread.sleep(200);
   if(error.get()!=null)throw new Exception(error.get());
   final android.graphics.Bitmap background=generated.get();
   if(background==null)throw new Exception("scene generation timed out");
   result.putLong("image_ms",android.os.SystemClock.uptimeMillis()-started);
   FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"scene-preview.png"));
   try{background.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{out.close();}
   final Throwable[] failed=new Throwable[1];
   runOnMainSync(new Runnable(){public void run(){
    Object oldScene=null,oldPaused=null;
    try{
     oldScene=field(activity,"sceneBitmap");oldPaused=field(activity,"paused");
     setField(activity,"sceneBitmap",background);setField(activity,"paused",false);setField(activity,"mode","speaking");setField(activity,"sceneStatus","");
     android.view.View view=(android.view.View)field(activity,"face");
     Method cutout=view.getClass().getDeclaredMethod("characterCutout",android.graphics.Movie.class,int.class);cutout.setAccessible(true);
     Field idle=view.getClass().getDeclaredField("idle");idle.setAccessible(true);
     android.graphics.Movie movie=(android.graphics.Movie)idle.get(view);
     android.graphics.Bitmap first=(android.graphics.Bitmap)cutout.invoke(view,movie,0);
     int[] firstPixels=new int[first.getWidth()*first.getHeight()];first.getPixels(firstPixels,0,first.getWidth(),0,0,first.getWidth(),first.getHeight());
     android.graphics.Bitmap next=(android.graphics.Bitmap)cutout.invoke(view,movie,200);
     int[] nextPixels=new int[firstPixels.length];next.getPixels(nextPixels,0,next.getWidth(),0,0,next.getWidth(),next.getHeight());
     if(java.util.Arrays.equals(firstPixels,nextPixels))throw new Exception("original animation is not advancing");
     if((next.getPixel(0,0)>>>24)!=0||(next.getPixel(160,120)>>>24)==0)throw new Exception("Muse cutout lost its face or retained white background");
     long start=android.os.SystemClock.uptimeMillis();setField(activity,"sceneStart",start);
     Method opacity=view.getClass().getDeclaredMethod("sceneOpacity",long.class);opacity.setAccessible(true);
     Method museOpacity=view.getClass().getDeclaredMethod("sceneMuseOpacity",long.class);museOpacity.setAccessible(true);
     if((Float)opacity.invoke(view,start)!=1f||(Float)museOpacity.invoke(view,start)!=0f||(Float)museOpacity.invoke(view,start+200L)!=0f||(Float)museOpacity.invoke(view,start+600L)!=1f)throw new Exception("scene-first entrance timing wrong");
     for(int f=0;f<4;f++){
      setField(activity,"sceneStart",android.os.SystemClock.uptimeMillis()-(f==0?0:f==1?200:f==2?400:1080));
      android.graphics.Bitmap screen=android.graphics.Bitmap.createBitmap(720,720,android.graphics.Bitmap.Config.ARGB_8888);
      view.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,720,720);
      Field labelStart=view.getClass().getDeclaredField("labelStart");labelStart.setAccessible(true);labelStart.setLong(view,0L);
      view.draw(new android.graphics.Canvas(screen));
      FileOutputStream capture=new FileOutputStream(new File(activity.getExternalFilesDir(null),"scene-frame-"+f+".png"));
      try{screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,capture);}finally{capture.close();screen.recycle();}
     }
     setField(activity,"recording",true);if((Float)opacity.invoke(view,start+360L)!=0f)throw new Exception("scene displayed while listening");setField(activity,"recording",false);
    }catch(Throwable e){failed[0]=e;}
    finally{try{setField(activity,"sceneBitmap",oldScene);setField(activity,"paused",oldPaused);}catch(Exception ignored){}}
   }});
   if(failed[0]!=null)throw new Exception("scene rendering failed: "+failed[0],failed[0]);
   background.recycle();
   result.putBoolean("fresh_scene",!sceneFixture);result.putBoolean("original_animation_composited",true);
   finish(-1,result);return;
  }
  if(uiOnly){final VoiceActivity test=a;final boolean connected=(Boolean)field(a,"connected"),ready=(Boolean)field(a,"voiceReady");runOnMainSync(new Runnable(){public void run(){try{setField(test,"connected",true);setField(test,"voiceReady",true);}catch(Exception e){throw new RuntimeException(e);}}});try{verifyReplyBridge(a);verifyCallbacks(a);verifySceneCancellation(a);verifyFullBleed(a);verifyUI(a);}finally{runOnMainSync(new Runnable(){public void run(){try{setField(test,"connected",connected);setField(test,"voiceReady",ready);}catch(Exception e){throw new RuntimeException(e);}}});}result.putBoolean("ui_states_passed",true);finish(-1,result);return;}
  if(speechOnly){
   final VoiceActivity activity=a;
   runOnMainSync(new Runnable(){public void run(){TurnLifecycle cycle=activityCycle(activity);long request=cycle.listen();cycle.waitForReply(request);activity.onReply("Hello. I'm here. What would you like to work on?","neutral",cycle.token());}});
   boolean heard=false,completed=false;
   for(int n=0;n<450;n++){String mode=(String)field(a,"mode");if(mode.equals("speaking"))heard=true;if(heard&&mode.equals("idle")){completed=true;break;}if(mode.equals("error"))throw new Exception("OpenAI speech failed: "+field(a,"caption"));Thread.sleep(200);}
   if(!completed)throw new Exception("OpenAI playback did not complete");
   result.putBoolean("openai_speech_completed",true);finish(-1,result);return;
  }
  final VoiceActivity preview=a;
  runOnMainSync(new Runnable(){public void run(){try{
   android.view.View v=(android.view.View)field(preview,"face");
   android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(720,720,android.graphics.Bitmap.Config.ARGB_8888);
   v.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY));v.layout(0,0,720,720);
   v.draw(new android.graphics.Canvas(bitmap));
   FileOutputStream out=new FileOutputStream(new File(preview.getExternalFilesDir(null),"voice-preview.png"));bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);out.close();
  }catch(Exception e){throw new RuntimeException(e);}}});
  Recognizer r=new Recognizer((Model)field(a,"model"),16000f);
  InputStream in=getTargetContext().getAssets().open("voice-test.pcm");byte[] b=new byte[3200];StringBuilder text=new StringBuilder();int n;
  while((n=in.read(b))>0){if(r.acceptWaveForm(b,n))text.append(new JSONObject(r.getResult()).optString("text")).append(' ');}
  text.append(new JSONObject(r.getFinalResult()).optString("text"));r.close();in.close();
  result.putString("recognition",text.toString());
  if(!text.toString().contains("one"))throw new Exception("test speech was not recognized");
  if(!fullTurn){finish(-1,result);return;}
  final VoiceActivity activity=a;
  String firstPrompt=sceneText==null?"Please do a happy dance and say hello in one short sentence.":sceneText;
  String[] prompts=followupText==null?new String[]{firstPrompt}:newSubjectText==null?new String[]{firstPrompt,followupText}:new String[]{firstPrompt,followupText,newSubjectText};
  for(int promptIndex=0;promptIndex<prompts.length;promptIndex++){
  final String fullPrompt=prompts[promptIndex];
  final Method send=VoiceActivity.class.getDeclaredMethod("send",String.class,long.class);send.setAccessible(true);
  runOnMainSync(new Runnable(){public void run(){try{Method mode=VoiceActivity.class.getDeclaredMethod("setMode",String.class,String.class);mode.setAccessible(true);TurnLifecycle cycle=activityCycle(activity);long request=cycle.listen();cycle.waitForReply(request);setField(activity,"turn",request);mode.invoke(activity,"thinking","muse is working");send.invoke(activity,fullPrompt,field(activity,"turn"));}catch(Exception e){throw new RuntimeException(e);}}});
  boolean speaking=false, completed=false;
  for(n=0;n<450;n++){String mode=(String)field(a,"mode");if(mode.equals("speaking")){speaking=true;result.putString("reaction",(String)field(a,"reaction"));}if(speaking&&mode.equals("idle")){completed=true;break;}if(mode.equals("error"))throw new Exception("voice turn reported an error");Thread.sleep(200);}
  result.putBoolean("spoken_reply",speaking);
  if(!completed)throw new Exception("spoken reply did not complete");
  if(sceneText!=null){
   result.putBoolean("voice_turn_generated_scene",(Boolean)field(a,"sceneGeneratedThisTurn"));
   result.putBoolean("scene_started_before_reply",(Boolean)field(a,"sceneBeganWhileThinking"));
   if(promptIndex==0&&!(Boolean)field(a,"sceneBeganWhileThinking"))throw new Exception("initial scene generation waited for answer");
   result.putBoolean("scene_arrived_before_speech",(Boolean)field(a,"sceneArrivedBeforeSpeech"));
   if((Boolean)field(a,"sceneGeneratedThisTurn")&&!(Boolean)field(a,"sceneArrivedBeforeSpeech"))throw new Exception("speech started before scene");
   if(field(a,"sceneBitmap")==null||(Boolean)field(a,"scenePending"))throw new Exception("answer completion lost scene");
   result.putBoolean("scene_retained_after_reply",true);
   if(promptIndex==1){if((Boolean)field(a,"sceneGeneratedThisTurn"))throw new Exception("follow-up regenerated scene");result.putBoolean("followup_reused_scene",true);}
   if(promptIndex==2){if(!(Boolean)field(a,"sceneGeneratedThisTurn"))throw new Exception("new subject did not regenerate scene");result.putBoolean("new_subject_generated_scene",true);}
  }
  if(sceneText==null&&!"dance".equals(result.getString("reaction")))throw new Exception("dance request did not select the dance reaction");
  result.putInt("completed_turns",promptIndex+1);
  }
  finish(-1,result);
 }catch(Exception e){result.putString("error",e.toString()+"; cause="+e.getCause());finish(0,result);}}


 private TurnLifecycle activityCycle(VoiceActivity a){try{return (TurnLifecycle)field(a,"lifecycle");}catch(Exception e){throw new RuntimeException(e);}}
 private void verifyCallbacks(final VoiceActivity a)throws Exception{
  final Throwable[] failure=new Throwable[1];
  final Object oldPaused=field(a,"paused");
  runOnMainSync(new Runnable(){public void run(){try{
   setField(a,"paused",false); // Synthetic callbacks simulate a foreground activity.
   TurnLifecycle cycle=activityCycle(a);
   long request=cycle.listen();cycle.waitForReply(request);String token=cycle.token();cycle.reply(token);
   setField(a,"turn",request);setField(a,"playback",10L);
   Method factory=VoiceActivity.class.getDeclaredMethod("speechListener",long.class,long.class);factory.setAccessible(true);
   OpenAiSpeech.Listener reply=(OpenAiSpeech.Listener)factory.invoke(a,request,10L);
   android.graphics.Bitmap visible=android.graphics.Bitmap.createBitmap(12,8,android.graphics.Bitmap.Config.ARGB_8888);
   setField(a,"sceneBitmap",visible);setField(a,"scenePending",true);
   reply.started();reply.done();reply.failed("error after completion");
   if(visible.isRecycled()||field(a,"sceneBitmap")!=visible||(Boolean)field(a,"scenePending"))throw new Exception("completion lost session scene");
   Method sceneFactory=VoiceActivity.class.getDeclaredMethod("sceneListener",long.class);sceneFactory.setAccessible(true);
   OpenAiScene.Listener late=(OpenAiScene.Listener)sceneFactory.invoke(a,request);
   android.graphics.Bitmap lateImage=android.graphics.Bitmap.createBitmap(12,8,android.graphics.Bitmap.Config.ARGB_8888);
   late.image(lateImage);late.failed("late scene failure");
   if(!lateImage.isRecycled()||field(a,"sceneBitmap")!=visible||!"".equals(field(a,"sceneStatus")))throw new Exception("completed answer resurrected scene");
   if(!"complete".equals(field(a,"mode")))throw new Exception("playback completion did not react");
   Method interrupt=VoiceActivity.class.getDeclaredMethod("interrupt");interrupt.setAccessible(true);interrupt.invoke(a);
   setField(a,"mode","idle");reply.started();reply.done();reply.failed("late error");
   if(!"idle".equals(field(a,"mode"))||cycle.accepts(token))throw new Exception("stale callback or reply escaped cancellation");
   request=cycle.listen();cycle.waitForReply(request);cycle.reply(cycle.token());setField(a,"turn",request);setField(a,"playback",11L);
   OpenAiSpeech.Listener previous=(OpenAiSpeech.Listener)factory.invoke(a,request,10L);
   setField(a,"mode","voicing");previous.started();previous.done();previous.failed("old chunk");
   if(!"voicing".equals(field(a,"mode")))throw new Exception("stale chunk callback changed state");
   OpenAiSpeech.Listener current=(OpenAiSpeech.Listener)factory.invoke(a,request,11L);current.failed("audio is busy — hold to retry");
   if(!"error".equals(field(a,"mode"))||cycle.complete(request))throw new Exception("failure celebrated");
   cycle.ready();setField(a,"turn",cycle.generation());
  }catch(Throwable e){failure[0]=e;}finally{try{setField(a,"paused",oldPaused);}catch(Exception e){failure[0]=e;}}}});
  if(failure[0]!=null)throw new Exception("Callback verification failed: "+failure[0],failure[0]);
 }


 private void verifySceneCancellation(final VoiceActivity a)throws Exception{
  final Throwable[] failure=new Throwable[1];
  runOnMainSync(new Runnable(){public void run(){try{
   Object oldPaused=field(a,"paused");setField(a,"paused",false);
   TurnLifecycle cycle=activityCycle(a);long old=cycle.listen();cycle.waitForReply(old);
   Method factory=VoiceActivity.class.getDeclaredMethod("sceneListener",long.class);factory.setAccessible(true);
   OpenAiScene.Listener stale=(OpenAiScene.Listener)factory.invoke(a,old);
   long fresh=cycle.listen();cycle.waitForReply(fresh);setField(a,"turn",fresh);setField(a,"sceneStatus","muse is working");
   Object current=field(a,"sceneBitmap");
   android.graphics.Bitmap oldImage=android.graphics.Bitmap.createBitmap(12,8,android.graphics.Bitmap.Config.ARGB_8888);
   stale.image(oldImage);stale.failed("late scene failure");
   if(!oldImage.isRecycled()||field(a,"sceneBitmap")!=current||!"muse is working".equals(field(a,"sceneStatus")))throw new Exception("stale scene altered newer turn");
   android.graphics.Bitmap visible=android.graphics.Bitmap.createBitmap(12,8,android.graphics.Bitmap.Config.ARGB_8888);
   setField(a,"sceneBitmap",visible);setField(a,"scenePending",true);
   Method preserve=VoiceActivity.class.getDeclaredMethod("interrupt",boolean.class);preserve.setAccessible(true);preserve.invoke(a,true);
   if(visible.isRecycled()||field(a,"sceneBitmap")!=visible||(Boolean)field(a,"scenePending"))throw new Exception("follow-up interruption lost scene or retained pending job");
   Method interrupt=VoiceActivity.class.getDeclaredMethod("interrupt");interrupt.setAccessible(true);interrupt.invoke(a);
   if(!visible.isRecycled()||field(a,"sceneBitmap")!=null||(Boolean)field(a,"scenePending"))throw new Exception("interruption retained previous scene");
   setField(a,"recording",true);
   Object view=field(a,"face");Method opacity=view.getClass().getDeclaredMethod("sceneOpacity",long.class);opacity.setAccessible(true);
   if((Float)opacity.invoke(view,android.os.SystemClock.uptimeMillis())!=0f)throw new Exception("dictation displayed scene");
   setField(a,"recording",false);setField(a,"paused",oldPaused);
  }catch(Throwable error){failure[0]=error;}}});
  if(failure[0]!=null)throw new Exception("Scene cancellation failed",failure[0]);
 }

 private void setField(VoiceActivity a,String name,Object value)throws Exception{Field f=VoiceActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
 private void verifyFullBleed(final VoiceActivity a)throws Exception{
  final Throwable[] failed=new Throwable[1];
  runOnMainSync(new Runnable(){public void run(){
   android.graphics.Bitmap background=null,screen=null;Object oldScene=null;long oldStart=0;
   try{
    oldScene=field(a,"sceneBitmap");oldStart=(Long)field(a,"sceneStart");
    background=android.graphics.Bitmap.createBitmap(832,832,android.graphics.Bitmap.Config.ARGB_8888);
    int color=android.graphics.Color.rgb(25,85,110);background.eraseColor(color);
    setField(a,"sceneBitmap",background);setField(a,"sceneStart",android.os.SystemClock.uptimeMillis()-1080L);
    android.view.View view=(android.view.View)field(a,"face");
    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,720,720);
    screen=android.graphics.Bitmap.createBitmap(720,720,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(screen));
    if(screen.getPixel(30,10)!=color||screen.getPixel(30,540)!=color)throw new Exception("scene has an unexpected band above footer");
    if(android.graphics.Color.red(screen.getPixel(30,705))<200)throw new Exception("frosted footer missing");
   }catch(Throwable e){failed[0]=e;}
   finally{try{setField(a,"sceneBitmap",oldScene);setField(a,"sceneStart",oldStart);}catch(Exception e){failed[0]=e;}if(background!=null)background.recycle();if(screen!=null)screen.recycle();}
  }});
  if(failed[0]!=null)throw new Exception("Full-screen scene verification failed",failed[0]);
 }

 private void verifyUI(final VoiceActivity a)throws Exception{
  final Throwable[] failure=new Throwable[1];
  runOnMainSync(new Runnable(){public void run(){
   try{
    android.view.View v=(android.view.View)field(a,"face");
    Object oldConnected=field(a,"connected");Object oldScene=field(a,"sceneBitmap");setField(a,"sceneBitmap",null);
    String[] states={"idle","recording","thinking","scene-wait","voicing","speaking","complete","error","connecting","idle"};
    String[] reactions={"neutral","curious","working","working","neutral","happy","neutral","neutral","neutral","dance"};
    for(int index=0;index<states.length;index++){
     setField(a,"mode",states[index]);setField(a,"recording",states[index].equals("recording"));
     setField(a,"connected",!states[index].equals("connecting"));
     setField(a,"reaction",reactions[index]);setField(a,"reactionUntil",Long.MAX_VALUE);
     setField(a,"caption",states[index].equals("error")?"audio is busy — hold to retry":"ready when you are");
     android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(720,720,android.graphics.Bitmap.Config.ARGB_8888);
     v.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY));v.layout(0,0,720,720);
     v.draw(new android.graphics.Canvas(bitmap));
     // Capture the settled label, and prove reply frames never enter the waving section.
     Field visualStart=v.getClass().getDeclaredField("visualStart");visualStart.setAccessible(true);
     visualStart.setLong(v,android.os.SystemClock.uptimeMillis()-1100L);
     Field lastFrame=v.getClass().getDeclaredField("lastFrame");lastFrame.setAccessible(true);lastFrame.setLong(v,0L);
     Field labelStart=v.getClass().getDeclaredField("labelStart");labelStart.setAccessible(true);labelStart.setLong(v,0L);
     v.draw(new android.graphics.Canvas(bitmap));
     if(bitmap.getPixel(360,300)==android.graphics.Color.rgb(247,247,247))throw new Exception("character disappeared during label transition");
     if(states[index].equals("complete")||states[index].equals("error")){
      Method frame=v.getClass().getDeclaredMethod("frameTime",long.class);frame.setAccessible(true);
      int nowFrame=(Integer)frame.invoke(v,android.os.SystemClock.uptimeMillis());
      if(states[index].equals("complete")&&nowFrame<=400)throw new Exception("completion did not wave");
      if(states[index].equals("error")&&nowFrame!=0)throw new Exception("blocked character did not pause");
     }
     if(states[index].equals("speaking")){
      Method frame=v.getClass().getDeclaredMethod("frameTime",long.class);frame.setAccessible(true);
      long now=android.os.SystemClock.uptimeMillis();
      for(int ms=0;ms<10400;ms+=66)if((Integer)frame.invoke(v,now+ms)>400)throw new Exception("reply entered a waving frame");
     }
     FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"ui-"+states[index]+"-"+reactions[index]+".png"));
     try{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{out.close();bitmap.recycle();}
    }
    // A cancelled gesture must never submit a voice turn.
    setField(a,"recording",true);setField(a,"turn",activityCycle(a).generation());
    long turn=(Long)field(a,"turn");
    android.view.MotionEvent cancel=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_CANCEL,360,360,0);
    v.onTouchEvent(cancel);cancel.recycle();
    if((Boolean)field(a,"recording")||(Long)field(a,"turn")!=turn+1)throw new Exception("cancel did not discard the recording");
    setField(a,"sceneBitmap",oldScene);setField(a,"connected",oldConnected);setField(a,"reaction","neutral");setField(a,"reactionUntil",0L);
    setField(a,"mode","idle");setField(a,"caption","ready when you are");
   }catch(Throwable e){failure[0]=e;}
  }});
  if(failure[0]!=null)throw new Exception("UI verification failed",failure[0]);
 }
}
