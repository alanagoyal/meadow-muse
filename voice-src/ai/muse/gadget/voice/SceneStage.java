package ai.muse.gadget.voice;

/** Scene first, original Muse second; keep the source clip's cropped body offscreen. */
public final class SceneStage {
    public static final long ARRIVAL_MS=600;
    public static final float SCALE=.82f;
    public static float characterAlpha(long elapsed){
        float t=Math.max(0,Math.min(1,(elapsed-200)/400f));
        return 1-(1-t)*(1-t)*(1-t);
    }
    public static float centerY(float bob,float tilt,float zoom,float alpha){
        double angle=Math.toRadians(tilt);
        float half=360*SCALE*zoom;
        // Even the higher corner of the rotated bottom edge stays below the screen.
        return 780+bob-half*(float)Math.cos(angle)+half*(float)Math.abs(Math.sin(angle))+20*(1-alpha);
    }
}
