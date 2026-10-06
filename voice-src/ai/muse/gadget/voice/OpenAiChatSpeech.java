package ai.muse.gadget.voice;
import android.content.Context;
/** Adapter keeps the chat's input and output lifecycle independent of its speech engine. */
final class OpenAiChatSpeech {
    private final OpenAiSpeech speech;
    OpenAiChatSpeech(Context context){speech=new OpenAiSpeech(context);}
    boolean ready(){return speech.ready();}
    void speak(String text,final SpeechOutput.Listener callback){speech.speak(text,new OpenAiSpeech.Listener(){public void started(){callback.started();}public void done(){callback.done();}public void failed(String message){callback.failed(message);}});}
    void stop(){speech.stop();}
    void close(){speech.stop();}
}
