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
        a=c.listen();c.waitForReply(a);String first=c.token();
        check(!c.releaseReply(a),"no playback before answer");
        c.reply(first);check(c.releaseReply(a),"accepted answer starts immediately");
        check(!c.releaseReply(a),"duplicate release cannot replay answer");
        c.started(a);c.complete(a);
        long follow=c.listen();c.waitForReply(follow);String second=c.token();
        check(!first.equals(second),"follow-up has fresh identity");
        check(c.reply(first)==-1&&c.reply("")==-1,"previous or missing ID cannot consume follow-up");
        check(c.reply(second)==follow&&c.releaseReply(follow),"follow-up starts immediately");
        c.invalidate();check(!c.releaseReply(follow)&&!c.started(follow),"interruption cancels playback");
        System.out.println("PASS: "+checks+" lifecycle checks");
    }
}
