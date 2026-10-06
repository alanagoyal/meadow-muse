package ai.muse.gadget.voice;

import java.io.*;
import java.util.Arrays;

public final class PcmStreamTest {
    static class Output implements PcmStream.Output {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        boolean cancelled,started,ended;
        int firstBytes,starts,readsAtStart,bytesAtStart,reads,idles,minimum,zeroWrites,cancelAfterBytes=-1;
        long clock,played;
        boolean stall;
        public boolean cancelled(){return cancelled;}
        public int write(byte[] b,int offset,int length){
            if(zeroWrites-->0)return 0;
            int n=Math.min(2,length); // force partial sample-aligned writes
            bytes.write(b,offset,n);
            if(cancelAfterBytes>=0&&bytes.size()>=cancelAfterBytes)cancelled=true;
            return n;
        }
        public void start(){started=true;starts++;readsAtStart=reads;bytesAtStart=bytes.size();}
        public int minimumStartBytes(){return minimum;}
        public long playedFrames(){return played;}
        public long now(){return clock;}
        public void idle(){
            idles++;clock+=5;
            if(ended&&!stall)played++;
        }
        public void receivedFirstBytes(){firstBytes++;}
        public void receivedEnd(long count){ended=true;check(count==6,"original byte count");}
    }
    static InputStream chunks(final byte[] source,final Output output){
        return new InputStream(){
            int offset;
            public int read(){throw new AssertionError("use bulk read");}
            public int read(byte[] dest,int start,int length){
                output.reads++;
                if(offset==source.length)return -1;
                int n=Math.min(length,Math.min(offset%3+1,source.length-offset));
                System.arraycopy(source,offset,dest,start,n);offset+=n;return n;
            }
        };
    }
    static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    static void malformed(byte[] data)throws Exception{
        try{Output output=new Output();PcmStream.play(chunks(data,output),output);throw new AssertionError("accepted malformed PCM");}
        catch(IOException expected){}
    }
    static void networkGap()throws Exception{
        final byte[] data=new byte[PcmStream.STARTUP_BYTES*3];
        for(int i=0;i<data.length;i++)data[i]=(byte)i;
        final Output output=new Output(){
            int underruns;
            {minimum=PcmStream.STARTUP_BYTES;}
            public int write(byte[] b,int offset,int length){
                int free=minimum-(bytes.size()-(int)played*2);
                int n=Math.min(length,free)&~1;
                bytes.write(b,offset,n);return n;
            }
            void advance(int ms){
                clock+=ms;
                if(started){
                    long wanted=played+ms*PcmStream.SAMPLE_RATE/1000;
                    if(!ended&&wanted>bytes.size()/2)underruns++;
                    played=Math.min(wanted,bytes.size()/2);
                }
            }
            public void idle(){advance(5);}
            public void receivedEnd(long count){ended=true;check(count==data.length,"stream size");check(underruns==0,"network gaps must not interrupt speech");}
        };
        InputStream input=new InputStream(){
            int offset;
            boolean earlyGap,gap;
            public int read(){throw new AssertionError("use bulk read");}
            public int read(byte[] dest,int start,int length){
                output.reads++;
                // Gaps during priming and just after playback has started.
                if(!earlyGap&&offset>=4096){
                    earlyGap=true;for(int i=0;i<40;i++)output.idle();
                }
                if(!gap&&offset>=PcmStream.STARTUP_BYTES+4096){
                    gap=true;for(int i=0;i<40;i++)output.idle();
                }
                if(offset==data.length)return -1;
                int n=Math.min(length,Math.min(2048,data.length-offset));
                System.arraycopy(data,offset,dest,start,n);offset+=n;return n;
            }
        };
        check(PcmStream.play(input,output),"jittered stream completes");
        check(output.bytesAtStart==PcmStream.STARTUP_BYTES,"prime the full buffer before playing");
        check(output.starts==1&&output.readsAtStart<output.reads-1,"start once before EOF");
        check(Arrays.equals(data,output.bytes.toByteArray()),"no repeated or dropped samples");
    }
    public static void main(String[] args)throws Exception{
        byte[] data={1,2,3,4,5,6};
        Output output=new Output();output.zeroWrites=2;
        check(PcmStream.play(chunks(data,output),output),"completed");
        check(Arrays.equals(data,output.bytes.toByteArray()),"sample framing preserves bytes");
        check(output.starts==1&&output.firstBytes==1,"one start and first-byte event");
        check(output.readsAtStart<output.reads-1,"playback begins before EOF");
        check(output.played==3&&output.idles>=5,"completion waits for drain and handles backpressure");
        Output shortAudio=new Output();shortAudio.minimum=10;
        check(PcmStream.play(chunks(data,shortAudio),shortAudio),"short clip starts");
        check(Arrays.equals(shortAudio.bytes.toByteArray(),new byte[]{1,2,3,4,5,6,0,0,0,0}),"short clip silence padding");
        check(shortAudio.bytesAtStart==10&&shortAudio.starts==1,"short clip starts only after padding");
        Output primeCancelled=new Output();primeCancelled.minimum=10;primeCancelled.cancelAfterBytes=4;
        check(!PcmStream.play(chunks(data,primeCancelled),primeCancelled)&&!primeCancelled.started,"cancel while priming");
        Output paddingCancelled=new Output();paddingCancelled.minimum=10;paddingCancelled.cancelAfterBytes=8;
        check(!PcmStream.play(chunks(data,paddingCancelled),paddingCancelled)&&!paddingCancelled.started,"cancel while padding");
        Output cancelled=new Output();cancelled.cancelAfterBytes=2;
        check(!PcmStream.play(chunks(data,cancelled),cancelled),"cancel does not complete");
        check(cancelled.bytes.size()==2&&!cancelled.ended&&!cancelled.started,"no writes or start after cancellation");
        Output drainCancelled=new Output(){public void idle(){super.idle();if(ended)cancelled=true;}};
        check(!PcmStream.play(chunks(data,drainCancelled),drainCancelled),"cancellation during drain");
        Output stalled=new Output();stalled.stall=true;
        try{PcmStream.play(chunks(data,stalled),stalled);throw new AssertionError("stalled drain accepted");}
        catch(IOException expected){check(stalled.clock>10000,"stalled drain bounded");}
        malformed(new byte[0]);malformed(new byte[]{1,2,3});
        networkGap();
        System.out.println("PcmStreamTest passed: chunk framing, buffered startup, network gaps, backpressure, short clips, drain, cancellation, malformed PCM, stalled output");
    }
}
