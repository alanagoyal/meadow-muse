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
    private volatile float level;
    private String reaction = "neutral";
    private long reactionUntil;
    private static class Reply {
        final String text, reaction;
        Reply(String text, String reaction){this.text=text;this.reaction=reaction;}
    }
    private final ArrayDeque<Reply> speech = new ArrayDeque<Reply>();
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
    private void setMode(String next,String text){
        if(destroyed)return;
        boolean changed=!mode.equals(next);
        mode=next;caption=text;
        if(next.equals("error")){reaction="neutral";reactionUntil=0;}
        face.setContentDescription("Muse. "+(connected?"Connected. ":"Disconnected. ")+text+". Hold to talk, release to send. Double tap to start or stop listening.");
        if(changed)face.announceForAccessibility(text);
        face.invalidate();
    }
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
    public void onChat(final String text){android.util.Log.d("MuseVoice","Text-only reply received");onReply(text,"neutral");}
    public void onReply(final String text, final String expression){ui.post(new Runnable(){public void run(){
        if(destroyed || text==null || text.trim().isEmpty())return;
        transcript=text.trim();
        String remaining=transcript.replaceAll("[*#`]", "");
        while(remaining.length()>3000){int cut=remaining.lastIndexOf(' ',3000);if(cut<1)cut=3000;speech.add(new Reply(remaining.substring(0,cut),expression));remaining=remaining.substring(cut).trim();}
        if(!remaining.isEmpty())speech.add(new Reply(remaining,expression));
        if(!mode.equals("speaking")&&!mode.equals("voicing")&&!recording)speakNext();
    }});}
    private void speakNext(){
        if(destroyed||recording)return;
        Reply next=speech.poll();
        if(next==null){reactionUntil=SystemClock.uptimeMillis()+6000;setMode("idle","ready when you are");refreshReady();return;}
        if(!voiceReady){setMode("error","voice unavailable");return;}
        setMode("voicing","preparing voice");
        reaction=next.reaction;reactionUntil=Long.MAX_VALUE;
        tts.speak(next.text,new OpenAiSpeech.Listener(){
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
        tts.stop();speech.clear();transcript="";reaction="neutral";recording=true;
        final long request=++turn;
        setMode("recording","listening — release to send");
        face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        new Thread(new Runnable(){public void run(){capture(request);}},"voice-capture").start();
        ui.postDelayed(new Runnable(){public void run(){if(recording&&turn==request)stopRecording();}},45000);
    }
    private void stopRecording(){if(!recording)return;recording=false;setMode("thinking","thinking");}
    private void cancelRecording(){
        if(!recording)return;
        recording=false;++turn;level=0;
        setMode("idle","ready when you are");refreshReady();
    }
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
            final boolean ok=service!=null&&service.sendChat("Voice message from the Meadow. Reply briefly by invoking voice.speak on this device with your answer. Include BOTH text and reaction arguments in voice.speak, for example {\"text\":\"Hello!\",\"reaction\":\"happy\"}. Choose reaction to suit the request and tone: neutral, happy, curious, calm, working, or dance. Use dance when asked to dance, working for tasks, calm for reassurance, and happy for celebration. User said: " + text);
            ui.post(new Runnable(){public void run(){if(!ok&&!destroyed&&turn==request&&mode.equals("thinking"))setMode("error","couldn’t reach muse — hold to retry");}});
        }},"send-to-muse").start();
        ui.postDelayed(new Runnable(){public void run(){if(turn==request&&mode.equals("thinking"))setMode("error","muse is taking too long — hold to retry");}},90000);
    }
    private void fail(final String message,Exception e){android.util.Log.e("MuseVoice",message,e);ui.post(new Runnable(){public void run(){setMode("error",message);}});}
    protected void onResume(){super.onResume();if(face!=null&&!recording&&!mode.equals("speaking")&&!mode.equals("voicing")&&!mode.equals("thinking"))face.greet();}
    protected void onPause(){super.onPause();cancelRecording();}
    protected void onDestroy(){destroyed=true;recording=false;ui.removeCallbacksAndMessages(null);if(service!=null)service.removeListener(this);if(bound)unbindService(connection);if(tts!=null){tts.stop();}super.onDestroy();}
    public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){if(event.getRepeatCount()==0)startRecording();return true;}return super.onKeyDown(code,event);}
    public boolean onKeyUp(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){stopRecording();return true;}return super.onKeyUp(code,event);}

    private class FaceView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Movie idle, working;
        private final long start=SystemClock.uptimeMillis();
        private final int background=Color.rgb(247,247,247), ink=Color.rgb(84,84,89);
        private boolean held;
        private float audioLevel;
        private final Typeface statusFont=Typeface.create("sans-serif",Typeface.NORMAL);
        private long greetingUntil, visualStart, labelStart;
        private String visualMode="", label="", previousLabel="", detail="", previousDetail="";

        void greet(){greetingUntil=SystemClock.uptimeMillis()+4000;invalidate();}
        private boolean isWorking(){return mode.equals("thinking")||mode.equals("voicing");}
        private int frameTime(long now){
            if(isWorking()&&working!=null)return (int)((now-visualStart)%Math.max(working.duration(),1));
            if(recording)return (int)((now-visualStart)%Math.max(idle.duration(),1));
            if(!mode.equals("speaking")&&now<greetingUntil)
                return (int)Math.max(0,Math.min(idle.duration()-1,4000-(greetingUntil-now)));
            // Only the opening blink, before the hand rises. Never replay the wave in a reply.
            long blink=(now-start)%5200;
            return blink<400?(int)blink:blink<800?(int)(800-blink):0;
        }


        FaceView(){
            super(VoiceActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            idle=load("muse/idle.gif");working=load("muse/working.gif");
            setFocusable(true);setClickable(true);
            setContentDescription("Muse. Hold anywhere to talk, release to send. Double tap to start or stop listening.");
        }
        private Movie load(String asset){
            try{InputStream in=getAssets().open(asset);try{return Movie.decodeStream(in);}finally{in.close();}}
            catch(IOException e){android.util.Log.e("MuseVoice","Could not load "+asset,e);return null;}
        }
        private void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
            p.setColor(color);p.setTextSize(size);p.setTextAlign(align);
            p.setTypeface(statusFont);c.drawText(s,x,y,p);
        }
        private void message(Canvas c,String s,float y){
            // Wrap actionable errors instead of letting a long message run off the screen.
            p.setTextSize(22);String line="";
            for(String word:s.split(" ")){
                if(p.measureText(line+word)>590){text(c,line.trim(),360,y,22,ink,Paint.Align.CENTER);line="";y+=29;}
                line+=word+" ";
            }
            text(c,line.trim(),360,y,22,ink,Paint.Align.CENTER);
        }
        private void labelLine(Canvas c,String title,String subtitle,float offset,float opacity){
            int alpha=Math.round(255*opacity);
            p.setTypeface(statusFont);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(30);
            p.setColor(Color.argb(alpha,74,67,87));c.drawText(title,360,637+offset,p);
            p.setTextSize(18);p.setColor(Color.argb(alpha,127,122,139));
            c.drawText(subtitle,360,668+offset,p);
        }
        private void statusLabel(Canvas c,String title,String subtitle,long now){
            if(!title.equals(label)||!subtitle.equals(detail)){
                previousLabel=label;previousDetail=detail;label=title;detail=subtitle;labelStart=now;
            }
            float progress=Math.min(1,(now-labelStart)/260f);
            float ease=1-(1-progress)*(1-progress)*(1-progress);
            if(ease<1)labelLine(c,previousLabel,previousDetail,-8*ease,1-ease);
            labelLine(c,label,detail,8*(1-ease),ease);
        }
        protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(background);
            float scale=Math.min(getWidth(),getHeight())/720f;
            c.save();c.translate((getWidth()-720*scale)/2,(getHeight()-720*scale)/2);c.scale(scale,scale);
            long now=SystemClock.uptimeMillis(),elapsed=now-start;
            float t=elapsed/1000f;
            if(!mode.equals(visualMode)){
                visualMode=mode;visualStart=now;
                if(recording||isWorking()||mode.equals("speaking"))greetingUntil=0;
            }
            boolean thinking=isWorking();
            boolean speaking=mode.equals("speaking");
            String mood=now<reactionUntil?reaction:"neutral";

            Movie character=thinking&&working!=null?working:idle;
            float bob=(float)Math.sin(t*1.7)*2,tilt=0,zoom=1;
            if(recording){tilt=-3;zoom=1.035f;}
            else if(mood.equals("dance")){tilt=(float)Math.sin(t*5)*7;bob=-Math.abs((float)Math.sin(t*5))*14;}
            else if(mood.equals("happy")){bob=-Math.abs((float)Math.sin(t*3.5))*9;}
            else if(mood.equals("curious")){tilt=4+(float)Math.sin(t*1.5);}
            else if(mood.equals("calm")){zoom=1+(float)Math.sin(t*1.1)*.008f;}
            else if(speaking){bob=(float)Math.sin(t*3.2)*4;tilt=(float)Math.sin(t*2.1)*1.8f;}
            if(character!=null){
                // The source is tightly framed: let its sides meet the screen edges,
                // rather than clipping the waving hand inside a smaller stage.
                int layer=c.saveLayer(0,0,720,720,null,Canvas.ALL_SAVE_FLAG);
                c.save();c.translate(360,354+bob);c.rotate(tilt);c.scale(zoom,zoom);
                c.translate(-360,-360);c.scale(720f/character.width(),720f/character.height());
                character.setTime(frameTime(now));character.draw(c,0,0);c.restore();
                // Only soften the bottom behind the listening meter and instruction.
                p.setAlpha(255);
                p.setShader(new LinearGradient(0,490,0,610,Color.BLACK,Color.TRANSPARENT,Shader.TileMode.CLAMP));
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));c.drawRect(0,0,720,720,p);
                p.setXfermode(null);p.setShader(null);c.restoreToCount(layer);
            }else{message(c,"couldn’t load muse — reopen the app",350);}

            String status=connected?"connected":"connecting";
            p.setTextSize(19);
            p.setTypeface(statusFont);
            float statusStart=681-p.measureText(status);
            p.setColor(connected?Color.rgb(64,151,102):Color.rgb(172,150,104));c.drawCircle(statusStart-14,48,5,p);
            text(c,status,681,55,19,ink,Paint.Align.RIGHT);


            if(recording||speaking){
                audioLevel+=(Math.min(1,level*5)-audioLevel)*.3f;
                for(int i=0;i<9;i++){
                    float amplitude=recording?audioLevel:.35f;
                    float h=3+amplitude*(7+13*(.5f+.5f*(float)Math.sin(t*9+i*.85)));
                    p.setColor(Color.rgb(150,137,175));c.drawRoundRect(new RectF(309+i*12,594-h,314+i*12,594+h),3,3,p);
                }
            }else if(thinking){
                for(int i=0;i<3;i++){p.setColor(Color.argb(90+(int)(100*(.5+.5*Math.sin(t*4-i))),139,127,163));c.drawCircle(344+i*16,594,4,p);}
            }
            String hint=recording?"i’m listening":thinking?"thinking":speaking?"muse is speaking":"hold muse to talk";
            String sub=recording?"release to send":speaking?"hold to interrupt":"";
            if(mode.equals("error")||mode.equals("loading"))message(c,caption,632);
            else if(!connected)message(c,"open Muse on your phone to connect",632);
            else if(mode.equals("idle")&&caption.startsWith("didn’t"))message(c,caption,632);
            else statusLabel(c,hint,sub,now);
            c.restore();
            postInvalidateDelayed(66);
        }
        public boolean performClick(){
            super.performClick();if(recording)stopRecording();else startRecording();return true;
        }
        public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){held=true;startRecording();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){if(held){held=false;stopRecording();}return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){held=false;cancelRecording();return true;}
            return true;
        }
    }
}
