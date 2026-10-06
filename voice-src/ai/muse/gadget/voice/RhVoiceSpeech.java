package ai.muse.gadget.voice;
import android.content.Context;
import android.os.*;
import android.speech.tts.*;
import java.util.*;

/** RHVoice is installed separately, including its US English voice data. */
final class RhVoiceSpeech {
    private final Handler ui=new Handler();
    private TextToSpeech engine;
    private boolean ready,closed;
    private long generation;
    RhVoiceSpeech(Context context){engine=new TextToSpeech(context,new TextToSpeech.OnInitListener(){public void onInit(final int status){ui.post(new Runnable(){public void run(){if(closed)return;ready=status==TextToSpeech.SUCCESS&&engine.setLanguage(Locale.US)>=0;if(ready)engine.setSpeechRate(1.02f);}});}},"com.github.olga_yakovleva.rhvoice.android");}
    boolean ready(){return ready&&!closed;}
    void speak(String text,final SpeechOutput.Listener listener){
        stop();if(!ready()){listener.failed("Open RHVoice and install the US English voice.");return;}final long request=generation;
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            private void event(final int kind){ui.post(new Runnable(){public void run(){if(closed||request!=generation)return;if(kind==0)listener.started();else if(kind==1)listener.done();else listener.failed("Couldn’t play the reply.");}});}
            public void onStart(String id){if(id.equals("reply-"+request))event(0);}public void onDone(String id){if(id.equals("reply-"+request))event(1);}public void onError(String id){if(id.equals("reply-"+request))event(2);}
        });
        HashMap<String,String> params=new HashMap<String,String>();params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,"reply-"+request);
        if(engine.speak(text,TextToSpeech.QUEUE_FLUSH,params)==TextToSpeech.ERROR)listener.failed("Couldn’t start RHVoice.");
    }
    void stop(){generation++;if(engine!=null)engine.stop();}
    void close(){closed=true;stop();if(engine!=null)engine.shutdown();ui.removeCallbacksAndMessages(null);}
}
