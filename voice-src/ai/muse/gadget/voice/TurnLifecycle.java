package ai.muse.gadget.voice;

import java.util.UUID;

/** UI-thread-owned conversation lifecycle. No inferred task progress. */
public final class TurnLifecycle {
    private long generation;
    private boolean sceneClaimed, sceneResolved=true, replyReleased;
    private String token = "", phase = "idle";

    public long generation(){return generation;}
    public String token(){return token;}
    public String phase(){return phase;}
    public boolean current(long request){return generation==request;}
    public long listen(){invalidate();phase="recording";return generation;}
    public boolean waitForReply(long request){
        if(!current(request)||!phase.equals("recording"))return false;
        token=UUID.randomUUID().toString();phase="thinking";return true;
    }
    public boolean accepts(String replyToken){
        if(replyToken==null||replyToken.isEmpty())return false;
        return replyToken.equals(token)&&phase.equals("thinking");
    }
    /** Start one visual job without consuming the pending spoken answer. */
    public long prepareScene(String sceneToken){
        if(!accepts(sceneToken)||sceneClaimed)return -1;
        sceneClaimed=true;sceneResolved=false;return generation;
    }
    public boolean resolveScene(long request){
        if(!sceneActive(request))return false;
        sceneResolved=true;return true;
    }
    /** Claim playback once both the accepted answer and scene outcome are ready. */
    public boolean releaseReply(long request){
        if(!current(request)||!phase.equals("voicing")||!sceneResolved||replyReleased)return false;
        replyReleased=true;return true;
    }
    public long reply(String replyToken){
        if(!accepts(replyToken))return -1;
        phase="voicing";return generation;
    }
    public boolean sceneActive(long request){
        return current(request)&&(phase.equals("thinking")||phase.equals("voicing")||phase.equals("speaking"));
    }
    public boolean started(long request){return move(request,"voicing","speaking");}
    public boolean nextChunk(long request){return move(request,"speaking","voicing");}
    public boolean complete(long request){return move(request,"speaking","complete");}
    public boolean settle(long request){return move(request,"complete","idle");}
    private boolean move(long request,String from,String to){
        if(!current(request)||!phase.equals(from))return false;
        phase=to;return true;
    }
    public void invalidate(){++generation;token="";phase="idle";sceneClaimed=false;sceneResolved=true;replyReleased=false;}
    public void blocked(){invalidate();phase="error";}
    public void ready(){if(phase.equals("error"))phase="idle";}
}
