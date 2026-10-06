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
    private String answerText="";
    private long sentAt, acceptedAt;
    private FaceView face;
    private String mode = "loading", caption = "getting ready", transcript = "";
    private volatile long turn;
    private final TurnLifecycle lifecycle = new TurnLifecycle();
    private boolean paused;
    private long playback;
    private final Object captureLock = new Object();
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
        public void onServiceDisconnected(ComponentName name) {service=null; connected=false;block("connection lost — open Muse on your phone");}
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        VoiceBridge.activity = new java.lang.ref.WeakReference<VoiceActivity>(this);
        face = new FaceView();
        setContentView(face);
        new File(getCacheDir(),"muse-world.png").delete(); // Remove the earlier experimental world cache.
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
        face.setContentDescription("Muse. "+(answerText.isEmpty()?"":answerText+". ")+(connected?"Connected. ":"Disconnected. ")+text+". Hold to talk, release to send. Double tap to start or stop listening.");
        if(changed)face.announceForAccessibility(text);
        face.invalidate();
    }
    private void refreshReady(){
        if(destroyed || recording || mode.equals("thinking") || mode.equals("voicing") || mode.equals("speaking") || mode.equals("error"))return;
        if(model==null)setMode("loading","getting ready");
        else if(!voiceReady)setMode("error","OpenAI voice needs setup");
        else if(!connected)setMode("connecting","connecting to muse");
        else setMode("idle","ready when you are");
    }
    public void onState(final GadgetService.State state,String detail){ui.post(new Runnable(){public void run(){
        if(destroyed)return;
        boolean wasConnected=connected;
        connected=state==GadgetService.State.CONNECTED;
        if(!connected && (wasConnected || state==GadgetService.State.ERROR)){
            block("connection lost — open Muse on your phone");
        } else if(connected && !wasConnected){
            lifecycle.ready();setMode("idle","ready when you are");refreshReady();
        } else refreshReady();
    }});}
    public void onListening(boolean listening){}
    public void onChat(final String text){onReply(text,"neutral","");}
    public void onReply(final String text, final String expression, final String token){ui.post(new Runnable(){public void run(){
        acceptReply(text,expression,token,"");
    }});}
    // Called on the UI thread so the command result reflects actual acceptance.
    private String acceptReply(String text,String expression,String token,String displayText){
        if(destroyed||paused||!connected||!voiceReady)return "voice screen is not ready; ask the user to reopen Muse";
        if(text==null||text.trim().isEmpty())return "text is required";
        if(!lifecycle.accepts(token)){
            android.util.Log.w("MuseVoice","Reply rejected: "+(token==null||token.isEmpty()?"missing turnId":"stale or duplicate turnId")+"; phase="+lifecycle.phase());
            return "reply not accepted: copy the exact turnId from the latest Meadow voice message; do not reuse an earlier turnId";
        }
        long request=lifecycle.generation();
        lifecycle.reply(token);
        acceptedAt=SystemClock.uptimeMillis();
        android.util.Log.d("MuseVoice","Reply accepted; answer_ms="+(acceptedAt-sentAt));
        turn=request;
        transcript=text.trim();
        answerText=displayText==null?"":displayText.replaceAll("[*#`]", "").replaceAll("\\s+", " ").trim();
        if(answerText.length()>90){int cut=answerText.lastIndexOf(' ',87);answerText=answerText.substring(0,cut>0?cut:87)+"…";}
        face.setAnswer(answerText);
        String remaining=transcript.replaceAll("[*#`]", "");
        while(remaining.length()>3000){int cut=remaining.lastIndexOf(' ',3000);if(cut<1)cut=3000;speech.add(new Reply(remaining.substring(0,cut),expression));remaining=remaining.substring(cut).trim();}
        if(!remaining.isEmpty())speech.add(new Reply(remaining,expression));
        releaseReply(request);
        return null;
    }
    public String submitReply(final String text,final String expression,final String token,final String displayText){
        if(Looper.myLooper()==Looper.getMainLooper())return acceptReply(text,expression,token,displayText);
        java.util.concurrent.FutureTask<String> task=new java.util.concurrent.FutureTask<String>(new java.util.concurrent.Callable<String>(){
            public String call(){return acceptReply(text,expression,token,displayText);}
        });
        if(!ui.post(task))return "voice screen unavailable";
        try{return task.get(3,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception e){task.cancel(false);return "could not confirm reply acceptance; reopen Muse if no answer plays";}
    }
    private void releaseReply(final long request){
        if(destroyed||paused||!lifecycle.releaseReply(request))return;
        speakNext(request);
    }
    private void speakNext(final long request){
        if(destroyed||paused||!lifecycle.current(request))return;
        Reply next=speech.poll();
        if(next==null){
            if(!lifecycle.complete(request))return;
            reaction="neutral";reactionUntil=0;
            lifecycle.settle(request);
            setMode("idle","ready when you are");
            return;
        }
        if(!voiceReady){block("voice unavailable — reopen Muse");return;}
        setMode("voicing","muse is working");
        reaction=next.reaction;reactionUntil=Long.MAX_VALUE;
        tts.speak(next.text,speechListener(request,++playback));
    }
    private OpenAiSpeech.Listener speechListener(final long request, final long chunk){
        return new OpenAiSpeech.Listener(){
            public void started(){if(!destroyed&&chunk==playback&&lifecycle.started(request)){android.util.Log.d("MuseVoice","Playback started; accepted_to_audio_ms="+(SystemClock.uptimeMillis()-acceptedAt));setMode("speaking","muse is speaking");}}
            public void done(){
                if(destroyed||chunk!=playback||!lifecycle.current(request)||!lifecycle.phase().equals("speaking"))return;
                if(!speech.isEmpty()&&!lifecycle.nextChunk(request))return;
                speakNext(request);
            }
            public void failed(String message){if(!destroyed&&chunk==playback&&lifecycle.current(request)&&(lifecycle.phase().equals("voicing")||lifecycle.phase().equals("speaking")))block(message);}
        };
    }
    private void interrupt(){interrupt(false);}
    private void interrupt(boolean keepAnswer){
        recording=false;lifecycle.invalidate();turn=lifecycle.generation();++playback;
        level=0;speech.clear();reaction="neutral";reactionUntil=0;
        if(tts!=null)tts.stop();
        if(!keepAnswer){answerText="";if(face!=null)face.setAnswer("");}
    }
    private void block(String message){
        interrupt();lifecycle.blocked();turn=lifecycle.generation();setMode("error",message);
    }
    private void startRecording(){
        if(recording||paused||model==null||!connected||!voiceReady)return;
        if(checkCallingOrSelfPermission("android.permission.RECORD_AUDIO")!=PackageManager.PERMISSION_GRANTED){
            try{Activity.class.getMethod("requestPermissions",String[].class,int.class).invoke(this,new Object[]{new String[]{"android.permission.RECORD_AUDIO"},7});}catch(Exception e){fail("microphone permission needed",e);}
            return;
        }
        interrupt();transcript="";
        final long request=lifecycle.listen();turn=request;recording=true;
        setMode("recording","muse is listening");
        face.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        new Thread(new Runnable(){public void run(){capture(request);}},"voice-capture").start();
        ui.postDelayed(new Runnable(){public void run(){if(recording&&turn==request)stopRecording();}},45000);
    }
    private void stopRecording(){if(!recording)return;recording=false;setMode("thinking","muse is working");}
    private void cancelRecording(){
        if(!recording)return;
        interrupt(true);
        setMode("idle","ready when you are");refreshReady();
    }
    private void capture(final long request){
        synchronized(captureLock){if(!destroyed&&turn==request)captureLocked(request);}
    }
    private void captureLocked(final long request){
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
                if(turn==request)level=peak/32768f;
                if(recognizer.acceptWaveForm(buffer,n)){String s=new JSONObject(recognizer.getResult()).optString("text");if(!s.isEmpty())words.append(s).append(' ');}
            }
            String last=new JSONObject(recognizer.getFinalResult()).optString("text");
            words.append(last);
            final String text=words.toString().trim();
            ui.post(new Runnable(){public void run(){
                if(destroyed||turn!=request)return;
                if(text.isEmpty()){interrupt(true);setMode("idle","didn’t catch that — hold to try again");return;}
                if(!lifecycle.waitForReply(request))return;
                transcript=text;setMode("thinking","muse is working");
                send(text,request);
            }});
        }catch(Exception e){
            ui.post(new Runnable(){public void run(){if(!destroyed&&turn==request)block("couldn’t hear you — hold to try again");}});
        }
        finally{if(mic!=null){try{mic.stop();}catch(Exception ignored){}mic.release();}if(recognizer!=null)recognizer.close();if(turn==request)level=0;}
    }
    private void send(final String text,final long request){
        transcript=text;sentAt=SystemClock.uptimeMillis();
        final String token=lifecycle.token();
        final GadgetService target=service;
        new Thread(new Runnable(){public void run(){
            if(destroyed||turn!=request)return;
            final boolean ok=target!=null&&target.sendChat("Voice message from the Meadow. Reply briefly by invoking voice.speak with text, reaction, turnId, and displayText. The complete text is spoken aloud. For displayText, provide only the key fact or a short visual caption, 3–10 words and at most 90 characters. Do not repeat the full answer on screen. This is drawn locally without image generation. Copy this exact turnId: " + token + ". Use neutral, happy, curious, calm, working, or dance for conversational tone. Always invoke voice.speak for this turn with this message's turnId, including follow-up questions, requests you cannot fulfill, and tool failures. Never reuse a previous turnId. Do not offer email access or any external action unless you actually have that capability available; if unavailable, explain briefly through voice.speak. Do not imply external work or task progress; this device supports conversation and speech only. "+VoiceBridge.CHAT_REPLY_INSTRUCTION+" User said: " + text);
            ui.post(new Runnable(){public void run(){if(!ok&&!destroyed&&turn==request&&lifecycle.phase().equals("thinking"))block("couldn’t reach muse — hold to retry");}});
        }},"send-to-muse").start();
        ui.postDelayed(new Runnable(){public void run(){if(!destroyed&&turn==request&&lifecycle.phase().equals("thinking")){android.util.Log.w("MuseVoice","Reply deadline reached without an accepted answer");block("no reply from muse — hold to retry");}}},90000);
    }
    private void fail(final String message,Exception e){android.util.Log.e("MuseVoice",message,e);ui.post(new Runnable(){public void run(){if(!destroyed)block(message);}});}
    protected void onResume(){super.onResume();paused=false;if(face!=null&&!recording&&!mode.equals("speaking")&&!mode.equals("voicing")&&!mode.equals("thinking"))face.greet();}
    protected void onPause(){super.onPause();paused=true;interrupt();setMode("idle","ready when you are");refreshReady();}
    protected void onDestroy(){interrupt();destroyed=true;ui.removeCallbacksAndMessages(null);if(service!=null)service.removeListener(this);if(bound)unbindService(connection);if(tts!=null){tts.stop();}super.onDestroy();}
    public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){if(event.getRepeatCount()==0)startRecording();return true;}return super.onKeyDown(code,event);}
    public boolean onKeyUp(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_VOLUME_UP){stopRecording();return true;}return super.onKeyUp(code,event);}

    private class FaceView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Movie idle, working;
        private Bitmap cutout;
        private Canvas cutoutCanvas;
        private int[] cutoutPixels;
        private MuseMatte matte;
        private Movie cutoutMovie;
        private int cutoutTime=-1;
        private Bitmap characterCutout(Movie movie,int time){
            if(cutout==null||cutout.getWidth()!=movie.width()||cutout.getHeight()!=movie.height()){
                if(cutout!=null)cutout.recycle();
                cutout=Bitmap.createBitmap(movie.width(),movie.height(),Bitmap.Config.ARGB_8888);
                cutoutCanvas=new Canvas(cutout);cutoutPixels=new int[movie.width()*movie.height()];
                matte=new MuseMatte(movie.width(),movie.height());cutoutTime=-1;
            }
            if(movie!=cutoutMovie||time!=cutoutTime){
                cutout.eraseColor(Color.TRANSPARENT);movie.setTime(time);movie.draw(cutoutCanvas,0,0);
                cutout.getPixels(cutoutPixels,0,movie.width(),0,0,movie.width(),movie.height());
                matte.apply(cutoutPixels);cutout.setPixels(cutoutPixels,0,movie.width(),0,0,movie.width(),movie.height());
                cutoutMovie=movie;cutoutTime=time;
            }
            return cutout;
        }
        private final long start=SystemClock.uptimeMillis();
        private final int background=Color.rgb(247,247,247), ink=Color.rgb(84,84,89);
        private boolean held;
        private float audioLevel, renderedBob, renderedTilt, renderedZoom=1;
        private long lastFrame;
        private final Typeface statusFont=Typeface.create("sans-serif",Typeface.NORMAL);
        private long greetingUntil, visualStart;
        private String visualMode="", label="", detail="";

        void greet(){greetingUntil=SystemClock.uptimeMillis()+4000;invalidate();}
        private boolean isWorking(){return !recording&&(mode.equals("thinking")||mode.equals("voicing"));}
        private boolean captionVisible(){return !answerText.isEmpty()&&!recording&&(mode.equals("speaking")||mode.equals("idle"));}
        private int restingFrameTime(long now){
            long blink=(now-start)%5200;
            return blink<400?(int)blink:blink<800?(int)(800-blink):0;
        }
        private int frameTime(long now){
            if(mode.equals("error")||mode.equals("connecting"))return 0; // Paused: no idle breathing/blinks.
            if(isWorking()&&working!=null)return (int)((now-visualStart)%Math.max(working.duration(),1));
            if(recording)return (int)((now-visualStart)%Math.max(idle.duration(),1));
            if(mode.equals("idle")&&now<greetingUntil)
                return (int)Math.max(0,Math.min(idle.duration()-1,4000-(greetingUntil-now)));
            // Only the opening blink, before the hand rises. Never replay the wave in a reply.
            return restingFrameTime(now);
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
            p.setTypeface(statusFont);
            c.drawText(s,x,y,p);p.clearShadowLayer();
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
            c.drawText(subtitle,360,668+offset,p);p.clearShadowLayer();
        }
        private void statusLabel(Canvas c,String title,String subtitle,long now){
            // State labels change with the pose/caption, without showing the old state.
            label=title;detail=subtitle;
            labelLine(c,title,subtitle,0,1);
        }
        protected void onDraw(Canvas c){
            super.onDraw(c);
            boolean card=captionVisible();
            c.drawColor(background);
            float scale=Math.min(getWidth(),getHeight())/720f;
            c.save();c.translate((getWidth()-720*scale)/2,(getHeight()-720*scale)/2);c.scale(scale,scale);
            long now=SystemClock.uptimeMillis(),elapsed=now-start;
            float t=elapsed/1000f;
            String nextVisual=isWorking()?"working":mode;
            if(!nextVisual.equals(visualMode)){
                visualMode=nextVisual;visualStart=now;
                if(recording||isWorking()||mode.equals("speaking"))greetingUntil=0;
            }
            boolean thinking=isWorking();
            boolean speaking=mode.equals("speaking");
            float phaseTime=(now-visualStart)/1000f;
            String mood=now<reactionUntil?reaction:"neutral";

            Movie character=thinking&&working!=null?working:idle;
            float bob=(float)Math.sin(t*1.7)*2,tilt=0,zoom=1;
            if(mode.equals("error")||mode.equals("connecting")){
                // A brief head shake, then hold a visibly lowered, tilted pose until recovery.
                tilt=-7+7*(float)Math.sin(phaseTime*12)*Math.max(0,1-phaseTime/.8f);
                bob=28;zoom=1.04f;
            }
            else if(mode.equals("voicing")){tilt=2;bob=(float)Math.sin(t*1.5)*2;}
            else if(recording){tilt=-3;zoom=1.035f;}
            else if(mood.equals("dance")){tilt=(float)Math.sin(t*5)*7;bob=-Math.abs((float)Math.sin(t*5))*14;}
            else if(mood.equals("happy")){bob=-Math.abs((float)Math.sin(t*3.5))*9;}
            else if(mood.equals("curious")){tilt=4+(float)Math.sin(t*1.5);}
            else if(mood.equals("calm")){zoom=1+(float)Math.sin(t*1.1)*.008f;}
            else if(speaking){bob=(float)Math.sin(t*3.2)*4;tilt=(float)Math.sin(t*2.1)*1.8f;}
            float blend=lastFrame==0?1:Math.min(1,(now-lastFrame)/180f);lastFrame=now;
            renderedBob+=(bob-renderedBob)*blend;renderedTilt+=(tilt-renderedTilt)*blend;renderedZoom+=(zoom-renderedZoom)*blend;
            bob=renderedBob;tilt=renderedTilt;zoom=renderedZoom;
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
            }else {message(c,"couldn’t load muse — reopen the app",350);}

            String status=connected?"connected":"disconnected";
            p.setTextSize(19);
            p.setTypeface(statusFont);
            float statusStart=681-p.measureText(status);
            p.setColor(connected?Color.rgb(64,151,102):Color.rgb(172,150,104));c.drawCircle(statusStart-14,48,5,p);
            text(c,status,681,55,19,ink,Paint.Align.RIGHT);


            // The same soft frosted footer in every conversational state.
            p.setShader(new LinearGradient(0,555,0,625,new int[]{Color.argb(0,247,247,247),Color.argb(235,247,247,247)},null,Shader.TileMode.CLAMP));
            c.drawRect(0,555,720,720,p);p.setShader(null);p.setAlpha(255);
            if(card)drawAnswer(c);
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
            String hint=recording?"muse is listening":(thinking||mode.equals("voicing"))?"muse is working":speaking?"muse is speaking":"hold muse to talk";
            String sub=recording?"release to send":(speaking||thinking||mode.equals("voicing"))?"hold to interrupt":"";
            if(mode.equals("error")||mode.equals("connecting")){
                statusLabel(c,"i’m paused","",now);
                message(c,mode.equals("error")?caption:"open Muse on your phone to connect",675);
            }
            else if(mode.equals("loading"))message(c,caption,632);
            else if(!connected)message(c,"open Muse on your phone to connect",632);
            else if(mode.equals("idle")&&caption.startsWith("didn’t"))message(c,caption,632);
            else statusLabel(c,hint,sub,now);
            c.restore();
            postInvalidateDelayed(66);
        }
        private final ArrayList<String> answerLines=new ArrayList<String>();
        private boolean answerRendered;
        void setAnswer(String answer){
            answerLines.clear();answerRendered=false;
            p.setTypeface(statusFont);p.setTextSize(25);
            String rest=answer.replaceAll("\\s+", " ").trim();
            while(!rest.isEmpty()&&answerLines.size()<2){
                int count=Math.max(1,p.breakText(rest,true,570,null));
                if(count<rest.length()){int space=rest.lastIndexOf(' ',count);if(space>0)count=space;}
                String line=rest.substring(0,count);rest=rest.substring(count).trim();
                if(answerLines.size()==1&&!rest.isEmpty())line=line+"…";
                answerLines.add(line);
            }
            invalidate();
        }
        private void drawAnswer(Canvas c){
            if(!answerRendered){answerRendered=true;android.util.Log.d("MuseVoice","Answer caption rendered; accepted_to_card_ms="+(SystemClock.uptimeMillis()-acceptedAt));}
            float top=answerLines.size()==1?557:529;
            p.setShader(new LinearGradient(0,495,0,575,Color.argb(0,247,247,247),Color.rgb(247,247,247),Shader.TileMode.CLAMP));
            c.drawRect(0,495,720,720,p);p.setShader(null);
            for(int line=0;line<answerLines.size();line++)text(c,answerLines.get(line),360,top+line*30,25,Color.rgb(66,60,78),Paint.Align.CENTER);
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
