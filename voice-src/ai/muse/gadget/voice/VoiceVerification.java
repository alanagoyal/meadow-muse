package ai.muse.gadget.voice;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import java.lang.reflect.*;
import java.io.*;
import org.json.JSONObject;
import org.vosk.*;

/** Screen-state rendering is offline. fullTurn explicitly exercises the paired Muse. */
public class VoiceVerification extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle args){super.onCreate(args);this.args=args==null?new Bundle():args;start();}
    private Object field(Object a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private void set(Object a,String name,Object value)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private Object call(Object a,String name,Class<?>[] types,Object...values)throws Exception{Method m=a.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,values);}
    public void onStart(){Bundle result=new Bundle();try{
        Intent i=new Intent(getTargetContext(),VoiceActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(i);
        VoiceActivity found=null;for(int n=0;n<150;n++){found=VoiceBridge.activity.get();if(found!=null)break;Thread.sleep(100);}if(found==null)throw new Exception("Open and unlock Meadow");final VoiceActivity a=found;
        final Throwable[] error=new Throwable[1];
        if("true".equals(args.getString("uiOnly"))){runOnMainSync(new Runnable(){public void run(){try{
            View v=(View)field(a,"face");String old=(String)field(a,"mode");
            for(String mode:new String[]{"idle","recording","thinking","voicing","speaking"}){
                set(a,"mode",mode);set(a,"recording",mode.equals("recording"));
                Bitmap bitmap=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888);v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));v.layout(0,0,720,720);v.draw(new Canvas(bitmap));
                FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"ui-"+mode+".png"));try{bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{out.close();bitmap.recycle();}
            }set(a,"recording",false);set(a,"mode",old);
        }catch(Throwable e){error[0]=e;}}});if(error[0]!=null)throw new Exception(error[0]);result.putBoolean("ui_states_passed",true);finish(-1,result);return;}
        for(int n=0;n<150;n++){if(field(a,"model")!=null&&(Boolean)field(a,"connected")&&(Boolean)field(a,"voiceReady"))break;Thread.sleep(200);}
        if(field(a,"model")==null||!(Boolean)field(a,"connected")||!(Boolean)field(a,"voiceReady"))throw new Exception("Pair Muse and provision the speech key first");
        if("true".equals(args.getString("fullTurn"))){
            final String prompt=args.getString("promptText")==null?"Say hello in one short sentence.":args.getString("promptText");
            runOnMainSync(new Runnable(){public void run(){try{call(a,"setMode",new Class<?>[]{String.class,String.class},"thinking","muse is working");call(a,"send",new Class<?>[]{String.class,long.class},prompt,field(a,"turn"));}catch(Throwable e){error[0]=e;}}});
            if(error[0]!=null)throw new Exception(error[0]);boolean speaking=false,done=false;
            for(int n=0;n<1200;n++){String mode=(String)field(a,"mode");if(mode.equals("speaking"))speaking=true;if(speaking&&(mode.equals("idle")||mode.equals("complete"))){done=true;break;}if(mode.equals("error"))throw new Exception("Speech failed: "+field(a,"caption"));Thread.sleep(100);}
            if(!done)throw new Exception("Live voice turn did not complete");result.putBoolean("live_reply",true);
        }else{
            Recognizer r=new Recognizer((Model)field(a,"model"),16000f);InputStream in=getTargetContext().getAssets().open("voice-test.pcm");StringBuilder text=new StringBuilder();try{byte[] b=new byte[3200];int n;while((n=in.read(b))!=-1)if(r.acceptWaveForm(b,n))text.append(new JSONObject(r.getResult()).optString("text")).append(' ');text.append(new JSONObject(r.getFinalResult()).optString("text"));}finally{in.close();r.close();}if(text.length()==0)throw new Exception("Fixture recognition was empty");result.putString("recognition",text.toString());
        }
        finish(-1,result);
    }catch(Exception e){result.putString("error",e.toString());finish(0,result);}}
}
