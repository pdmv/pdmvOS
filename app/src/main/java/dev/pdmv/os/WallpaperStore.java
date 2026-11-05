package dev.pdmv.os;
import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Decode and prepare a complete wallpaper cohort before publishing it on the UI thread. */
final class WallpaperStore {
    private static final String TAG="pdmvOS.Wallpaper";
    // One non-owning cohort shared across recreated Home windows. A cache hit
    // reuses image/frost textures; memory pressure remains free to collect them.
    private static CachedCohort recent;
    private static final class CachedCohort {
        final String key;
        final java.lang.ref.WeakReference<Bitmap> image;
        final java.util.ArrayList<java.lang.ref.WeakReference<Bitmap>> frost=new java.util.ArrayList<>(3);
        java.util.ArrayList<java.lang.ref.WeakReference<Bitmap>> frames;
        CachedCohort(String key,Cohort cohort){
            this.key=key;image=new java.lang.ref.WeakReference<>(cohort.ready);
            for(Bitmap bitmap:cohort.frost)frost.add(new java.lang.ref.WeakReference<>(bitmap));
            rememberFrames(cohort.frames);
        }
        void rememberFrames(Bitmap[] bitmaps){
            if(bitmaps==null)return;
            frames=new java.util.ArrayList<>(bitmaps.length);
            for(Bitmap bitmap:bitmaps)frames.add(new java.lang.ref.WeakReference<>(bitmap));
        }
        Cohort snapshot(String wanted){
            if(!key.equals(wanted))return null;
            Bitmap ready=image.get();if(ready==null||ready.isRecycled())return null;
            Cohort result=new Cohort(ready);
            for(int i=0;i<3;i++){
                Bitmap bitmap=frost.get(i).get();if(bitmap==null||bitmap.isRecycled())return null;
                result.frost[i]=bitmap;
            }
            if(frames!=null){
                Bitmap[] available=new Bitmap[frames.size()];boolean complete=true;
                for(int i=0;i<available.length;i++){available[i]=frames.get(i).get();if(available[i]==null||available[i].isRecycled()){complete=false;break;}}
                if(complete)result.frames=available;
            }
            return result;
        }
    }
    private String sourceKey(){
        File file=currentFile();return file.getAbsolutePath()+":"+file.exists()+":"+file.length()+":"+file.lastModified()+":"+targetWidth+":"+targetHeight;
    }
    static final long MAX_UPLOAD=25L*1024*1024;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=new ThreadPoolExecutor(
        0,1,15,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),
        task->new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"pdmvOS-wallpaper"));
    private volatile Bitmap image;
    private volatile Bitmap[] frost=new Bitmap[3];
    private volatile Bitmap[] transitions;
    private Bitmap transitionSource,transitionFrost;
    private volatile boolean closed,started;
    private volatile int generation;
    private int optionalEpoch;
    private boolean optionalTrimmed;
    private Runnable listener;
    private int targetWidth=1920,targetHeight=1080;
    WallpaperStore(Context c){
        context=c.getApplicationContext();
        try{
            android.view.Display display=((android.view.WindowManager)c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            android.view.Display.Mode mode=NativeSurfaceView.selectMode(display);
            targetWidth=Math.min(1920,mode.getPhysicalWidth());targetHeight=Math.min(1080,mode.getPhysicalHeight());
        }catch(RuntimeException unavailableDisplay){Log.w(TAG,"Using reference wallpaper dimensions",unavailableDisplay);}
    }
    File currentFile(){return new File(context.getFilesDir(),"wallpaper/current.image");}
    Bitmap full(){return image;}
    boolean optionalTransitionsReady(){return transitions!=null||pendingFrames!=null;}
    Bitmap frost(int depth){return frost[Math.max(0,Math.min(2,depth))];}
    private Bitmap[] pendingFrames;
    private Bitmap pendingSource,pendingFrost;
    Bitmap transition(float progress){
        // Adopt optional blur frames only at Home's exact endpoint, never swap
        // rendering recipes midway through an opening/closing transition.
        if(progress<=.0001f&&pendingFrames!=null&&pendingSource==image&&pendingFrost==frost[2]){
            transitions=pendingFrames;transitionSource=pendingSource;transitionFrost=pendingFrost;pendingFrames=null;
        }
        Bitmap[] frames=transitions;
        if(frames==null||transitionSource!=image||transitionFrost!=frost[2])return null;
        int index=Math.max(0,Math.min(16,Math.round(progress*16)));
        return index==0?image:frames[index-1];
    }
    void setListener(Runnable r){listener=r;}
    void close(){closed=true;++generation;listener=null;worker.shutdownNow();main.removeCallbacksAndMessages(null);}
    void trimOptionalMemory(){
        // Published textures may still be referenced by HWUI: release ownership,
        // never recycle a bitmap that RenderThread might still be sampling.
        ++optionalEpoch;
        optionalTrimmed=true;
        transitions=null;pendingFrames=null;pendingSource=null;pendingFrost=null;
        transitionSource=null;transitionFrost=null;optionalCohort=null;
        if(recent!=null&&recent.image.get()==image)recent.frames=null;
    }
    void restoreOptionalTransitions(){
        if(!optionalTrimmed||closed||image==null)return;
        android.app.ActivityManager manager=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager==null)return;
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
        try{manager.getMemoryInfo(memory);}
        catch(RuntimeException unavailable){Log.w(TAG,"Memory information unavailable",unavailable);return;}
        if(memory.lowMemory||memory.availMem-memory.threshold<40L*1024*1024)return;
        Cohort cohort=new Cohort(image);cohort.frost=frost;
        optionalCohort=cohort;optionalRequest=generation;optionalTrimmed=false;
        prepareOptionalTransitions();
    }
    synchronized void start(){
        if(started)return;started=true;int request=++generation;
        if(recent!=null&&context.getSharedPreferences("wallpaper_defaults",Context.MODE_PRIVATE).getBoolean("v3_initialized",false)){
            Cohort cached=recent.snapshot(sourceKey());
            if(cached!=null){
                worker.execute(this::cleanupStaleFiles);
                publish(cached);if(cached.frames==null){optionalCohort=cached;optionalRequest=request;}
                Log.i(TAG,"Reused prepared wallpaper cohort");return;
            }
        }
        worker.execute(()->{
            try{
                preservePreviousDefault();
                Bitmap ready=null;
                if(currentFile().isFile())try{ready=decode(()->new FileInputStream(currentFile()));}catch(IOException badSaved){Log.w(TAG,"Saved wallpaper unavailable",badSaved);}
                if(ready==null)ready=decode(()->context.getAssets().open("wallpapers/wallpaper.jpg"));
                Cohort cohort=prepareBase(ready,request);
                // The matching image + all material frost levels are ready now;
                // the 16 optional transition frames must not delay the first image.
                main.post(()->{if(!closed&&request==generation){publish(cohort);optionalCohort=cohort;optionalRequest=request;}});
            }catch(IOException|RuntimeException|OutOfMemoryError error){Log.w(TAG,"Wallpaper preparation failed",error);}
        });
    }
    /** Keep a chosen custom image; otherwise use the sole packaged default. */
    private void preservePreviousDefault()throws IOException{
        android.content.SharedPreferences state=context.getSharedPreferences("wallpaper_defaults",Context.MODE_PRIVATE);
        if(!state.getBoolean("v3_initialized",false)&&!state.edit().putBoolean("v3_initialized",true).commit())
            throw new IOException("Cannot save wallpaper state");
        cleanupStaleFiles();
    }
    private void cleanupStaleFiles(){
        long cutoff=System.currentTimeMillis()-24L*60*60*1000;
        File[] cached=context.getCacheDir().listFiles();
        if(cached!=null)for(File file:cached)if(file.isFile()&&file.getName().startsWith("wallpaper-")&&file.getName().endsWith(".pending")&&file.lastModified()<cutoff)file.delete();
        File folder=context.getExternalFilesDir("wallpaper-import");File[] imports=folder==null?null:folder.listFiles();
        if(imports!=null)for(File file:imports)if(file.isFile()&&file.getName().matches("[a-f0-9]{32}\\.(image|json)")&&file.lastModified()<cutoff)file.delete();
        File legacy=new File(currentFile().getParentFile(),"previous-default.tmp");if(legacy.isFile()&&legacy.lastModified()<cutoff)legacy.delete();
    }
    private Cohort optionalCohort;
    private int optionalRequest;
    void prepareOptionalTransitions(){
        Cohort cohort=optionalCohort;int request=optionalRequest,epoch=optionalEpoch;
        if(cohort==null||closed)return;optionalCohort=null;
        worker.execute(()->{
            try{
                Bitmap[] frames=prepareTransitions(cohort.ready,request);
                main.post(()->{
                    if(closed||request!=generation||epoch!=optionalEpoch||image!=cohort.ready){for(Bitmap frame:frames)frame.recycle();return;}
                    pendingFrames=frames;pendingSource=cohort.ready;pendingFrost=cohort.frost[2];
                    Log.i(TAG,"Optional transition frames ready");
                    if(recent!=null&&recent.image.get()==cohort.ready)recent.rememberFrames(frames);
                    if(listener!=null)listener.run();
                });
            }catch(IOException|RuntimeException|OutOfMemoryError error){Log.w(TAG,"Optional blur frames unavailable",error);}
        });
    }
    void replace(File uploaded,String expectedSha,Consumer<String> completed){
        int request=++generation;
        worker.execute(()->{
            File candidate=new File(context.getCacheDir(),"wallpaper-"+java.util.UUID.randomUUID()+".pending");
            try{
                check(request);
                if(!currentFile().getParentFile().isDirectory()&&!currentFile().getParentFile().mkdirs())throw new IOException("Không tạo được nơi lưu hình nền");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long length=0;
                try(InputStream input=new FileInputStream(uploaded);FileOutputStream output=new FileOutputStream(candidate)){
                    byte[] buffer=new byte[64*1024];int n;
                    while((n=input.read(buffer))!=-1){length+=n;if(length>MAX_UPLOAD)throw new IOException("Ảnh vượt quá 25 MiB");output.write(buffer,0,n);digest.update(buffer,0,n);check(request);}
                    output.getFD().sync();
                }
                StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
                if(length==0||!actual.toString().equals(expectedSha))throw new IOException("Ảnh truyền lên không toàn vẹn");
                Cohort cohort=prepare(decode(()->new FileInputStream(candidate)),request);
                compactCandidate(candidate,cohort.ready);
                main.post(()->{
                    try{
                        check(request);
                        Files.move(candidate.toPath(),currentFile().toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    }catch(IOException|RuntimeException error){cohort.recycle();candidate.delete();completed.accept(error.getMessage());return;}
                    publish(cohort);completed.accept(null);
                });
            }catch(Exception|OutOfMemoryError error){
                candidate.delete();Log.w(TAG,"Cannot import wallpaper",error);
                main.post(()->completed.accept(error instanceof OutOfMemoryError?"Không đủ bộ nhớ để xử lý ảnh":error.getMessage()));
            }finally{uploaded.delete();}
        });
    }
    private void compactCandidate(File candidate,Bitmap ready){
        File normalized=new File(context.getCacheDir(),"wallpaper-"+java.util.UUID.randomUUID()+".pending");
        try{
            try(FileOutputStream output=new FileOutputStream(normalized)){
                if(!ready.compress(Bitmap.CompressFormat.PNG,100,output))return;
                output.getFD().sync();
            }
            // Lossless screen-resolution pixels; keep a smaller original as-is.
            if(normalized.length()<candidate.length())Files.move(normalized.toPath(),candidate.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException|RuntimeException|OutOfMemoryError error){Log.w(TAG,"Keeping original wallpaper encoding",error);}
        finally{normalized.delete();}
    }
    void restoreDefault(Consumer<String> completed){
        int request=++generation;
        worker.execute(()->{
            try{
                check(request);Cohort cohort=prepare(decode(()->context.getAssets().open("wallpapers/wallpaper.jpg")),request);
                main.post(()->{
                    try{check(request);if(currentFile().exists()&&!currentFile().delete())throw new IOException("Không xóa được hình nền tùy chỉnh");}
                    catch(IOException|RuntimeException error){cohort.recycle();completed.accept(error.getMessage());return;}
                    publish(cohort);completed.accept(null);
                });
            }catch(IOException|RuntimeException|OutOfMemoryError error){main.post(()->completed.accept("Không khôi phục được nền mặc định"));}
        });
    }
    private void check(int request)throws IOException{
        if(closed||request!=generation||Thread.currentThread().isInterrupted())throw new IOException("Yêu cầu đã bị thay thế hoặc hủy");
    }
    private void publish(Cohort c){
        optionalCohort=null;optionalTrimmed=false;recent=new CachedCohort(sourceKey(),c);
        // No recycle of the old published cohort: RenderThread may still reference it.
        pendingFrames=null;pendingSource=null;pendingFrost=null;
        transitionSource=c.ready;transitionFrost=c.frost[2];transitions=c.frames;frost=c.frost;image=c.ready;
        if(listener!=null)listener.run();
    }
    private static final class Cohort{
        final Bitmap ready;Bitmap[] frost=new Bitmap[3],frames;
        Cohort(Bitmap ready){this.ready=ready;}
        void recycle(){if(ready!=null)ready.recycle();for(Bitmap b:frost)if(b!=null)b.recycle();if(frames!=null)for(Bitmap b:frames)if(b!=null)b.recycle();}
    }
    private Bitmap resized(Bitmap source,int width,int height){
        Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        new Canvas(output).drawBitmap(source,null,new RectF(0,0,width,height),new Paint(Paint.FILTER_BITMAP_FLAG));
        if(!source.hasAlpha())output.setHasAlpha(false);return output;
    }
    private Cohort prepareBase(Bitmap ready,int request)throws IOException{
        Cohort result=new Cohort(ready);Bitmap base=null;boolean success=false;
        try{
            check(request);base=resized(ready,480,270);
            for(int i=0;i<3;i++){check(request);result.frost[i]=blur(base,new int[]{1,5,6}[i]);}
            success=true;return result;
        }finally{if(base!=null)base.recycle();if(!success)result.recycle();}
    }
    private Cohort prepare(Bitmap ready,int request)throws IOException{
        Cohort result=prepareBase(ready,request);boolean success=false;
        try{result.frames=prepareTransitions(ready,request);success=true;return result;}
        finally{if(!success)result.recycle();}
    }
    private Bitmap[] prepareTransitions(Bitmap ready,int request)throws IOException{
        Bitmap[] frames=new Bitmap[16];Bitmap sharp=null,low=null,high=null;boolean success=false;
        try{
            check(request);sharp=resized(ready,640,360);low=sharp;high=blur(sharp,1);int level=0;
            Paint blendPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
            for(int i=0;i<16;i++){
                check(request);float radius=8f*(i+1)/16;int wanted=(int)radius;
                while(level<wanted){if(low!=sharp)low.recycle();low=high;++level;high=level<8?blur(sharp,level+1):null;}
                frames[i]=low.copy(Bitmap.Config.ARGB_8888,true);
                if(high!=null&&radius>wanted){blendPaint.setAlpha(Math.round(255*(radius-wanted)));new Canvas(frames[i]).drawBitmap(high,0,0,blendPaint);}
                // Queue texture preparation while idle, before the first drawer slide.
                frames[i].prepareToDraw();
            }
            success=true;return frames;
        }finally{
            if(low!=null&&low!=sharp)low.recycle();if(high!=null)high.recycle();if(sharp!=null)sharp.recycle();
            if(!success)for(Bitmap frame:frames)if(frame!=null)frame.recycle();
        }
    }
    /** Three separable box passes; full wallpaper work runs on the preparation thread,
     * while UI captures use only a small regional bitmap. */
    static Bitmap blur(Bitmap source,int radius){
        int w=source.getWidth(),h=source.getHeight(),d=radius*2+1;
        int[] a=new int[w*h],b=new int[w*h];source.getPixels(a,0,w,0,0,w,h);
        PixelBlur.apply(a,b,w,h,radius);
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(a,0,w,0,0,w,h);out.setHasAlpha(false);return out;
    }
    private interface Source{InputStream open()throws IOException;}
    private Bitmap decode(Source source)throws IOException{
        BitmapFactory.Options meta=new BitmapFactory.Options();meta.inJustDecodeBounds=true;
        try(InputStream input=source.open()){BitmapFactory.decodeStream(input,null,meta);}
        if(meta.outWidth<=0||meta.outHeight<=0)throw new IOException("Tệp không phải ảnh được Android hỗ trợ");
        int sample=1;
        while((long)(meta.outWidth/sample)*(meta.outHeight/sample)*4L>16L*1024*1024)sample*=2;
        while(meta.outWidth/sample>targetWidth*1.35f&&meta.outHeight/sample>targetHeight*1.35f)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap raw;try(InputStream input=source.open()){raw=BitmapFactory.decodeStream(input,null,options);}
        if(raw==null)throw new IOException("Không giải mã được ảnh");
        Bitmap output=null;
        try{
            int orientation=1;try(InputStream input=source.open()){orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException unsupportedExif){}
            Matrix transform=new Matrix();
            switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setScale(1,-1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;case 7:transform.setRotate(90);transform.postScale(1,-1);break;case 8:transform.setRotate(270);break;}
            boolean swapped=orientation>=5&&orientation<=8;
            float factor=Math.max((float)targetWidth/(swapped?raw.getHeight():raw.getWidth()),(float)targetHeight/(swapped?raw.getWidth():raw.getHeight()));
            output=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(output);c.drawColor(Color.BLACK);c.translate(targetWidth/2f,targetHeight/2f);c.scale(factor,factor);c.concat(transform);
            c.drawBitmap(raw,-raw.getWidth()/2f,-raw.getHeight()/2f,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));output.setHasAlpha(false);return output;
        }catch(RuntimeException|OutOfMemoryError error){if(output!=null)output.recycle();throw error;}finally{raw.recycle();}
    }
}
