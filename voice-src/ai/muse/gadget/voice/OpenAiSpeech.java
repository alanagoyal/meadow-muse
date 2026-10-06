package ai.muse.gadget.voice;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.URL;
import java.lang.reflect.Method;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Personal-device speech client. The API key is provisioned separately, never in the APK. */
public final class OpenAiSpeech {
    public interface Listener {void started(); void done(); void failed(String message);}
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private String key;
    // active and focus are main-thread owned; each worker only owns its own resources.
    private volatile Request active;
    private int generation;
    private final class Request {
        final int id=++generation;
        final Listener callback;
        final long began=SystemClock.elapsedRealtime();
        volatile boolean cancelled;
        boolean playbackNotified; // main-thread owned
        HttpsURLConnection connection;
        AudioTrack track;
        Thread worker;
        AudioManager.OnAudioFocusChangeListener focus;
        Request(Listener callback){this.callback=callback;}
    }

    public OpenAiSpeech(Context context) {
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        try {
            File incoming=new File(context.getExternalFilesDir(null),"openai-key.pending");
            File stored=new File(context.getFilesDir(),"openai-key");
            if(incoming.exists()) {
                String candidate=readKey(incoming);
                if(!candidate.startsWith("sk-")||candidate.length()<20)throw new IOException("Invalid key");
                FileOutputStream out=context.openFileOutput("openai-key",Context.MODE_PRIVATE);
                try {out.write(candidate.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
                if(!incoming.delete())throw new IOException("Could not remove provisioning file");
            }
            key=readKey(stored);
        } catch(Exception ignored) {key=null;}
    }
    private String readKey(File file)throws IOException {
        if(file.length()>1024)throw new IOException("Invalid key file");
        FileInputStream in=new FileInputStream(file);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8").trim();}
        finally{in.close();}
    }
    public boolean ready(){return key!=null&&!key.isEmpty();}
    public void speak(final String text,final Listener callback) {
        if(Looper.myLooper()!=ui.getLooper()){ui.post(new Runnable(){public void run(){speak(text,callback);}});return;}
        stop();
        if(!ready()){callback.failed("OpenAI voice needs setup");return;}
        final Request request=new Request(callback);
        active=request;
        request.focus=new AudioManager.OnAudioFocusChangeListener(){
            public void onAudioFocusChange(final int change){
                ui.post(new Runnable(){public void run(){
                    if(change<0&&active==request){stop();callback.failed("audio interrupted — hold to try again");}
                }});
            }
        };
        if(audio.requestAudioFocus(request.focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
            stop();callback.failed("audio is busy — hold to retry");return;
        }
        request.worker=new Thread(new Runnable(){public void run(){stream(text,request);}},"openai-speech-"+request.id);
        request.worker.start();
    }
    private void stream(String text,final Request request) {
        HttpsURLConnection http=null;
        AudioTrack track=null;
        boolean completed=false;
        String failure=null;
        try {
            JSONObject body=new JSONObject();body.put("model","gpt-4o-mini-tts");body.put("voice","marin");body.put("input",text);
            body.put("instructions","Speak naturally and conversationally. Warm, calm, clear, and direct. No exaggerated enthusiasm. Use a comfortable pace.");
            body.put("response_format","pcm");
            http=(HttpsURLConnection)new URL("https://api.openai.com/v1/audio/speech").openConnection();
            http.setInstanceFollowRedirects(false);http.setConnectTimeout(15000);http.setReadTimeout(45000);http.setRequestMethod("POST");http.setDoOutput(true);
            http.setRequestProperty("Authorization","Bearer "+key);http.setRequestProperty("Content-Type","application/json");
            synchronized(request){if(request.cancelled)return;request.connection=http;}
            byte[] json=body.toString().getBytes("UTF-8");http.setFixedLengthStreamingMode(json.length);
            OutputStream out=http.getOutputStream();try{out.write(json);}finally{out.close();}
            int code=http.getResponseCode();
            if(code!=200)throw new IOException(code==401?"OpenAI key rejected — update the speech key":code==429?"OpenAI quota or rate limit reached — check the speech account":"OpenAI speech unavailable ("+code+") — hold to retry");
            if(request.cancelled)return;
            int minimum=AudioTrack.getMinBufferSize(PcmStream.SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IOException("PCM playback unavailable");
            track=new AudioTrack(AudioManager.STREAM_MUSIC,PcmStream.SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,PcmStream.STARTUP_BYTES),AudioTrack.MODE_STREAM);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("PCM playback unavailable");
            // The bundled compile jar predates API 23; Meadow's runtime is API 29+.
            // Resolve the nonblocking API once, never fall back to a UI-blocking write.
            final Method writePcm=AudioTrack.class.getMethod("write",byte[].class,int.class,int.class,int.class);
            final int startupBytes=((Integer)AudioTrack.class.getMethod("getBufferSizeInFrames").invoke(track))*2;
            synchronized(request){if(request.cancelled)return;request.track=track;}
            InputStream in=http.getInputStream();
            try {completed=PcmStream.play(in,new PcmStream.Output(){
                public boolean cancelled(){return request.cancelled;}
                public int write(byte[] bytes,int offset,int length)throws IOException {
                    synchronized(request){
                        if(request.cancelled)return 0;
                        try {
                            int count=(Integer)writePcm.invoke(request.track,bytes,offset,length,1); // WRITE_NON_BLOCKING
                            return count;
                        }catch(Exception e){throw new IOException("Audio output failed",e);}
                    }
                }
                public void start() {
                    synchronized(request){if(request.cancelled)return;request.track.play();}
                    timing(request,"playback_requested");
                    ui.post(new Runnable(){public void run(){watchPlayback(request);}});
                }
                public int minimumStartBytes(){return startupBytes;}
                public long playedFrames(){
                    synchronized(request){
                        if(request.cancelled)return 0;
                        return request.track.getPlaybackHeadPosition()&0xffffffffL;
                    }
                }
                public long now(){return SystemClock.elapsedRealtime();}
                public void idle()throws InterruptedException{Thread.sleep(5);}
                public void receivedFirstBytes(){timing(request,"first_bytes");}
                public void receivedEnd(long bytes){timing(request,"stream_end bytes="+bytes);}
            });}finally{in.close();}
        }catch(Exception e){
            failure=e instanceof IOException&&e.getMessage()!=null&&e.getMessage().startsWith("OpenAI")?e.getMessage():"couldn’t play voice — hold to retry";
        }finally{
            synchronized(request){request.track=null;request.connection=null;}
            if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}
            if(http!=null)http.disconnect();
        }
        if(request.cancelled)return;
        final boolean done=completed;
        final String message=failure;
        if(done)timing(request,"playback_done");
        ui.post(new Runnable(){public void run(){
            if(active!=request||request.cancelled)return;
            // A very short clip can drain between UI polls; it still started before done.
            if(done)notifyPlaybackStarted(request);
            active=null;abandonFocus(request);
            if(done)request.callback.done();else request.callback.failed(message==null?"couldn’t play voice — hold to retry":message);
        }});
    }
    private void notifyPlaybackStarted(Request request){
        if(active!=request||request.cancelled||request.playbackNotified)return;
        request.playbackNotified=true;
        timing(request,"playback_progress");
        request.callback.started();
    }
    private void watchPlayback(final Request request){
        if(active!=request||request.cancelled||request.playbackNotified)return;
        boolean advanced;
        synchronized(request){advanced=request.track!=null&&request.track.getPlaybackHeadPosition()!=0;}
        if(advanced){notifyPlaybackStarted(request);return;}
        // Poll independently of the network: a blocked read must not delay the UI.
        ui.postDelayed(new Runnable(){public void run(){watchPlayback(request);}},10);
    }
    private void timing(Request request,String event){Log.i("MuseSpeech","request="+request.id+" "+event+" ms="+(SystemClock.elapsedRealtime()-request.began));}
    private void abandonFocus(Request request){
        AudioManager.OnAudioFocusChangeListener previous=request.focus;request.focus=null;
        if(previous!=null)audio.abandonAudioFocus(previous);
    }
    public void stop(){
        if(Looper.myLooper()!=ui.getLooper()){ui.post(new Runnable(){public void run(){stop();}});return;}
        final Request previous=active;active=null;
        if(previous==null)return;
        final HttpsURLConnection old;
        synchronized(previous){
            previous.cancelled=true;old=previous.connection;
            // Nonblocking writes hold this lock only briefly. Stop sound immediately; the
            // worker releases its track, so an old worker can never release a new track.
            if(previous.track!=null){try{previous.track.pause();previous.track.flush();}catch(Exception ignored){}}
        }
        abandonFocus(previous);
        if(previous.worker!=null)previous.worker.interrupt();
        if(old!=null)new Thread(new Runnable(){public void run(){old.disconnect();}},"cancel-speech").start();
    }
}
