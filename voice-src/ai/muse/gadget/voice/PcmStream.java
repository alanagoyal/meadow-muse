package ai.muse.gadget.voice;

import java.io.IOException;
import java.io.InputStream;

/** Bounded PCM transfer. Network chunks need not end on a 16-bit sample boundary. */
final class PcmStream {
    static final int SAMPLE_RATE=24000;
    private static final long MAX_BYTES=8L*1024*1024;
    interface Output {
        boolean cancelled();
        int write(byte[] bytes,int offset,int length)throws IOException;
        void start()throws IOException;
        int minimumStartBytes();
        long playedFrames()throws IOException;
        long now();
        void idle()throws InterruptedException;
        void receivedFirstBytes();
        void receivedEnd(long bytes);
    }
    static boolean play(InputStream in,Output out)throws IOException,InterruptedException {
        byte[] bytes=new byte[4096];
        int pending=0;
        long total=0,written=0;
        boolean started=false;
        while(!out.cancelled()) {
            int n=in.read(bytes,pending,bytes.length-pending);
            if(out.cancelled())return false;
            if(n<0)break;
            if(n==0)continue;
            if(total==0)out.receivedFirstBytes();
            total+=n;
            if(total>MAX_BYTES)throw new IOException("Audio response too large");
            int available=pending+n,aligned=available&~1,offset=0;
            long progress=out.now();
            while(offset<aligned) {
                if(out.cancelled())return false;
                int count=out.write(bytes,offset,aligned-offset);
                if(count<0||count>aligned-offset||(count&1)!=0)throw new IOException("Audio output failed");
                if(count==0) {
                    if(out.now()-progress>10000)throw new IOException("Audio output stalled");
                    out.idle();continue;
                }
                offset+=count;written+=count;progress=out.now();
                if(out.cancelled())return false;
                if(!started){out.start();started=true;}
            }
            pending=available-aligned;
            if(pending!=0)bytes[0]=bytes[aligned];
        }
        if(out.cancelled())return false;
        if(pending!=0)throw new IOException("Incomplete PCM sample");
        if(!started)throw new IOException("Empty speech response");
        out.receivedEnd(total);
        // Android may wait for its startup threshold even after play(). Pad a tiny
        // response with silence so it can start; normal responses need no padding.
        long target=Math.max(written,out.minimumStartBytes());
        java.util.Arrays.fill(bytes,(byte)0);
        long paddingProgress=out.now();
        while(written<target) {
            if(out.cancelled())return false;
            int length=(int)Math.min(bytes.length,target-written);
            int count=out.write(bytes,0,length);
            if(count<0||count>length||(count&1)!=0)throw new IOException("Audio output failed");
            if(count==0){
                if(out.now()-paddingProgress>10000)throw new IOException("Audio output stalled");
                out.idle();continue;
            }
            written+=count;paddingProgress=out.now();
        }
        // AudioTrack.write queues frames; EOF is not playback completion.
        long frames=written/2,lastPlayed=-1,progress=out.now();
        while(!out.cancelled()) {
            long played=out.playedFrames();
            if(played>=frames)return true;
            if(played!=lastPlayed){lastPlayed=played;progress=out.now();}
            if(out.now()-progress>10000)throw new IOException("Audio playback stalled");
            out.idle();
        }
        return false;
    }
}
