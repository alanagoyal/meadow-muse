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
    private OpenAiScene scenes;
    private Bitmap sceneBitmap;
    private long sceneStart;
    private boolean scenePending, sceneBeganWhileThinking, sceneArrivedBeforeSpeech, sceneGeneratedThisTurn;
    private String sceneStatus="", previousContext="", sceneSubject="";
    private FaceView face;
    private String mode = "loading", caption = "getting ready", transcript = "";
    private volatile long turn;
    private final TurnLifecycle lifecycle = new TurnLifecycle();
    private boolean paused;
    private long playback;
    private static final long COMPLETION_MS = 2600;
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
        scenes=new OpenAiScene(this);
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
        if(destroyed || recording || mode.equals("thinking") || mode.equals("scene-wait") || mode.equals("voicing") || mode.equals("speaking") || mode.equals("complete") || mode.equals("error"))return;
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
        acceptReply(text,expression,token,"keep");
    }});}
    // Called on the UI thread so the command result reflects actual acceptance.
    private String acceptReply(String text,String expression,String token,String sceneDecision){
        if(destroyed||paused||!connected||!voiceReady)return "voice screen is not ready; ask the user to reopen Muse";
        if(text==null||text.trim().isEmpty())return "text is required";
        if(!lifecycle.accepts(token)){
            android.util.Log.w("MuseVoice","Reply rejected: "+(token==null||token.isEmpty()?"missing turnId":"stale or duplicate turnId")+"; phase="+lifecycle.phase());
            return "reply not accepted: copy the exact turnId from the latest Meadow voice message; do not reuse an earlier turnId";
        }
        if(!"keep".equals(sceneDecision)&&!"new".equals(sceneDecision))return "sceneDecision is required: keep for a follow-up on the current subject, new for a change of subject";
        long request=lifecycle.generation();
        if(sceneBitmap!=null&&"new".equals(sceneDecision)&&lifecycle.prepareScene(token)==request){
            clearScene();generateScene(transcript,request);
        }
        lifecycle.reply(token);
        android.util.Log.d("MuseVoice","Reply accepted; scenePending="+scenePending);
        turn=request;
        transcript=text.trim();
        previousContext+="\nMuse: "+transcript;
        if(previousContext.length()>1600)previousContext=previousContext.substring(previousContext.length()-1600);
        String remaining=transcript.replaceAll("[*#`]", "");
        while(remaining.length()>3000){int cut=remaining.lastIndexOf(' ',3000);if(cut<1)cut=3000;speech.add(new Reply(remaining.substring(0,cut),expression));remaining=remaining.substring(cut).trim();}
        if(!remaining.isEmpty())speech.add(new Reply(remaining,expression));
        if(scenePending)setMode("scene-wait","muse is working");
        releaseReply(request);
        return null;
    }
    public String submitReply(final String text,final String expression,final String token,final String sceneDecision){
        if(Looper.myLooper()==Looper.getMainLooper())return acceptReply(text,expression,token,sceneDecision);
        java.util.concurrent.FutureTask<String> task=new java.util.concurrent.FutureTask<String>(new java.util.concurrent.Callable<String>(){
            public String call(){return acceptReply(text,expression,token,sceneDecision);}
        });
        if(!ui.post(task))return "voice screen unavailable";
        try{return task.get(3,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception e){task.cancel(false);return "could not confirm reply acceptance; reopen Muse if no answer plays";}
    }
    private void releaseReply(final long request){
        if(destroyed||paused||!lifecycle.releaseReply(request))return;
        // Complete the scene entrance before audio starts.
        long delay=sceneBitmap==null?0:Math.max(0,SceneStage.ARRIVAL_MS-(SystemClock.uptimeMillis()-sceneStart));
        ui.postDelayed(new Runnable(){public void run(){
            if(!destroyed&&!paused&&lifecycle.current(request)&&lifecycle.phase().equals("voicing"))speakNext(request);
        }},delay);
    }
    private void speakNext(final long request){
        if(destroyed||paused||!lifecycle.current(request))return;
        Reply next=speech.poll();
        if(next==null){
            if(!lifecycle.complete(request))return;
            scenes.cancel();scenePending=false;sceneStatus="";
            reaction="neutral";reactionUntil=0;
            setMode("complete","there you go");
            ui.postDelayed(new Runnable(){public void run(){
                if(!destroyed&&lifecycle.settle(request)){setMode("idle","ready when you are");refreshReady();}
            }},COMPLETION_MS);
            return;
        }
        if(!voiceReady){block("voice unavailable — reopen Muse");return;}
        setMode("voicing","muse is working");
        reaction=next.reaction;reactionUntil=Long.MAX_VALUE;
        tts.speak(next.text,speechListener(request,++playback));
    }
    private OpenAiSpeech.Listener speechListener(final long request, final long chunk){
        return new OpenAiSpeech.Listener(){
            public void started(){if(!destroyed&&chunk==playback&&lifecycle.started(request))setMode("speaking","muse is speaking");}
            public void done(){
                if(destroyed||chunk!=playback||!lifecycle.current(request)||!lifecycle.phase().equals("speaking"))return;
                if(!speech.isEmpty()&&!lifecycle.nextChunk(request))return;
                speakNext(request);
            }
            public void failed(String message){if(!destroyed&&chunk==playback&&lifecycle.current(request)&&(lifecycle.phase().equals("voicing")||lifecycle.phase().equals("speaking")))block(message);}
        };
    }
    private void interrupt(){interrupt(false);}
    private void interrupt(boolean keepScene){
        recording=false;lifecycle.invalidate();turn=lifecycle.generation();++playback;
        level=0;speech.clear();reaction="neutral";reactionUntil=0;
        if(tts!=null)tts.stop();
        if(keepScene){if(scenes!=null)scenes.cancel();scenePending=false;sceneStatus="";}
        else {clearScene();previousContext="";}
        sceneBeganWhileThinking=false;
    }
    private void clearScene(){
        if(scenes!=null)scenes.cancel();
        sceneStatus="";scenePending=false;sceneSubject="";
        if(sceneBitmap!=null){sceneBitmap.recycle();sceneBitmap=null;}
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
        interrupt(true);transcript="";
        final long request=lifecycle.listen();turn=request;recording=true;
        setMode("recording","listening — release to send");
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
    private void generateScene(final String text,final long request){
        sceneSubject=text;sceneStatus="muse is working";scenePending=true;
        scenes.generate(text,previousContext,sceneListener(request));
        ui.postDelayed(new Runnable(){public void run(){
            if(!destroyed&&!paused&&lifecycle.current(request)&&scenePending)sceneFailed(request,"scene took too long — answering normally");
        }},30000);
    }
    private OpenAiScene.Listener sceneListener(final long request){
        return new OpenAiScene.Listener(){
            public void image(Bitmap image){
                if(destroyed||paused||!scenePending||!lifecycle.sceneActive(request)){image.recycle();return;}
                sceneGeneratedThisTurn=true;
                if(sceneBitmap!=null)sceneBitmap.recycle();sceneBitmap=image;
                sceneArrivedBeforeSpeech=lifecycle.phase().equals("thinking")||lifecycle.phase().equals("voicing");
                sceneStart=SystemClock.uptimeMillis();sceneStatus="";scenePending=false;face.invalidate();
                lifecycle.resolveScene(request);releaseReply(request);
                android.util.Log.d("MuseVoice","Fresh generated background displayed before speech");
            }
            public void failed(String hint){
                if(!destroyed&&!paused&&scenePending&&lifecycle.sceneActive(request))sceneFailed(request,hint);
            }
        };
    }
    private void sceneFailed(long request,String hint){
        clearScene();sceneStatus=hint;
        lifecycle.resolveScene(request);releaseReply(request);face.invalidate();
    }
    private void send(final String text,final long request){
        sceneBeganWhileThinking=false;sceneArrivedBeforeSpeech=false;sceneGeneratedThisTurn=false;
        transcript=text;
        previousContext+="\nUser: "+text;
        final String token=lifecycle.token();
        // Finalized dictation starts two independent jobs. The image model interprets
        // the question itself; neither a remote command nor the answer gates it.
        if(sceneBitmap==null&&lifecycle.prepareScene(token)==request){
            sceneBeganWhileThinking=true;
            generateScene(text,request);
        }
        final String sceneContext=sceneBitmap==null?"No current scene.":"Current scene subject: "+sceneSubject;
        final GadgetService target=service;
        new Thread(new Runnable(){public void run(){
            if(destroyed||turn!=request)return;
            final boolean ok=target!=null&&target.sendChat("Voice message from the Meadow. Reply briefly by invoking voice.speak with text, reaction, turnId, and sceneDecision. Set sceneDecision to keep for follow-ups, clarifications, or next steps on the current subject (including checking email about the same package), and new only for a genuine change of subject. "+sceneContext+" . The device generates the first scene while you prepare your answer and retains it across follow-ups; do not wait for scene generation or issue scene commands. Copy this exact turnId: " + token + ". Use neutral, happy, curious, calm, working, or dance for conversational tone. Always finish this turn by invoking voice.speak with this message's turnId, including follow-up questions, requests you cannot fulfill, and tool failures. Never reuse a previous turnId. Do not offer email access or any external action unless you actually have that capability available; if unavailable, explain briefly through voice.speak. Do not imply external work or task progress; this device supports conversation and speech only. User said: " + text);
            ui.post(new Runnable(){public void run(){if(!ok&&!destroyed&&turn==request&&lifecycle.phase().equals("thinking"))block("couldn’t reach muse — hold to retry");}});
        }},"send-to-muse").start();
        ui.postDelayed(new Runnable(){public void run(){if(!destroyed&&turn==request&&lifecycle.phase().equals("thinking")){android.util.Log.w("MuseVoice","Reply deadline reached without an accepted answer");block("no reply from muse — hold to retry");}}},90000);
    }
    private void fail(final String message,Exception e){android.util.Log.e("MuseVoice",message,e);ui.post(new Runnable(){public void run(){if(!destroyed)block(message);}});}
    protected void onResume(){super.onResume();paused=false;if(face!=null&&!recording&&!mode.equals("speaking")&&!mode.equals("voicing")&&!mode.equals("scene-wait")&&!mode.equals("thinking"))face.greet();}
    protected void onPause(){super.onPause();paused=true;interrupt();setMode("idle","ready when you are");refreshReady();}
    protected void onDestroy(){interrupt();if(sceneBitmap!=null){sceneBitmap.recycle();sceneBitmap=null;}destroyed=true;ui.removeCallbacksAndMessages(null);if(service!=null)service.removeListener(this);if(bound)unbindService(connection);if(tts!=null){tts.stop();}super.onDestroy();}
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
        private long greetingUntil, visualStart, labelStart;
        private String visualMode="", label="", previousLabel="", detail="", previousDetail="";

        void greet(){greetingUntil=SystemClock.uptimeMillis()+4000;invalidate();}
        private boolean isWorking(){return !recording&&(mode.equals("thinking")||scenePending);}
        private float sceneOpacity(long now){return sceneBitmap==null||recording?0:1;}
        private float sceneMuseOpacity(long now){return sceneBitmap==null||recording?0:SceneStage.characterAlpha(now-sceneStart);}
        private int restingFrameTime(long now){
            long blink=(now-start)%5200;
            return blink<400?(int)blink:blink<800?(int)(800-blink):0;
        }
        private int frameTime(long now){
            if(mode.equals("error")||mode.equals("connecting"))return 0; // Paused: no idle breathing/blinks.
            if(mode.equals("complete")){
                long elapsed=now-visualStart;
                if(elapsed>=COMPLETION_MS)return 0;
                // One original wave, beginning and ending with the hand lowered.
                return (int)Math.min(idle.duration()-1,Math.max(0,elapsed)*idle.duration()/COMPLETION_MS);
            }
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
            if(sceneOpacity(SystemClock.uptimeMillis())>0){p.setColor(Color.WHITE);p.setShadowLayer(3,0,1,Color.argb(190,0,0,0));}
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
            if(!title.equals(label)||!subtitle.equals(detail)){
                previousLabel=label;previousDetail=detail;label=title;detail=subtitle;labelStart=now;
            }
            float progress=Math.min(1,(now-labelStart)/260f);
            float ease=1-(1-progress)*(1-progress)*(1-progress);
            if(ease<1)labelLine(c,previousLabel,previousDetail,-8*ease,1-ease);
            labelLine(c,label,detail,8*(1-ease),ease);
        }
        protected void onDraw(Canvas c){
            super.onDraw(c);
            float opacity=sceneOpacity(SystemClock.uptimeMillis());
            boolean scenic=opacity>0;
            c.drawColor(background);
            float scale=Math.min(getWidth(),getHeight())/720f;
            c.save();c.translate((getWidth()-720*scale)/2,(getHeight()-720*scale)/2);c.scale(scale,scale);
            if(scenic){
                int sceneLayer=c.saveLayerAlpha(0,0,720,720,(int)(255*opacity),Canvas.ALL_SAVE_FLAG);
                p.setAlpha(255);c.drawBitmap(sceneBitmap,null,new RectF(0,0,720,720),p);
                c.restoreToCount(sceneLayer);
            }
            long now=SystemClock.uptimeMillis(),elapsed=now-start;
            float t=elapsed/1000f;
            if(!mode.equals(visualMode)){
                visualMode=mode;visualStart=now;
                if(recording||isWorking()||mode.equals("speaking"))greetingUntil=0;
            }
            boolean thinking=isWorking();
            boolean speaking=mode.equals("speaking");
            float phaseTime=(now-visualStart)/1000f;
            String mood=now<reactionUntil?reaction:"neutral";

            Movie character=thinking&&working!=null?working:idle;
            float bob=(float)Math.sin(t*1.7)*2,tilt=0,zoom=1;
            if(mode.equals("complete")){
                // Two joyful hops, then a quiet landing. Only final playback completion enters here.
                float hop=Math.min(1,phaseTime/1.25f);
                bob=hop<1?-30*Math.abs((float)Math.sin(hop*2*Math.PI))*(1-.4f*hop):0;
                tilt=5*(float)Math.sin(phaseTime*7)*Math.max(0,1-phaseTime/2f);
            }
            else if(mode.equals("error")||mode.equals("connecting")){
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
            if(!scenic&&character!=null){
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
            }else if(!scenic){message(c,"couldn’t load muse — reopen the app",350);}

            float museAlpha=sceneMuseOpacity(now);
            if(scenic&&idle!=null&&museAlpha>0){
                // A fresh entrance of normal Muse, never a crossfade from the laptop clip.
                Bitmap original=characterCutout(idle,restingFrameTime(now));
                float sceneBob=Math.max(-16,Math.min(16,bob));
                float sceneTilt=Math.max(-7,Math.min(7,tilt));
                float sceneZoom=Math.max(.98f,Math.min(1.04f,zoom));
                p.setAlpha((int)(255*museAlpha));
                c.save();c.translate(360,SceneStage.centerY(sceneBob,sceneTilt,sceneZoom,museAlpha));
                c.rotate(sceneTilt);c.scale(sceneZoom*SceneStage.SCALE,sceneZoom*SceneStage.SCALE);
                c.translate(-360,-360);c.drawBitmap(original,null,new RectF(0,0,720,720),p);c.restore();p.setAlpha(255);
            }

            String status=connected?"connected":"disconnected";
            p.setTextSize(19);
            p.setTypeface(statusFont);
            float statusStart=681-p.measureText(status);
            p.setColor(connected?Color.rgb(64,151,102):Color.rgb(172,150,104));c.drawCircle(statusStart-14,48,5,p);
            text(c,status,681,55,19,ink,Paint.Align.RIGHT);


            // The same soft frosted footer in every conversational state.
            p.setShader(new LinearGradient(0,555,0,625,new int[]{Color.argb(0,247,247,247),Color.argb(235,247,247,247)},null,Shader.TileMode.CLAMP));
            c.drawRect(0,555,720,720,p);p.setShader(null);p.setAlpha(255);
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
            String hint=recording?"i’m listening":(thinking||mode.equals("scene-wait")||mode.equals("voicing"))?"muse is working":mode.equals("complete")?"there you go":speaking?"muse is speaking":scenePending?"muse is working":"hold muse to talk";
            String sub=recording?"release to send":(speaking||thinking||mode.equals("voicing")||mode.equals("scene-wait"))?"hold to interrupt":sceneStatus;
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
