package ai.muse.gadget.voice;

/** Remove only pale neutral background connected to the edge of an original clip.
 * Interior highlights remain intact; pixel buffers are reused by the renderer. */
public final class MuseMatte {
    private final int width,height;
    private final int[] queue;
    private final boolean[] visited;
    public MuseMatte(int width,int height){this.width=width;this.height=height;queue=new int[width*height];visited=new boolean[width*height];}
    private boolean background(int color){
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        return Math.min(r,Math.min(g,b))>=232&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=12;
    }
    public void apply(int[] pixels){
        java.util.Arrays.fill(visited,false);int tail=0;
        for(int i=0;i<pixels.length;i++){
            int x=i%width,y=i/width;
            if((x==0||x==width-1||y==0||y==height-1)&&background(pixels[i])){visited[i]=true;queue[tail++]=i;}
        }
        for(int head=0;head<tail;head++){
            int i=queue[head],x=i%width,y=i/width;
            if(x>0)tail=visit(pixels,i-1,tail);
            if(x+1<width)tail=visit(pixels,i+1,tail);
            if(y>0)tail=visit(pixels,i-width,tail);
            if(y+1<height)tail=visit(pixels,i+width,tail);
        }
        for(int n=0;n<tail;n++)pixels[queue[n]]&=0x00ffffff;
        // Soften only the exposed fringe; never key interior pale face/fur pixels.
        for(int i=0;i<pixels.length;i++){
            if(visited[i])continue;
            int x=i%width,y=i/width;
            boolean fringe=(x>0&&visited[i-1])||(x+1<width&&visited[i+1])||(y>0&&visited[i-width])||(y+1<height&&visited[i+width]);
            if(!fringe)continue;
            int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            int minimum=Math.min(r,Math.min(g,b)),spread=Math.max(r,Math.max(g,b))-minimum;
            if(minimum>=220&&spread>12&&spread<30)pixels[i]=(((spread-12)*255/18)<<24)|(c&0x00ffffff);
        }
    }
    private int visit(int[] pixels,int i,int tail){
        if(!visited[i]&&background(pixels[i])){visited[i]=true;queue[tail++]=i;}
        return tail;
    }
}
