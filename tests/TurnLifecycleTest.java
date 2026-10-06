package ai.muse.gadget.voice;

public final class TurnLifecycleTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        TurnLifecycle c=new TurnLifecycle();
        check(!c.accepts(""),"uncorrelated speech is unsafe");
        long a=c.listen();check(!c.complete(a),"recording cannot celebrate");
        check(c.waitForReply(a),"recognition submits turn");String token=c.token();
        check(!token.isEmpty(),"turn has a token");
        check(!c.accepts(""),"text-only response must not steal a voice turn");
        check(!c.accepts("wrong"),"wrong token rejected");
        check(c.reply(token)==a,"current reply accepted");check(!c.accepts(token),"duplicate command rejected");
        check(!c.complete(a),"download is not completion");
        check(c.started(a),"audio started");check(c.nextChunk(a),"more speech remains");
        check(!c.complete(a),"between chunks cannot celebrate");check(c.started(a),"next chunk started");
        check(c.complete(a),"last playback completes");check(!c.complete(a),"one completion per reply");
        check(c.settle(a),"reaction settles");check(!c.accepts(token),"finished token is closed");
        for(String phase:new String[]{"recording","thinking","voicing","speaking","complete"}){
            a=c.listen();c.waitForReply(a);token=c.token();
            if(phase.equals("recording")){a=c.listen();}
            if(phase.equals("voicing")||phase.equals("speaking")||phase.equals("complete"))c.reply(token);
            if(phase.equals("speaking")||phase.equals("complete"))c.started(a);
            if(phase.equals("complete"))c.complete(a);
            c.invalidate();long b=c.listen();c.waitForReply(b);String fresh=c.token();
            check(!c.accepts(token),"stale reply after interrupt in "+phase);
            check(!c.started(a)&&!c.complete(a)&&!c.settle(a),"stale callbacks after "+phase);
            check(c.accepts(fresh),"new turn survives old callbacks");
            c.blocked();check(!c.accepts(fresh)&&!c.complete(b),"failure invalidates reply");
            c.ready();check(c.phase().equals("idle"),"reconnect ready");
            check(!c.accepts(""),"reconnect cannot admit stale untagged text");
        }
        a=c.listen();
        check(c.prepareScene("")==-1,"dictation cannot start a scene");
        c.waitForReply(a);token=c.token();
        check(c.prepareScene("wrong")==-1,"wrong scene token rejected");
        check(c.prepareScene(token)==a,"interpreted prompt starts scene early");
        check(c.phase().equals("thinking")&&c.accepts(token),"scene does not consume answer");
        check(c.prepareScene(token)==-1,"duplicate scene rejected");
        check(c.reply(token)==a,"answer accepted after scene starts");
        check(c.prepareScene(token)==-1,"late preparation rejected");
        String oldToken=token;
        a=c.listen();c.waitForReply(a);token=c.token();
        check(c.prepareScene(oldToken)==-1,"interrupted scene direction rejected");
        c.blocked();check(c.prepareScene(token)==-1,"blocked scene rejected");
        a=c.listen();c.waitForReply(a);token=c.token();
        check(c.prepareScene(token)==a,"new turn allows fresh scene");
        check(c.accepts(token),"automatic scene start keeps answer pending");
        check(c.prepareScene(token)==-1,"remote scene reminder cannot restart automatic job");
        check(c.sceneActive(a),"pending answer accepts scene");
        c.reply(token);check(c.sceneActive(a),"voice preparation accepts scene");
        c.started(a);check(c.sceneActive(a),"speech accepts scene");
        c.complete(a);check(!c.sceneActive(a),"completed answer rejects late scene");
        c.settle(a);check(!c.sceneActive(a),"idle cannot resurrect scene");
        c.listen();check(!c.sceneActive(a),"cancelled scene rejected");
        check(!c.sceneActive(c.generation()),"dictation has no scene");
        a=c.listen();c.waitForReply(a);token=c.token();c.prepareScene(token);c.reply(token);
        check(!c.releaseReply(a),"fast answer waits for scene");
        check(c.resolveScene(a),"scene outcome releases wait");
        check(c.releaseReply(a),"answer starts after scene");
        check(!c.releaseReply(a),"duplicate scene cannot replay answer");
        a=c.listen();c.waitForReply(a);token=c.token();c.prepareScene(token);c.resolveScene(a);
        check(!c.releaseReply(a),"fast scene still waits for answer");
        c.reply(token);check(c.releaseReply(a),"late answer starts with ready scene");
        a=c.listen();c.waitForReply(a);token=c.token();c.prepareScene(token);c.reply(token);
        check(c.resolveScene(a)&&c.releaseReply(a),"failed or timed-out scene allows normal reply");
        a=c.listen();c.waitForReply(a);token=c.token();c.prepareScene(token);c.reply(token);c.invalidate();
        check(!c.resolveScene(a)&&!c.releaseReply(a),"interruption discards held reply and late scene");
        // A completed reply followed by a scene-first follow-up must use a fresh ID.
        a=c.listen();c.waitForReply(a);String first=c.token();c.reply(first);c.releaseReply(a);c.started(a);c.complete(a);
        long follow=c.listen();c.waitForReply(follow);String second=c.token();c.prepareScene(second);c.resolveScene(follow);
        check(!first.equals(second),"follow-up has a fresh identity");
        check(c.reply(first)==-1&&c.reply("")==-1,"missing or previous ID cannot consume follow-up");
        check(c.phase().equals("thinking"),"rejected follow-up stays available for corrected reply");
        check(c.reply(second)==follow&&c.releaseReply(follow),"corrected follow-up starts after ready scene");
        check(c.started(follow)&&c.complete(follow),"follow-up finishes normally");
        System.out.println("PASS: "+checks+" lifecycle checks");
    }
}
