package ai.muse.gadget.service;
import android.app.Service;
import android.os.Binder;
import android.os.IBinder;
import android.content.Intent;
import kotlin.Pair;
public class GadgetService extends Service {
 public enum State {IDLE,PAIRING,PROVISIONING,CONNECTING,CONNECTED,ERROR}
 public interface StateListener {void onState(State s,String detail);void onChat(String text);void onListening(boolean listening);}
 public class LocalBinder extends Binder {public GadgetService service(){return null;}}
 public boolean addListener(StateListener l){return false;}
 public boolean removeListener(StateListener l){return false;}
 public Pair<State,String> currentState(){return null;}
 public boolean sendChat(String text){return false;}
 public IBinder onBind(Intent i){return null;}
}
