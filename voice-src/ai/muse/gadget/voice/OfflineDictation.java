package ai.muse.gadget.voice;
import android.content.Context;
import android.media.*;
import android.os.Handler;
import java.io.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Offline microphone-to-composer transcription. Stop finishes; cancel discards. */
final class OfflineDictation {
    interface Listener {void ready();void text(String text);void failed(String message);}
    private final Handler ui=new Handler();
    private final Listener listener;
    private volatile Model model;
    private volatile boolean recording,closed,capturing;
    private volatile long generation;
    OfflineDictation(final Context context,Listener listener){this.listener=listener;
        new Thread(new Runnable(){public void run(){try{
            File base=new File(context.getFilesDir(),"speech"),path=new File(base,"vosk-model-small-en-us-0.15"),marker=new File(base,"ready");
            if(!marker.exists()){
                base.mkdirs();ZipInputStream zip=new ZipInputStream(context.getAssets().open("voice-model.zip"));
                try{ZipEntry entry;byte[] bytes=new byte[32768];while((entry=zip.getNextEntry())!=null){File dest=new File(base,entry.getName());if(!dest.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new IOException("Invalid model path");if(entry.isDirectory()){dest.mkdirs();continue;}dest.getParentFile().mkdirs();FileOutputStream out=new FileOutputStream(dest);try{int n;while((n=zip.read(bytes))>0)out.write(bytes,0,n);}finally{out.close();}}}finally{zip.close();}marker.createNewFile();
            }
            final Model loaded=new Model(path.getAbsolutePath());ui.post(new Runnable(){public void run(){if(closed){loaded.close();return;}model=loaded;OfflineDictation.this.listener.ready();}});
        }catch(Exception e){ui.post(new Runnable(){public void run(){if(!closed)OfflineDictation.this.listener.failed("Couldn’t load dictation. Reopen the app.");}});}}},"dictation-model").start();
    }
    boolean start(){if(closed||model==null||capturing)return false;recording=true;capturing=true;final long request=++generation;
        new Thread(new Runnable(){public void run(){capture(request);}},"dictation-microphone").start();
        ui.postDelayed(new Runnable(){public void run(){if(request==generation)stop();}},45000);return true;
    }
    void stop(){recording=false;}
    void cancel(){generation++;recording=false;}
    void close(){closed=true;cancel();ui.removeCallbacksAndMessages(null);if(!capturing&&model!=null){model.close();model=null;}}
    private void capture(final long request){
        AudioRecord mic=null;Recognizer recognizer=null;String text="";boolean success=false;
        try{
            recognizer=new Recognizer(model,16000f);int size=Math.max(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            mic=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");mic.startRecording();short[] buffer=new short[1600];StringBuilder words=new StringBuilder();
            while(recording&&!closed&&request==generation){int n=mic.read(buffer,0,buffer.length);if(n<0)throw new IOException("Microphone read failed");if(recognizer.acceptWaveForm(buffer,n))words.append(new JSONObject(recognizer.getResult()).optString("text")).append(' ');}
            words.append(new JSONObject(recognizer.getFinalResult()).optString("text"));text=words.toString().trim();success=true;
        }catch(Exception ignored){}
        finally{if(mic!=null){try{mic.stop();}catch(Exception ignored){}mic.release();}if(recognizer!=null)recognizer.close();}
        final String transcript=text;final boolean ok=success;
        ui.post(new Runnable(){public void run(){capturing=false;if(closed){if(model!=null){model.close();model=null;}return;}if(request!=generation)return;recording=false;if(ok)listener.text(transcript);else listener.failed("Couldn’t hear you. Try dictating again.");}});
    }
}
