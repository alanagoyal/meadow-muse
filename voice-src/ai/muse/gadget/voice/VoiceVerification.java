package ai.muse.gadget.voice;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** uiOnly checks the real composer without sending a message or calling speech APIs. */
public class VoiceVerification extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle args){super.onCreate(args);this.args=args==null?new Bundle():args;start();}
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    public void onStart(){Bundle result=new Bundle();try{
        Intent intent=new Intent(getTargetContext(),VoiceActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(intent);
        VoiceActivity found=null;for(int i=0;i<100;i++){found=VoiceBridge.activity.get();if(found!=null)break;Thread.sleep(100);}
        if(found==null)throw new Exception("Open and unlock Meadow.");final VoiceActivity a=found;final Throwable[] failure=new Throwable[1];
        runOnMainSync(new Runnable(){public void run(){try{
            EditText input=(EditText)field(a,"input");if(input.getText().length()!=0)throw new Exception("Composer must start empty");
            input.setText("How do I repot a cactus?");if(!input.getText().toString().equals("How do I repot a cactus?"))throw new Exception("Composer is not editable");
            input.setText("");android.view.View v=a.getWindow().getDecorView();v.measure(android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY));v.layout(0,0,720,720);Bitmap b=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));
            FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"chat-ui.png"));try{b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{out.close();b.recycle();}
        }catch(Throwable e){failure[0]=e;}}});if(failure[0]!=null)throw new Exception(failure[0]);result.putBoolean("editable_composer",true);
        if("true".equals(args.getString("dictationOnly"))){
            OfflineDictation dictation=(OfflineDictation)field(a,"dictation");
            for(int i=0;i<150&&field(dictation,"model")==null;i++)Thread.sleep(200);
            org.vosk.Model model=(org.vosk.Model)field(dictation,"model");if(model==null)throw new Exception("Dictation model did not load");
            org.vosk.Recognizer recognizer=new org.vosk.Recognizer(model,16000f);InputStream in=getTargetContext().getAssets().open("voice-test.pcm");StringBuilder words=new StringBuilder();
            try{byte[] data=new byte[3200];int n;while((n=in.read(data))!=-1)if(recognizer.acceptWaveForm(data,n))words.append(new org.json.JSONObject(recognizer.getResult()).optString("text")).append(' ');words.append(new org.json.JSONObject(recognizer.getFinalResult()).optString("text"));}finally{in.close();recognizer.close();}
            if(words.length()==0)throw new Exception("Fixture transcription was empty");result.putString("dictation",words.toString());
        }
        if("true".equals(args.getString("fullTurn"))){
            for(int i=0;i<150&&!((Boolean)field(a,"connected"));i++)Thread.sleep(200);
            if(!((Boolean)field(a,"connected")))throw new Exception("Pair Muse before a live test");
            final String prompt=args.getString("promptText")==null?"Say hello in one short sentence.":args.getString("promptText");
            runOnMainSync(new Runnable(){public void run(){try{((EditText)field(a,"input")).setText(prompt);Method m=a.getClass().getDeclaredMethod("sendInput");m.setAccessible(true);m.invoke(a);}catch(Throwable e){failure[0]=e;}}});
            if(failure[0]!=null)throw new Exception(failure[0]);boolean replied=false;
            for(int i=0;i<1000;i++){final boolean[] done={false};runOnMainSync(new Runnable(){public void run(){try{TextView text=(TextView)field(a,"response");done[0]=text!=null&&text.getText().length()>0&&((TextView)field(a,"status")).getText().length()==0;}catch(Exception ignored){}}});if(done[0]){replied=true;break;}Thread.sleep(100);}
            if(!replied)throw new Exception("Live reply did not complete");result.putBoolean("live_reply",true);
        }
        finish(-1,result);
    }catch(Exception e){result.putString("error",e.toString());finish(0,result);}}
}
