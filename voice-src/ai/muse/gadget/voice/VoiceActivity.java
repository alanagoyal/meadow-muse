package ai.muse.gadget.voice;

import ai.muse.gadget.service.GadgetService;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.InputStream;
import java.util.UUID;

/** Live Muse chat. Input is always entered by the user, never supplied as a fixture. */
public class VoiceActivity extends Activity implements GadgetService.StateListener {
    private final Handler ui=new Handler();
    private GadgetService service;
    private boolean bound,connected,paused,destroyed;
    private String pending="";
    private EditText input;
    private TextView status,response;
    private LinearLayout messages;
    private ScrollView scroll;
    private float scale;
    // INPUT_FIELDS
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((GadgetService.LocalBinder)binder).service();service.addListener(VoiceActivity.this);onState(service.currentState().getFirst(),service.currentState().getSecond());}
        public void onServiceDisconnected(ComponentName name){service=null;connected=false;cancel();status.setText("Open Muse on your phone to reconnect.");}
    };
    public void onCreate(Bundle state){
        super.onCreate(state);scale=getResources().getDisplayMetrics().widthPixels/720f;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(4096|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        VoiceBridge.activity=new java.lang.ref.WeakReference<VoiceActivity>(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.WHITE);
        root.addView(new Header(),new LinearLayout.LayoutParams(-1,px(124)));
        status=label("Connecting to Muse…",19);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,px(30)));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        messages=new LinearLayout(this);messages.setOrientation(1);messages.setPadding(px(28),px(8),px(28),px(16));scroll.addView(messages,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);composer.setPadding(px(12),px(5),px(10),px(5));composer.setBackgroundDrawable(round(0xfff2f2f2,40));
        // INPUT_BUTTON
        input=new EditText(this);input.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(27));input.setTextColor(0xff232323);input.setHintTextColor(0xff7b7b7b);input.setHint("Message");input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(px(10),px(10),px(8),px(10));input.setMaxLines(3);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        SendButton send=new SendButton();composer.addView(send,new LinearLayout.LayoutParams(px(48),px(48)));send.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendInput();}});
        LinearLayout.LayoutParams bottom=new LinearLayout.LayoutParams(-1,-2);bottom.setMargins(px(24),px(8),px(24),px(20));root.addView(composer,bottom);setContentView(root);input.requestFocus();
        // INPUT_INIT
        connect();
    }
    private int px(float n){return Math.round(n*scale);}
    private TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));t.setTextColor(0xff232323);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;}
    private void connect(){
        if(bound||destroyed)return;
        if(Build.VERSION.SDK_INT>=31&&(checkCallingOrSelfPermission("android.permission.BLUETOOTH_CONNECT")!=PackageManager.PERMISSION_GRANTED||checkCallingOrSelfPermission("android.permission.BLUETOOTH_ADVERTISE")!=PackageManager.PERMISSION_GRANTED)){
            try{Activity.class.getMethod("requestPermissions",String[].class,int.class).invoke(this,new Object[]{new String[]{"android.permission.BLUETOOTH_CONNECT","android.permission.BLUETOOTH_ADVERTISE"},4});}catch(Exception e){status.setText("Bluetooth permission needed.");}return;
        }
        Intent i=new Intent(this,GadgetService.class);
        try{Context.class.getMethod("startForegroundService",Intent.class).invoke(this,i);bound=bindService(i,connection,Context.BIND_AUTO_CREATE);}catch(Exception e){status.setText("Couldn’t start Muse.");}
    }
    public void onRequestPermissionsResult(int request,String[] names,int[] results){if(request==4&&results.length==2&&results[0]==0&&results[1]==0)connect();}
    public void onState(final GadgetService.State state,String detail){ui.post(new Runnable(){public void run(){if(destroyed)return;connected=state==GadgetService.State.CONNECTED;if(!connected)cancel();status.setText(connected?"":"Open Muse on your phone to connect.");}});}
    public void onListening(boolean listening){}
    public void onChat(String text){} // Only accept a reply carrying this request's ID.
    private void sendInput(){
        final String text=input.getText().toString().trim();if(text.isEmpty()||paused||destroyed)return;
        if(!connected||service==null){status.setText("Open Muse on your phone to connect.");return;}
        cancel();input.setText("");((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        TextView user=label(text,25);user.setPadding(px(20),px(14),px(20),px(14));user.setBackgroundDrawable(round(0xfff2f2f2,23));
        LinearLayout.LayoutParams bubble=new LinearLayout.LayoutParams(px(530),-2);bubble.gravity=Gravity.RIGHT;bubble.bottomMargin=px(24);messages.addView(user,bubble);
        response=label("",27);response.setLineSpacing(px(6),1);LinearLayout.LayoutParams reply=new LinearLayout.LayoutParams(-1,-2);reply.bottomMargin=px(26);messages.addView(response,reply);
        final String token=UUID.randomUUID().toString();pending=token;status.setText("Muse is thinking…");scroll.post(new Runnable(){public void run(){scroll.fullScroll(View.FOCUS_DOWN);}});
        final GadgetService target=service;
        new Thread(new Runnable(){public void run(){final boolean ok=target.sendChat("Message from Meadow. Reply briefly using chat.reply with text and this exact turnId: "+token+". After the tool, write the same complete answer in Muse chat. User said: "+text);ui.post(new Runnable(){public void run(){if(!ok&&pending.equals(token)){pending="";status.setText("Couldn’t reach Muse. Try again.");}}});}},"muse-chat-send").start();
        ui.postDelayed(new Runnable(){public void run(){if(pending.equals(token)){pending="";status.setText("No reply from Muse. Try again.");}}},90000);
    }
    public String submitReply(final String text,final String token){
        if(Looper.myLooper()==Looper.getMainLooper())return accept(text,token);
        java.util.concurrent.FutureTask<String> task=new java.util.concurrent.FutureTask<String>(new java.util.concurrent.Callable<String>(){public String call(){return accept(text,token);}});
        ui.post(task);try{return task.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);return "Could not confirm reply acceptance.";}
    }
    private String accept(String text,String token){
        if(destroyed||paused||!connected||pending.isEmpty()||!pending.equals(token))return "No matching active turn. Copy the latest turnId.";
        if(text==null||text.trim().isEmpty())return "text is required";
        pending="";status.setText("");response.setText(text.trim());
        // OUTPUT_REPLY
        return null;
    }
    private void cancel(){pending="";/* INPUT_CANCEL */}
    protected void onResume(){super.onResume();paused=false;}
    protected void onPause(){super.onPause();paused=true;cancel();}
    protected void onDestroy(){destroyed=true;cancel();ui.removeCallbacksAndMessages(null);if(service!=null)service.removeListener(this);if(bound)unbindService(connection);/* INPUT_CLOSE */super.onDestroy();}
    // INPUT_METHODS
    private final class SendButton extends View{
        final Paint p=new Paint(3);SendButton(){super(VoiceActivity.this);setContentDescription("Send message");}
        protected void onDraw(Canvas c){c.save();c.scale(getWidth()/48f,getHeight()/48f);p.setColor(0xff2e7ae0);c.drawCircle(24,24,24,p);p.setColor(Color.WHITE);p.setStrokeWidth(2.5f);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(24,34,24,14,p);c.drawLine(24,14,16,22,p);c.drawLine(24,14,32,22,p);c.restore();}
    }
    private final class Header extends View{
        final Paint p=new Paint(3);Movie avatar;
        Header(){super(VoiceActivity.this);try{InputStream in=getAssets().open("muse/idle.gif");avatar=Movie.decodeStream(in);in.close();}catch(Exception ignored){}}
        protected void onDraw(Canvas c){c.save();c.scale(scale,scale);if(avatar!=null){c.save();Path clip=new Path();clip.addCircle(360,44,33,Path.Direction.CW);c.clipPath(clip);c.translate(327,11);c.scale(66f/avatar.width(),66f/avatar.height());avatar.setTime(0);avatar.draw(c,0,0);c.restore();}p.setColor(0xfff2f2f2);c.drawRoundRect(new RectF(326,80,394,111),16,16,p);p.setColor(0xff232323);p.setTextSize(22);c.drawText("Muse",335,103,p);c.restore();}
    }
}
