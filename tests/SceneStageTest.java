package ai.muse.gadget.voice;
public final class SceneStageTest {
 public static void main(String[] args){
  if(SceneStage.characterAlpha(0)!=0||SceneStage.characterAlpha(200)!=0||SceneStage.characterAlpha(600)!=1)throw new AssertionError("scene must appear before Muse");
  if(SceneStage.characterAlpha(400)<=0||SceneStage.characterAlpha(400)>=1)throw new AssertionError("Muse entrance missing");
  int checks=0;
  for(float bob:new float[]{-16,0,16})for(float tilt:new float[]{-7,0,7})for(float zoom:new float[]{.98f,1,1.04f})for(float alpha:new float[]{0,.5f,1}){
   double a=Math.toRadians(tilt);float half=360*SceneStage.SCALE*zoom;
   double upperBottom=SceneStage.centerY(bob,tilt,zoom,alpha)+half*Math.cos(a)-half*Math.abs(Math.sin(a));
   if(upperBottom<760)throw new AssertionError("cropped bottom entered screen");checks++;
  }
  System.out.println("PASS: scene-first entrance and "+checks+" grounded motion bounds");
 }
}
