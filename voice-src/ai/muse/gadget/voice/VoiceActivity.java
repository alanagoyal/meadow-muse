package ai.muse.gadget.voice;

import ai.muse.gadget.service.GadgetService;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class VoiceActivity extends Activity implements GadgetService.StateListener {
    private final Handler ui = new Handler();
    private GadgetService service;
    private boolean bound, connected, voiceReady, destroyed;
    private volatile boolean recording;
    private volatile Model model;
    private OpenAiSpeech tts;
    private FaceView face;
    private String mode = "loading", caption = "getting ready", transcript = "";
    private long turn;
    private float level;
    private final ArrayDeque<String> speech = new ArrayDeque<String>();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((GadgetService.LocalBinder)binder).service();
            service.addListener(VoiceActivity.this);
            onState(service.currentState().getFirst(), service.currentState().getSecond());
        }
        public void onServiceDisconnected(ComponentName name) {service=null; connected=false; refreshReady();}
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        VoiceBridge.activity = new java.lang.ref.WeakReference<VoiceActivity>(this);
        face = new FaceView();
        setContentView(face);
        tts = new OpenAiSpeech(this);
        voiceReady=tts.ready();
        Intent intent=new Intent(this,GadgetService.class);
        try { Context.class.getMethod("startForegroundService", Intent.class).invoke(this, intent); } catch(Exception e) { fail("couldn’t start Muse",e); }
        bound=bindService(intent,connection,Context.BIND_AUTO_CREATE);
        new Thread(new Runnable(){public void run(){
            try {
                File base=new File(getFilesDir(),"speech");
                File path=new File(base,"vosk-model-small-en-us-0.15");
                File marker=new File(base,"ready");
                if(!marker.exists()) {
                    base.mkdirs();
                    ZipInputStream z=new ZipInputStream(getAssets().open("voice-model.zip"));
                    try {
                        ZipEntry entry; byte[] buffer=new byte[32768];
                        while((entry=z.getNextEntry())!=null) {
                            File dest=new File(base,entry.getName());
                            if(!dest.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator)) throw new IOException("Invalid model archive");
                            if(entry.isDirectory()){dest.mkdirs();continue;}
                            dest.getParentFile().mkdirs();
                            FileOutputStream out=new FileOutputStream(dest);
                            try {int n;while((n=z.read(buffer))>0)out.write(buffer,0,n);} finally {out.close();}
                        }
                    } finally {z.close();}
                    marker.createNewFile();
                }
                Model loaded=new Model(path.getAbsolutePath());
                if(destroyed){loaded.close();return;}
                model=loaded;
                ui.post(new Runnable(){public void run(){refreshReady();}});
            } catch(Exception e){fail("couldn’t load speech — reopen the app",e);}
        }},"speech-model").start();
    }
    private void setMode(String next,String text){if(destroyed)return;mode=next;caption=text;face.invalidate();}
    private void refreshReady(){
        if(destroyed || recording || mode.equals("thinking") || mode.equals("voicing") || mode.equals("speaking"))return;
        if(model==null)setMode("loading","getting ready");
        else if(!voiceReady)setMode("error","OpenAI voice needs setup");
        else if(!connected)setMode("connecting","connecting to muse");
        else setMode("idle","ready when you are");
    }
    public void onState(final GadgetService.State state,String detail){ui.post(new Runnable(){public void run(){
        connected=state==GadgetService.State.CONNECTED;
        if(!connected && mode.equals("thinking"))setMode("error","connection lost — try again shortly");
        refreshReady();
    }});}
    public void onListening(boolean listening){}
    public void onChat(final String text){ui.post(new Runnable(){public void run(){
        if(destroyed || text==null || text.trim().isEmpty())return;
        transcript=text.trim();
        String remaining=transcript.replaceAll("[*#`]", "");
        while(remaining.length()>3000){int cut=remaining.lastIndexOf(' ',3000);if(cut<1)cut=3000;speech.add(remaining.substring(0,cut));remaining=remaining.substring(cut).trim();}
        if(!remaining.isEmpty())speech.add(remaining);
        if(!mode.equals("speaking")&&!mode.equals("voicing")&&!recording)speakNext();
    }});}
    private void speakNext(){
        if(destroyed||recording)return;
        String next=speech.poll();
        if(next==null){setMode("idle","ready when you are");return;}
        if(!voiceReady){setMode("error","voice unavailable");return;}
        setMode("voicing","preparing voice");
        tts.speak(next,new OpenAiSpeech.Listener(){
            public void started(){if(!destroyed)setMode("speaking","muse is speaking");}
            public void done(){if(!destroyed)speakNext();}
            public void failed(String message){if(!destroyed){speech.clear();setMode("error",message);}}
        });
    }
    private void startRecording(){
        if(recording||mode.equals("thinking")||model==null||!connected||!voiceReady)return;
        if(checkCallingOrSelfPermission("android.permission.RECORD_AUDIO")!=PackageManager.PERMISSION_GRANTED){
            try{Activity.class.getMethod("requestPermissions",String[].class,int.class).invoke(this,new Object[]{new String[]{"android.permission.RECORD_AUDIO"},7});}catch(Exception e){fail("microphone permission needed",e);}
            return;
        }
        tts.stop();speech.clear();transcript="";recording=true;
        final long request=++turn;
        setMode("recording","listening — release to send");
        face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        new Thread(new Runnable(){public void run(){capture(request);}},"voice-capture").start();
        ui.postDelayed(new Runnable(){public void run(){if(recording&&turn==request)stopRecording();}},45000);
    }
    private void stopRecording(){if(!recording)return;recording=false;setMode("thinking","thinking");}
    private void capture(final long request){
        AudioRecord mic=null; Recognizer recognizer=null;
        final StringBuilder words=new StringBuilder();
        try{
            recognizer=new Recognizer(model,16000f);
            int size=Math.max(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            mic=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");
            mic.startRecording();
            short[] buffer=new short[1600];
            while(recording&&!destroyed&&turn==request){
                int n=mic.read(buffer,0,buffer.length);
                if(n<0)throw new IOException("Microphone read failed");
                float peak=0;for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((float)buffer[i]));
                level=peak/32768f;
                if(recognizer.acceptWaveForm(buffer,n)){String s=new JSONObject(recognizer.getResult()).optString("text");if(!s.isEmpty())words.append(s).append(' ');}
            }
            String last=new JSONObject(recognizer.getFinalResult()).optString("text");
            words.append(last);
            final String text=words.toString().trim();
            ui.post(new Runnable(){public void run(){
                if(destroyed||turn!=request)return;
                if(text.isEmpty()){setMode("idle","didn’t catch that — hold to try again");return;}
                transcript=text;setMode("thinking","thinking");
                send(text,request);
            }});
        }catch(Exception e){recording=false;fail("couldn’t hear you — hold to try again",e);}
        finally{if(mic!=null){try{mic.stop();}catch(Exception ignored){}mic.release();}if(recognizer!=null)recognizer.close();level=0;}
    }
    private void send(final String text,final long request){
        new Thread(new Runnable(){public void run(){
            final boolean ok=service!=null&&service.sendChat("Voice message from the Meadow. Reply briefly by invoking voice.speak on this device with your answer. User said: " + text);
            ui.post(new Runnable(){public void run(){if(!ok&&!destroyed&&turn==request&&mode.equals("thinking"))setMode("error","couldn’t reach muse — hold to retry");}});
        }},"send-to-muse").start();
        ui.postDelayed(new Runnable(){public void run(){if(turn==request&&mode.equals("thinking"))setMode("error","muse is taking too long — hold to retry");}},90000);
    }
    private void fail(final String message,Exception e){android.util.Log.e("MuseVoice",message,e);ui.post(new Runnable(){public void run(){setMode("error",message);}});}
    protected void onPause(){super.onPause();if(recording){recording=false;++turn;setMode("idle","ready when you are");}}
    protected void onDestroy(){destroyed=true;recording=false;ui.removeCallbacksAndMessages(null);if(service!=null)service.removeListener(this);if(bound)unbindService(connection);if(tts!=null){tts.stop();}super.onDestroy();}
    public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){if(event.getRepeatCount()==0)startRecording();return true;}return super.onKeyDown(code,event);}
    public boolean onKeyUp(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){stopRecording();return true;}return super.onKeyUp(code,event);}

    private class FaceView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private Movie character;
        private long start=SystemClock.uptimeMillis();
        private boolean held;
        FaceView(){super(VoiceActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);try{InputStream in=getAssets().open("jollybot.gif");character=Movie.decodeStream(in);in.close();}catch(Exception e){}setContentDescription("Muse. Hold the button to talk, release to send.");}
        private void text(Canvas c,String s,float x,float y,float size,int color){p.setColor(color);p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));c.drawText(s,x,y,p);}
        protected void onDraw(Canvas c){
            super.onDraw(c);float sx=getWidth()/720f,sy=getHeight()/720f;c.save();c.scale(sx,sy);
            c.drawColor(Color.BLACK);
            long elapsed=SystemClock.uptimeMillis()-start;
            float wave=(float)Math.sin(elapsed/480.0);
            p.setColor(connected?Color.rgb(73,139,105):Color.rgb(175,166,148));c.drawCircle(313,54,4,p);
            text(c,"muse",361,63,25,Color.rgb(235,229,220));
            p.setColor(Color.BLACK);c.drawOval(new RectF(272,415,448,437),p);
            if(character!=null){
                c.save();float bob=mode.equals("thinking")?wave*9:wave*3;
                c.translate(200,132+bob);
                int duration=Math.max(character.duration(),1);
                int t=(int)(elapsed%duration);
                character.setTime(t);character.draw(c,0,0);c.restore();
            }
            if(mode.equals("thinking")){
                for(int i=0;i<3;i++){p.setColor(Color.argb(110+(int)(100*(1+Math.sin(elapsed/250.0-i))*.5),112,91,200));c.drawCircle(342+i*18,470,4,p);}
            }else if(recording||mode.equals("speaking")){
                for(int i=0;i<13;i++){float amp=recording?Math.max(.15f,level*4):.5f;float h=5+amp*(10+12*(float)Math.sin(elapsed/110.0+i*.8));p.setColor(Color.rgb(126,111,185));c.drawRoundRect(new RectF(298+i*10,466-h,303+i*10,466+h),2,2,p);}
            }
            text(c,caption,360,513,22,Color.rgb(182,176,192));
            boolean busy=mode.equals("thinking")||mode.equals("loading")||mode.equals("connecting");
            p.setColor(recording?Color.rgb(122,96,220):busy?Color.rgb(52,44,73):Color.rgb(158,130,250));
            c.drawRoundRect(new RectF(210,550,510,644),47,47,p);
            String label=recording?"release to send":(mode.equals("speaking")||mode.equals("voicing"))?"hold to interrupt":busy?"one moment":"hold to talk";
            text(c,label,360,608,26,Color.WHITE);

            text(c,"AI-generated voice",360,686,14,Color.rgb(125,119,139));
            c.restore();postInvalidateDelayed(40);
        }
        public boolean onTouchEvent(MotionEvent e){float x=e.getX()*720/getWidth(),y=e.getY()*720/getHeight();
            if(e.getAction()==MotionEvent.ACTION_DOWN){if(x>=180&&x<=540&&y>=530&&y<=660){held=true;startRecording();return true;}return true;}
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){if(held){held=false;stopRecording();}return true;}return true;
        }
    }
}
