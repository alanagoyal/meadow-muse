package ai.muse.gadget.voice;
interface SpeechOutput {
    interface Listener {void started();void done();void failed(String message);}
}
