package dev.pdmv.os;

import android.graphics.*;

/** Neutral optical stack. One recipe per thickness, independent of light/dark appearance. */
final class GlassMaterial {
    interface Shape {void draw(Canvas c,float x,float y,float w,float h,float radius,Paint p);}
    interface Outline {Path get(float w,float h,float radius);}
    private final Outline outline;
    private final WallpaperStore wallpapers;
    private final Shape shape;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix=new Matrix();
    private final Matrix samplingFrame=new Matrix(),canvasTransform=new Matrix();
    private final Matrix inverseTransform=new Matrix(),anchoredSample=new Matrix();
    // Record the wallpaper coordinate system before any floating surface moves.
    // Each draw pass supplies its own frame, including offscreen scene preparation.
    @SuppressWarnings("deprecation")
    void beginSurface(Canvas c){c.getMatrix(samplingFrame);}

    private final Bitmap[] sources=new Bitmap[5];
    private final BitmapShader[] shaders=new BitmapShader[5];
    private Bitmap toastBackdrop,menuBackdrop;
    private final RectF menuBackdropArea=new RectF(0,0,1920,1080);
    void setMenuBackdrop(Bitmap bitmap){menuBackdrop=bitmap;menuBackdropArea.set(0,0,1920,1080);}
    void setMenuBackdrop(Bitmap bitmap,RectF area){menuBackdrop=bitmap;menuBackdropArea.set(area);}
    private float toastBackdropTop,toastBackdropLeft,toastBackdropWidth=1920f;
    void setToastBackdrop(Bitmap bitmap){setToastBackdrop(bitmap,0);}
    void setToastBackdrop(Bitmap bitmap,float top){toastBackdrop=bitmap;toastBackdropTop=top;toastBackdropLeft=0f;toastBackdropWidth=1920f;}
    void setToastBackdrop(Bitmap bitmap,RectF area){toastBackdrop=bitmap;toastBackdropTop=area.top;toastBackdropLeft=area.left;toastBackdropWidth=area.width();}
    // Dock, nested control, pill, menu/dialog, toast. No alternate theme palette.
    private static final int[] SMOKE={0,0,0,0,0};
    private static final int[] GLAZE={0x08ffffff,0x18ffffff,0x08ffffff,0x18ffffff,0x1affffff};
    private static final float[] DEPTH={7f,1.6f,3.6f,6f,8f};
    private static final float[] BACKDROP={0f,0f,.55f,.96f,1f};
    GlassMaterial(WallpaperStore store,Shape shape,Outline outline){wallpapers=store;this.shape=shape;this.outline=outline;}
    private final java.util.LinkedHashMap<String,Bitmap> pillShadows=new java.util.LinkedHashMap<String,Bitmap>(8,.75f,true);
    private final Rect shadowSource=new Rect();
    private final RectF shadowDestination=new RectF();
    private final Rect decorationSource=new Rect();
    private final RectF decorationDestination=new RectF();
    void drawPillShadow(Canvas c,float x,float y,float w,float h){
        int width=Math.round(w),height=Math.round(h),pad=24;
        int sourceWidth=Math.min(width,height*3);
        String key=sourceWidth+":"+height;
        Bitmap bitmap=pillShadows.get(key);
        if(bitmap==null){
            bitmap=Bitmap.createBitmap(sourceWidth+pad*2,height+pad*2,Bitmap.Config.ARGB_8888);
            Canvas cached=new Canvas(bitmap);
            Paint shadow=new Paint(Paint.ANTI_ALIAS_FLAG);shadow.setColor(0x24000000);
            shadow.setMaskFilter(new BlurMaskFilter(16f,BlurMaskFilter.Blur.NORMAL));
            cached.drawRoundRect(pad,pad+3,pad+sourceWidth,pad+height+3,height/2f,height/2f,shadow);
            shadow.setMaskFilter(null);shadow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            cached.drawRoundRect(pad,pad,pad+sourceWidth,pad+height,height/2f,height/2f,shadow);
            pillShadows.put(key,bitmap);
            while(pillShadows.size()>8)pillShadows.remove(pillShadows.keySet().iterator().next());
        }
        p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
        if(sourceWidth==width){shadowDestination.set(x-pad,y-pad,x+w+pad,y+h+pad);c.drawBitmap(bitmap,null,shadowDestination,p);return;}
        // Keep both end caps at native size; stretch only the flat middle.
        // Width springs now reuse one raster instead of allocating a blurred
        // bitmap for each intermediate width (and evicting it on reversal).
        int cap=height+pad,total=bitmap.getWidth();float l=x-pad,r=x+w+pad;
        shadowSource.set(0,0,cap,bitmap.getHeight());shadowDestination.set(l,y-pad,l+cap,y+h+pad);c.drawBitmap(bitmap,shadowSource,shadowDestination,p);
        shadowSource.set(cap,0,total-cap,bitmap.getHeight());shadowDestination.set(l+cap,y-pad,r-cap,y+h+pad);c.drawBitmap(bitmap,shadowSource,shadowDestination,p);
        shadowSource.set(total-cap,0,total,bitmap.getHeight());shadowDestination.set(r-cap,y-pad,r,y+h+pad);c.drawBitmap(bitmap,shadowSource,shadowDestination,p);
    }
    void trimMemory(){
        pillShadows.clear();decorations.clear();decorationBytes=0;
        menuBackdrop=null;toastBackdrop=null;
        java.util.Arrays.fill(sources,null);java.util.Arrays.fill(shaders,null);
        p.setShader(null);
    }
    private float sceneBlur,nativeOffsetY;
    void setNativeOffsetY(float offset){nativeOffsetY=offset;}
    void setSceneBlur(float progress){sceneBlur=Math.max(0,Math.min(1,progress));}
    void draw(Canvas c,float x,float y,float w,float h,float r,int kind,float sampleX,float sampleY){
        drawBackdrop(c,x,y,w,h,r,kind,sampleX,sampleY,1f);
        drawDecorationOnly(c,x,y,w,h,r,kind);
    }
    void drawBackdrop(Canvas c,float x,float y,float w,float h,float r,int kind,float sampleX,float sampleY,float strength){
        int k=Math.max(0,Math.min(4,kind));
        if(k==1)return;
        if(k==3||k==4){
            Bitmap image=k==3?menuBackdrop:toastBackdrop;
            // Until the actual scene is ready, transmit it directly instead of substituting wallpaper.
            if(image!=null)sample(c,image,k==3?4:3,x,y,w,h,r,sampleX,sampleY,strength);
            return;
        }
        if(k==0||k==2){
            // Dock and header pills share the same glass transmission. Blend
            // preblurred scene scales without moving the wallpaper sample.
            sample(c,wallpapers.frost(0),0,x,y,w,h,r,sampleX,sampleY,.28f*(1-sceneBlur));
            sample(c,wallpapers.frost(1),1,x,y,w,h,r,sampleX,sampleY,.625f*(1-sceneBlur));
            sample(c,wallpapers.frost(2),2,x,y,w,h,r,sampleX,sampleY,.76f*sceneBlur);
            return;
        }
    }
    void drawFocusedControl(Canvas c,float x,float y,float size,float focus,int tint){
        if(focus<.001f)return;
        sample(c,wallpapers.frost(2),2,x,y,size,size,size/2f,0,0,.96f*focus);
        p.reset();p.setAntiAlias(true);p.setDither(true);p.setColor(tint);p.setAlpha(Math.round(196f*focus));
        shape.draw(c,x,y,size,size,size/2f,p);
        drawRimAlpha(c,x,y,size,size,size/2f,1,focus);
    }
    private void sample(Canvas c,Bitmap image,int slot,float x,float y,float w,float h,float r,float sampleX,float sampleY,float opacity){
        if(image==null||image.isRecycled()||opacity<.001f)return;
        if(sources[slot]!=image){sources[slot]=image;shaders[slot]=new BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);}
        float scale=1920f/image.getWidth();
        if(image==menuBackdrop){
            matrix.setScale(menuBackdropArea.width()/image.getWidth(),menuBackdropArea.height()/image.getHeight());
            matrix.postTranslate(menuBackdropArea.left-sampleX,menuBackdropArea.top-sampleY-nativeOffsetY);
        }else if(image==toastBackdrop){
            scale=toastBackdropWidth/image.getWidth();matrix.setScale(scale,scale);
            matrix.postTranslate(toastBackdropLeft-sampleX,toastBackdropTop-sampleY-nativeOffsetY);
        }else{
            matrix.setScale(scale,1080f/image.getHeight());
            matrix.postTranslate(-sampleX,-sampleY-nativeOffsetY);
        }
        // Cancel only the surface transform, keeping the texture in wallpaper
        // coordinates. The mask and its decoration still stretch with the control.
        c.getMatrix(canvasTransform);
        if(canvasTransform.invert(inverseTransform)){
            anchoredSample.setConcat(inverseTransform,samplingFrame);
            matrix.setConcat(anchoredSample,matrix);
        }
        boolean popup=image==menuBackdrop;
        if(!popup&&r<Math.min(w,h)*.5f-.25f){
            // Counter the cached squircle's local canvas transform in the sampler only.
            matrix.postTranslate(-x,-y);matrix.postScale(Math.max(1,Math.round(w))/w,Math.max(1,Math.round(h))/h);
        }
        shaders[slot].setLocalMatrix(matrix);
        p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);p.setShader(shaders[slot]);p.setAlpha(Math.round(255*opacity));
        drawMaterialShape(c,x,y,w,h,r,popup);
        p.setShader(null);
    }
    private void drawMaterialShape(Canvas c,float x,float y,float w,float h,float r,boolean popup){
        // Circular arcs make the popup contour a true offset of its capsule items.
        if(popup)c.drawRoundRect(x,y,x+w,y+h,r,r,p);
        else shape.draw(c,x,y,w,h,r,p);
    }
    private boolean liveRims;
    void setLiveRims(boolean active){liveRims=active;}
    boolean hasLiveRims(){return liveRims;}
    void drawDecorationOnly(Canvas c,float x,float y,float w,float h,float r,int kind){
        int k=Math.max(0,Math.min(4,kind));
        if(k==1||k==3){
            drawItem(c,x,y,w,h,r,k,1f);
            return;
        }
        if(!liveRims){drawStaticDecoration(c,x,y,w,h,r,kind);return;}
        drawCachedDecoration(c,x,y,w,h,r,kind,false);
        drawRim(c,x,y,w,h,r,kind);
    }
    void drawItem(Canvas c,float x,float y,float w,float h,float r,float alpha){
        drawItem(c,x,y,w,h,r,1,alpha);
    }
    private void drawItem(Canvas c,float x,float y,float w,float h,float r,int k,float alpha){
        float a=UiGeometry.clamp(alpha,0f,1f);
        if(a<.001f)return;
        p.reset();p.setAntiAlias(true);p.setDither(true);
        int smoke=SMOKE[k];
        if(smoke!=0){
            p.setColor(smoke);p.setAlpha(Math.round(Color.alpha(smoke)*a));
            drawMaterialShape(c,x,y,w,h,r,k==3);
        }
        int glaze=GLAZE[k];
        if(glaze!=0){
            p.setColor(glaze);p.setAlpha(Math.round(Color.alpha(glaze)*a));
            drawMaterialShape(c,x,y,w,h,r,k==3);
        }
        drawRimAlpha(c,x,y,w,h,r,k,a);
    }
    void drawStaticDecoration(Canvas c,float x,float y,float w,float h,float r,int kind){
        drawCachedDecoration(c,x,y,w,h,r,kind,true);
    }
    private void drawCachedDecoration(Canvas c,float x,float y,float w,float h,float r,int kind,boolean rim){
        int k=Math.max(0,Math.min(4,kind));
        if(!rim&&(k==1||k==3)){
            p.reset();p.setAntiAlias(true);
            p.setColor(SMOKE[k]);drawMaterialShape(c,x,y,w,h,r,k==3);
            p.setColor(GLAZE[k]);drawMaterialShape(c,x,y,w,h,r,k==3);p.setAlpha(255);
            return;
        }
        if((kind==4||kind==2)&&r>=h*.5f-.25f){
            int cap=(int)Math.ceil(h),middle=8;
            if(w>=2*cap+middle){
                // Toast and header pill widths can change continuously. Lighting varies only
                // vertically: preserve both circular ends and stretch only a
                // flat center, rather than rasterizing/uploading a new rim at
                // every intermediate width.
                Bitmap strip=decoration(2*cap+middle,h,r,kind,rim);
                p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
                decorationSource.set(0,0,cap,strip.getHeight());
                decorationDestination.set(x,y,x+cap,y+h);c.drawBitmap(strip,decorationSource,decorationDestination,p);
                decorationSource.set(cap,0,cap+middle,strip.getHeight());
                decorationDestination.set(x+cap,y,x+w-cap,y+h);c.drawBitmap(strip,decorationSource,decorationDestination,p);
                decorationSource.set(cap+middle,0,strip.getWidth(),strip.getHeight());
                decorationDestination.set(x+w-cap,y,x+w,y+h);c.drawBitmap(strip,decorationSource,decorationDestination,p);
                return;
            }
        }
        Bitmap overlay=decoration(w,h,r,k,rim);
        decorationDestination.set(x,y,x+w,y+h);
        p.reset();p.setFilterBitmap(true);p.setAntiAlias(true);c.drawBitmap(overlay,null,decorationDestination,p);
    }
    private final java.util.LinkedHashMap<String,Bitmap> decorations=new java.util.LinkedHashMap<>(32,.75f,true);
    private long decorationBytes;
    private Bitmap decoration(float w,float h,float radius,int kind,boolean rim){
        int width=Math.max(1,(int)Math.ceil(w)),height=Math.max(1,(int)Math.ceil(h));
        float r=radius>=Math.min(w,h)*.499f?Math.min(width,height)/2f:radius;
        String key=kind+":"+width+":"+height+":"+Float.floatToIntBits(r)+":"+rim;
        Bitmap cached=decorations.get(key);if(cached!=null)return cached;
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        p.reset();p.setAntiAlias(true);drawDecoration(new Canvas(bitmap),0,0,width,height,r,kind,rim);
        decorations.put(key,bitmap);decorationBytes+=bitmap.getAllocationByteCount();
        java.util.Iterator<java.util.Map.Entry<String,Bitmap>> entries=decorations.entrySet().iterator();
        while((decorationBytes>4*1024*1024||decorations.size()>32)&&entries.hasNext()){
            Bitmap evicted=entries.next().getValue();decorationBytes-=evicted.getAllocationByteCount();entries.remove();
            // RenderThread may still hold a recorded texture; let GC release evictions safely.
        }
        return bitmap;
    }
    private void drawDecoration(Canvas c,float x,float y,float w,float h,float r,int k,boolean rim){
        p.setColor(SMOKE[k]);drawMaterialShape(c,x,y,w,h,r,k==3);
        p.setColor(GLAZE[k]);drawMaterialShape(c,x,y,w,h,r,k==3);p.setAlpha(255);
        if(k==1||k==3){if(rim)drawRim(c,x,y,w,h,r,k);return;}
        // A single feathered inner reflection and a fine lip; no dark tint or double contour.
        float depth=DEPTH[k];int clipped=c.save();c.clipPath(outline.get(w,h,r));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(depth*.45f);
        p.setMaskFilter(new BlurMaskFilter(depth,BlurMaskFilter.Blur.NORMAL));
        p.setShader(edge(x,y,h,k==0?0x21ffffff:0x25ffffff,k==0?0x27ffffff:0x2cffffff));
        float inset=depth*.55f+1f;
        shape.draw(c,x+inset,y+inset,w-2*inset,h-2*inset,Math.max(0,r-inset),p);
        p.setMaskFilter(null);c.restoreToCount(clipped);
        p.setShader(null);p.setStyle(Paint.Style.FILL);
        if(rim)drawRim(c,x,y,w,h,r,k);
    }
    private static final float[] EDGE_STOPS={0,.18f,.5f,.82f,1};
    private final Matrix edgeMatrix=new Matrix();
    private final LinearGradient[] rimShaders={
        new LinearGradient(0,0,0,1,new int[]{0x28ffffff,0x0affffff,0x00ffffff,0x09ffffff,0x20ffffff},EDGE_STOPS,Shader.TileMode.CLAMP),
        new LinearGradient(0,0,0,1,new int[]{0x58ffffff,0x0affffff,0x00ffffff,0x09ffffff,0x60ffffff},EDGE_STOPS,Shader.TileMode.CLAMP),
        new LinearGradient(0,0,0,1,new int[]{0x70ffffff,0x0affffff,0x00ffffff,0x09ffffff,0x78ffffff},EDGE_STOPS,Shader.TileMode.CLAMP)
    };
    void drawRim(Canvas c,float x,float y,float w,float h,float r,int kind){
        drawRimAlpha(c,x,y,w,h,r,kind,1f);
    }
    void drawRimAlpha(Canvas c,float x,float y,float w,float h,float r,int kind,float alpha){
        float a=UiGeometry.clamp(alpha,0f,1f);
        if(a<.001f)return;
        // The reflection belongs to the surface's local coordinates. A global y
        // here is clamped by translated squircle paths, brightening the whole rim.
        int saved=c.save();c.translate(x,y);
        int k=Math.max(0,Math.min(4,kind));
        c.getMatrix(canvasTransform);
        float baseScale=Math.max(.001f,samplingFrame.mapRadius(1f));
        float currentScale=Math.max(.001f,canvasTransform.mapRadius(1f));
        float relScale=UiGeometry.clamp(currentScale/baseScale,.55f,1f);
        p.reset();p.setAntiAlias(true);p.setDither(true);p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((k==1?.85f:.9f)/relScale);
        LinearGradient rimShader=rimShaders[k==1?0:(k==0||k==3)?1:2];
        edgeMatrix.setScale(1f,Math.max(1f,h));rimShader.setLocalMatrix(edgeMatrix);
        p.setShader(rimShader);
        p.setAlpha(Math.round(255f*a));
        float inset=k==1?.5f:.7f;
        drawMaterialShape(c,inset,inset,w-2*inset,h-2*inset,Math.max(0,r-inset),k==3);
        p.setShader(null);p.setStyle(Paint.Style.FILL);c.restoreToCount(saved);
    }
    private Shader edge(float x,float y,float h,int top,int bottom){
        return new LinearGradient(x,y,x,y+h,new int[]{top,0x0affffff,0x00ffffff,0x09ffffff,bottom},EDGE_STOPS,Shader.TileMode.CLAMP);
    }
}
