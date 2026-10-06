package ai.muse.gadget.voice;
public final class MuseMatteTest {
 public static void main(String[] args){
  int[] p=new int[25];java.util.Arrays.fill(p,0xfff7f7f7);
  for(int y=1;y<4;y++)for(int x=1;x<4;x++)p[y*5+x]=0xffccb899;
  p[12]=0xffffffff;
  MuseMatte m=new MuseMatte(5,5);m.apply(p);
  if(p[0]>>>24!=0||p[24]>>>24!=0)throw new AssertionError("background retained");
  if(p[12]!=0xffffffff||p[6]!=0xffccb899)throw new AssertionError("interior face/fur changed");
  java.util.Arrays.fill(p,0xfffafafa);m.apply(p);
  for(int c:p)if(c>>>24!=0)throw new AssertionError("reused mask retained prior frame");
  System.out.println("PASS: matte background, enclosed highlights, fur and reused buffers");
 }
}
