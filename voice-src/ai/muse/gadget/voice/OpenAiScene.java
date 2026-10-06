package ai.muse.gadget.voice;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** One fresh environment per conversation subject. The original Muse animation is rendered locally. */
public final class OpenAiScene {
    public interface Listener {
        void image(Bitmap image);
        void failed(String message);
    }
    private final Context context;
    private final Handler ui=new Handler();
    private volatile int generation;
    private HttpsURLConnection active;

    public OpenAiScene(Context context){this.context=context;}
    private boolean current(int request){return request==generation;}
    public synchronized void cancel(){
        ++generation;
        final HttpsURLConnection old=active;active=null;
        if(old!=null)new Thread(new Runnable(){public void run(){old.disconnect();}},"cancel-scene").start();
    }
    private synchronized boolean attach(HttpsURLConnection http,int request){
        if(!current(request))return false;
        active=http;return true;
    }
    private synchronized void detach(HttpsURLConnection http){if(active==http)active=null;}
    private String key()throws IOException{
        File file=new File(context.getFilesDir(),"openai-key");
        if(file.length()<20||file.length()>1024)throw new IOException("scene setup needed");
        InputStream in=new FileInputStream(file);
        try{return new String(read(in,1024),"UTF-8").trim();}finally{in.close();}
    }
    public void generate(final String message,final String previous,final Listener listener){
        cancel();final int request=generation;
        new Thread(new Runnable(){public void run(){
            try{
                String secret=key();
                String direction="Generate one tasteful tactile 3D miniature environment inspired by the meaning of the latest question and conversation. Soft natural lighting, warm muted colors, delightful and calm. This is a BACKGROUND ONLY for an existing animated cream furry companion composited by the app. Do not draw the companion, people, animals, mascots or faces. Invent a specific appropriate place and environmental props, not a generic room. Keep the central foreground quiet so the existing character is readable, but fill the entire square edge to edge with the world: no empty white space, borders, panels, fades, captions or UI. Camera at the companion's eye level, natural depth. Conversation: "+previous+"\nLatest question: "+message;
                JSONObject body=new JSONObject();body.put("model","gpt-image-2");body.put("prompt",direction);
                body.put("size","832x832");body.put("quality","low");body.put("output_format","jpeg");
                JSONObject imageResult=generateImage(secret,request,body);
                if(!current(request))return;
                byte[] imageBytes=Base64.decode(imageResult.getJSONArray("data").getJSONObject(0).getString("b64_json"),Base64.DEFAULT);
                if(imageBytes.length>12*1024*1024)throw new IOException("scene too large");
                final Bitmap image=BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.length);
                if(image==null)throw new IOException("unexpected scene format");
                if(image.getWidth()!=832||image.getHeight()!=832){image.recycle();throw new IOException("unexpected scene format");}
                ui.post(new Runnable(){public void run(){if(current(request))listener.image(image);else image.recycle();}});
            }catch(final Exception error){
                final String hint=error instanceof SceneHttpException?((SceneHttpException)error).hint:"scene unavailable — try another question";
                ui.post(new Runnable(){public void run(){if(current(request))listener.failed(hint);}});
            }
        }},"muse-scene").start();
    }
    private HttpsURLConnection open(String path,String secret,int request)throws IOException{
        HttpsURLConnection http=(HttpsURLConnection)new URL("https://api.openai.com/v1/"+path).openConnection();
        http.setInstanceFollowRedirects(false);http.setConnectTimeout(15000);http.setReadTimeout(180000);
        http.setRequestProperty("Authorization","Bearer "+secret);
        if(!attach(http,request)){http.disconnect();throw new IOException("cancelled");}
        return http;
    }
    private JSONObject generateImage(String secret,int request,JSONObject body)throws Exception{
        HttpsURLConnection http=open("images/generations",secret,request);
        try{
            http.setRequestMethod("POST");http.setDoOutput(true);http.setRequestProperty("Content-Type","application/json");
            byte[] payload=body.toString().getBytes("UTF-8");http.setFixedLengthStreamingMode(payload.length);
            OutputStream out=http.getOutputStream();try{out.write(payload);}finally{out.close();}
            return response(http);
        }finally{detach(http);http.disconnect();}
    }
    private JSONObject response(HttpsURLConnection http)throws Exception{
        verify(http);InputStream in=http.getInputStream();try{return new JSONObject(new String(read(in,24*1024*1024),"UTF-8"));}finally{in.close();}
    }
    private void verify(HttpsURLConnection http)throws IOException{
        int code=http.getResponseCode();
        if(code<200||code>=300)throw new SceneHttpException(code==401?"scene key needs updating":code==403?"scene generation needs account access":code==429?"scene generation is busy — try later":"scene unavailable ("+code+")");
    }
    private static class SceneHttpException extends IOException {final String hint;SceneHttpException(String hint){this.hint=hint;}}
    private static byte[] read(InputStream in,int maximum)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>maximum)throw new IOException("response too large");out.write(buffer,0,n);}return out.toByteArray();
    }
}
