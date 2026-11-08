package dev.pdmv.os.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Runs with the debug signing key; injects actual keys, never accepts an uninstall dialog. */
public final class TvAcceptance extends Instrumentation {
    private Activity activity;
    private Bundle options;
    private volatile float entryMin=2f,entryMax=-1f;
    private boolean entryObserverInstalled;
    private android.view.ViewTreeObserver.OnDrawListener returnObserver;
    private Window.OnFrameMetricsAvailableListener frameMetricsListener;
    private Handler frameMetricsHandler;
    private Window metricsWindow;
    private void attachMetricsWindow(Activity target){
        if(frameMetricsListener==null)return;
        if(metricsWindow!=null)metricsWindow.removeOnFrameMetricsAvailableListener(frameMetricsListener);
        metricsWindow=target.getWindow();metricsWindow.addOnFrameMetricsAvailableListener(frameMetricsListener,frameMetricsHandler);
    }
    @Override public void callActivityOnResume(Activity resumedActivity){
        super.callActivityOnResume(resumedActivity);
        resumedActivity.getWindow().getDecorView().post(()->attachMetricsWindow(resumedActivity));
        if(options!=null&&("return-motion".equals(options.getString("mode"))||"interaction-soak".equals(options.getString("mode")))){
            if(returnObserver!=null&&activity!=null)activity.getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(returnObserver);
            activity=resumedActivity;
            if(returnObserver!=null)activity.getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(returnObserver);
        }
        if(!entryObserverInstalled&&options!=null&&"startup-motion".equals(options.getString("mode"))&&resumedActivity.getClass().getName().equals("dev.pdmv.os.MainActivity")){
            entryObserverInstalled=true;
            resumedActivity.getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(()->{
                try{float v=(Float)field(field(field(resumedActivity,"view"),"startupReveal"),"value");entryMin=Math.min(entryMin,v);entryMax=Math.max(entryMax,v);}catch(Exception ignored){}
            });
        }
    }
    private final ArrayList<Long> frames=new ArrayList<>(),draws=new ArrayList<>();
    private volatile boolean recording;
    private File output;
    @Override public void onCreate(Bundle args){options=args;start();}
    private static Object field(Object target,String name)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static Object call(Object target,String name)throws Exception{
        Class<?> type=target.getClass();
        while(type!=null){try{Method m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}catch(NoSuchMethodException missing){type=type.getSuperclass();}}
        throw new NoSuchMethodException(name);
    }
    private Object view()throws Exception{return field(activity,"view");}
    private Object repo()throws Exception{return field(activity,"catalog");}
    private Object main(java.util.concurrent.Callable<Object> operation){
        Object[] result=new Object[1];Throwable[] failure=new Throwable[1];
        runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new RuntimeException(failure[0]);return result[0];
    }
    private Object state(String name){return main(()->field(view(),name));}
    @SuppressWarnings("unchecked") private List<Object> tiles(String name){return (List<Object>)main(()->new ArrayList<>((List<?>)field(repo(),name)));}
    private String id(Object tile){try{return (String)field(tile,"id");}catch(Exception e){throw new RuntimeException(e);}}
    private List<String> ids(String name){ArrayList<String> ids=new ArrayList<>();for(Object t:tiles(name))ids.add(id(t));return ids;}
    private void check(boolean pass,String description){if(!pass)throw new AssertionError(description);}
    private int injectedActions;
    private void injectKey(KeyEvent event){
        injectedActions++;
        if(!recording&&!(Boolean)main(()->activity.hasWindowFocus())){
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            long until=SystemClock.uptimeMillis()+3000;
            while(!(Boolean)main(()->activity.hasWindowFocus())&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
        }
        check((Boolean)main(()->activity.hasWindowFocus()),"launcher lost input focus before key "+event.getKeyCode()+" action "+event.getAction());
        KeyEvent systemKey=new KeyEvent(event.getDownTime(),event.getEventTime(),event.getAction(),event.getKeyCode(),event.getRepeatCount(),event.getMetaState(),KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD);
        check(getUiAutomation().injectInputEvent(systemKey,false),"input injection failed");
    }
    private void key(int key){
        long started=SystemClock.uptimeMillis();injectKey(new KeyEvent(started,started,KeyEvent.ACTION_DOWN,key,0));injectKey(new KeyEvent(started,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0));long finished=SystemClock.uptimeMillis();SystemClock.sleep(35);
        if("features".equals(options.getString("mode")))android.util.Log.i("TvAcceptance","key="+key+" inject_ms="+(finished-started)+" state="+main(()->{
            Object v=view();Object m=field(v,"menu");return "page="+field(v,"page")+" move="+field(v,"moveMode")+" dock="+field(v,"dockFocus")+" holding="+field(v,"holdingOK")+" menu="+(m==null?"none":field(m,"selected"));
        }));
    }
    private void waitMotion(){SystemClock.sleep(650);}
    private void home(){key(4);key(4);key(20);key(4);for(int i=0;i<6;i++)key(21);waitMotion();}
    private int screenshot(String name)throws Exception{
        Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"screenshot unavailable");
        try(FileOutputStream f=new FileOutputStream(new File(output,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}int surface=b.getPixel(20,20);b.recycle();return surface;
    }
    private JSONObject summary(){
        ArrayList<Long> a,d;
        synchronized(frames){a=new ArrayList<>(frames);d=new ArrayList<>(draws);}
        Collections.sort(a);Collections.sort(d);
        JSONObject result=new JSONObject();
        try{
            int slow=0;for(long t:a)if(t>16666667L)slow++;
            result.put("frames",a.size()).put("over_16_67ms",slow);
            if(!a.isEmpty())result.put("p50_ms",a.get(a.size()/2)/1e6).put("p95_ms",a.get(Math.min(a.size()-1,(int)(a.size()*.95)))/1e6)
                .put("p99_ms",a.get(Math.min(a.size()-1,(int)(a.size()*.99)))/1e6).put("draw_p95_ms",d.get(Math.min(d.size()-1,(int)(d.size()*.95)))/1e6);
            main(()->{
                Object v=view();long samples=((Number)field(v,"pacingSamples")).longValue();long misses=(Long)field(v,"pacingMisses");
                long[] intervals=(long[])field(v,"pacingIntervals");
                long[] sorted=Arrays.copyOf(intervals,(int)Math.min(samples,intervals.length));Arrays.sort(sorted);
                result.put("motion_vsync_samples",samples).put("motion_vsync_gaps_over_25ms",misses);
                if(sorted.length>0)result.put("motion_interval_p95_ms",sorted[Math.min(sorted.length-1,(int)(sorted.length*.95))]/1e6);
                return null;
            });
        }catch(Exception e){throw new RuntimeException(e);}return result;
    }
    private void resetSamples(){synchronized(frames){frames.clear();draws.clear();}main(()->call(view(),"resetPacing"));recording=true;}
    private void captureLatency(String name)throws Exception{
        ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("dumpsys SurfaceFlinger --latency dev.pdmv.os/dev.pdmv.os.MainActivity#0");
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);OutputStream out=new FileOutputStream(new File(output,name+"-presentation.txt"))){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
        }
    }
    private void morphEndpoint(JSONObject report)throws Exception{
        home();JSONArray cases=new JSONArray();
        for(String name:new String[]{"com.android.vending","com.google.android.play.games"}){
            Object tile=livePackage(name);check(tile!=null,"missing endpoint test app");selectTile(tile);SystemClock.sleep(900);
            JSONObject result=(JSONObject)main(()->{
                Object v=view();Method capture=v.getClass().getDeclaredMethod("captureMorphOrigin",tile.getClass());capture.setAccessible(true);capture.invoke(v,tile);
                View node=(View)((Map<?,?>)field(v,"tileNodes")).get(id(tile));
                android.graphics.RectF origin=new android.graphics.RectF((android.graphics.RectF)field(v,"morphOrigin"));
                float scale=(Float)field(v,"canvasScale"),left=(Float)field(v,"canvasLeft"),top=(Float)field(v,"canvasTop");
                int x=(int)Math.floor(left+origin.left*scale)-3,y=(int)Math.floor(top+origin.top*scale)-3;
                int w=(int)Math.ceil(origin.width()*scale)+6,h=(int)Math.ceil(origin.height()*scale)+6;
                Bitmap nativeImage=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888),morphImage=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                try{
                    android.graphics.Canvas c=new android.graphics.Canvas(nativeImage);c.translate(-x,-y);c.concat(node.getMatrix());node.draw(c);
                    Method draw=v.getClass().getDeclaredMethod("drawArtworkMorph",android.graphics.Canvas.class,tile.getClass(),android.graphics.RectF.class,float.class,boolean.class);draw.setAccessible(true);
                    float worst=0;
                    for(float progress:new float[]{0f}){
                        morphImage.eraseColor(0);c=new android.graphics.Canvas(morphImage);c.translate(-x,-y);c.translate(left,top);c.scale(scale,scale);draw.invoke(v,c,tile,origin,progress,false);
                        long total=0;int[] expected=new int[w*h],actual=new int[w*h];nativeImage.getPixels(expected,0,w,0,0,w,h);morphImage.getPixels(actual,0,w,0,0,w,h);
                        for(int i=0;i<actual.length;i++)for(int shift:new int[]{0,8,16,24})total+=Math.abs(((actual[i]>>>shift)&255)-((expected[i]>>>shift)&255));
                        float mean=(float)total/(actual.length*4);worst=Math.max(worst,mean);check(mean<1f,"endpoint differs from native tile at "+progress+": "+mean);
                    }
                    try(FileOutputStream f=new FileOutputStream(new File(output,"native-endpoint-"+name+".png"))){nativeImage.compress(Bitmap.CompressFormat.PNG,100,f);}
                    try(FileOutputStream f=new FileOutputStream(new File(output,"morph-endpoint-"+name+".png"))){morphImage.compress(Bitmap.CompressFormat.PNG,100,f);}
                    morphImage.eraseColor(0);c=new android.graphics.Canvas(morphImage);c.translate(-x,-y);c.translate(left,top);c.scale(scale,scale);draw.invoke(v,c,tile,origin,-.01f,false);
                    android.graphics.RectF rebound=(android.graphics.RectF)field(v,"morphBounds");
                    check(rebound.width()<origin.width()&&rebound.height()<origin.height(),"rendered rebound was clamped at equilibrium");
                    float dx=rebound.centerX()-origin.centerX(),dy=rebound.centerY()-origin.centerY();
                    check(dx*(origin.centerX()-960f)+dy*(origin.centerY()-540f)>0,"rebound did not carry past destination along approach");
                    return new JSONObject().put("app",name).put("mean_channel_error",worst).put("rest_pose_matches_tile",true).put("signed_rebound_rendered",true);
                }finally{nativeImage.recycle();morphImage.recycle();}
            });cases.put(result);
        }
        report.put("native_tile_endpoint_parity",cases);home();
    }
    private void restoredGlass(JSONObject report)throws Exception{
        home();SystemClock.sleep(1200);
        main(()->{
            Object glass=field(view(),"glassMaterials");
            try{glass.getClass().getDeclaredField("opticalSurfaces");throw new AssertionError("experimental refraction remains");}catch(NoSuchFieldException expected){}
            try{Class.forName("dev.pdmv.os.GlassOptics");throw new AssertionError("experimental optics class remains");}catch(ClassNotFoundException expected){}
            Class<?> geometry=Class.forName("dev.pdmv.os.UiGeometry");
            try{geometry.getField("GRID_PANEL_HEIGHT");throw new AssertionError("experimental list panel remains");}catch(NoSuchFieldException expected){}
            return null;
        });screenshot("restored-home");key(20);SystemClock.sleep(1200);
        main(()->{Object blur=field(view(),"listBlur");check((Float)field(blur,"target")==1f&&(Float)field(blur,"value")>.99f,"original app-list background blur not restored");return null;});
        screenshot("restored-list");key(4);SystemClock.sleep(1000);check((Integer)state("page")==0,"Home return failed");
        report.put("no_experimental_refraction",true).put("no_experimental_list_panel",true).put("original_list_blur_restored",true);
    }
    private void appListPanel(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        SystemClock.sleep(800);Bitmap home=getUiAutomation().takeScreenshot();check(home!=null,"Home screenshot unavailable");
        try{
            key(20);SystemClock.sleep(1600);Bitmap list=getUiAutomation().takeScreenshot();check(list!=null,"list screenshot unavailable");
            try{
                int differences=0;for(int y=200;y<1050;y+=50)for(int x:new int[]{20,40,1880,1900})if(home.getPixel(x,y)!=list.getPixel(x,y))differences++;
                check(differences==0,"wallpaper outside glass changed entering app list: "+differences);
                try(FileOutputStream out=new FileOutputStream(new File(output,"app-list-panel.png"))){list.compress(Bitmap.CompressFormat.PNG,100,out);}
                report.put("unchanged_outside_wallpaper_samples",68).put("outside_wallpaper_differences",differences);
            }finally{list.recycle();}
            main(()->{
                Object v=view(),blur=field(v,"listBlur"),glass=field(v,"glassMaterials"),store=field(activity,"wallpapers");
                check((Float)field(blur,"value")==0f&&(Float)field(blur,"target")==0f,"full-screen blur is still active");
                check((Float)field(glass,"sceneBlur")==0f,"glass still switches to app-list blur");
                check(field(store,"transitions")==null&&field(store,"pendingFrames")==null,"unused full-screen blur frames allocated");
                Class<?> geometry=Class.forName("dev.pdmv.os.UiGeometry");float pad=geometry.getField("DOCK_PADDING").getFloat(null),gridTop=geometry.getField("GRID_TOP").getFloat(null),panelHeight=geometry.getField("GRID_PANEL_HEIGHT").getFloat(null),panelTop=(Float)call(v,"gridPanelTop");
                check(Math.abs(gridTop-panelTop-pad)<.05f,"list padding does not match Dock");
                check(panelTop+panelHeight<1056f,"list panel exceeds bottom safe area");
                report.put("panel_top",panelTop).put("panel_height",panelHeight).put("padding",pad);return null;
            });
            key(20);key(20);SystemClock.sleep(700);screenshot("last-visible-row-focus");
            for(int i=0;i<5;i++){key(20);key(22);}SystemClock.sleep(900);screenshot("panel-scrolled");
            key(4);SystemClock.sleep(1100);screenshot("panel-return-home");
            check((Integer)state("page")==0,"Back did not close app list");check(state("renderFailure")==null,"panel render failed");
            report.put("list_scroll_and_home_return",true);
        }finally{home.recycle();}
    }
    private void liquidGlass(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        Object store=main(()->field(activity,"wallpapers"));
        Bitmap original=(Bitmap)main(()->field(store,"image"));Bitmap[] originalFrost=(Bitmap[])main(()->field(store,"frost"));
        Bitmap pattern=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas grid=new android.graphics.Canvas(pattern);android.graphics.Paint ink=new android.graphics.Paint();
        for(int y=0;y<1080;y+=20)for(int x=0;x<1920;x+=20){ink.setColor(((x/20+y/20)&1)==0?0xffe2eef6:0xff233e65);grid.drawRect(x,y,x+20,y+20,ink);}
        try{
            JSONObject optics=(JSONObject)main(()->{
                Object glass=field(view(),"glassMaterials");float savedBlur=(Float)field(glass,"sceneBlur");
                Method begin=glass.getClass().getDeclaredMethod("beginSurface",android.graphics.Canvas.class);begin.setAccessible(true);
                Method draw=glass.getClass().getDeclaredMethod("draw",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class,float.class,float.class);draw.setAccessible(true);
                Method refract=glass.getClass().getDeclaredMethod("drawRefraction",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class,float.class,float.class,float.class);refract.setAccessible(true);
                Method backdrop=glass.getClass().getDeclaredMethod("drawBackdrop",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class,float.class,float.class,float.class);backdrop.setAccessible(true);
                Method decoration=glass.getClass().getDeclaredMethod("drawDecorationOnly",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class);decoration.setAccessible(true);
                Bitmap before=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888),after=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);
                float[][] panels={{100,838,1720,190,83,0},{100,53,360,70,35,2},{1570,53,130,70,35,2},{1750,53,70,70,35,2}};
                try{
                    set(store,"frost",new Bitmap[]{pattern,pattern,pattern});set(glass,"sceneBlur",0f);
                    for(int pass=0;pass<2;pass++){
                        set(store,"image",pass==0?null:pattern);android.graphics.Canvas c=new android.graphics.Canvas(pass==0?before:after);c.scale(.5f,.5f);c.drawBitmap(pattern,0,0,null);begin.invoke(glass,c);
                        for(float[] b:panels){
                            if(pass==0)draw.invoke(glass,c,b[0],b[1],b[2],b[3],b[4],(int)b[5],0f,0f);
                            else{backdrop.invoke(glass,c,b[0],b[1],b[2],b[3],b[4],(int)b[5],0f,0f,1f);refract.invoke(glass,c,b[0],b[1],b[2],b[3],b[4],(int)b[5],0f,0f,1f);decoration.invoke(glass,c,b[0],b[1],b[2],b[3],b[4],(int)b[5]);}
                        }
                    }
                    int changed=0,outside=0,centre=0;
                    for(int y=0;y<540;y++)for(int x=0;x<960;x++)if(before.getPixel(x,y)!=after.getPixel(x,y)){
                        changed++;boolean inside=false;for(float[] b:panels)if(x*2>=b[0]-2&&x*2<=b[0]+b[2]+2&&y*2>=b[1]-2&&y*2<=b[1]+b[3]+2)inside=true;
                        if(!inside)outside++;if(x>460&&x<500&&y>457&&y<477)centre++;
                    }
                    check(changed>300,"refraction does not displace patterned background");check(outside==0,"refraction leaks outside glass");check(centre==0,"flat glass centre unexpectedly changed");
                    try(FileOutputStream f=new FileOutputStream(new File(output,"optics-before.png"))){before.compress(Bitmap.CompressFormat.PNG,100,f);}
                    try(FileOutputStream f=new FileOutputStream(new File(output,"optics-after.png"))){after.compress(Bitmap.CompressFormat.PNG,100,f);}
                    Object[] surfaces=(Object[])field(glass,"opticalSurfaces");Object last=surfaces[3];Object first=((Object[])field(field(last,"ready"),"strips"))[0];int rebuilds=(Integer)field(last,"rebuilds");
                    android.graphics.Canvas stable=new android.graphics.Canvas(after);stable.scale(.5f,.5f);begin.invoke(glass,stable);float[] panel=panels[3];refract.invoke(glass,stable,panel[0],panel[1],panel[2],panel[3],panel[4],2,0f,0f,1f);
                    check((Integer)field(last,"rebuilds")==rebuilds&&((Object[])field(field(last,"ready"),"strips"))[0]==first,"stable surface rebuilt optical texture");
                    return new JSONObject().put("changed_pixels",changed).put("outside_changed_pixels",outside).put("flat_centre_changed_pixels",centre).put("optical_surfaces",4).put("stable_texture_reused",true);
                }finally{set(store,"image",original);set(store,"frost",originalFrost);set(glass,"sceneBlur",savedBlur);before.recycle();after.recycle();}
            });report.put("optics",optics);
            main(()->{set(store,"image",pattern);set(store,"frost",new Bitmap[]{pattern,pattern,pattern});Object v=view();set(v,"headerStamp","");for(Object n:((Map<?,?>)field(v,"tileNodes")).values())((View)n).invalidate();call(v,"invalidateScene");return null;});
            SystemClock.sleep(650);screenshot("optics-hardware-pattern");
        }finally{
            main(()->{set(store,"image",original);set(store,"frost",originalFrost);Object v=view();set(v,"headerStamp","");for(Object n:((Map<?,?>)field(v,"tileNodes")).values())((View)n).invalidate();call(v,"invalidateScene");return null;});
            // Shader/display-list references may still sample the temporary source.
            // Drop ownership without recycling pixels recorded by RenderThread.
        }
        SystemClock.sleep(700);screenshot("liquid-home");key(20);SystemClock.sleep(1000);screenshot("liquid-drawer");key(4);SystemClock.sleep(800);
        check(state("renderFailure")==null,"liquid glass caused render failure");
        report.put("optical_resources",main(()->{
            Object glass=field(view(),"glassMaterials");Object lock=field(glass,"opticalPixelLock");long pixels=0,images=0;int surfaces=0;
            synchronized(lock){for(Object data:(Object[])field(glass,"opticalPixels")){int[] values=(int[])field(data,"pixels");if(values!=null)pixels+=values.length*4L;}}
            for(Object surface:(Object[])field(glass,"opticalSurfaces"))if(surface!=null&&field(surface,"ready")!=null){surfaces++;for(Object image:(Object[])field(field(surface,"ready"),"strips"))if(image!=null)images+=((Bitmap)image).getAllocationByteCount();}
            check(pixels+images<16*1024*1024,"optical buffers exceed quality budget");
            return new JSONObject().put("source_pixel_bytes",pixels).put("surface_bitmap_bytes",images).put("cached_surfaces",surfaces).put("worker_background_priority",true);
        }));
    }
    private void interactionCaches(JSONObject report)throws Exception{
        home();
        JSONObject shadow=(JSONObject)main(()->{
            Object glass=field(view(),"glassMaterials");Method draw=glass.getClass().getDeclaredMethod("drawPillShadow",android.graphics.Canvas.class,float.class,float.class,float.class,float.class);draw.setAccessible(true);
            float worst=0;
            for(int width:new int[]{330,500}){
                Bitmap expected=Bitmap.createBitmap(width+48,118,Bitmap.Config.ARGB_8888),actual=Bitmap.createBitmap(width+48,118,Bitmap.Config.ARGB_8888);
                try{
                    android.graphics.Canvas c=new android.graphics.Canvas(expected);android.graphics.Paint brush=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);brush.setColor(0x24000000);brush.setMaskFilter(new android.graphics.BlurMaskFilter(16f,android.graphics.BlurMaskFilter.Blur.NORMAL));
                    c.drawRoundRect(24,27,24+width,97,35,35,brush);brush.setMaskFilter(null);brush.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));c.drawRoundRect(24,24,24+width,94,35,35,brush);
                    draw.invoke(glass,new android.graphics.Canvas(actual),24f,24f,(float)width,70f);
                    long error=0;for(int y=0;y<118;y++)for(int x=0;x<width+48;x++)error+=Math.abs(android.graphics.Color.alpha(expected.getPixel(x,y))-android.graphics.Color.alpha(actual.getPixel(x,y)));
                    float mean=(float)error/(118*(width+48));worst=Math.max(worst,mean);check(mean<1f,"stretched shadow changed shape: "+mean);
                }finally{expected.recycle();actual.recycle();}
            }
            Method decoration=glass.getClass().getDeclaredMethod("decoration",float.class,float.class,float.class,int.class,boolean.class);decoration.setAccessible(true);
            Method render=glass.getClass().getDeclaredMethod("drawStaticDecoration",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class);render.setAccessible(true);
            float lightError=0;
            for(int width:new int[]{330,500}){
                Bitmap expected=(Bitmap)decoration.invoke(glass,(float)width,70f,35f,2,true),actual=Bitmap.createBitmap(width,70,Bitmap.Config.ARGB_8888);
                try{
                    render.invoke(glass,new android.graphics.Canvas(actual),0f,0f,(float)width,70f,35f,2);
                    long error=0;for(int y=0;y<70;y++)for(int x=0;x<width;x++)for(int shift:new int[]{0,8,16,24})error+=Math.abs(((expected.getPixel(x,y)>>>shift)&255)-((actual.getPixel(x,y)>>>shift)&255));
                    float mean=(float)error/(width*70*4);lightError=Math.max(lightError,mean);check(mean<1f,"pill lighting shape changed: "+mean);
                }finally{actual.recycle();}
            }
            Bitmap target=Bitmap.createBitmap(700,118,Bitmap.Config.ARGB_8888);
            try{android.graphics.Canvas c=new android.graphics.Canvas(target);for(int width=210;width<610;width++)draw.invoke(glass,c,24f,24f,(float)width,70f);}finally{target.recycle();}
            Map<?,?> cached=(Map<?,?>)field(glass,"pillShadows");long bytes=0;for(Object value:cached.values())bytes+=((Bitmap)value).getAllocationByteCount();
            check(cached.size()<=3,"shadow widths still grow the cache");
            return new JSONObject().put("mean_alpha_error_max",worst).put("lighting_mean_channel_error_max",lightError).put("widths_exercised",400).put("entries",cached.size()).put("bytes",bytes);
        });
        key(82);SystemClock.sleep(650);key(20);SystemClock.sleep(150);
        main(()->{
            Object v=view(),menu=field(v,"menu"),spring=field(v,"menuReveal"),selection=field(v,"menuSelection");int selected=(Integer)field(menu,"selected");check(selected>0,"menu selection did not move");
            Bitmap surface=(Bitmap)field(menu,"surface");call(v,"closeMenu");float progress=(Float)field(spring,"value"),velocity=(Float)field(spring,"velocity"),row=(Float)field(selection,"value"),rowVelocity=(Float)field(selection,"velocity");
            call(v,"openTileMenu");Object reopened=field(v,"menu");check(field(reopened,"surface")==surface,"menu reversal rebuilt surface");check((Integer)field(reopened,"selected")==selected,"menu reversal reset selected row");
            check((Float)field(spring,"value")==progress&&(Float)field(spring,"velocity")==velocity,"menu reversal lost momentum");check((Float)field(selection,"value")==row&&(Float)field(selection,"velocity")==rowVelocity,"menu reversal reset row motion");
            android.util.LruCache cache=(android.util.LruCache)field(v,"menuTextures");
            for(int i=0;i<80;i++)cache.put("qa-budget-"+i,Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888));
            check(cache.size()<=cache.maxSize()&&cache.maxSize()<=4*1024*1024,"menu cache exceeded byte budget");check(!surface.isRecycled(),"cache eviction recycled live menu");cache.evictAll();return null;
        });SystemClock.sleep(700);screenshot("menu-after-eviction");key(4);home();
        report.put("shadow",shadow).put("menu_reversal_preserves_surface_selection_momentum",true).put("cache_budget_and_live_texture_safety",true);
    }
    private JSONObject interactionResources()throws Exception{
        return (JSONObject)main(()->{
            Object v=view(),cache=field(v,"menuTextures"),glass=field(v,"glassMaterials");
            Map<?,?> entries=cache instanceof Map?(Map<?,?>)cache:((android.util.LruCache<?,?>)cache).snapshot();
            long menu=0,shadow=0;for(Object b:entries.values())menu+=((Bitmap)b).getAllocationByteCount();
            Map<?,?> shadows=(Map<?,?>)field(glass,"pillShadows");for(Object b:shadows.values())shadow+=((Bitmap)b).getAllocationByteCount();
            return new JSONObject().put("menu_cache_bytes",menu).put("menu_entries",entries.size()).put("shadow_cache_bytes",shadow).put("shadow_entries",shadows.size()).put("decoration_cache_bytes",(Long)field(glass,"decorationBytes")).put("decoration_entries",((Map<?,?>)field(glass,"decorations")).size()).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize());
        });
    }
    private void recordBusy(JSONArray result,String name,Object spring)throws Exception{
        if(spring!=null&&(Boolean)call(spring,"busy")){
            JSONObject entry=new JSONObject().put("name",name).put("value",field(spring,"value")).put("velocity",field(spring,"velocity"));
            if(spring.getClass().getSimpleName().equals("SpringValue"))entry.put("target",field(spring,"target"));
            result.put(entry);
        }
    }
    private JSONObject settlingState()throws Exception{
        return (JSONObject)main(()->{
            Object v=view();JSONArray busy=new JSONArray();
            for(Field field:v.getClass().getDeclaredFields()){
                String type=field.getType().getName();
                if(type.equals("dev.pdmv.os.SpringValue")||type.equals("dev.pdmv.os.ElasticBoundary$Axis")){
                    field.setAccessible(true);recordBusy(busy,field.getName(),field.get(v));
                }
            }
            for(Object tile:(List<?>)field(repo(),"all"))for(Field field:tile.getClass().getDeclaredFields())if(field.getType().getName().equals("dev.pdmv.os.SpringValue")){
                field.setAccessible(true);recordBusy(busy,id(tile)+"."+field.getName(),field.get(tile));
            }
            Object[] header=(Object[])field(v,"headerButtonFocus");for(int i=0;i<header.length;i++)recordBusy(busy,"header-"+i,header[i]);
            Object[] rows=(Object[])field(field(v,"drawerFlow"),"rows");for(int i=0;i<rows.length;i++)recordBusy(busy,"drawer-row-"+i,rows[i]);
            Object depth=field(v,"focusDepth");recordBusy(busy,"depth-horizontal",field(depth,"horizontal"));recordBusy(busy,"depth-vertical",field(depth,"vertical"));
            return new JSONObject().put("active",field(v,"motionWasActive")).put("resumed",field(activity,"resumed")).put("window_focus",activity.hasWindowFocus()).put("busy",busy);
        });
    }
    private void rapidSequence(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        // Warm the menu and header before counting retained cache growth.
        key(82);SystemClock.sleep(300);key(4);SystemClock.sleep(500);
        JSONObject before=interactionResources();JSONArray rounds=new JSONArray();resetSamples();int firstAction=injectedActions;
        int count=Integer.parseInt(options.getString("cycles","18"));
        for(int cycle=0;cycle<count;cycle++){
            key(22);key(22);key(21);key(20);key(22);key(20);key(21);key(19);key(4);
            key(82);key(20);key(19);key(4);key(82);key(4);
            if(cycle%3==0){
                long started=SystemClock.uptimeMillis();injectKey(new KeyEvent(started,started,KeyEvent.ACTION_DOWN,23,0));SystemClock.sleep(570);
                check(state("menu")!=null,"held OK failed to open menu during rapid interactions");
                injectKey(new KeyEvent(started,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));key(4);
            }
            if(cycle%3==1){key(19);key(22);key(21);key(20);}
            if((cycle+1)%6==0)rounds.put(interactionResources().put("cycle",cycle+1));
            check(!(Boolean)state("holdingOK"),"held button stuck during rapid interactions");
            check(state("renderFailure")==null,"render failed during rapid interactions");
            check((Boolean)main(()->field(activity,"resumed")),"rapid navigation unexpectedly launched an application");
        }
        recording=false;report.put("motion",summary()).put("injected_key_events",injectedActions-firstAction);key(4);key(4);home();SystemClock.sleep(1800);
        report.put("settling_state",settlingState());
        check(state("menu")==null&&state("closingMenu")==null,"closed menu retained after settling");
        check(!(Boolean)state("holdingOK")&&!(Boolean)state("moveMode"),"input or arrange mode remained stuck");
        if((Boolean)state("motionWasActive")){
            long waiting=SystemClock.uptimeMillis();
            await(()->!(Boolean)state("motionWasActive"),"animation kept running after controls settled");
            report.put("extra_settle_wait_ms",SystemClock.uptimeMillis()-waiting).put("settling_state_after_wait",settlingState());
        }
        check(!(Boolean)state("motionWasActive"),"animation kept running after controls settled");
        report.put("cycles",count).put("before",before).put("rounds",rounds).put("after",interactionResources()).put("settled_without_stuck_input",true);
        screenshot("rapid-sequence-settled");
    }
    private void resourcePressure(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        main(()->{
            Map<String,Bitmap> images=new HashMap<>();
            for(Object tile:(List<?>)field(repo(),"all")){Bitmap image=(Bitmap)field(tile,"image");if(image!=null){images.put(id(tile),image);check(field(tile,"artwork")==null,"prepared tile still retains source drawable");}}
            call(repo(),"refresh");
            for(Object tile:(List<?>)field(repo(),"all"))if(images.containsKey(id(tile))){check(field(tile,"image")==images.get(id(tile)),"catalog refresh lost prepared pixels");check(field(tile,"artwork")==null,"unchanged package reloaded source artwork");check((Boolean)field(tile,"preparationAttempted"),"unchanged package requested reprocessing");}
            Object store=field(activity,"wallpapers");Bitmap wallpaper=(Bitmap)field(store,"image");
            activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            check(field(store,"transitions")==null&&field(store,"pendingFrames")==null,"optional wallpaper frames survived memory pressure");
            Object glass=field(view(),"glassMaterials");check(((Map<?,?>)field(glass,"decorations")).isEmpty()&&(Long)field(glass,"decorationBytes")==0L,"decoration cache survived memory pressure");
            check(field(store,"image")==wallpaper&&!wallpaper.isRecycled(),"trim invalidated wallpaper still owned by renderer");
            Bitmap[] frost=(Bitmap[])field(store,"frost");for(Bitmap bitmap:frost)check(bitmap!=null&&!bitmap.isRecycled(),"trim invalidated required glass texture");
            return null;
        });
        key(20);SystemClock.sleep(1000);screenshot("memory-pressure-drawer");key(4);SystemClock.sleep(800);
        check(state("renderFailure")==null,"render failure after cache trim");
        report.put("prepared_source_release","PASS").put("catalog_reuses_prepared_images","PASS").put("memory_pressure_safe_fallback","PASS");
        if("true".equals(options.getString("expect-recovery"))){
            ActivityManager manager=(ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);
            if(!memory.lowMemory&&memory.availMem-memory.threshold>=40L*1024*1024){
                await(()->(Boolean)main(()->call(field(activity,"wallpapers"),"optionalTransitionsReady")),"optional transitions did not recover while Home was idle");
                report.put("idle_transition_cache_recovery","PASS");
            }else report.put("idle_transition_cache_recovery","deferred while RAM is low");
        }
    }
    private void launchCancellation(JSONObject report)throws Exception{
        home();Object tile=livePackage("com.android.vending");check(tile!=null,"launch cancellation app unavailable");
        JSONArray cases=new JSONArray();
        for(boolean homeKey:new boolean[]{false,true})for(int delay:new int[]{20,60}){
            selectTile(tile);waitMotion();key(23);
            await(()->state("launchingTile")!=null,"launch surface was not created");SystemClock.sleep(delay);
            check((Boolean)main(()->field(activity,"resumed"))&&state("pendingLaunch")!=null,"cancellation arrived after app handoff");
            if(homeKey)check(getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"Home injection failed");
            else key(4);
            await(()->(Boolean)main(()->field(activity,"resumed"))&&state("launchingTile")==null,"cancelled launch did not settle on Home");
            SystemClock.sleep(400);
            check((Boolean)main(()->field(activity,"resumed"))&&state("pendingLaunch")==null,"cancelled launch opened its app later");
            cases.put(new JSONObject().put("cancel",homeKey?"Home":"Back").put("delay_ms",delay).put("settled_on_home",true));
        }
        report.put("launch_cancellation",cases);home();
    }
    private void recordedBackdrop(JSONObject report)throws Exception{
        home();main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});SystemClock.sleep(1000);
        Object[] pair=(Object[])main(()->{
            Object v=view();android.graphics.RectF area=new android.graphics.RectF((android.graphics.RectF)call(v,"toastCaptureBounds"));
            Method raster=v.getClass().getDeclaredMethod("sampleGlassRegion",android.graphics.RectF.class,int.class,int.class),record=v.getClass().getDeclaredMethod("recordGlassRegion",android.graphics.RectF.class,int.class,int.class);raster.setAccessible(true);record.setAccessible(true);
            return new Object[]{raster.invoke(v,area,12,1),record.invoke(v,area,12,1)};
        });
        Bitmap expected=(Bitmap)pair[0];android.graphics.Picture picture=(android.graphics.Picture)pair[1];Bitmap actual=Bitmap.createBitmap(picture.getWidth(),picture.getHeight(),Bitmap.Config.ARGB_8888);
        try{
            new android.graphics.Canvas(actual).drawPicture(picture);long total=0;
            for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++)for(int shift:new int[]{0,8,16,24})total+=Math.abs(((actual.getPixel(x,y)>>>shift)&255)-((expected.getPixel(x,y)>>>shift)&255));
            double mean=total/(actual.getWidth()*(double)actual.getHeight()*4);check(mean<.1,"off-thread replay changed pixels: "+mean);report.put("mean_channel_error",mean).put("replay_off_ui",true);
        }finally{expected.recycle();actual.recycle();}
        key(4);key(4);SystemClock.sleep(1200);
    }
    private long directoryBytes(File file){
        if(file==null||!file.exists())return 0;if(file.isFile())return file.length();long size=0;File[] children=file.listFiles();if(children!=null)for(File child:children)size+=directoryBytes(child);return size;
    }
    private void storageAudit(JSONObject report)throws Exception{
        android.content.Context c=getTargetContext();Object store=main(()->field(activity,"wallpapers"));File current=(File)main(()->call(store,"currentFile"));
        report.put("files_bytes",directoryBytes(c.getFilesDir())).put("cache_bytes",directoryBytes(c.getCacheDir())).put("code_cache_bytes",directoryBytes(c.getCodeCacheDir())).put("external_files_bytes",directoryBytes(c.getExternalFilesDir(null))).put("qa_bytes",directoryBytes(output)).put("custom_wallpaper_bytes",current.length());
        wallpaperState(report);
        long deadline=SystemClock.uptimeMillis()+15000;while(main(()->call(store,"full"))==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        home();SystemClock.sleep(1000);screenshot("new-default-home");
    }
    private void storageSafety(JSONObject report)throws Exception{
        android.content.Context c=getTargetContext();Object store=main(()->field(activity,"wallpapers"));
        File old=new File(c.getCacheDir(),"wallpaper-test-stale.pending"),recent=new File(c.getCacheDir(),"wallpaper-test-recent.pending"),other=new File(c.getCacheDir(),"stability-keep.bin");
        File importDir=c.getExternalFilesDir("wallpaper-import");importDir.mkdirs();File orphan=new File(importDir,"ffffffffffffffffffffffffffffffff.image");
        File candidate=new File(c.getCacheDir(),"wallpaper-test-normalize.pending");Bitmap image=Bitmap.createBitmap(96,54,Bitmap.Config.ARGB_8888);
        try{
            for(File f:new File[]{old,recent,other,orphan})try(FileOutputStream out=new FileOutputStream(f)){out.write(1);}
            old.setLastModified(System.currentTimeMillis()-172800000L);orphan.setLastModified(System.currentTimeMillis()-172800000L);
            main(()->call(store,"cleanupStaleFiles"));check(!old.exists()&&!orphan.exists(),"stale wallpaper files retained");check(recent.exists()&&other.exists(),"cleanup removed active/unrelated file");
            for(int y=0;y<54;y++)for(int x=0;x<96;x++)image.setPixel(x,y,0xff000000|((x*2)<<16)|((y*4)<<8)|(x+y));
            try(FileOutputStream out=new FileOutputStream(candidate)){image.compress(Bitmap.CompressFormat.PNG,100,out);out.write(new byte[256*1024]);}
            long before=candidate.length();Method compact=store.getClass().getDeclaredMethod("compactCandidate",File.class,Bitmap.class);compact.setAccessible(true);compact.invoke(store,candidate,image);
            Bitmap decoded=android.graphics.BitmapFactory.decodeFile(candidate.getAbsolutePath());check(decoded!=null&&candidate.length()<before,"wallpaper was not compacted");
            try{for(int y=0;y<54;y++)for(int x=0;x<96;x++)check(decoded.getPixel(x,y)==image.getPixel(x,y),"lossless compaction changed pixels");}finally{decoded.recycle();}
            report.put("normalized_before_bytes",before).put("normalized_after_bytes",candidate.length()).put("lossless_pixels",true).put("stale_cleanup_preserves_active_files",true);
        }finally{image.recycle();for(File f:new File[]{old,recent,other,orphan,candidate})f.delete();}
    }
    private void stabilityUi(JSONObject report)throws Exception{
        home();main(()->{Object r=repo();Method unpin=r.getClass().getDeclaredMethod("unpin",int.class);unpin.setAccessible(true);unpin.invoke(r,5);set(view(),"dockFocus",4);call(view(),"updateFocus");return null;});SystemClock.sleep(600);
        Object sound=main(()->field(activity,"uiSounds"));long previous=(Long)main(()->field(sound,"lastPlayed"));key(22);SystemClock.sleep(180);
        Object focused=callViaMain("focusSoundTarget");check(focused!=null&&focused.toString().startsWith("empty:"),"empty slot missing sound target");
        boolean enabled=(Boolean)main(()->field(sound,"enabled"));if(enabled)check((Long)main(()->field(sound,"lastPlayed"))>previous,"empty focus did not play sound");
        report.put("empty_slot_sound_target",focused).put("sound_enabled",enabled).put("sound_played",enabled);
        main(()->{set(view(),"focusHeader",true);set(view(),"headerSelection",2);call(view(),"updateFocus");call(view(),"openInputMenu");return null;});SystemClock.sleep(900);
        Object menu=state("menu");check(menu!=null,"input menu unavailable");
        report.put("input_menu",main(()->{Object v=view();Method rect=v.getClass().getDeclaredMethod("menuRect",menu.getClass());rect.setAccessible(true);android.graphics.RectF area=(android.graphics.RectF)rect.invoke(v,menu);check(area.top>=123&&area.left>=32&&area.right<=1888&&area.bottom<=1080,"input menu outside pill bounds");return new JSONObject().put("left",area.left).put("top",area.top).put("right",area.right).put("bottom",area.bottom);}));
        screenshot("input-under-pill");key(4);home();
    }
    private Object callViaMain(String name){return main(()->call(view(),name));}
    private void pinSequence(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        Set<String> before=new HashSet<>(ids("grid"));for(String id:ids("dock"))if(!id.startsWith("empty:"))before.add(id);
        resetSamples();
        for(int i=0;i<6;i++){
            boolean empty="true".equals(options.getString("empty-slot"));int destination=empty?5:0;
            main(()->{if(empty){Object r=repo();Method unpin=r.getClass().getDeclaredMethod("unpin",int.class);unpin.setAccessible(true);unpin.invoke(r,5);}set(view(),"dockFocus",destination);call(view(),"choosePinSlot");return null;});SystemClock.sleep(100);
            check((Integer)state("replaceSlot")==destination,"pin chooser lost destination");key(22);key(21);key(23);SystemClock.sleep(160);
            key(4);key(20);SystemClock.sleep(90);key(4);SystemClock.sleep(700);
            Set<String> current=new HashSet<>();List<String> dock=ids("dock"),grid=ids("grid");check(dock.size()==6,"dock size changed");
            for(String id:dock)if(!id.startsWith("empty:"))check(current.add(id),"duplicate dock app");for(String id:grid)check(current.add(id),"duplicate grid app");
            check(current.equals(before),"pinning lost apps");check(state("renderFailure")==null,"pinning render failed");
        }
        recording=false;report.put("motion",summary()).put("cycles",6).put("app_set_preserved",true);
        home();SystemClock.sleep(1800);check(!(Boolean)state("holdingOK")&&!(Boolean)state("motionWasActive"),"pin chain did not settle");screenshot("pin-sequence-home");
    }
    private void toastWorker(JSONObject report)throws Exception{
        home();main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});SystemClock.sleep(1200);
        main(()->{
            Object v=view();Class<?> surface=v.getClass().getSuperclass();Method active=surface.getDeclaredMethod("setRenderingActive",boolean.class);active.setAccessible(true);active.invoke(v,false);
            set(v,"toastSceneKey",123L);call(v,"refreshToastBackdrop");
            check((Boolean)field(v,"toastWorkPending"),"blur work was not scheduled");
            Object worker=field(v,"toastWorker");for(int i=0;i<20;i++)call(v,"refreshToastBackdrop");
            check(worker==field(v,"toastWorker"),"created more than one blur executor");
            activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);return null;
        });SystemClock.sleep(400);
        main(()->{
            Object v=view();check(!(Boolean)field(v,"toastWorkPending"),"blur worker did not finish");
            check(field(field(v,"glassMaterials"),"toastBackdrop")==null,"old blur republished after memory trim");
            Class<?> surface=v.getClass().getSuperclass();Method active=surface.getDeclaredMethod("setRenderingActive",boolean.class);active.setAccessible(true);active.invoke(v,true);call(v,"stopMove");return null;
        });
        key(4);key(4);SystemClock.sleep(1000);
        check(state("renderFailure")==null,"renderer failed after cancelled blur work");
        report.put("one_pending_blur",true).put("stale_result_rejected_after_trim",true).put("renderer_recovers",true);
    }
    private void toastRegion(JSONObject report)throws Exception{
        home();main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});SystemClock.sleep(1400);
        report.put("comparison",main(()->{
            Object v=view();Method capture=v.getClass().getDeclaredMethod("captureGlassRegion",android.graphics.RectF.class,int.class,int.class);capture.setAccessible(true);
            android.graphics.RectF area=new android.graphics.RectF((android.graphics.RectF)call(v,"toastCaptureBounds"));
            Bitmap full=(Bitmap)capture.invoke(v,new android.graphics.RectF(0,864,1920,1080),12,1),crop=(Bitmap)capture.invoke(v,area,12,1);
            try{
                long error=0;int samples=0,left=Math.round(area.left/4f);
                for(int y=0;y<crop.getHeight();y++)for(int x=40;x<crop.getWidth()-40;x++){
                    int a=full.getPixel(x+left,y),b=crop.getPixel(x,y);
                    for(int shift:new int[]{0,8,16,24}){error+=Math.abs(((a>>>shift)&255)-((b>>>shift)&255));samples++;}
                }
                double mean=(double)error/samples;check(mean<.1,"cropped blur changed visible material: "+mean);
                return new JSONObject().put("mean_channel_error",mean).put("old_pixels",full.getWidth()*full.getHeight()).put("new_pixels",crop.getWidth()*crop.getHeight()).put("context_world_px",160);
            }finally{full.recycle();crop.recycle();}
        }));
        key(4);key(4);SystemClock.sleep(1000);
    }
    private void arrangeEntry(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        SystemClock.sleep(1500);JSONArray entries=new JSONArray();
        for(int i=0;i<4;i++){
            resetSamples();main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});
            SystemClock.sleep(1200);recording=false;entries.put(summary());
            check((Boolean)state("moveMode"),"arrange entry failed");
            check(state("renderFailure")==null,"render failure during arrange entry");
            key(4);key(4);SystemClock.sleep(1000);
        }
        main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});SystemClock.sleep(1000);
        resetSamples();SystemClock.sleep(1700);recording=false;report.put("steady_ring",summary()).put("entries",entries);
        screenshot("arrange-entry-settled");key(4);key(4);
        long settled=SystemClock.uptimeMillis()+4000;
        while(((Boolean)state("moveMode")||(Boolean)state("motionWasActive"))&&SystemClock.uptimeMillis()<settled)SystemClock.sleep(50);
        check(!(Boolean)state("moveMode")&&!(Boolean)state("motionWasActive"),"arrange mode did not stop after toast and springs settled");
        report.put("exit_settles",true);
    }
    private void staticScene(JSONObject report)throws Exception{
        home();long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        SystemClock.sleep(1400);
        deadline=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<deadline && !(Boolean)main(()->{Object store=field(activity,"wallpapers");return field(store,"transitions")!=null||field(store,"pendingFrames")!=null;}))SystemClock.sleep(100);
        SystemClock.sleep(500);
        Object[] nodes=(Object[])main(()->{
            Class<?> surface=view().getClass().getSuperclass();Field sf=surface.getDeclaredField("scene"),bf=surface.getDeclaredField("backdrop");sf.setAccessible(true);bf.setAccessible(true);
            android.view.View scene=(android.view.View)sf.get(view());android.view.ViewGroup backdrop=(android.view.ViewGroup)bf.get(view());
            check(scene.getLayerType()==android.view.View.LAYER_TYPE_HARDWARE,"static scene not cached");
            check(backdrop.getLayerType()==android.view.View.LAYER_TYPE_NONE,"tile container still has full-screen cache");
            Map<?,?> tileNodes=(Map<?,?>)field(view(),"tileNodes");Object tile=((List<?>)field(repo(),"dock")).get(0);android.view.View node=(android.view.View)tileNodes.get(id(tile));
            return new Object[]{scene,backdrop,node,node.getParent(),field(view(),"sceneDrawCount")};
        });
        key(22);SystemClock.sleep(180);key(21);SystemClock.sleep(800);
        main(()->{
            check(((android.view.View)nodes[2]).getParent()==nodes[3],"focus reparented tile");
            check(((Long)field(view(),"sceneDrawCount")).longValue()==((Long)nodes[4]).longValue(),"focus redrew static scene: before="+nodes[4]+" after="+field(view(),"sceneDrawCount"));
            check(((android.view.View)nodes[0]).getLayerType()==android.view.View.LAYER_TYPE_HARDWARE,"focus discarded scene texture");return null;
        });
        key(20);SystemClock.sleep(1000);screenshot("static-scene-list");key(4);SystemClock.sleep(1000);screenshot("static-scene-home");
        check(state("renderFailure")==null,"render failed with static scene cache");
        report.put("focus_keeps_parent",true).put("focus_reuses_scene_texture",true).put("focus_does_not_redraw_wallpaper_glass",true);
    }
    private void launcherPerformance(JSONObject report)throws Exception{
        home();
        long deadline=SystemClock.uptimeMillis()+30000;
        while((Boolean)state("artworkWarmScheduled")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        check(!(Boolean)state("artworkWarmScheduled"),"artwork warmup incomplete");
        for(int i=0;i<3;i++){key(22);SystemClock.sleep(130);key(21);SystemClock.sleep(130);}
        resetSamples();
        for(int i=0;i<20;i++){key(22);SystemClock.sleep(130);key(21);SystemClock.sleep(130);}
        SystemClock.sleep(600);recording=false;report.put("focus",summary());
        home();resetSamples();
        for(int i=0;i<4;i++){
            main(()->{Method m=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);m.setAccessible(true);m.invoke(view(),false);return null;});SystemClock.sleep(850);
            main(()->{call(view(),"exitDrawer");return null;});SystemClock.sleep(850);
        }
        recording=false;report.put("drawer",summary());
        report.put("resources",main(()->{
            JSONObject result=new JSONObject();Object store=field(activity,"wallpapers");
            long bytes=0;Set<Bitmap> unique=Collections.newSetFromMap(new IdentityHashMap<>());
            Bitmap image=(Bitmap)field(store,"image");if(image!=null)unique.add(image);
            for(String name:new String[]{"frost","transitions","pendingFrames"}){Bitmap[] bitmaps=(Bitmap[])field(store,name);if(bitmaps!=null)for(Bitmap b:bitmaps)if(b!=null)unique.add(b);}
            for(Bitmap b:unique)bytes+=b.getAllocationByteCount();
            int sourceDrawables=0;long tiles=0;
            for(Object t:(List<?>)field(repo(),"all")){if(field(t,"artwork")!=null)sourceDrawables++;Bitmap b=(Bitmap)field(t,"image");if(b!=null)tiles+=b.getAllocationByteCount();}
            return result.put("wallpaper_bitmap_bytes",bytes).put("prepared_tile_bytes",tiles).put("retained_source_drawables",sourceDrawables);
        }));home();
    }
    private void profile(JSONObject report)throws Exception{
        home();
        long deadline=SystemClock.uptimeMillis()+20000;
        while((Boolean)state("artworkWarmScheduled") && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(150);
        check(!(Boolean)state("artworkWarmScheduled"),"artwork warmup did not finish before profiling");
        // Warm font/texture and hardware-layer allocation before the sustained test.
        for(int i=0;i<3;i++){key(82);SystemClock.sleep(260);key(4);SystemClock.sleep(300);}
        if(!"true".equals(options.getString("hold-only"))){
        resetSamples();
        for(int i=0;i<12;i++){
            key(82);SystemClock.sleep(260);key(20);key(19);SystemClock.sleep(120);key(4);SystemClock.sleep(350);
        }
        recording=false;report.put("menu",summary());captureLatency("menu");
        }
        resetSamples();
        for(int i=0;i<8;i++){
            long now=SystemClock.uptimeMillis();
            injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,23,0));
            SystemClock.sleep(540);
            check(state("menu")!=null,"long press did not open menu");
            injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));
            SystemClock.sleep(200);key(4);SystemClock.sleep(350);
            check(!(Boolean)state("holdingOK"),"held key stuck after release");
        }
        recording=false;report.put("hold_release",summary());captureLatency("hold-release");
        if("true".equals(options.getString("hold-only")))return;
        key(82);waitMotion();
        // Down/up feedback inside menu, cancelling before release avoids invoking an app.
        resetSamples();
        for(int i=0;i<8;i++){
            long now=SystemClock.uptimeMillis();
            injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,23,0));SystemClock.sleep(100);
            main(()->call(view(),"closeMenu"));injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));
            SystemClock.sleep(300);key(82);SystemClock.sleep(250);
        }
        recording=false;report.put("menu_press_release",summary());captureLatency("menu-press");key(4);waitMotion();
        key(82);key(20);key(23);waitMotion();
        check((Boolean)state("moveMode"),"arrange animation did not start");
        resetSamples();SystemClock.sleep(1500);recording=false;
        for(Object tile:tiles("all"))check(Math.abs((Float)field(field(tile,"ring"),"value"))<2.5f,"arrange ring amplitude excessive");
        report.put("arrange_ring_bounded",true);
        
        key(4);key(4);waitMotion();
        resetSamples();
        for(int i=0;i<12;i++){key(22);SystemClock.sleep(65);key(21);SystemClock.sleep(65);}
        SystemClock.sleep(600);recording=false;report.put("focus",summary());captureLatency("focus");
        home();resetSamples();
        for(int i=0;i<6;i++){
            main(()->{Method enter=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);enter.setAccessible(true);enter.invoke(view(),false);return null;});SystemClock.sleep(850);
            main(()->{call(view(),"exitDrawer");return null;});SystemClock.sleep(850);
        }
        recording=false;report.put("app_list",summary());captureLatency("app-list");
        home();key(19);resetSamples();
        for(int i=0;i<12;i++){
            key(21);key(21);key(21);SystemClock.sleep(100);key(22);key(22);key(22);SystemClock.sleep(180);
        }
        SystemClock.sleep(600);recording=false;report.put("header_controls",summary());captureLatency("header-controls");key(20);
    }
    private String digest(InputStream in)throws Exception{
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream source=in){byte[] b=new byte[32768];int n;while((n=source.read(b))!=-1)sha.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:sha.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private void wallpaperState(JSONObject report)throws Exception{
        Object store=main(()->field(activity,"wallpapers"));File saved=(File)main(()->call(store,"currentFile"));
        report.put("saved_wallpaper",saved.isFile());
        report.put("effective_source_sha256",digest(saved.isFile()?new FileInputStream(saved):getTargetContext().getAssets().open("wallpapers/wallpaper.jpg")));
        report.put("bundled_default_sha256",digest(getTargetContext().getAssets().open("wallpapers/wallpaper.jpg")));
    }
    private void releaseThree(JSONObject report)throws Exception{
        wallpaperState(report);
        check(report.getString("bundled_default_sha256").equals("71f07d0d0ec2d79a86220cc4fad4bcededa5fba3d41c068cb538a82ceab73945"),"wrong bundled wallpaper");
        check(report.getString("effective_source_sha256").equals(options.getString("wallpaper-sha")),"upgrade changed device wallpaper");
        main(()->{Class<?> geometry=Class.forName("dev.pdmv.os.UiGeometry");Method bottom=geometry.getMethod("gridClipBottom",float.class);for(float v:new float[]{0,.25f,.5f,1})check((Float)bottom.invoke(null,v)==1080f,"bottom padding in motion clip");
            Method offset=geometry.getMethod("gridTransitionOffset",float.class);check(Math.abs(geometry.getField("GRID_TOP").getFloat(null)+(Float)offset.invoke(null,0f)-1080f)<.01f,"first row starts away from bottom edge");return null;});
        report.put("bottom_edge_entry",true);drawerDismiss(report);
    }
    private void drawerDismiss(JSONObject report)throws Exception{
        home();
        for(int run=0;run<3;run++){
            main(()->{Method enter=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);enter.setAccessible(true);enter.invoke(view(),true);return null;});
            SystemClock.sleep(1500);if(run==0)screenshot("dismiss-before");
            main(()->{call(view(),"exitDrawer");return null;});
            boolean ordered=false;long end=SystemClock.uptimeMillis()+1500;
            while(SystemClock.uptimeMillis()<end){
                float[] v=(float[])main(()->{Object f=field(view(),"drawerFlow");Object[] r=(Object[])field(f,"rows");return new float[]{(Float)field(field(f,"dock"),"value"),(Float)field(r[0],"value"),(Float)field(r[1],"value"),(Float)field(r[2],"value")};});
                check(v[0]+.04f>=v[1]&&v[1]+.04f>=v[2]&&v[2]+.04f>=v[3],"closing rows crowded into descending dock: "+Arrays.toString(v));
                if(v[0]>.3f&&v[0]<.9f&&v[0]>v[1]+.01f&&v[1]>v[2]+.01f&&v[2]>v[3]+.01f)ordered=true;
                SystemClock.sleep(16);
            }
            check(ordered,"bottom-first dismissal not observed");
            check(!(Boolean)main(()->call(field(view(),"drawerFlow"),"busy")),"dismissal did not settle");
        }
        screenshot("dismiss-home");report.put("bottom_rows_exit_before_dock",true).put("no_row_overlap_on_dismiss",true).put("three_cycles_settled",true);
    }
    private void startupMotion(JSONObject report)throws Exception{
        resetSamples();
        float min=2,max=-1;long until=SystemClock.uptimeMillis()+4000;
        while(SystemClock.uptimeMillis()<until){
            float v=(Float)main(()->field(field(view(),"startupReveal"),"value"));min=Math.min(min,v);max=Math.max(max,v);SystemClock.sleep(16);
        }
        min=Math.min(min,entryMin);max=Math.max(max,entryMax);
        check(min<.95f&&max>1.001f&&max<1.04f,"startup spring was not observed or rebound excessive: "+min+"/"+max);
        Object sounds=main(()->field(activity,"uiSounds"));
        check((Long)main(()->field(sounds,"lastPlayed"))<0,"startup must be silent");
        report.put("startup_min",min).put("startup_max",max).put("startup_silent",true);
        recording=false;report.put("startup_frames",summary());captureLatency("startup-entrance");resetSamples();
        for(boolean open:new boolean[]{true,false}){
            main(()->{if(open){Method m=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);m.setAccessible(true);m.invoke(view(),true);}else call(view(),"exitDrawer");return null;});
            boolean staggered=false;float peak=0;long end=SystemClock.uptimeMillis()+1800;
            while(SystemClock.uptimeMillis()<end){
                float[] values=(float[])main(()->{Object f=field(view(),"drawerFlow");Object[] rows=(Object[])field(f,"rows");return new float[]{(Float)field(field(f,"dock"),"value"),(Float)field(rows[0],"value"),(Float)field(rows[1],"value"),(Float)field(rows[2],"value")};});
                if(open&&values[0]>.1f&&values[0]<.9f&&values[0]>values[1]&&values[1]>values[2]&&values[2]>values[3])staggered=true;
                float offset=(Float)main(()->call(view(),"dockRecoil"));peak=Math.max(peak,Math.abs(offset));SystemClock.sleep(16);
            }
            if(open){check(staggered,"rows did not trail dock in sequence");report.put("rows_physical_stagger",true);}
            check(peak>2f&&peak<700f,"dock rebound missing or excessive");
            check(Math.abs((Float)main(()->call(view(),"dockRecoil")))<.05f,"dock rebound did not settle");
            report.put(open?"dock_open_recoil_px":"dock_close_recoil_px",peak);
        }
        recording=false;report.put("dock_frames",summary());captureLatency("dock-rebound");
        main(()->{call(view(),"openTileMenu");return null;});waitMotion();
        for(int target:new int[]{2,0}){
            main(()->{Object m=field(view(),"menuSelection");set(m,"target",(float)target);set(m,"velocity",0f);((View)view()).invalidate();return null;});
            float prior=target==2?0f:2f;long end=SystemClock.uptimeMillis()+700;
            while(SystemClock.uptimeMillis()<end){float v=(Float)main(()->field(field(view(),"menuSelection"),"value"));check(v>=-.001f&&v<=2.001f,"menu selection overshot");check(target==2?v>=prior-.001f:v<=prior+.001f,"menu selection bounced");prior=v;SystemClock.sleep(16);}
            check(Math.abs((Float)main(()->field(field(view(),"menuSelection"),"value"))-target)<.002f,"menu selection did not reach target");
        }
        main(()->{call(view(),"closeMenu");return null;});report.put("menu_selection_no_spring",true);
    }
    private void wifiSound(JSONObject report)throws Exception{
        home();Object sounds=main(()->field(activity,"uiSounds"));
        await(()->(Boolean)main(()->((Set<?>)field(sounds,"loaded")).size()==5),"audio samples not loaded");
        Object previous=main(()->field(view(),"wifiFeedback"));
        Class<?> type=Class.forName("dev.pdmv.os.WifiFeedback");Constructor<?> ctor=type.getDeclaredConstructor(int.class);ctor.setAccessible(true);
        try{
            main(()->{((Handler)field(view(),"handler")).removeCallbacks((Runnable)field(view(),"statusTick"));set(view(),"wifiFeedback",ctor.newInstance(0));return null;});
            Method present=view().getClass().getDeclaredMethod("presentWifiState",int.class);present.setAccessible(true);
            String[] labels={"Đã bật","Đã kết nối","Đã tắt","Đã kết nối","Mất kết nối"};
            int[] states={1,2,0,2,1},events={1,2,0,2,3};
            for(int i=0;i<states.length;i++){
                final int state=states[i],event=events[i];final String label=labels[i];
                int before=(Integer)main(()->field(sounds,"lastEvent"));
                main(()->{present.invoke(view(),state);return null;});
                if(event==3){SystemClock.sleep(650);main(()->{present.invoke(view(),state);return null;});}
                check((Integer)main(()->field(sounds,"lastEvent"))==before,"Wi-Fi unexpectedly played a sound");
                check(((String)state("toastMessage")).equals("Wi-Fi · "+label),"Wi-Fi sound/toast mismatch");
                int played=(Integer)main(()->field(sounds,"lastEvent"));main(()->{present.invoke(view(),state);return null;});
                check((Integer)main(()->field(sounds,"lastEvent"))==played,"repeated Wi-Fi state played sound");SystemClock.sleep(25);
            }
            report.put("wifi_toasts_without_sound",true).put("wifi_repeat_is_silent",true).put("actual_radio_toggled",false);
        }finally{main(()->{set(view(),"wifiFeedback",previous);((Runnable)field(view(),"hideToast")).run();((Handler)field(view(),"handler")).postDelayed((Runnable)field(view(),"statusTick"),1000);return null;});}
    }
    private void navigationSound(JSONObject report)throws Exception{
        home();Object sounds=main(()->field(activity,"uiSounds"));
        await(()->(Boolean)main(()->field(sounds,"ready")),"navigation sample did not load");
        check((Boolean)main(()->field(sounds,"enabled")),"system sound effects disabled");
        long atEdge=(Long)main(()->field(sounds,"lastPlayed"));key(21);
        check((Long)main(()->field(sounds,"lastPlayed"))==atEdge,"edge pressure played a navigation sound");
        SystemClock.sleep(150);key(22);
        long moved=(Long)main(()->field(sounds,"lastPlayed"));check(moved>atEdge,"real focus movement did not start a sound stream");
        SystemClock.sleep(150);key(82);waitMotion();long beforeMenu=(Long)main(()->field(sounds,"lastPlayed"));key(20);
        check((Long)main(()->field(sounds,"lastPlayed"))==beforeMenu,"menu used the app focus sound");
        check((Long)main(()->field(sounds,"lastMenuFocus"))>beforeMenu,"menu did not play keyboard focus sound");
        long menuPress=(Long)main(()->field(sounds,"lastPress"));key(23);
        check((Long)main(()->field(sounds,"lastPress"))>menuPress,"menu selection did not play keyboard press");key(4);home();
        key(19);main(()->{set(view(),"headerSelection",3);call(view(),"updateFocus");return null;});SystemClock.sleep(150);long beforePill=(Long)main(()->field(sounds,"lastPlayed"));key(21);
        check((Long)main(()->field(sounds,"lastPlayed"))>beforePill,"pill button focus does not use app sound");
        long pillPress=(Long)main(()->field(sounds,"lastPress"));key(23);
        check((Long)main(()->field(sounds,"lastPress"))>pillPress,"pill selection did not play keyboard press");key(4);key(20);waitMotion();
        String fixture="dev.pdmv.os.qa.fixture";
        boolean installed=false;
        try{
            try{getTargetContext().getPackageManager().getPackageInfo(fixture,0);throw new AssertionError("QA fixture already installed");}catch(android.content.pm.PackageManager.NameNotFoundException absent){}
            check(shellOutput("pm install -r /data/local/tmp/pdmvos-fixture-1.apk").contains("Success"),"sound fixture installation failed");installed=true;
            await(()->livePackage(fixture)!=null,"sound fixture not in catalog");Object tile=livePackage(fixture);selectTile(tile);
            main(()->{
                Object v=view();Method capture=v.getClass().getDeclaredMethod("captureMorphOrigin",tile.getClass());capture.setAccessible(true);capture.invoke(v,tile);
                Method draw=v.getClass().getDeclaredMethod("drawArtworkMorph",android.graphics.Canvas.class,tile.getClass(),android.graphics.RectF.class,float.class,boolean.class);draw.setAccessible(true);
                Bitmap bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
                android.graphics.RectF origin=(android.graphics.RectF)field(v,"morphOrigin");
                draw.invoke(v,canvas,tile,origin,1f,true);
                for(int[] point:new int[][]{{0,0},{1919,0},{0,1079},{1919,1079},{960,540}})check(bitmap.getPixel(point[0],point[1])==android.graphics.Color.BLACK,"opening morph must cover entire screen black");
                draw.invoke(v,canvas,tile,origin,1f,false);
                check(bitmap.getPixel(960,540)!=android.graphics.Color.BLACK,"return must not force black tint");
                android.graphics.RectF full=(android.graphics.RectF)field(v,"morphBounds");check(full.width()==1920&&full.height()==1080,"morph must reach fullscreen");
                bitmap.recycle();return null;
            });
            report.put("morph_fullscreen_black_open",true).put("morph_return_without_black_tint",true);
            long beforeOpen=(Long)main(()->field(sounds,"lastOpen")),beforeReturn=(Long)main(()->field(sounds,"lastReturn"));
            main(()->{Method open=activity.getClass().getDeclaredMethod("open",tile.getClass());open.setAccessible(true);open.invoke(activity,tile);return null;});
            await(()->!(Boolean)main(()->field(activity,"resumed")),"fixture did not leave launcher");
            check((Long)main(()->field(sounds,"lastOpen"))>beforeOpen,"app launch did not play navigation_push");
            shellOutput("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n dev.pdmv.os/.MainActivity");
            await(()->(Boolean)main(()->field(activity,"resumed")),"launcher did not resume from fixture");
            await(()->(Long)main(()->field(sounds,"lastReturn"))>beforeReturn,"app return did not play navigation_pop after focus");
            long returned=(Long)main(()->field(sounds,"lastReturn"));
            main(()->{call(view(),"onReturnToHome");return null;});
            check((Long)main(()->field(sounds,"lastReturn"))==returned,"return sound repeated without leaving for an app");
            report.put("real_app_open_push",true).put("real_app_return_pop",true).put("no_duplicate_return",true);
        }finally{if(installed)shellOutput("pm uninstall "+fixture);}
        android.media.AudioManager audio=(android.media.AudioManager)getTargetContext().getSystemService(Context.AUDIO_SERVICE);
        report.put("sound_sample_loaded",true).put("app_and_pill_focus_streams_started",true).put("menu_uses_keyboard_focus",true).put("menu_and_pill_keyboard_press",true).put("boundary_is_silent",true)
            .put("system_stream_volume",audio.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)).put("system_stream_muted",audio.isStreamMute(android.media.AudioManager.STREAM_SYSTEM));
    }
    private void popupProfile(JSONObject report)throws Exception{
        home();main(()->{call(view(),"closeMenu");return null;});waitMotion();
        resetSamples();
        for(int i=0;i<6;i++){
            final String message=i%2==0?"Đã hoàn tất":"Thông báo kiểm tra chuyển động giao diện";
            main(()->{Method m=view().getClass().getDeclaredMethod("showToast",String.class);m.setAccessible(true);m.invoke(view(),message);return null;});SystemClock.sleep(700);
            main(()->{((Runnable)field(view(),"hideToast")).run();return null;});SystemClock.sleep(i%2==0?120:650);
        }
        SystemClock.sleep(800);recording=false;report.put("toast_slide",summary());captureLatency("toast-slide");
        check((Float)main(()->field(field(view(),"toastReveal"),"value"))==0f,"toast did not finish hiding");
        resetSamples();
        for(int i=0;i<6;i++){
            main(()->{Method m=view().getClass().getDeclaredMethod("showDialog",String.class,String.class,String.class,Runnable.class);m.setAccessible(true);m.invoke(view(),"Kiểm tra chuyển động","Nền kính giữ đúng vị trí trong khi hiện và ẩn.","Đóng",(Runnable)()->{});return null;});SystemClock.sleep(700);
            main(()->{call(view(),"dismissDialog");return null;});SystemClock.sleep(i%2==0?120:650);
        }
        SystemClock.sleep(800);recording=false;report.put("dialog_reveal",summary());captureLatency("dialog-reveal");
        check((Float)main(()->field(field(view(),"dialogReveal"),"value"))==0f,"dialog did not finish hiding");
        check((android.graphics.Rect)main(()->call(view(),"overlayViewport"))==null,"invisible popup retained an overlay");
        report.put("popup_reversal_and_hidden_cleanup",true);
    }
    private void selectTile(Object tile){
        main(()->{
            Object v=view();List<?> pinned=(List<?>)field(repo(),"dock"), grid=(List<?>)field(repo(),"grid");
            int i=pinned.indexOf(tile);
            if(i>=0){call(v,"exitDrawer");set(v,"dockFocus",i);set(v,"focusHeader",false);}
            else{
                Method enter=v.getClass().getDeclaredMethod("enterDrawer",boolean.class);enter.setAccessible(true);enter.invoke(v,false);
                set(v,"gridFocus",grid.indexOf(tile));set(v,"focusDrawerDock",false);
            }
            ((View)v).invalidate();return null;
        });waitMotion();
    }
    private void testUninstallMenu(Object tile,boolean allowed)throws Exception{
        selectTile(tile);key(82);waitMotion();
        List<?> items=(List<?>)main(()->field(field(view(),"menu"),"items"));
        int uninstall=-1,sorts=0;
        for(int i=0;i<items.size();i++){
            String title=(String)field(items.get(i),"title");
            if(title.equals("Gỡ cài đặt"))uninstall=i;
            if(title.equals("Sắp xếp ứng dụng"))sorts++;
            check(!title.equals("Di chuyển sang trái")&&!title.equals("Di chuyển sang phải")&&!title.equals("Thay ứng dụng"),"obsolete dock action returned");
        }
        check((uninstall>=0)==allowed,"uninstall menu eligibility mismatch");check(sorts==1,"sort option duplicated");
        if(allowed){
            IntentFilter filter=new IntentFilter(Intent.ACTION_DELETE);filter.addDataScheme("package");
            ActivityMonitor monitor=addMonitor(filter,new ActivityResult(Activity.RESULT_CANCELED,null),true);
            for(int i=0;i<uninstall;i++)key(20);
            key(23);waitMotion();check(monitor.getHits()==1,"uninstall did not dispatch the system confirmation intent");removeMonitor(monitor);
        }else key(4);
    }
    private void features(JSONObject report)throws Exception{
        home();
        List<String> initial=ids("dock");
        int occupied=0;for(String id:initial)if(!id.startsWith("empty:"))occupied++;
        check(occupied>0,"need one pinned application for acceptance test");
        String removed=initial.get(0);
        key(82);key(20);key(20);key(23);waitMotion();
        List<String> after=ids("dock");
        check(after.size()==5,"unpin changed dock size");
        check(!after.contains(removed)&&ids("grid").contains(removed),"unpin lost application");
        for(int i=0;i<occupied-1;i++)check(after.get(i).equals(initial.get(i+1)),"unpin did not compact right neighbors");
        screenshot("unpin-plus");
        int empty=occupied-1;
        for(int i=0;i<empty;i++)key(22);
        key(23);waitMotion();check((Integer)state("replaceSlot")==empty,"plus slot did not enter pin chooser");
        check((Integer)state("gridFocus")==0,"pin chooser must start at the first available application");
        key(23);waitMotion();check(ids("dock").contains(removed),"plus chooser failed to pin app");
        check((Integer)state("replaceSlot")==-1,"pin chooser remained active");
        screenshot("app-list-spacing");
        home();key(82);key(20);key(23);waitMotion();
        screenshot("sort-action-state");
        check((Boolean)state("moveMode"),"sort action did not enter arrange mode");
        double drift=0;
        for(int sample=0;sample<16;sample++){
            double error=(Double)main(()->{
                Object v=view();Map<?,?> nodes=(Map<?,?>)field(v,"tileNodes");
                int slot=(Integer)field(v,"dockFocus");Object tile=((List<?>)field(repo(),"dock")).get(slot);
                View node=(View)nodes.get(field(tile,"id"));
                float scale=(Float)field(v,"canvasScale"),top=(Float)field(v,"canvasTop");
                float dockTop=(Float)call(v,"docTop");
                check(node.getPivotX()==138f && node.getPivotY()==83f,"arrange pivot is outside artwork center");
                return (double)Math.abs(node.getY()+node.getPivotY()-(top+(dockTop+Class.forName("dev.pdmv.os.UiGeometry").getField("DOCK_HEIGHT").getFloat(null)/2f)*scale));
            });drift=Math.max(drift,error);SystemClock.sleep(20);
        }
        check(drift<.05,"arrange tile drifts upward from its container center");
        report.put("arrange_center_max_drift_px",drift);
        screenshot("arrange-centered");
        String moving=ids("dock").get(0);
        key(20);waitMotion();check(!(Boolean)state("moveDock"),"dock-to-list movement blocked");
        check(ids("grid").contains(moving),"dock-to-list lost moving item");
        key(19);waitMotion();check((Boolean)state("moveDock"),"list-to-dock movement blocked");
        check(ids("dock").contains(moving),"list-to-dock lost moving item");
        HashSet<String> unique=new HashSet<>();
        for(String id:ids("dock"))if(!id.startsWith("empty:"))check(unique.add(id),"duplicate dock item");
        for(String id:ids("grid"))check(unique.add(id),"application duplicated across dock/grid");
        screenshot("cross-region-arrange");key(4);key(4);
        // Validate uninstall affordance for every real package against PackageManager.
        int checked=0;Object removable=null,system=null;
        for(Object tile:tiles("all")){
            String packageName=(String)field(tile,"packageName");if(packageName==null)continue;
            ApplicationInfo app=getTargetContext().getPackageManager().getApplicationInfo(packageName,0);
            check((Boolean)field(tile,"uninstallable")==((app.flags&ApplicationInfo.FLAG_SYSTEM)==0),"uninstall eligibility mismatch");checked++;
            if((Boolean)field(tile,"uninstallable"))removable=tile;else system=tile;
        }
        if(removable!=null)testUninstallMenu(removable,true);
        if(system!=null)testUninstallMenu(system,false);
        for(Object tile:tiles("all"))check(!id(tile).equals("settings")&&!id(tile).equals("tv")&&!id(tile).startsWith("hdmi:"),"synthetic settings/input tile returned");
        home();key(19);waitMotion();screenshot("header-clear-glass");
        check((Boolean)main(()->Math.abs((Float)call(view(),"clockPillWidth")-(Float)call(view(),"desiredClockWidth"))<.1f),"clock/date pill does not fit its current text");
        main(()->{set(view(),"headerSelection",0);return null;});key(22);
        check((Integer)state("headerSelection")==2,"input must follow Wi-Fi directly");
        key(21);check((Integer)state("headerSelection")==0,"Bluetooth still receives focus");
        // Stable header buttons remain disjoint at maximum focus size.
        ArrayList<android.graphics.RectF> rects=new ArrayList<>();
        for(int i:new int[]{0,2,3}){final int index=i;rects.add((android.graphics.RectF)main(()->{Method m=view().getClass().getDeclaredMethod("headerRect",int.class);m.setAccessible(true);return m.invoke(view(),index);}));}
        for(int i=0;i<rects.size();i++){android.graphics.RectF r=new android.graphics.RectF(rects.get(i));r.inset(-3.24f,-3.24f);for(int j=0;j<rects.size();j++)if(i!=j)check(!android.graphics.RectF.intersects(r,rects.get(j)),"header focus overlaps neighbor");}
        // A real held key must dispatch exactly one system settings intent.
        for(int index:new int[]{0,2}){
            String action=index==0?android.provider.Settings.ACTION_WIFI_SETTINGS:index==2?android.media.tv.TvInputManager.ACTION_SETUP_INPUTS:android.provider.Settings.ACTION_SETTINGS;
            ActivityMonitor monitor=addMonitor(new IntentFilter(action),new ActivityResult(Activity.RESULT_CANCELED,null),true);
            main(()->{set(view(),"focusHeader",true);set(view(),"headerSelection",index);return null;});
            long down=SystemClock.uptimeMillis();injectKey(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,23,0));SystemClock.sleep(550);
            injectKey(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));waitMotion();
            check(monitor.getHits()==1,"header hold did not dispatch exactly once: "+index);removeMonitor(monitor);
            check(!(Boolean)state("holdingOK"),"header hold stuck after release");
        }
        main(()->{set(view(),"headerSelection",3);return null;});
        ActivityMonitor settings=addMonitor(new IntentFilter(android.provider.Settings.ACTION_SETTINGS),new ActivityResult(Activity.RESULT_CANCELED,null),true);
        key(23);check(settings.getHits()==1,"clock settings short press failed");removeMonitor(settings);
        main(()->{set(view(),"headerSelection",2);return null;});key(23);waitMotion();
        if(state("menu")==null){
            check(((String)state("toastMessage")).equals("Không tìm thấy đầu vào nào"),"missing input fallback toast");
            report.put("input_discovery","no-inputs-toast");
        }else{
        screenshot("system-inputs");
        android.graphics.RectF box=(android.graphics.RectF)main(()->{Object m=field(view(),"menu");Method rect=view().getClass().getDeclaredMethod("menuRect",m.getClass());rect.setAccessible(true);return rect.invoke(view(),m);});
        check(Math.abs(box.centerX()-960)<.01f&&Math.abs(box.centerY()-540)<.01f,"input menu is not centered");key(4);
        report.put("input_discovery","system-input-menu");
        }
        main(()->{Object c=field(activity,"controls");int current=(Integer)field(c,"wifi");set(c,"wifi",current==0?1:0);call(view(),"refreshControlStates");return null;});waitMotion();
        check(((String)state("toastMessage")).startsWith("Wi-Fi ·"),"radio change did not produce a toast");screenshot("system-state-toast");
        main(()->{Method m=view().getClass().getDeclaredMethod("showToast",String.class);m.setAccessible(true);m.invoke(view(),"Đã kết nối");return null;});waitMotion();
        check((Float)main(()->field(field(view(),"toastWidth"),"value"))<360,"short toast has fixed minimum width");screenshot("content-fit-toast");
        check(getTargetContext().getPackageManager().getPackageInfo("dev.pdmv.os",android.content.pm.PackageManager.GET_SERVICES).services==null,"obsolete power service declared");
        report.put("header_controls_features","PASS").put("bluetooth_removed_and_input_grouped","PASS").put("system_settings_holds","PASS").put("centered_input_menu","PASS").put("radios_toggled",false).put("content_fit_toast","PASS");
        home();int removals=0;
        while(!ids("dock").get(0).startsWith("empty:")){
            key(82);key(20);key(20);key(23);waitMotion();check(++removals<=6,"unpin loop failed to reach empty dock");
        }
        for(String item:ids("dock"))check(item.startsWith("empty:"),"all-empty dock missing plus slot");
        screenshot("six-empty-slots");
        main(()->{call(repo(),"refresh");return null;});
        check(ids("dock").size()==5,"reload changed slot count");
        for(String item:ids("dock"))check(item.startsWith("empty:"),"restart silently refilled an unpinned dock");
        report.put("features","PASS").put("uninstall_eligibility_packages",checked).put("uninstall_intent_blocked_safely",removable!=null).put("all_empty_dock_persistence","PASS");
    }
    private void glassFeatures(JSONObject report)throws Exception{
        Object store=main(()->field(activity,"wallpapers"));
        long readyDeadline=SystemClock.uptimeMillis()+20000;
        while(SystemClock.uptimeMillis()<readyDeadline&&((Bitmap)main(()->field(store,"image"))==null||((Bitmap[])main(()->field(store,"frost")))[2]==null))SystemClock.sleep(100);
        Bitmap original=(Bitmap)main(()->field(store,"image"));
        Object oldFrost=main(()->field(store,"frost"));
        check(original!=null,"wallpaper unavailable");
        Method blur=store.getClass().getDeclaredMethod("blur",Bitmap.class,int.class);blur.setAccessible(true);
        try{
            // A translated squircle must sample its screen position, not its top-left local origin.
            Bitmap coordinates=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cc=new android.graphics.Canvas(coordinates);cc.drawColor(0xffff0000);
            android.graphics.Paint green=new android.graphics.Paint();green.setColor(0xff00ff00);cc.drawRect(0,135,480,270,green);
            main(()->{set(store,"frost",new Bitmap[]{coordinates,coordinates,coordinates});Object glass=field(view(),"glassMaterials");
                Method progress=glass.getClass().getDeclaredMethod("setSceneBlur",float.class);progress.setAccessible(true);progress.invoke(glass,0f);
                Method draw=glass.getClass().getDeclaredMethod("drawBackdrop",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class,float.class,float.class,float.class);draw.setAccessible(true);
                Bitmap probe=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
                draw.invoke(glass,new android.graphics.Canvas(probe),480f,560f,500f,200f,70f,0,0f,0f,1f);
                int pixel=probe.getPixel(730,660);check(android.graphics.Color.green(pixel)>240&&android.graphics.Color.red(pixel)<10,"glass samples local instead of screen coordinates");probe.recycle();
                set(store,"frost",oldFrost);return null;});
            report.put("glass_screen_coordinate_sampling","PASS");
            home();
            main(()->{set(view(),"clockCache","11:11");((View)view()).invalidate();return null;});waitMotion();
            float narrow=(Float)main(()->call(view(),"clockPillWidth"));
            main(()->{set(view(),"clockCache","20:08");((View)view()).invalidate();return null;});SystemClock.sleep(80);
            float between=(Float)main(()->call(view(),"clockPillWidth"));waitMotion();
            float wide=(Float)main(()->call(view(),"clockPillWidth"));
            check(wide>narrow&&between>narrow&&between<wide,"clock width jumped instead of animating");
            main(()->{Method format=view().getClass().getDeclaredMethod("formatClock",java.util.Date.class);format.setAccessible(true);set(view(),"clockCache",format.invoke(view(),new java.util.Date()));return null;});waitMotion();
            main(()->{call(view(),"openTileMenu");call(view(),"closeMenu");return null;});waitMotion();
            check(state("menu")==null&&(Float)main(()->field(field(view(),"menuReveal"),"target"))==0f,"cancelled blur revealed menu again");
            report.put("cancel_pending_material_no_late_reveal","PASS");
            main(()->{call(view(),"openTileMenu");return null;});
            check((Float)main(()->field(field(view(),"menuReveal"),"target"))==1f,"menu still waits for an asynchronous image");
            waitMotion();check(main(()->field(field(view(),"menu"),"backdrop"))!=null,"menu backdrop not captured");
            check(main(()->field(field(view(),"glassMaterials"),"menuBackdrop"))!=null,"menu shown before blur ready");
            check((Float)main(()->field(field(field(view(),"menu"),"frostMix"),"value"))==1f,"menu has a second frost phase");
            android.graphics.RectF anchored=(android.graphics.RectF)main(()->{Object m=field(view(),"menu");Method rect=view().getClass().getDeclaredMethod("menuRect",m.getClass());rect.setAccessible(true);return rect.invoke(view(),m);});
            check(anchored.left>=32&&anchored.right<=1888&&anchored.bottom<850,"held menu is not above its Home tile");
            screenshot("anchored-held-menu");key(4);waitMotion();
            main(()->{Method show=view().getClass().getDeclaredMethod("showToast",String.class);show.setAccessible(true);show.invoke(view(),"Kiểm tra nền kính");return null;});
            waitMotion();check(main(()->field(field(view(),"glassMaterials"),"toastBackdrop"))!=null,"toast shown before blur ready");
            main(()->{Object reveal=field(view(),"toastReveal");set(reveal,"value",0f);set(reveal,"target",0f);set(reveal,"velocity",0f);return null;});
            report.put("material_ready_before_reveal","PASS").put("held_menu_anchored","PASS").put("clock_width_animation","PASS");
            main(()->{Method enter=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);enter.setAccessible(true);enter.invoke(view(),false);return null;});waitMotion();
            final int[] sizes=new int[2];
            main(()->{List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");sizes[0]=grid.size();sizes[1]=all.size();
                Class<?> tile=Class.forName("dev.pdmv.os.Tile");java.lang.reflect.Constructor<?> create=tile.getDeclaredConstructor(String.class,String.class);create.setAccessible(true);
                while(grid.size()<31){Object extra=create.newInstance("qa-scroll-"+grid.size(),"Kiểm tra hàng "+grid.size());grid.add(extra);all.add(extra);}
                set(view(),"gridFocus",30);((View)view()).invalidate();return null;});key(20);waitMotion();
            int first=(Integer)state("gridFirstRow");float rowOffset=(Float)main(()->field(field(view(),"gridScroll"),"value"));
            check(first==3&&Math.abs(rowOffset-first)<.005f,"long list did not snap to a whole row");screenshot("row-snapped-scroll");
            main(()->{List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");grid.subList(sizes[0],grid.size()).clear();all.subList(sizes[1],all.size()).clear();set(view(),"gridFocus",0);set(view(),"gridFirstRow",0);Object scroll=field(view(),"gridScroll");set(scroll,"value",0f);set(scroll,"target",0f);set(scroll,"velocity",0f);call(view(),"exitDrawer");return null;});waitMotion();
            report.put("long_list_row_snap","PASS");


            home();
            main(()->{Method enter=view().getClass().getDeclaredMethod("enterDrawer",boolean.class);enter.setAccessible(true);enter.invoke(view(),false);return null;});
            SystemClock.sleep(80);float opening=(Float)main(()->field(field(view(),"listBlur"),"value"));
            check(opening>0&&opening<.95f,"app-list blur did not animate in");
            waitMotion();key(4);SystemClock.sleep(80);
            float closing=(Float)main(()->field(field(view(),"listBlur"),"value"));
            check(closing>0&&closing<1,"app-list blur did not animate out");waitMotion();
            report.put("app_list_blur_in_out","PASS");
            main(()->{set(view(),"focusHeader",true);set(view(),"headerSelection",0);return null;});key(22);
            check((Integer)state("headerSelection")==2,"Bluetooth still in header focus order");
            key(20);waitMotion();
            for(boolean bright:new boolean[]{false,true}){
                Bitmap fixture=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas=new android.graphics.Canvas(fixture);
                android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                float gain=bright?.52f:.28f,offset=bright?118:0;
                paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{gain,0,0,0,offset,0,gain,0,0,offset,0,0,gain,0,offset,0,0,0,1,0}));
                canvas.drawBitmap(original,null,new android.graphics.RectF(0,0,1920,1080),paint);
                Bitmap small=Bitmap.createScaledBitmap(fixture,480,270,true);
                Bitmap[] frost={(Bitmap)blur.invoke(null,small,1),(Bitmap)blur.invoke(null,small,3),(Bitmap)blur.invoke(null,small,6)};small.recycle();
                main(()->{set(store,"image",fixture);set(store,"frost",frost);((Runnable)field(store,"listener")).run();return null;});waitMotion();
                String name=bright?"light":"dark";
                screenshot("glass-"+name+"-dock-pill");
                key(20);waitMotion();screenshot("glass-"+name+"-app-list");
                main(()->{call(view(),"openInputMenu");return null;});waitMotion();screenshot("glass-"+name+"-app-list-menu");key(4);waitMotion();key(4);waitMotion();
                main(()->{call(view(),"openInputMenu");return null;});waitMotion();screenshot("glass-"+name+"-menu");key(4);waitMotion();
                main(()->{Method show=view().getClass().getDeclaredMethod("showToast",String.class);show.setAccessible(true);show.invoke(view(),"Đã kết nối Wi-Fi");return null;});waitMotion();screenshot("glass-"+name+"-toast");
                main(()->{Object reveal=field(view(),"toastReveal");set(reveal,"value",0f);set(reveal,"target",0f);set(reveal,"velocity",0f);((View)view()).invalidate();return null;});
            }
            report.put("glass_materials_light_dark","PASS").put("separate_dock_pill_menu_toast",true).put("palette_switches",0);
        }finally{
            main(()->{set(store,"image",original);set(store,"frost",oldFrost);((Runnable)field(store,"listener")).run();return null;});
        }
    }
    private void toastTransition(JSONObject report)throws Exception{
        Object wallpaperStore=main(()->field(activity,"wallpapers"));long ready=SystemClock.uptimeMillis()+90000;
        while(main(()->field(wallpaperStore,"image"))==null && SystemClock.uptimeMillis()<ready)SystemClock.sleep(100);
        check(main(()->field(wallpaperStore,"image"))!=null,"wallpaper not ready");
        home();
        main(()->{Method show=view().getClass().getDeclaredMethod("showToast",String.class);show.setAccessible(true);show.invoke(view(),"Kiểm tra nền thông báo");return null;});waitMotion();
        main(()->{((android.os.Handler)field(view(),"handler")).removeCallbacks((Runnable)field(view(),"hideToast"));return null;});
        Object first=main(()->field(field(view(),"glassMaterials"),"toastBackdrop"));
        float savedToastReveal=(Float)main(()->field(field(view(),"toastReveal"),"value"));
        main(()->{Object r=field(view(),"toastReveal");set(r,"value",0f);set(r,"target",0f);set(r,"velocity",0f);((View)view()).invalidate();return null;});SystemClock.sleep(150);
        Bitmap screen=getUiAutomation().takeScreenshot(),sample=Bitmap.createBitmap(480,54,Bitmap.Config.ARGB_8888);
        android.graphics.Paint filtering=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        new android.graphics.Canvas(sample).drawBitmap(screen,new android.graphics.Rect(0,screen.getHeight()*4/5,screen.getWidth(),screen.getHeight()),new android.graphics.Rect(0,0,480,54),filtering);
        Method blur=wallpaperStore.getClass().getDeclaredMethod("blur",Bitmap.class,int.class);blur.setAccessible(true);
        Bitmap expected=(Bitmap)blur.invoke(null,sample,12);long error=0;
        int firstWidth=((Bitmap)first).getWidth(),leftSample=Math.round((Float)main(()->field(field(view(),"glassMaterials"),"toastBackdropLeft"))/4f);
        for(int y=0;y<54;y++)for(int x=40;x<firstWidth-40;x++){
            int a=((Bitmap)first).getPixel(x,y),b=expected.getPixel(x+leftSample,y);
            error+=Math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))+Math.abs(android.graphics.Color.green(a)-android.graphics.Color.green(b))+Math.abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b));
        }
        double mean=error/((firstWidth-80.0)*54*3);check(mean<8,"toast capture differs from actual visible scene: "+mean);
        report.put("toast_capture_mean_rgb_error",mean);screen.recycle();sample.recycle();expected.recycle();
        main(()->{Object r=field(view(),"toastReveal");set(r,"value",savedToastReveal);set(r,"target",1f);((View)view()).invalidate();return null;});
        key(20);waitMotion();
        Object second=main(()->field(field(view(),"glassMaterials"),"toastBackdrop"));
        check(first!=second,"toast retained Home snapshot after opening App List");
        check(((Bitmap)second).getHeight()==54,"toast must capture only its local strip");
        check((Float)main(()->field(field(view(),"glassMaterials"),"toastBackdropTop"))==864f,"toast backdrop world origin incorrect");
        screenshot("toast-app-list");
        key(4);waitMotion();
        Object third=main(()->field(field(view(),"glassMaterials"),"toastBackdrop"));
        check(second!=third,"toast retained App List snapshot after closing");
        screenshot("toast-home-return");
        main(()->{
            Object v=view(),glass=field(v,"glassMaterials");
            Bitmap original=(Bitmap)field(glass,"toastBackdrop");float originalTop=(Float)field(glass,"toastBackdropTop");
            Object reveal=field(v,"toastReveal"),width=field(v,"toastWidth");
            String message=(String)field(v,"toastMessage");
            float savedWidth=(Float)field(width,"value");
            Bitmap stripe=Bitmap.createBitmap(480,54,Bitmap.Config.ARGB_8888);
            android.graphics.Canvas pattern=new android.graphics.Canvas(stripe);pattern.drawColor(0xffff0000);
            android.graphics.Paint green=new android.graphics.Paint();green.setColor(0xff00ff00);pattern.drawRect(240,0,480,54,green);
            Method setBackdrop=glass.getClass().getDeclaredMethod("setToastBackdrop",Bitmap.class,float.class);setBackdrop.setAccessible(true);
            Method begin=glass.getClass().getDeclaredMethod("beginSurface",android.graphics.Canvas.class);begin.setAccessible(true);
            Method draw=v.getClass().getDeclaredMethod("drawToast",android.graphics.Canvas.class);draw.setAccessible(true);
            Bitmap probe=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
            try{
                setBackdrop.invoke(glass,stripe,864f);
                set(reveal,"value",1f);set(width,"value",600f);set(v,"toastMessage","");set(v,"previousToast","");set(v,"toastSceneKey",call(v,"toastSceneKey"));
                android.graphics.Canvas offscreen=new android.graphics.Canvas(stripe);offscreen.scale(.25f,.25f);offscreen.translate(0,-864f);begin.invoke(glass,offscreen);
                draw.invoke(v,new android.graphics.Canvas(probe));
                int left=probe.getPixel(740,960),right=probe.getPixel(1200,960);
                check(android.graphics.Color.red(left)>android.graphics.Color.green(left)+100,"toast inherited offscreen sampling coordinates on left");
                check(android.graphics.Color.green(right)>android.graphics.Color.red(right)+100,"toast source coordinates incorrect on right");
            }finally{setBackdrop.invoke(glass,original,originalTop);set(width,"value",savedWidth);set(v,"toastMessage",message);set(v,"toastSceneKey",Long.MIN_VALUE);stripe.recycle();probe.recycle();}
            return null;
        });
        main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);((Handler)field(v,"handler")).removeCallbacks((Runnable)field(v,"hideToast"));return null;});SystemClock.sleep(1700);
        long ringKey=(Long)state("toastSceneKey");SystemClock.sleep(120);
        check(ringKey!=(Long)state("toastSceneKey"),"toast failed to refresh for ring/tilt motion");
        main(()->call(view(),"stopMove"));
        report.put("toast_follows_scene_both_directions","PASS").put("regional_backdrop_coordinates","PASS").put("offscreen_frame_isolation","PASS").put("toast_follows_ring_motion","PASS");
    }
    private void navigationLauncher(JSONObject report)throws Exception{
        home();key(20);waitMotion();
        int[] originalSizes=(int[])main(()->{
            List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");int[] sizes={grid.size(),all.size()};
            Class<?> type=Class.forName("dev.pdmv.os.Tile");Constructor<?> create=type.getDeclaredConstructor(String.class,String.class);create.setAccessible(true);
            while(grid.size()<36){Object tile=create.newInstance("qa-navigation-"+grid.size(),"Điều hướng "+grid.size());grid.add(tile);all.add(tile);}
            return sizes;
        });
        try{
            for(int row:new int[]{0,2})for(int column=0;column<6;column++){
                final int r=row,c=column;
                main(()->{set(view(),"page",1);set(view(),"focusDrawerDock",true);set(view(),"dockFocus",c);set(view(),"gridFocus",35);set(view(),"gridFirstRow",r);Object scroll=field(view(),"gridScroll");set(scroll,"value",(float)r);set(scroll,"target",(float)r);set(scroll,"velocity",0f);((View)view()).invalidate();return null;});
                key(20);check(!(Boolean)state("focusDrawerDock"),"Down stayed in dock");
                check((Integer)state("gridFocus")==row*6+column,"Down restored stale list position instead of visual column");
            }
            screenshot("navigation-visible-column");
            for(int row:new int[]{0,2}){
                final int r=row;
                main(()->{set(view(),"page",1);set(view(),"focusDrawerDock",true);set(view(),"dockFocus",5);set(view(),"gridFirstRow",r);call(view(),"updateFocus");return null;});
                key(22);check(!(Boolean)state("focusDrawerDock")&&(Integer)state("gridFocus")==r*6,"dock Right did not wrap to first app in visible row");
            }
            for(int index:new int[]{5,17,35}){
                final int position=index;
                main(()->{set(view(),"page",1);set(view(),"focusDrawerDock",false);set(view(),"gridFocus",position);set(view(),"gridFirstRow",Math.max(0,position/6-2));call(view(),"updateFocus");return null;});
                key(22);check((Integer)state("gridFocus")==Math.min(35,index+1),"row wrap or end-of-list clamp incorrect");
                if(index==17)check((Integer)state("gridFirstRow")==1,"row wrap did not scroll new focus into view");
            }
            for(int index:new int[]{6,18}){
                final int position=index;
                main(()->{set(view(),"page",1);set(view(),"focusDrawerDock",false);set(view(),"gridFocus",position);set(view(),"gridFirstRow",position/6);call(view(),"updateFocus");return null;});
                key(21);check((Integer)state("gridFocus")==index-1,"Left must wrap to previous row end");
                check((Integer)state("gridFirstRow")<=((index-1)/6),"previous row must be visible");
            }
            report.put("left_previous_row_end",true);
            home();main(()->{set(view(),"dockFocus",5);return null;});key(22);
            check((Integer)state("page")==0&&(Integer)state("dockFocus")==5,"Home dock must not wrap Right");
            report.put("right_row_wrap_and_list_end",true).put("drawer_dock_right_wrap",true).put("home_dock_right_clamped",true);
            home();main(()->{set(view(),"dockFocus",4);return null;});key(20);waitMotion();
            check((Integer)state("gridFocus")==0,"Home Down must select the first application");
            screenshot("equal-visible-row-gaps");
        }finally{
            main(()->{List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");grid.subList(originalSizes[0],grid.size()).clear();all.subList(originalSizes[1],all.size()).clear();set(view(),"gridFocus",0);set(view(),"gridFirstRow",0);Object scroll=field(view(),"gridScroll");set(scroll,"value",0f);set(scroll,"target",0f);set(scroll,"velocity",0f);return null;});
        }
        home();key(19);main(()->{set(view(),"headerSelection",3);return null;});
        ActivityMonitor settings=addMonitor(new IntentFilter(android.provider.Settings.ACTION_SETTINGS),new ActivityResult(Activity.RESULT_CANCELED,null),true);
        try{
            long down=SystemClock.uptimeMillis();injectKey(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,23,0));SystemClock.sleep(550);injectKey(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));waitMotion();
            check(state("menu")==null&&settings.getHits()==0,"Settings hold must not open menu or settings");
            check((Boolean)state("updateDialog")&&state("dialogAction")!=null
                &&"Cập nhật pdmvOS".equals(state("dialogTitle")),"Settings hold must open the update dialog");
            key(4);waitMotion();
            key(23);check(settings.getHits()==1,"Short Settings press must still open Android settings");
        }finally{removeMonitor(settings);}
        key(4);key(20);waitMotion();
        main(()->{set(view(),"moveMode",true);Method m=activity.getClass().getDeclaredMethod("onNewIntent",Intent.class);m.setAccessible(true);m.invoke(activity,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));return null;});waitMotion();
        check((Integer)state("page")==0&&!(Boolean)state("moveMode")&&state("menu")==null,"System Home must return to the launcher root");
        report.put("system_home_returns_to_root","PASS");
        key(4);report.put("dock_down_follows_visible_column","PASS").put("home_down_selects_first","PASS").put("settings_hold_update_dialog","PASS").put("settings_short_press","PASS");
    }
    private void brightReadability(JSONObject report)throws Exception{
        Object store=main(()->field(activity,"wallpapers"));
        long deadline=SystemClock.uptimeMillis()+20000;
        while(main(()->field(store,"image"))==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        Object image=main(()->field(store,"image")),frost=main(()->field(store,"frost"));
        Bitmap bright=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);bright.eraseColor(0xfff2f4f6);
        Bitmap small=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);small.eraseColor(0xfff2f4f6);
        try{
            main(()->{set(store,"image",bright);set(store,"frost",new Bitmap[]{small,small,small});((Runnable)field(store,"listener")).run();return null;});
            home();waitMotion();
            main(()->{Object tile=((List<?>)field(repo(),"dock")).get(0);View node=(View)((Map<?,?>)field(view(),"tileNodes")).get(field(tile,"id"));
                float scale=(Float)field(view(),"canvasScale");check(node.getScaleX()/scale>1.045f,"application focus not enlarged");
                check(252f*node.getScaleX()/scale<272f,"application focus overlaps neighbor");return null;});
            check((Integer)state("clockInk")==android.graphics.Color.WHITE&&(Integer)state("networkInk")==android.graphics.Color.WHITE,"header ink must remain white on every wallpaper");
            report.put("adaptive_header_contrast","PASS");screenshot("bright-app-focus");
            int[] points=(int[])main(()->{Method m=view().getClass().getDeclaredMethod("headerRect",int.class);m.setAccessible(true);android.graphics.RectF r=(android.graphics.RectF)m.invoke(view(),3);
                return new int[]{Math.round(r.left-5f),88,Math.round(r.centerX()+16f),Math.round(r.centerY()+10f)};});
            Bitmap before=getUiAutomation().takeScreenshot();int bodyBefore=before.getPixel(points[0],points[1]),buttonBefore=before.getPixel(points[2],points[3]);before.recycle();
            try{view().getClass().getDeclaredField("idleFocus");throw new AssertionError("idle tracer still present");}catch(NoSuchFieldException removed){}
            report.put("perimeter_tracer_removed","PASS");
            key(19);waitMotion();
            for(int index:new int[]{0,2,3}){
                final int selected=index;main(()->{set(view(),"headerSelection",selected);call(view(),"updateFocus");return null;});waitMotion();
                android.graphics.RectF r=(android.graphics.RectF)main(()->{Method m=view().getClass().getDeclaredMethod("headerRect",int.class);m.setAccessible(true);return m.invoke(view(),selected);});
                check(r.width()==48f&&r.height()==48f,"pill button size");screenshot("bright-pill-focus-"+index);
            }
            Bitmap after=getUiAutomation().takeScreenshot();int bodyAfter=after.getPixel(points[0],points[1]),buttonAfter=after.getPixel(points[2],points[3]);after.recycle();
            check(Math.abs(android.graphics.Color.red(bodyAfter)-android.graphics.Color.red(bodyBefore))<=3,"whole pill darkens on focus");
            check(android.graphics.Color.red(buttonAfter)<android.graphics.Color.red(buttonBefore)-8,"focused button lacks emphasis");
            report.put("pill_body_unchanged_on_focus","PASS").put("contextual_frosted_button","PASS");
            for(int shade:new int[]{128,32}){
                Bitmap context=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);context.eraseColor(android.graphics.Color.rgb(shade,shade,shade));
                Bitmap contextFrost=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);contextFrost.eraseColor(android.graphics.Color.rgb(shade,shade,shade));
                main(()->{set(store,"image",context);set(store,"frost",new Bitmap[]{contextFrost,contextFrost,contextFrost});((Runnable)field(store,"listener")).run();set(view(),"focusHeader",false);call(view(),"updateFocus");return null;});waitMotion();
                int[] point=(int[])main(()->{Method m=view().getClass().getDeclaredMethod("headerRect",int.class);m.setAccessible(true);android.graphics.RectF r=(android.graphics.RectF)m.invoke(view(),0);return new int[]{Math.round(r.centerX()+16f),Math.round(r.centerY()+10f)};});
                Bitmap plain=getUiAutomation().takeScreenshot();int old=android.graphics.Color.red(plain.getPixel(point[0],point[1]));plain.recycle();
                main(()->{set(view(),"focusHeader",true);set(view(),"headerSelection",0);call(view(),"updateFocus");return null;});waitMotion();
                Bitmap dense=getUiAutomation().takeScreenshot();int current=android.graphics.Color.red(dense.getPixel(point[0],point[1]));dense.recycle();
                check(current>old+15,"focus must become lighter/denser rather than black on background "+shade);
                screenshot("context-focus-"+shade);
            }
            report.put("gray_and_dark_focus_brightens","PASS");
            key(20);home();key(20);waitMotion();screenshot("bright-app-list");
            report.put("larger_application_focus","PASS").put("focus_within_cell","PASS").put("pill_buttons_48px","PASS").put("white_wallpaper_screenshots","PASS");
        }finally{
            main(()->{set(store,"image",image);set(store,"frost",frost);((Runnable)field(store,"listener")).run();return null;});
        }
    }
    private void listLayout(JSONObject report)throws Exception{
        home();waitMotion();screenshot("home-lowered-dock-no-peek");
        main(()->{Object tile=((List<?>)field(repo(),"grid")).get(0);View node=(View)((Map<?,?>)field(view(),"tileNodes")).get(field(tile,"id"));float scale=(Float)field(view(),"canvasScale"),top=(Float)field(view(),"canvasTop");
            float visibleTop=(node.getY()+node.getPivotY()-71f*node.getScaleY()-top)/scale;
            check(visibleTop>=1080f&&node.getVisibility()==View.INVISIBLE,"Home must not expose app-list edges");return null;});
        key(20);waitMotion();
        main(()->{Class<?> g=Class.forName("dev.pdmv.os.UiGeometry");check(g.getField("VISIBLE_ROWS").getInt(null)==3,"list should show three rows");
            check(g.getField("APP_LABEL_SIZE").getFloat(null)==25f&&g.getField("LABEL_BASELINE").getFloat(null)==179f,"label must retain font size and use 8px downward offset");
            float gap=g.getField("ROW_GAP").getFloat(null);check(Math.abs(gap-113.6f)<.01f,"uneven row gap");
            Object tile=((List<?>)field(repo(),"grid")).get((Integer)field(view(),"gridFocus"));View node=(View)((Map<?,?>)field(view(),"tileNodes")).get(field(tile,"id"));
            check(node.getHeight()==236,"label viewport was not expanded");
            android.graphics.Rect clip=node.getClipBounds();check(clip==null||clip.bottom>=194,"label clipped below tile");return null;});
        screenshot("three-rows-label-spacing");
        int[] sizes=(int[])main(()->{List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");int[] before={grid.size(),all.size()};
            Class<?> type=Class.forName("dev.pdmv.os.Tile");Constructor<?> create=type.getDeclaredConstructor(String.class,String.class);create.setAccessible(true);
            while(grid.size()<36){Object tile=create.newInstance("qa-layout-"+grid.size(),"Kiểm tra hàng "+grid.size());grid.add(tile);all.add(tile);}set(view(),"gridFocus",0);((View)view()).invalidate();return before;});
        try{
            for(int i=0;i<5;i++)key(20);waitMotion();
            int first=(Integer)state("gridFirstRow");float offset=(Float)main(()->field(field(view(),"gridScroll"),"value"));
            check(first==3&&Math.abs(offset-first)<.005f,"three-row long list did not snap");screenshot("three-row-scroll-last");
        }finally{
            main(()->{List grid=(List)field(repo(),"grid"),all=(List)field(repo(),"all");grid.subList(sizes[0],grid.size()).clear();all.subList(sizes[1],all.size()).clear();set(view(),"gridFocus",0);set(view(),"gridFirstRow",0);Object scroll=field(view(),"gridScroll");set(scroll,"value",0f);set(scroll,"target",0f);set(scroll,"velocity",0f);((View)view()).invalidate();return null;});
        }
        report.put("three_app_list_rows","PASS").put("equal_artwork_gaps_px",113.6).put("label_viewport_and_gap","PASS").put("home_no_app_list_peek","PASS").put("three_row_scroll_snap","PASS");
    }
    private float[] popupMotion(){
        return (float[])main(()->{
            Object v=view(),m=field(v,"menu");if(m==null)m=field(v,"closingMenu");if(m==null)return null;
            Method visual=v.getClass().getDeclaredMethod("menuVisualRect",m.getClass());visual.setAccessible(true);
            Method target=v.getClass().getDeclaredMethod("menuRect",m.getClass());target.setAccessible(true);
            android.graphics.RectF r=(android.graphics.RectF)visual.invoke(v,m),t=(android.graphics.RectF)target.invoke(v,m);
            Object spring=field(v,"menuReveal"),motion=field(m,"motion");
            return new float[]{(Float)field(spring,"value"),(Float)field(spring,"velocity"),r.centerX(),r.centerY(),r.width(),t.width(),(Float)field(m,"left"),(Float)field(m,"top"),t.centerX(),t.centerY(),(Float)field(motion,"clarity")};
        });
    }
    private void menuMorph(JSONObject report)throws Exception{
        home();key(22);waitMotion();
        JSONArray traces=new JSONArray();
        for(int location=0;location<2;location++){
            if(location==1){key(20);waitMotion();}
            long pressed=SystemClock.uptimeMillis();injectKey(new KeyEvent(pressed,pressed,KeyEvent.ACTION_DOWN,23,0));
            long limit=SystemClock.uptimeMillis()+1600;float[] first=null;
            while(first==null&&SystemClock.uptimeMillis()<limit){first=popupMotion();if(first==null)SystemClock.sleep(8);}
            check(first!=null&&first[0]<.95f&&first[4]<first[5],"held menu did not start small");
            check(first[10]<1f,"opening menu did not start soft");
            float sourceDistance=(float)Math.hypot(first[2]-first[6],first[3]-first[7]);
            float targetDistance=(float)Math.hypot(first[8]-first[6],first[9]-first[7]);
            check(sourceDistance<targetDistance,"popup did not originate at application");
            injectKey(new KeyEvent(pressed,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));
            SystemClock.sleep(850);float[] settled=popupMotion();
            check(settled!=null&&Math.abs(settled[4]-settled[5])<.05f&&settled[10]==1f,"menu did not settle sharp at full size");
            screenshot("menu-morph-"+location+"-open");
            key(4);boolean intermediate=false;limit=SystemClock.uptimeMillis()+1800;
            while(SystemClock.uptimeMillis()<limit){
                float[] f=popupMotion();if(f==null)break;
                if(f[0]>.15f&&f[0]<.7f){
                    intermediate=true;
                    check(f[4]<f[5]&&f[10]<1f,"return did not shrink and soften");
                    if(traces.length()==location){traces.put(new JSONObject().put("opening_progress",first[0]).put("return_progress",f[0]).put("return_velocity",f[1]));screenshot("menu-morph-"+location+"-return");}
                }
                SystemClock.sleep(12);
            }
            check(intermediate&&state("menu")==null&&state("closingMenu")==null,"menu return failed to finish");
        }
        key(4);waitMotion();
        main(()->{call(view(),"openTileMenu");return null;});SystemClock.sleep(65);
        main(()->{Object v=view(),spring=field(v,"menuReveal");float p=(Float)field(spring,"value"),speed=(Float)field(spring,"velocity");
            call(v,"closeMenu");check((Float)field(spring,"value")==p&&(Float)field(spring,"velocity")==speed,"close discarded popup momentum");
            call(v,"openTileMenu");check((Float)field(spring,"value")==p&&(Float)field(spring,"velocity")==speed,"reopen discarded popup momentum");return null;});
        SystemClock.sleep(850);key(4);SystemClock.sleep(850);
        check(state("closingMenu")==null,"interrupted popup leaked closing surface");
        report.put("menu_morph", "PASS: real long press in Dock/App List, source anchor, small/soft opening, full/sharp endpoint, shrinking return and momentum-preserving reversal").put("motion_samples",traces);
    }
    private void classicResources(JSONObject report)throws Exception{
        home();Object store=main(()->field(activity,"wallpapers"));
        await(()->main(()->field(store,"image"))!=null,"wallpaper was not ready");
        main(()->{
            Class<?> type=store.getClass();Constructor<?> create=type.getDeclaredConstructor(android.content.Context.class);create.setAccessible(true);
            Object second=create.newInstance(activity);
            try{
                call(second,"start");Bitmap original=(Bitmap)field(store,"image");
                check(field(second,"image")==original,"recreated Home decoded another full wallpaper");
                Bitmap[] a=(Bitmap[])field(store,"frost"),b=(Bitmap[])field(second,"frost");long reused=original.getAllocationByteCount();
                for(int i=0;i<3;i++){check(a[i]==b[i],"recreated Home allocated duplicate frost texture");reused+=a[i].getAllocationByteCount();}
                Field recent=type.getDeclaredField("recent");recent.setAccessible(true);Object cache=recent.get(null);
                check(field(cache,"image") instanceof java.lang.ref.WeakReference,"wallpaper cache retains strong image ownership");
                for(Object ref:(List<?>)field(cache,"frost"))check(ref instanceof java.lang.ref.WeakReference,"frost cache pins image memory");
                Method snapshot=cache.getClass().getDeclaredMethod("snapshot",String.class);snapshot.setAccessible(true);
                check(snapshot.invoke(cache,"different-source") == null,"changed wallpaper source did not invalidate cache");
                report.put("base_bitmap_bytes_reused",reused).put("non_owning_wallpaper_cache",true);
            }finally{call(second,"close");}
            check(!((Bitmap)field(store,"image")).isRecycled(),"closing reused store invalidated current wallpaper");
            Object v=view(),r=field(v,"returnReveal"),tile=((List<?>)field(repo(),"dock")).get(0);
            set(v,"returningTile",tile);set(v,"returnWaiting",false);set(r,"value",.48f);set(r,"velocity",-3.2f);
            Method queue=v.getClass().getDeclaredMethod("queueExternalLaunch",tile.getClass(),Runnable.class);queue.setAccessible(true);
            queue.invoke(v,tile,(Runnable)()->{});
            Object launch=field(v,"launchReveal");check((Float)field(launch,"value")==.48f&&(Float)field(launch,"velocity")==-3.2f,"classic reopening discarded position or momentum");
            call(v,"resetLaunchMotion");return null;
        });
        try{Class.forName("dev.pdmv.os.CardInertia");throw new AssertionError("new deformation model still bundled");}
        catch(ClassNotFoundException expected){}
        wallpaperState(report);
        report.put("classic_resources","PASS: old renderer, momentum-preserving reversal, bitmap identity reuse, weak references, source invalidation and safe store close");
    }
    private JSONObject returnPresentation(long started,long ended)throws Exception{
        JSONObject result=new JSONObject();String layer=null;
        for(String line:shellOutput("dumpsys SurfaceFlinger --list").split("\n"))if(line.contains("dev.pdmv.os/dev.pdmv.os.MainActivity")&&!line.contains("AppWindowToken")){layer=line.trim();break;}
        if(layer==null)return result.put("available",false);
        String trace=shellOutput("dumpsys SurfaceFlinger --latency '"+layer+"'");
        ArrayList<Long> times=new ArrayList<>(),intervals=new ArrayList<>();
        for(String line:trace.split("\n")){
            String[] values=line.trim().split("\\s+");if(values.length!=3)continue;
            try{long actual=Long.parseLong(values[1]);if(actual>=started&&actual<=ended+100000000L)times.add(actual);}catch(NumberFormatException ignored){}
        }
        Collections.sort(times);int late=0;
        for(int i=1;i<times.size();i++){long dt=times.get(i)-times.get(i-1);if(dt>0&&dt<100000000L){intervals.add(dt);if(dt>25000000L)late++;}}
        Collections.sort(intervals);result.put("available",!intervals.isEmpty()).put("intervals",intervals.size()).put("gaps_over_25ms",late);
        if(!intervals.isEmpty())result.put("p50_ms",intervals.get(intervals.size()/2)/1e6).put("p95_ms",intervals.get(Math.min(intervals.size()-1,(int)(intervals.size()*.95)))/1e6);
        return result;
    }
    private void returnMotion(JSONObject report)throws Exception{
        home();JSONArray cases=new JSONArray();
        for(String name:new String[]{"com.android.vending","com.google.android.play.games"}){
            Object tile=livePackage(name);check(tile!=null,"return-motion test app unavailable: "+name);selectTile(tile);
            final int[] visible={0},boundedTail={0};final long[] maxTailArea={0};final float[] range={2f,-1f};
            android.view.ViewTreeObserver.OnDrawListener observer=()->{
                try{Object v=view(),source=field(v,"returningTile");if(source!=null&&id(source).equals(id(tile))&&(Boolean)field(activity,"resumed")&&activity.hasWindowFocus()&&!(Boolean)field(v,"returnWaiting")){
                    float p=(Float)field(field(v,"returnReveal"),"value");visible[0]++;range[0]=Math.min(range[0],p);range[1]=Math.max(range[1],p);
                    if(p<.1f){android.graphics.Rect viewport=(android.graphics.Rect)field(v,"popupViewport");long area=(long)viewport.width()*viewport.height();maxTailArea[0]=Math.max(maxTailArea[0],area);if(area<(long)((View)v).getWidth()*((View)v).getHeight()/4)boundedTail[0]++;}
                }}catch(Exception e){throw new RuntimeException(e);}
            };
            resetSamples();
            main(()->{returnObserver=observer;activity.getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(observer);Method open=activity.getClass().getDeclaredMethod("open",tile.getClass());open.setAccessible(true);open.invoke(activity,tile);return null;});
            ParcelFileDescriptor homeKey=null;
            try{
                await(()->!(Boolean)main(()->field(activity,"resumed")),"app did not leave Home");recording=false;JSONObject launchMetrics=summary();
                long pausedFrame=(Long)state("lastFrame");SystemClock.sleep(650);
                check((Long)state("lastFrame")==pausedFrame,"launcher kept running motion frames in background");
                check((Boolean)main(()->{android.content.SharedPreferences saved=(android.content.SharedPreferences)field(activity,"returnFeedback");return id(tile).equals(saved.getString("origin_id",null));}),"source application was not saved for process recreation");
                resetSamples();
                long returnStarted=System.nanoTime();
                homeKey=getUiAutomation().executeShellCommand("input keyevent 3");
                await(()->(Boolean)main(()->field(activity,"resumed"))&&visible[0]>2,"no visible return animation after Home focus");
                await(()->main(()->field(view(),"returningTile"))==null,"return animation did not settle");
                long returnEnded=System.nanoTime();
                recording=false;JSONObject returnMetrics=summary();
                if("true".equals(options.getString("assert-bounded")))check(boundedTail[0]>3,"return still damages the full window near endpoint");
                check(visible[0]>6&&range[1]>.4f&&range[0]<-.005f,"return animation ran invisibly or skipped its trajectory: draws="+visible[0]+" min="+range[0]+" max="+range[1]);
                main(()->{Map<?,?> nodes=(Map<?,?>)field(view(),"tileNodes");View node=(View)nodes.get(id(tile));check(node!=null&&node.getVisibility()==View.VISIBLE&&node.getAlpha()>.99f,"destination app tile did not reappear");return null;});
                screenshot("return-motion-"+name+"-settled");
                cases.put(new JSONObject().put("app",name).put("visible_draws",visible[0]).put("maximum_progress",range[1]).put("minimum_progress",range[0]).put("launch_metrics",launchMetrics).put("return_metrics",returnMetrics).put("bounded_tail_frames",boundedTail[0]).put("maximum_tail_area_px",maxTailArea[0]).put("presentation",returnPresentation(returnStarted,returnEnded)));recording=false;
            }finally{
                if(homeKey!=null)homeKey.close();
                main(()->{activity.getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(observer);returnObserver=null;return null;});
            }
        }
        report.put("return_motion","PASS: visible focused-window shrink to real Dock/App List tile, saved source, intermediate frames and final tile restored").put("cases",cases);
    }
    private void deferredReturnSound(JSONObject report)throws Exception{
        home();Object sounds=main(()->field(activity,"uiSounds"));
        await(()->(Boolean)main(()->field(sounds,"ready")),"sound samples not ready");
        check((Boolean)main(()->field(sounds,"enabled")),"system sound effects disabled");
        long before=(Long)main(()->field(sounds,"lastReturn"));
        main(()->{Method active=sounds.getClass().getDeclaredMethod("setHomeActive",boolean.class);active.setAccessible(true);active.invoke(sounds,false);
            call(sounds,"returnHome");call(sounds,"returnHome");return null;});
        SystemClock.sleep(150);check((Long)main(()->field(sounds,"lastReturn"))==before,"return played before window focus");
        main(()->{Method active=sounds.getClass().getDeclaredMethod("setHomeActive",boolean.class);active.setAccessible(true);active.invoke(sounds,true);return null;});
        await(()->(Long)main(()->field(sounds,"lastReturn"))>before,"focused Home did not release queued sound");
        long played=(Long)main(()->field(sounds,"lastReturn"));SystemClock.sleep(150);
        check((Long)main(()->field(sounds,"lastReturn"))==played,"duplicate pending events replayed twice");
        int sample=(Integer)main(()->field(sounds,"returnSample"));
        main(()->{((Set<Integer>)field(sounds,"loaded")).remove(sample);call(sounds,"returnHome");return null;});
        try{
            SystemClock.sleep(150);check((Long)main(()->field(sounds,"lastReturn"))==played,"unloaded return sample played");
        }finally{main(()->{((Set<Integer>)field(sounds,"loaded")).add(sample);call(sounds,"scheduleReturn");return null;});}
        await(()->(Long)main(()->field(sounds,"lastReturn"))>played,"queued return was lost while sample unavailable");
        long loaded=(Long)main(()->field(sounds,"lastReturn"));
        try{main(()->{set(sounds,"enabled",false);call(sounds,"returnHome");return null;});}
        finally{main(()->{call(sounds,"refreshPreference");call(sounds,"scheduleReturn");return null;});}
        SystemClock.sleep(150);check((Long)main(()->field(sounds,"lastReturn"))==loaded,"muted return replayed after enabling sounds");
        main(()->{call(view(),"onReturnToHome");return null;});SystemClock.sleep(150);
        check((Long)main(()->field(sounds,"lastReturn"))==loaded,"ordinary resume replayed return sound");
        report.put("deferred_return_sound","PASS: wait for focus, coalesce repeated requests, wait for unloaded sample, respect mute and avoid duplicate resume feedback");
    }
    private void inputMenuMorph(JSONObject report)throws Exception{
        home();key(19);
        main(()->{set(view(),"headerSelection",2);call(view(),"updateFocus");return null;});waitMotion();
        long now=SystemClock.uptimeMillis();injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,23,0));
        injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,23,0));
        float[] first=null;long openedDeadline=SystemClock.uptimeMillis()+1200;
        while(first==null&&SystemClock.uptimeMillis()<openedDeadline){first=popupMotion();if(first==null)SystemClock.sleep(8);}
        final float[] opening=first;
        check(first!=null&&first[0]<.95f&&first[4]<first[5]&&first[10]<1f,"input menu did not begin small and soft");
        main(()->{Object v=view(),m=field(v,"menu");check((Integer)field(m,"sourceHeader")==2&&!(Boolean)field(m,"nearTile"),"input menu has wrong source/placement");
            Method rect=v.getClass().getDeclaredMethod("headerRect",int.class);rect.setAccessible(true);
            android.graphics.RectF button=(android.graphics.RectF)rect.invoke(v,2);
            check(Math.abs(opening[6]-button.centerX())<.01f&&Math.abs(opening[7]-button.centerY())<.01f,"input popup did not originate at input button");return null;});
        SystemClock.sleep(850);float[] settled=popupMotion();
        check(settled!=null&&Math.abs(settled[2]-960)<.05f&&Math.abs(settled[3]-540)<.05f&&settled[10]==1f,"input popup did not settle clear at screen center");
        screenshot("input-menu-centered");
        key(4);boolean shrinking=false;long deadline=SystemClock.uptimeMillis()+1600;
        while(SystemClock.uptimeMillis()<deadline){float[] f=popupMotion();if(f==null)break;
            if(f[0]>.15f&&f[0]<.7f){shrinking=true;check(f[4]<f[5],"input menu did not shrink on return");screenshot("input-menu-return");}
            SystemClock.sleep(12);
        }
        check(shrinking&&state("closingMenu")==null,"input menu return did not finish");
        check((Boolean)state("focusHeader")&&(Integer)state("headerSelection")==2,"closing input menu lost button focus");
        key(4);waitMotion();check(!(Boolean)state("focusHeader"),"Back from input pill did not return to Dock");
        report.put("input_menu_morph","PASS: real input-button press, small/soft origin, centered clear endpoint, shrinking return to input button, and Back to Dock");
    }
    private void pillBack(JSONObject report)throws Exception{
        home();key(22);key(22);waitMotion();
        int dock=(Integer)state("dockFocus");
        for(int selected:new int[]{0,2,3}){
            key(19);
            main(()->{set(view(),"headerSelection",selected);call(view(),"updateFocus");return null;});
            waitMotion();check((Boolean)state("focusHeader"),"pill did not receive focus");
            key(4);waitMotion();
            check(!(Boolean)state("focusHeader")&&(Integer)state("page")==0,"Back did not return pill focus to Dock");
            check((Integer)state("dockFocus")==dock,"Back changed remembered Dock selection");
            main(()->{Object v=view();Object focus=call(field(v,"tileFocus"),"selected");
                check(focus==field(((List<?>)field(repo(),"dock")).get(dock),"scale"),"Dock tile did not receive animated focus");
                check(!(Boolean)call(field(v,"edgeX"),"busy")&&!(Boolean)call(field(v,"edgeY"),"busy"),"Back retained boundary force");return null;});
        }
        key(4);waitMotion();
        check((Integer)state("page")==0&&!(Boolean)state("focusHeader"),"Dock Back left Home");
        report.put("pill_back","PASS: Wi-Fi, input and settings return to remembered Dock tile; Dock Back stays Home");
    }
    private void clockBalance(JSONObject report)throws Exception{
        home();waitMotion();
        for(String value:new String[]{"11:11","20:08","17:18"})for(float focus:new float[]{0f,1f}){
            main(()->{Bitmap bitmap=Bitmap.createBitmap(512,160,Bitmap.Config.ARGB_8888);float left=100f,right=400f-2.88f*focus;
                Method draw=view().getClass().getDeclaredMethod("drawClockTime",android.graphics.Canvas.class,String.class,float.class,float.class);draw.setAccessible(true);draw.invoke(view(),new android.graphics.Canvas(bitmap),value,left,right);
                int first=512,last=-1;for(int y=50;y<115;y++)for(int x=0;x<512;x++)if(android.graphics.Color.alpha(bitmap.getPixel(x,y))>64){first=Math.min(first,x);last=Math.max(last,x);}
                bitmap.recycle();check(last>=first&&Math.abs((first-left)-(right-last-1))<=2f,"clock ink margins are unequal for "+value);return null;});
        }
        main(()->{float width=(Float)call(view(),"desiredClockWidth");android.graphics.Rect bounds=(android.graphics.Rect)field(view(),"clockGlyphBounds");check(Math.abs(width-bounds.width()-45f)<.01f,"clock excess right spacing remains");return null;});
        screenshot("clock-balanced-normal");key(19);main(()->{set(view(),"headerSelection",3);call(view(),"updateFocus");return null;});waitMotion();screenshot("clock-balanced-settings-focus");
        report.put("visible_digit_margins_equal","PASS").put("normal_and_focus_geometry","PASS").put("clock_right_spacing_reduced","PASS");
    }
    private String sha(File file)throws Exception{
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private String replaceWallpaper(Object store,File source,String hash)throws Exception{
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);String[] error={null};
        main(()->{Method m=store.getClass().getDeclaredMethod("replace",File.class,String.class,java.util.function.Consumer.class);m.setAccessible(true);m.invoke(store,source,hash,(java.util.function.Consumer<String>)(value->{error[0]=value;done.countDown();}));return null;});
        check(done.await(60,java.util.concurrent.TimeUnit.SECONDS),"wallpaper replacement did not complete");return error[0];
    }
    private void wallpaperFeatures(JSONObject report)throws Exception{
        Object store=main(()->field(activity,"wallpapers"));
        long until=SystemClock.uptimeMillis()+30000;
        while(main(()->field(store,"image"))==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
        Bitmap original=(Bitmap)main(()->field(store,"image"));check(original!=null,"wallpaper did not load");
        Object[] saved=(Object[])main(()->new Object[]{field(store,"frost"),field(store,"transitions"),field(store,"transitionSource"),field(store,"transitionFrost"),field(store,"listener")});
        File persisted=(File)main(()->call(store,"currentFile"));byte[] previous=persisted.isFile()?java.nio.file.Files.readAllBytes(persisted.toPath()):null;
        int[] published={0};
        main(()->{set(store,"listener",(Runnable)()->{try{
            Bitmap image=(Bitmap)field(store,"image");Bitmap[] frames=(Bitmap[])field(store,"transitions"),frost=(Bitmap[])field(store,"frost");
            check(field(store,"transitionSource")==image&&field(store,"transitionFrost")==frost[2],"mixed wallpaper cohort published");
            check(frames.length==16,"blur frames missing");for(Bitmap b:frames)check(b!=null&&!b.isRecycled(),"invalid blur frame");
            published[0]++;((Runnable)saved[4]).run();
        }catch(Exception failure){throw new RuntimeException(failure);}});return null;});
        try{
            File image=new File(getTargetContext().getCacheDir(),"wallpaper-test.jpg");
            Bitmap portrait=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888);android.graphics.Canvas c=new android.graphics.Canvas(portrait);
            c.drawColor(android.graphics.Color.GREEN);android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(android.graphics.Color.RED);c.drawRect(0,0,360,320,paint);
            try(FileOutputStream out=new FileOutputStream(image)){portrait.compress(Bitmap.CompressFormat.JPEG,95,out);}portrait.recycle();
            android.media.ExifInterface exif=new android.media.ExifInterface(image.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
            String expected=sha(image);check(replaceWallpaper(store,image,expected)==null,"valid JPEG failed");
            Bitmap loaded=(Bitmap)main(()->field(store,"image"));int left=loaded.getPixel(loaded.getWidth()/4,loaded.getHeight()/2),right=loaded.getPixel(loaded.getWidth()*3/4,loaded.getHeight()/2);
            check(android.graphics.Color.green(left)>180&&android.graphics.Color.red(right)>180,"EXIF rotation or crop incorrect");
            check(expected.equals(sha(persisted)),"custom wallpaper was not persisted intact");screenshot("wallpaper-imported-exif");
            File bad=new File(getTargetContext().getCacheDir(),"wallpaper-bad.bin");java.nio.file.Files.write(bad.toPath(),new byte[]{1,2,3,4});
            check(replaceWallpaper(store,bad,sha(bad))!=null,"invalid image accepted");
            check(main(()->field(store,"image"))==loaded&&expected.equals(sha(persisted)),"invalid upload changed active wallpaper");
            java.nio.file.Files.write(bad.toPath(),new byte[]{5,6,7});check(replaceWallpaper(store,bad,"0000000000000000000000000000000000000000000000000000000000000000")!=null,"bad transfer hash accepted");
            check(main(()->field(store,"image"))==loaded&&published[0]==1,"failed upload published a cohort");
            report.put("wallpaper_exif_orientation","PASS").put("wallpaper_atomic_cohort","PASS").put("wallpaper_persistence","PASS").put("invalid_wallpaper_keeps_previous","PASS").put("wallpaper_hash_validation","PASS");
        }finally{
            if(previous==null)persisted.delete();else java.nio.file.Files.write(persisted.toPath(),previous);
            main(()->{set(store,"image",original);set(store,"frost",saved[0]);set(store,"transitions",saved[1]);set(store,"transitionSource",saved[2]);set(store,"transitionFrost",saved[3]);set(store,"listener",saved[4]);((Runnable)saved[4]).run();return null;});
        }
    }
    private void writeReport(JSONObject report)throws Exception{
        try(FileWriter writer=new FileWriter(new File(output,"report.json"))){writer.write(report.toString(2));}
    }
    private String shellOutput(String command)throws Exception{
        ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");
        }
    }
    private void toastDecorationParity(JSONObject report)throws Exception{
        double[] result=(double[])main(()->{
            Object glass=field(view(),"glassMaterials");
            Method original=glass.getClass().getDeclaredMethod("decoration",float.class,float.class,float.class,int.class,boolean.class);original.setAccessible(true);
            Method draw=glass.getClass().getDeclaredMethod("drawStaticDecoration",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class);draw.setAccessible(true);
            long total=0,count=0;int max=0;
            for(int width:new int[]{320,744,1100}){
                Bitmap reference=(Bitmap)original.invoke(glass,(float)width,60f,30f,4,true);
                Bitmap actual=Bitmap.createBitmap(width,60,Bitmap.Config.ARGB_8888);draw.invoke(glass,new android.graphics.Canvas(actual),0f,0f,(float)width,60f,30f,4);
                for(int y=0;y<60;y++)for(int x=0;x<width;x++){
                    int difference=Math.abs(android.graphics.Color.alpha(reference.getPixel(x,y))-android.graphics.Color.alpha(actual.getPixel(x,y)));
                    total+=difference;count++;max=Math.max(max,difference);
                }
                actual.recycle();
            }
            return new double[]{total/(double)count,max};
        });
        check(result[0]<.15&&result[1]<=12,"toast cached caps changed the original rim: "+Arrays.toString(result));
        report.put("toast_rim_mean_alpha_error",result[0]).put("toast_rim_max_alpha_error",result[1]);
    }
    private void materialAudit(JSONObject report)throws Exception{
        main(()->{call(view(),"showHomeFromSystem");return null;});SystemClock.sleep(700);
        main(()->{Object v=view();call(v,"openTileMenu");check(field(v,"menu")!=null,"menu not created immediately");check((Float)field(field(v,"menuReveal"),"target")==1f,"menu waits for asynchronous image");return null;});
        SystemClock.sleep(650);
        main(()->{Object m=field(view(),"menu");Bitmap b=(Bitmap)field(m,"backdrop");check(b!=null&&b.getWidth()<480,"menu backdrop is not regional");return null;});screenshot("material-menu-regional");
        main(()->{Object v=view();Method show=v.getClass().getDeclaredMethod("showDialog",String.class,String.class,String.class,Runnable.class);show.setAccessible(true);show.invoke(v,"Kiểm tra nền kính","Hộp thoại lấy vùng nền hiện tại.","Đóng",(Runnable)()->{});check(field(v,"dialogAction")!=null&&(Float)field(field(v,"dialogReveal"),"target")==1f,"dialog waits for asynchronous image");return null;});
        SystemClock.sleep(650);screenshot("material-dialog-regional");
        main(()->{
            Object v=view(),glass=field(v,"glassMaterials");Bitmap original=(Bitmap)field(glass,"menuBackdrop");android.graphics.RectF originalArea=new android.graphics.RectF((android.graphics.RectF)field(glass,"menuBackdropArea"));
            Bitmap source=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888);android.graphics.Canvas stripes=new android.graphics.Canvas(source);stripes.drawColor(0xffff0000);android.graphics.Paint p=new android.graphics.Paint();p.setColor(0xff00ff00);stripes.drawRect(50,0,100,50,p);
            Bitmap probe=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);android.graphics.Canvas c=new android.graphics.Canvas(probe);
            Method setBackdrop=glass.getClass().getDeclaredMethod("setMenuBackdrop",Bitmap.class,android.graphics.RectF.class);setBackdrop.setAccessible(true);
            Method begin=glass.getClass().getDeclaredMethod("beginSurface",android.graphics.Canvas.class);begin.setAccessible(true);
            Method draw=glass.getClass().getDeclaredMethod("drawBackdrop",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class,float.class,float.class,float.class);draw.setAccessible(true);
            try{
                setBackdrop.invoke(glass,source,new android.graphics.RectF(500,300,900,500));begin.invoke(glass,c);draw.invoke(glass,c,500f,300f,400f,200f,80f,3,0f,0f,1f);
                check(android.graphics.Color.red(probe.getPixel(600,400))>240&&android.graphics.Color.green(probe.getPixel(800,400))>240,"regional material coordinates do not match screen");
            }finally{setBackdrop.invoke(glass,original,originalArea);source.recycle();probe.recycle();}
            call(v,"dismissDialog");call(v,"closeMenu");return null;
        });SystemClock.sleep(650);
        report.put("menu_immediate_target",true).put("dialog_immediate_target",true).put("regional_material_coordinates",true).put("matching_wallpaper_frost",main(()->{Object store=field(activity,"wallpapers");Bitmap[] frost=(Bitmap[])field(store,"frost");return field(store,"image")!=null&&frost[0]!=null&&frost[1]!=null&&frost[2]!=null;}));
    }
    private Object livePackage(String name){
        return main(()->{Object r=repo();Method lookup=r.getClass().getDeclaredMethod("lookup",String.class);lookup.setAccessible(true);Object tile=lookup.invoke(r,"app:"+name);return tile==null||(Boolean)field(tile,"retired")?null:tile;});
    }
    private void await(java.util.concurrent.Callable<Boolean> condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+8000;while(!condition.call()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(30);check(condition.call(),message);
    }
    private int externalSession(String name,long size)throws Exception{
        String result=shellOutput("pm install-create -r --pkg "+name+" -S "+size);
        java.util.regex.Matcher id=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(result);check(id.find(),"session creation failed: "+result);return Integer.parseInt(id.group(1));
    }
    private void fixturePage(Object tile,boolean dock)throws Exception{
        main(()->{Object v=view();set(v,"focusHeader",false);set(v,"focusDrawerDock",dock);set(v,"page",dock?0:1);
            Object drawer=field(v,"drawer");set(drawer,"value",dock?0f:1f);set(drawer,"target",dock?0f:1f);set(drawer,"velocity",0f);
            List<?> entries=(List<?>)field(repo(),dock?"dock":"grid");int index=entries.indexOf(tile);check(index>=0,"fixture not in target region");
            set(v,dock?"dockFocus":"gridFocus",index);int row=dock?0:Math.max(0,index/6-2);set(v,"gridFirstRow",row);
            Object scroll=field(v,"gridScroll");set(scroll,"value",(float)row);set(scroll,"target",(float)row);set(scroll,"velocity",0f);call(v,"updateFocus");return null;
        });SystemClock.sleep(650);
    }
    private JSONObject removeFixture(Object tile,String label)throws Exception{
        Object neighbor=main(()->{List<?> entries=(List<?>)field(repo(),((List<?>)field(repo(),"dock")).contains(tile)?"dock":"grid");int index=entries.indexOf(tile);return index+1<entries.size()?entries.get(index+1):null;});
        ParcelFileDescriptor command=getUiAutomation().executeShellCommand("pm uninstall dev.pdmv.os.qa.fixture");
        boolean intermediate=false,compacting=false;float smallest=99f;
        long deadline=SystemClock.uptimeMillis()+8000;
        while(SystemClock.uptimeMillis()<deadline){
            float[] sample=(float[])main(()->{
                boolean removed=(Boolean)field(tile,"retired");float presence=(Float)field(field(tile,"presence"),"value");
                View node=(View)((Map<?,?>)field(view(),"tileNodes")).get(field(tile,"id"));
                if(removed&&node!=null&&presence>.01f&&presence<.99f){
                    float scale=(Float)field(view(),"canvasScale"),left=(Float)field(view(),"canvasLeft"),top=(Float)field(view(),"canvasTop");
                    check(Math.abs(node.getX()+node.getPivotX()-(left+(Float)field(tile,"exitX")*scale))<.03f,"uninstall shrink pivot moved horizontally");
                    check(Math.abs(node.getY()+node.getPivotY()-(top+(Float)field(tile,"exitY")*scale))<.03f,"uninstall shrink pivot moved vertically");
                    float speed=neighbor==null?0f:Math.abs((Float)field(field(neighbor,"offsetX"),"velocity"))+Math.abs((Float)field(field(neighbor,"offsetY"),"velocity"));
                    return new float[]{1f,node.getScaleX()/scale,speed};
                }
                return new float[]{removed&&node==null?2f:0f,99f,0f};
            });
            if(sample[0]==1f){intermediate=true;smallest=Math.min(smallest,sample[1]);compacting=compacting||sample[2]>1f;}
            if(sample[0]==2f)break;SystemClock.sleep(10);
        }
        String result;
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(command);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[1024];int n;while((n=in.read(b))>0)out.write(b,0,n);result=out.toString("UTF-8");
        }
        check(result.contains("Success"),"fixture uninstall failed: "+result);
        check(intermediate&&smallest<.5f,"no real centered shrink after uninstall: scale="+smallest+" retired="+main(()->field(tile,"retired"))+" presence="+main(()->field(field(tile,"presence"),"value"))+" resumed="+main(()->field(activity,"resumed")));
        await(()->livePackage("dev.pdmv.os.qa.fixture")==null,"uninstalled fixture remained live");SystemClock.sleep(600);
        check((Boolean)main(()->!((Map<?,?>)field(view(),"tileNodes")).containsKey(field(tile,"id"))),"retired render node leaked");
        check(neighbor==null||compacting,"neighbor did not compact concurrently with uninstall shrink");
        screenshot("uninstall-"+label+"-compacted");return new JSONObject().put("intermediate_shrink",intermediate).put("minimum_observed_scale",smallest).put("fixed_center",true).put("simultaneous_compaction",compacting);
    }
    private void packageLifecycle(JSONObject report)throws Exception{
        final String name="dev.pdmv.os.qa.fixture";
        try{getTargetContext().getPackageManager().getPackageInfo(name,0);throw new AssertionError("fixture already installed; do not overwrite unknown data");}
        catch(android.content.pm.PackageManager.NameNotFoundException expected){}
        check(livePackage(name)==null,"fixture already present; do not overwrite unknown installation");
        android.content.pm.PackageInstaller pi=getTargetContext().getPackageManager().getPackageInstaller();
        int own=-1,external=-1;android.content.pm.PackageInstaller.Session staged=null;
        try{
            byte[] payload;
            ParcelFileDescriptor source=getUiAutomation().executeShellCommand("cat /data/local/tmp/pdmvos-fixture-1.apk");
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(source);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);payload=out.toByteArray();
            }
            android.content.pm.PackageInstaller.SessionParams params=new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(name);params.setAppLabel("Kiểm tra tiến trình");params.setSize(payload.length);
            Bitmap icon=Bitmap.createBitmap(128,72,Bitmap.Config.ARGB_8888);icon.eraseColor(0xff1455aa);params.setAppIcon(icon);
            own=pi.createSession(params);staged=pi.openSession(own);
            try(OutputStream out=staged.openWrite("base.apk",0,payload.length)){
                int half=payload.length/2;out.write(payload,0,half);staged.fsync(out);staged.setStagingProgress(half/(float)payload.length);
                await(()->livePackage(name)!=null,"new real installer metadata did not create placeholder");
                Object pending=livePackage(name);fixturePage(pending,false);
                int id=own;float actual=pi.getSessionInfo(id).getProgress();
                await(()->(Float)main(()->field(pending,"installProgress"))==actual,"placeholder progress differs from system progress");
                screenshot("install-new-app-partial");report.put("new_install_system_progress",actual);
                out.write(payload,half,payload.length-half);staged.fsync(out);staged.setStagingProgress(1f);
                SystemClock.sleep(300);
            }
            Object installing=livePackage(name);
            check(shellOutput("pm install -r /data/local/tmp/pdmvos-fixture-1.apk").contains("Success"),"real fixture installation failed");
            await(()->(Boolean)main(()->{Object r=repo();Method m=r.getClass().getDeclaredMethod("lookup",String.class);m.setAccessible(true);Object t=m.invoke(r,"app:"+name);return t!=null&&!(Boolean)field(t,"retired")&&!(Boolean)field(t,"installPlaceholder");}),"placeholder did not merge into actual installed app");
            check(livePackage(name)==installing,"new app installation replaced the stable placeholder node");
            staged.abandon();staged.close();staged=null;own=-1;
            await(()->(Integer)main(()->field(installing,"installSessionId"))<0,"completed install retained an overlay");
            params.setAppPackageName("dev.pdmv.os.qa.cancelprobe");params.setAppLabel("Kiểm tra hủy");own=pi.createSession(params);
            await(()->livePackage("dev.pdmv.os.qa.cancelprobe")!=null,"cancel test session not visible");
            pi.abandonSession(own);own=-1;await(()->livePackage("dev.pdmv.os.qa.cancelprobe")==null,"canceled session left a phantom app");SystemClock.sleep(800);
            Object tile=livePackage(name);report.put("new_install_stable_identity",true);
            main(()->{Object v=view(),r=repo();Method replace=r.getClass().getDeclaredMethod("replaceAt",int.class,tile.getClass());replace.setAccessible(true);Method animate=v.getClass().getDeclaredMethod("animateCatalogChange",Runnable.class);animate.setAccessible(true);animate.invoke(v,(Runnable)()->{try{replace.invoke(r,2,tile);}catch(Exception e){throw new RuntimeException(e);}});return null;});
            fixturePage(tile,true);SystemClock.sleep(600);
            long size2=Long.parseLong(options.getString("size2"));external=externalSession(name,size2);int update=external;
            await(()->(Integer)main(()->field(tile,"installSessionId"))==update,"external update session not shown on dock");
            String wrote=shellOutput("pm install-write -S "+size2+" "+update+" base /data/local/tmp/pdmvos-fixture-2.apk");check(wrote.contains("Success"),"real update staging failed");
            float systemProgress=pi.getSessionInfo(update).getProgress();
            await(()->(Float)main(()->field(tile,"installProgress"))==systemProgress,"dock progress differs from system");
            await(()->(Boolean)main(()->Math.abs((Float)field(field(tile,"installSweep"),"value")-systemProgress)<.001f&&(Float)field(field(tile,"installReveal"),"value")>.999f),"animated sweep did not settle on actual system progress");
            screenshot("update-dock-real-progress");report.put("update_system_progress",systemProgress);
            check(shellOutput("pm install-abandon "+update).contains("Success"),"could not cancel real update session");external=-1;
            await(()->(Integer)main(()->field(tile,"installSessionId"))<0,"canceled update retained an overlay");
            check(livePackage(name)==tile&&getTargetContext().getPackageManager().getPackageInfo(name,0).versionCode==1,"canceling update removed original app");
            report.put("canceled_update_keeps_original",true);
            external=externalSession(name,size2);int committed=external;
            check(shellOutput("pm install-write -S "+size2+" "+committed+" base /data/local/tmp/pdmvos-fixture-2.apk").contains("Success"),"second real update staging failed");
            await(()->(Integer)main(()->field(tile,"installSessionId"))==committed,"replacement update not observed");
            main(()->{Object v=view();set(v,"page",1);set(v,"focusDrawerDock",true);Object d=field(v,"drawer");set(d,"value",1f);set(d,"target",1f);set(d,"velocity",0f);call(v,"updateFocus");return null;});SystemClock.sleep(650);
            screenshot("update-app-list-real-progress");
            String[] commitResult={null};Throwable[] commitError={null};
            Thread commitThread=new Thread(()->{try{commitResult[0]=shellOutput("pm install-commit "+committed);}catch(Throwable e){commitError[0]=e;}});commitThread.start();
            boolean sawCompletion=false,sawFade=false;long visualDeadline=SystemClock.uptimeMillis()+12000;
            while(SystemClock.uptimeMillis()<visualDeadline){
                float[] visual=(float[])main(()->new float[]{(Float)field(field(tile,"installSweep"),"value"),(Float)field(field(tile,"installReveal"),"value"),(Boolean)field(tile,"installCompleting")?1f:0f});
                if(visual[2]>0||visual[0]>.999f)sawCompletion=true;
                if(sawCompletion&&visual[1]>.01f&&visual[1]<.99f)sawFade=true;
                if(sawCompletion&&visual[1]<.001f&&!commitThread.isAlive())break;
                SystemClock.sleep(16);
            }
            commitThread.join(1000);check(commitError[0]==null&&commitResult[0]!=null&&commitResult[0].contains("Success"),"actual update commit failed");external=-1;
            check(sawCompletion&&sawFade,"successful update did not animate completion and reveal");
            check((Float)main(()->field(field(tile,"installReveal"),"value"))<.001f,"completion overlay did not disappear");
            report.put("successful_completion_fills_then_fades",true);
            await(()->(Integer)main(()->field(tile,"installSessionId"))<0,"completed update left progress overlay");
            check(getTargetContext().getPackageManager().getPackageInfo(name,0).versionCode==2,"fixture version did not update");
            check(livePackage(name)==tile&&!(Boolean)main(()->field(tile,"retired")),"update replaced or retired the stable app node");
            report.put("uninstall_dock",removeFixture(tile,"dock"));
            check(shellOutput("pm install -r /data/local/tmp/pdmvos-fixture-1.apk").contains("Success"),"grid fixture installation failed");
            await(()->livePackage(name)!=null,"grid fixture not observed");Object gridTile=livePackage(name);
            main(()->{Object r=repo(),v=view();List<?> grid=(List<?>)field(r,"grid");int from=grid.indexOf(gridTile);Method move=r.getClass().getDeclaredMethod("moveGrid",int.class,int.class);move.setAccessible(true);Method animate=v.getClass().getDeclaredMethod("animateCatalogChange",Runnable.class);animate.setAccessible(true);animate.invoke(v,(Runnable)()->{try{move.invoke(r,from,1);}catch(Exception e){throw new RuntimeException(e);}});return null;});
            fixturePage(gridTile,false);
            shellOutput("am start -n dev.pdmv.os.qa.fixture/.MainActivity");
            await(()->!(Boolean)main(()->field(activity,"resumed")),"fixture did not take launcher to background");
            report.put("uninstall_grid",removeFixture(gridTile,"grid"));
            check((Boolean)main(()->field(activity,"resumed")),"launcher did not resume after external app removal");
            report.put("background_uninstall_plays_on_return",true);
            report.put("actual_update_committed",true).put("canceled_session_cleanup",true);
        }finally{
            if(staged!=null){try{staged.abandon();}catch(Exception ignored){}staged.close();own=-1;}
            if(own>=0)pi.abandonSession(own);
            if(external>=0)shellOutput("pm install-abandon "+external);
            try{getTargetContext().getPackageManager().getPackageInfo(name,0);shellOutput("pm uninstall "+name);}catch(android.content.pm.PackageManager.NameNotFoundException gone){}
        }
    }
    private void installCapability(JSONObject report)throws Exception{
        android.content.pm.PackageInstaller installer=getTargetContext().getPackageManager().getPackageInstaller();
        java.util.concurrent.atomic.AtomicInteger events=new java.util.concurrent.atomic.AtomicInteger();
        android.content.pm.PackageInstaller.SessionCallback callback=new android.content.pm.PackageInstaller.SessionCallback(){
            public void onCreated(int id){events.incrementAndGet();}public void onBadgingChanged(int id){}public void onActiveChanged(int id,boolean active){}
            public void onProgressChanged(int id,float progress){events.incrementAndGet();}public void onFinished(int id,boolean success){}
        };
        installer.registerSessionCallback(callback,new Handler(Looper.getMainLooper()));int session=-1;
        try{
            String created=shellOutput("pm install-create -r --pkg dev.pdmv.os.tests -S "+options.getString("size"));
            java.util.regex.Matcher id=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(created);
            check(id.find(),"cannot create external session: "+created);session=Integer.parseInt(id.group(1));
            android.content.pm.PackageInstaller.SessionInfo before=installer.getSessionInfo(session);
            check(before!=null,"launcher cannot read external installer session");
            String wrote=shellOutput("pm install-write -S "+options.getString("size")+" "+session+" base /data/local/tmp/pdmvos-session-probe.apk");
            check(wrote.contains("Success"),"staging failed: "+wrote);SystemClock.sleep(400);
            android.content.pm.PackageInstaller.SessionInfo after=installer.getSessionInfo(session);
            check(after!=null&&after.getProgress()>before.getProgress(),"external session has no measurable progress");
            check(events.get()>0,"no external session callbacks delivered");
            report.put("external_installer_session",new JSONObject().put("installer",before.getInstallerPackageName()).put("package",before.getAppPackageName()).put("before",before.getProgress()).put("after",after.getProgress()).put("callbacks",events.get()));
        }finally{if(session>=0)shellOutput("pm install-abandon "+session);installer.unregisterSessionCallback(callback);}
    }
    private void arrangeRing(JSONObject report)throws Exception{
        // This mode can follow boundary tests with focus at the last column.
        // Start at an occupied interior slot so Right actually reorders a tile.
        main(()->{call(view(),"showHomeFromSystem");set(view(),"dockFocus",0);set(view(),"focusHeader",false);call(view(),"updateFocus");return null;});SystemClock.sleep(1000);
        main(()->{Object v=view();Method start=v.getClass().getDeclaredMethod("startMove",boolean.class);start.setAccessible(true);start.invoke(v,true);return null;});SystemClock.sleep(900);
        float min=99,max=-99;boolean captured=false;
        for(int i=0;i<45;i++){
            float angle=(Float)main(()->{
                Object v=view(),tile=((List<?>)field(repo(),"dock")).get((Integer)field(v,"dockFocus"));
                View node=(View)((Map<?,?>)field(v,"tileNodes")).get(field(tile,"id"));
                Bitmap image=(Bitmap)field(tile,"image");
                if(image!=null){check(image.getWidth()==508&&image.getHeight()==288,"supersampled gutter missing");check(android.graphics.Color.alpha(image.getPixel(0,144))==0,"texture gutter is not transparent");check(image.hasMipMap(),"mipmap filtering missing");}
                float x=(Float)field(v,"canvasLeft"),scale=(Float)field(v,"canvasScale");
                Class<?> g=Class.forName("dev.pdmv.os.UiGeometry");Method center=g.getDeclaredMethod("centerX",int.class);
                float expected=(Float)center.invoke(null,(Integer)field(v,"dockFocus"));
                check(Math.abs(node.getX()+node.getPivotX()-(x+expected*scale))<.02f,"rotation moved the tile pivot");
                return node.getRotation();
            });
            min=Math.min(min,angle);max=Math.max(max,angle);check(Math.abs(angle)<2f,"ring amplitude excessive");
            if(!captured&&angle>.65f){screenshot("arrange-ring");captured=true;}SystemClock.sleep(20);
        }
        check(min<-.3f&&max>.3f,"ring is not visibly oscillating");
        Object selected=main(()->((List<?>)field(repo(),"dock")).get((Integer)field(view(),"dockFocus")));
        key(22);float peakLean=0f;
        for(int i=0;i<20;i++){
            float lean=(Float)main(()->field(field(selected,"tilt"),"value"));peakLean=Math.min(peakLean,lean);SystemClock.sleep(20);
        }
        check(peakLean<-.3f,"moving app has no velocity-driven inertial lean");
        main(()->call(view(),"stopMove"));SystemClock.sleep(2000);
        main(()->{for(Object tile:(List<?>)field(repo(),"all")){check(Math.abs((Float)field(field(tile,"ring"),"value"))<.002f,"ring did not settle after arrange mode");check(Math.abs((Float)field(field(tile,"tilt"),"value"))<.002f,"travel lean did not settle");}return null;});
        report.put("inertial_lean_peak_degrees",peakLean);
        report.put("arrange_ring",new JSONObject().put("min_degrees",min).put("max_degrees",max).put("stable_pivot",true).put("transparent_texture_gutter",true).put("returns_to_rest",true));
    }
    private void rimProfile(JSONObject report)throws Exception{
        JSONArray results=new JSONArray();
        main(()->{
            Object glass=field(view(),"glassMaterials");
            Method rim=glass.getClass().getDeclaredMethod("drawRim",android.graphics.Canvas.class,float.class,float.class,float.class,float.class,float.class,int.class);rim.setAccessible(true);
            Bitmap probe=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
            try{
                for(float[] box:new float[][]{{132,838,1656,190,56,0},{1570,53,130,70,35,2},{60,448,440,400,32,3}}){
                    int min=Integer.MAX_VALUE,max=0,sideMax=0;
                    for(int frame=0;frame<21;frame++){
                        probe.eraseColor(0);android.graphics.Canvas c=new android.graphics.Canvas(probe);
                        c.scale(1f,1f+.025f*frame/20f,box[0]+box[2]/2,box[1]+box[3]);
                        rim.invoke(glass,c,box[0],box[1],box[2],box[3],box[4],(int)box[5]);
                        int energy=0,x=(int)(box[0]+box[2]/2);
                        int top=(int)(box[1]-box[3]*.025f*frame/20f);
                        for(int y=top-3;y<top+5;y++)energy+=android.graphics.Color.alpha(probe.getPixel(x,y));
                        min=Math.min(min,energy);max=Math.max(max,energy);
                        int middle=Math.round(box[1]+box[3]/2-box[3]*.0125f*frame/20f);
                        for(int xx=(int)box[0]-1;xx<box[0]+3;xx++)sideMax=Math.max(sideMax,android.graphics.Color.alpha(probe.getPixel(xx,middle)));
                    }
                    check(min>35 && max<min*1.2f,"rim energy unstable during vertical stretch: "+min+".."+max);
                    check(sideMax<12,"middle side rim brightened into a full outline: "+sideMax);
                    results.put(new JSONObject().put("kind",(int)box[5]).put("min_top_alpha_sum",min).put("max_top_alpha_sum",max).put("max_side_alpha",sideMax));
                }
            }finally{probe.recycle();}
            return null;
        });
        report.put("rim_profile",results);
    }
    private void visualCompare(JSONObject report)throws Exception{
        String phase=options.getString("phase","before");
        Object store=main(()->field(activity,"wallpapers"));
        long ready=SystemClock.uptimeMillis()+90000;
        while(main(()->field(store,"image"))==null&&SystemClock.uptimeMillis()<ready)SystemClock.sleep(100);
        check(main(()->field(store,"image"))!=null,"wallpaper not ready");
        String clock=(String)state("clockCache");
        try{
            main(()->{Object v=view();((Handler)field(v,"handler")).removeCallbacks((Runnable)field(v,"clockTick"));set(v,"clockCache","13:30  CN, 25 thg 10");return null;});
            main(()->{Object v=view();call(v,"cancelHeldInput");call(v,"closeMenu");call(v,"exitDrawer");set(v,"dockFocus",0);set(v,"focusHeader",false);call(v,"updateFocus");return null;});
            SystemClock.sleep(2500);
            if("menu".equals(options.getString("only"))){
                main(()->call(view(),"openTileMenu"));SystemClock.sleep(1500);screenshot(phase+"-menu");
                long held=SystemClock.uptimeMillis();injectKey(new KeyEvent(held,held,KeyEvent.ACTION_DOWN,19,0));SystemClock.sleep(900);screenshot(phase+"-menu-stretch");
                injectKey(new KeyEvent(held,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,19,0));
                report.put("visual_compare",phase+": menu captured from a fully settled underlying scene");return;
            }
            screenshot(phase+"-home");
            key(19);SystemClock.sleep(900);screenshot(phase+"-pill");
            long now=SystemClock.uptimeMillis();injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,19,0));SystemClock.sleep(900);
            screenshot(phase+"-pill-stretch");
            injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,19,0));SystemClock.sleep(1700);
            key(20);key(82);SystemClock.sleep(1500);screenshot(phase+"-menu");
            now=SystemClock.uptimeMillis();injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,19,0));SystemClock.sleep(900);
            screenshot(phase+"-menu-stretch");
            injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,19,0));SystemClock.sleep(1700);key(4);
            report.put("visual_compare",phase+": home, pill, menu and vertical stretch screenshots captured");
        }finally{
            main(()->{Object v=view();set(v,"clockCache",clock);((Handler)field(v,"handler")).postDelayed((Runnable)field(v,"clockTick"),1000);return null;});
        }
    }
    private long rimEnergy(Bitmap image,int top){
        long sum=0;float scale=image.getWidth()/1920f;
        for(int x=300;x<1600;x+=2){
            int peak=0;
            for(int dy=0;dy<5;dy++){
                int a=image.getPixel(Math.round(x*scale),Math.round((top+dy)*scale));
                int b=image.getPixel(Math.round(x*scale),Math.round((top-6)*scale));
                peak=Math.max(peak,android.graphics.Color.red(a)+android.graphics.Color.green(a)+android.graphics.Color.blue(a)
                    -android.graphics.Color.red(b)-android.graphics.Color.green(b)-android.graphics.Color.blue(b));
            }
            sum+=peak;
        }
        return sum;
    }
    private void rimInvestigation(JSONObject report)throws Exception{
        // Cold startup prepares all wallpaper blur levels asynchronously. Comparing
        // its gradient fallback to the newly published photo is not a rim test.
        Object store=main(()->field(activity,"wallpapers"));
        long readyDeadline=SystemClock.uptimeMillis()+90000;
        while(main(()->field(store,"image"))==null && SystemClock.uptimeMillis()<readyDeadline)SystemClock.sleep(100);
        check(main(()->field(store,"image"))!=null,"wallpaper was not ready for rim comparison");
        SystemClock.sleep(700);
        home();SystemClock.sleep(1000);screenshot("rim-0-initial");
        JSONArray runs=new JSONArray();report.put("rim_investigation",runs);
        for(int i=0;i<3;i++){
            key(20);SystemClock.sleep(1500);screenshot("rim-"+i+"-drawer");
            if("rim-regression".equals(options.getString("mode")))
                check((Float)state("sceneDrawnDrawer")==1f,"drawer endpoint not rendered before caching");
            key(4);SystemClock.sleep(1500);screenshot("rim-"+i+"-returned");
            Bitmap before=getUiAutomation().takeScreenshot();
            long initial=rimEnergy(before,838);before.recycle();
            int wallpaperBefore=System.identityHashCode(main(()->field(store,"image")));
            JSONObject previous=new JSONObject().put("draws",state("sceneDrawCount")).put("rendered_drawer",state("sceneDrawnDrawer")).put("rendered_blur",state("sceneDrawnBlur"));
            main(()->{Object v=view();Method invalidate=v.getClass().getSuperclass().getDeclaredMethod("invalidateScene");invalidate.setAccessible(true);invalidate.invoke(v);return null;});
            SystemClock.sleep(500);screenshot("rim-"+i+"-forced");
            check(wallpaperBefore==System.identityHashCode(main(()->field(store,"image"))),"wallpaper changed during rim comparison");
            Bitmap after=getUiAutomation().takeScreenshot();long forced=rimEnergy(after,838);after.recycle();
            JSONObject run=new JSONObject().put("return_rim_energy",initial).put("forced_redraw_rim_energy",forced)
                .put("before_redraw",previous).put("after_draws",state("sceneDrawCount")).put("drawer",main(()->field(field(view(),"drawer"),"value"))).put("list_blur",main(()->field(field(view(),"listBlur"),"value")));
            runs.put(run);
            if("rim-regression".equals(options.getString("mode"))){
                check((Float)previous.get("rendered_drawer")==0f,"home endpoint not rendered before caching");
                check((Float)previous.get("rendered_blur")==0f,"home blur endpoint not rendered");
                check(Math.abs(initial-forced)<=Math.max(200,forced/100),"rim changed after redundant redraw");
            }
        }
        report.put("rim_investigation",runs);
    }
    private void kineticMotion(JSONObject report)throws Exception{
        home();for(int i=0;i<7;i++)key(22);waitMotion();
        check(((Integer)state("page"))==0 && ((Integer)state("dockFocus"))==5,"right boundary opened drawer");
        Bitmap before=getUiAutomation().takeScreenshot();
        long now=SystemClock.uptimeMillis();
        injectKey(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,22,0));SystemClock.sleep(180);
        check((Float)main(()->field(field(view(),"edgeX"),"value"))>.3f,"held edge has no physical displacement");
        check((Float)main(()->((View)view()).getScaleX())==1f && (Float)main(()->((View)view()).getScaleY())==1f,"wallpaper root was stretched");
        Bitmap held=getUiAutomation().takeScreenshot();
        for(int y=300;y<700;y+=40)for(int x=200;x<1700;x+=100)
            check(before.getPixel(x,y)==held.getPixel(x,y),"wallpaper changed under floating surface motion");
        before.recycle();held.recycle();screenshot("kinetic-edge-held");
        float displacement=(Float)main(()->field(field(view(),"edgeX"),"value"));
        injectKey(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,22,0));
        SystemClock.sleep(100);
        check((Float)main(()->field(field(view(),"edgeX"),"value"))<displacement,"released edge did not recoil");
        SystemClock.sleep(1700);
        check(!(Boolean)main(()->call(field(view(),"edgeX"),"busy")),"edge animation never settled");
        key(20);waitMotion();check(((Integer)state("page"))==1,"drawer navigation failed");
        key(4);waitMotion();key(82);waitMotion();
        check(state("menu")!=null,"menu did not open");key(20);key(19);key(4);waitMotion();
        report.put("kinetic_motion","PASS: held force, release recoil, idle settling, fixed wallpaper pixels, drawer and menu navigation");
    }
    private void anchoredGlass(JSONObject report)throws Exception{
        main(()->{
            Object glass=field(view(),"glassMaterials");
            Method begin=glass.getClass().getDeclaredMethod("beginSurface",android.graphics.Canvas.class);begin.setAccessible(true);
            Method sample=glass.getClass().getDeclaredMethod("sample",android.graphics.Canvas.class,Bitmap.class,int.class,
                float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class);sample.setAccessible(true);
            Bitmap source=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
            for(int x=0;x<1920;x++)for(int y=0;y<1080;y++)source.setPixel(x,y,android.graphics.Color.rgb(x%256,y%256,(x+y)%256));
            for(float radius:new float[]{100f,30f}){
                Bitmap[] images=new Bitmap[2];
                for(int i=0;i<2;i++){
                    images[i]=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas=new android.graphics.Canvas(images[i]);canvas.scale(.25f,.25f);
                    begin.invoke(glass,canvas);
                    if(i==1){canvas.translate(12f,8f);canvas.scale(1.06f,1.04f,200f,200f);}
                    sample.invoke(glass,canvas,source,0,200f,200f,800f,200f,radius,0f,0f,1f);
                }
                int max=0;
                for(int y=72;y<88;y++)for(int x=90;x<210;x++){
                    int a=images[0].getPixel(x,y),b=images[1].getPixel(x,y);
                    max=Math.max(max,Math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b)));
                    max=Math.max(max,Math.abs(android.graphics.Color.green(a)-android.graphics.Color.green(b)));
                    max=Math.max(max,Math.abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b)));
                }
                check(max<=2,"glass texture drift after surface stretch, radius="+radius+" difference="+max);
                for(Bitmap image:images)image.recycle();
            }
            source.recycle();return null;
        });
        report.put("anchored_glass","PASS: capsule and squircle sampling stays aligned during translation and stretch");
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();Bundle result=new Bundle();HandlerThread metrics=new HandlerThread("tv-frame-metrics");
        SharedPreferences prefs=getTargetContext().getSharedPreferences("pdmvos_settings",0);
        Map<String,?> original=new HashMap<>(prefs.getAll());
        output=getTargetContext().getExternalFilesDir("tv-qa");
        String runId=options.getString("run-id");
        if(runId!=null){if(!runId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("run-id");output=new File(output,runId);}
        output.mkdirs();
        try{
            // Durable backup stays on TV even if instrumentation is interrupted.
            File source=new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs/pdmvos_settings.xml");
            if(source.isFile())try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(output,"preferences-before.xml"))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            getUiAutomation(); // Stabilize the observer before any timed presentation samples.
            Intent entry=new Intent().setClassName(getTargetContext(),"dev.pdmv.os.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if("return-motion".equals(options.getString("mode"))||"interaction-soak".equals(options.getString("mode")))entry.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            activity=startActivitySync(entry);
            long focusStart=SystemClock.uptimeMillis(),focusDeadline=focusStart+15000;boolean dismissedSystemOverlay=false;
            while(!(Boolean)main(()->activity.hasWindowFocus())&&SystemClock.uptimeMillis()<focusDeadline){
                if(!dismissedSystemOverlay&&SystemClock.uptimeMillis()-focusStart>1000){getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);dismissedSystemOverlay=true;}
                SystemClock.sleep(100);
            }
            check((Boolean)main(()->activity.hasWindowFocus()),"system window owns input; dismiss it before measuring launcher");
            if(!"startup-motion".equals(options.getString("mode")))waitMotion();metrics.start();
            frameMetricsHandler=new Handler(metrics.getLooper());
            frameMetricsListener=(w,m,dropped)->{
                if(recording && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0){synchronized(frames){frames.add(m.getMetric(FrameMetrics.TOTAL_DURATION));draws.add(m.getMetric(FrameMetrics.DRAW_DURATION));}}
            };
            runOnMainSync(()->attachMetricsWindow(activity));
            if("recorded-backdrop".equals(options.getString("mode"))){recordedBackdrop(report);}
            else if("storage-audit".equals(options.getString("mode"))){storageAudit(report);}
            else if("storage-safety".equals(options.getString("mode"))){storageSafety(report);}
            else if("stability-ui".equals(options.getString("mode"))){stabilityUi(report);}
            else if("pin-sequence".equals(options.getString("mode"))){pinSequence(report);}
            else if("toast-worker".equals(options.getString("mode"))){toastWorker(report);}
            else if("toast-region".equals(options.getString("mode"))){toastRegion(report);}
            else if("arrange-entry".equals(options.getString("mode"))){arrangeEntry(report);}
            else if("static-scene".equals(options.getString("mode"))){staticScene(report);}
            else if("restored-glass".equals(options.getString("mode"))){restoredGlass(report);}
            else if("app-list-panel".equals(options.getString("mode"))){appListPanel(report);}
            else if("liquid-glass".equals(options.getString("mode"))){liquidGlass(report);}
            else if("interaction-soak".equals(options.getString("mode"))){
                JSONObject first=new JSONObject();report.put("before_apps",first);rapidSequence(first);
                returnMotion(report);
                launchCancellation(report);
                options.putString("cycles","6");JSONObject after=new JSONObject();report.put("after_apps",after);rapidSequence(after);
                resourcePressure(report);
            }
            else if("interaction-caches".equals(options.getString("mode"))){interactionCaches(report);}
            else if("rapid-sequence".equals(options.getString("mode"))){rapidSequence(report);}
            else if("morph-endpoint".equals(options.getString("mode"))){morphEndpoint(report);}
            else if("resource-pressure".equals(options.getString("mode"))){resourcePressure(report);}
            else if("launcher-performance".equals(options.getString("mode"))){launcherPerformance(report);}
            else if("classic-resources".equals(options.getString("mode"))){classicResources(report);}
            else if("return-motion".equals(options.getString("mode"))){returnMotion(report);}
            else if("return-sound".equals(options.getString("mode"))){deferredReturnSound(report);}
            else if("input-menu-morph".equals(options.getString("mode"))){inputMenuMorph(report);}
            else if("menu-morph".equals(options.getString("mode"))){menuMorph(report);}
            else if("pill-back".equals(options.getString("mode"))){pillBack(report);}
            else if("wallpaper-state".equals(options.getString("mode"))){wallpaperState(report);}
            else if("release-three".equals(options.getString("mode"))){releaseThree(report);}
            else if("drawer-dismiss".equals(options.getString("mode"))){drawerDismiss(report);}
            else if("startup-motion".equals(options.getString("mode"))){startupMotion(report);wifiSound(report);navigationSound(report);}
            else if("wifi-sound".equals(options.getString("mode"))){wifiSound(report);}
            else if("navigation-sound".equals(options.getString("mode"))){navigationSound(report);}
            else if("popup-profile".equals(options.getString("mode"))){toastDecorationParity(report);materialAudit(report);toastTransition(report);popupProfile(report);}
            else if("final-ui".equals(options.getString("mode"))){toastDecorationParity(report);materialAudit(report);toastTransition(report);anchoredGlass(report);kineticMotion(report);arrangeRing(report);popupProfile(report);}
            else if("material-audit".equals(options.getString("mode"))){materialAudit(report);}
            else if("package-lifecycle".equals(options.getString("mode"))){packageLifecycle(report);}
            else if("install-capability".equals(options.getString("mode"))){installCapability(report);}
            else if("arrange-ring".equals(options.getString("mode"))){arrangeRing(report);}
            else if("rim-profile".equals(options.getString("mode"))){rimProfile(report);}
            else if("visual-compare".equals(options.getString("mode"))){visualCompare(report);}
            else if("rim-investigation".equals(options.getString("mode"))||"rim-regression".equals(options.getString("mode"))){rimInvestigation(report);}
            else if("kinetic-motion".equals(options.getString("mode"))){kineticMotion(report);}
            else if("anchored-glass".equals(options.getString("mode"))){anchoredGlass(report);}
            else if("clock-balance".equals(options.getString("mode"))){clockBalance(report);}
            else if("list-layout".equals(options.getString("mode"))){listLayout(report);}
            else if("toast-transition".equals(options.getString("mode"))){toastTransition(report);}
            else if("readability".equals(options.getString("mode"))){brightReadability(report);}
            else if("wallpaper".equals(options.getString("mode"))){wallpaperFeatures(report);}
            else if("navigation-launcher".equals(options.getString("mode"))){navigationLauncher(report);}
            else if("glass".equals(options.getString("mode"))||"glass-profile".equals(options.getString("mode"))){glassFeatures(report);if("glass-profile".equals(options.getString("mode")))profile(report);}
            else{
                if(!"features".equals(options.getString("mode","all")))profile(report);
                if(!"profile".equals(options.getString("mode","all")))features(report);
            }
            report.put("status","PASS");
        }catch(Throwable e){try{report.put("status","FAIL").put("error",e.toString()).put("stack",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}result.putString("error",e.toString());}
        finally{
            recording=false;
            // Restore test mutations. Only package-lifecycle may uninstall its exact QA fixture.
            SharedPreferences.Editor edit=prefs.edit().clear();
            for(Map.Entry<String,?> entry:original.entrySet()){
                String k=entry.getKey();Object v=entry.getValue();
                if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);
                else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Long)edit.putLong(k,(Long)v);
                else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set)edit.putStringSet(k,new HashSet<>((Set<String>)v));
            }
            edit.commit();
            if(activity!=null)try{main(()->{
                Object v=view();call(v,"cancelHeldInput");call(v,"closeMenu");set(v,"moveMode",false);
                call(v,"exitDrawer");set(v,"focusHeader",false);set(v,"dockFocus",0);set(v,"gridFocus",0);
                call(repo(),"refresh");call(v,"scheduleArtworkWarmup");call(v,"updateFocus");return null;
            });}catch(Exception cleanupError){result.putString("ui_restore_error",cleanupError.toString());}
            if(frameMetricsListener!=null)runOnMainSync(()->{
                if(metricsWindow!=null)metricsWindow.removeOnFrameMetricsAvailableListener(frameMetricsListener);
                frameMetricsListener=null;metricsWindow=null;
            });
            metrics.quitSafely();
            try{writeReport(report);}catch(Exception e){result.putString("report_error",e.toString());}
        }
        result.putString("report",report.toString());finish("PASS".equals(report.optString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
