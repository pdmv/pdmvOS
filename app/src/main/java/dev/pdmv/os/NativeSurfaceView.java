package dev.pdmv.os;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Display;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/** HWUI records the scene once per VSYNC; RenderThread owns GPU submission.
 * Never lock a second buffer queue on the input/UI thread. Android's window
 * resolution is authoritative: a larger buffer cannot force 4K composition.
 */
abstract class NativeSurfaceView extends ViewGroup {
    private final View scene, overlay;
    private final ViewGroup backdrop;
    NativeSurfaceView(Activity activity) {
        super(activity);
        setWillNotDraw(true);
        setClipChildren(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        scene=new View(activity){@Override public boolean isOpaque(){return true;} @Override protected void onDraw(Canvas c){drawScene(c);}};
        overlay=new View(activity){@Override protected void onDraw(Canvas c){int saved=c.save();c.translate(-getLeft(),-getTop());drawOverlay(c);c.restoreToCount(saved);}};
        overlay.setTranslationZ(32f);
        backdrop=new ViewGroup(activity){
            @Override public boolean isOpaque(){return true;}
            @Override protected void onMeasure(int w,int h){
                setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h));
                scene.measure(w,h);
                for(int i=1;i<getChildCount();i++)measureNode(getChildAt(i));
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
                scene.layout(0,0,r-l,b-t);
                for(int i=1;i<getChildCount();i++)layoutNode(getChildAt(i));
            }
        };
        backdrop.setClipChildren(false);backdrop.addView(scene);
        addView(backdrop);addView(overlay);
        configureDisplay();
    }
    @Override public boolean isOpaque(){return true;}
    private void measureNode(View node){
        LayoutParams p=node.getLayoutParams();
        node.measure(MeasureSpec.makeMeasureSpec(p.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.height,MeasureSpec.EXACTLY));
    }
    private void layoutNode(View node){LayoutParams p=node.getLayoutParams();node.layout(0,0,p.width,p.height);}
    @Override protected void onMeasure(int w,int h){
        super.onMeasure(w,h);
        int ww=MeasureSpec.makeMeasureSpec(getMeasuredWidth(),MeasureSpec.EXACTLY);
        int hh=MeasureSpec.makeMeasureSpec(getMeasuredHeight(),MeasureSpec.EXACTLY);
        backdrop.measure(ww,hh);overlay.measure(ww,hh);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        backdrop.layout(0,0,r-l,b-t);
        layoutOverlay();
    }
    private boolean scheduled;
    private boolean renderingActive=true;
    final void setRenderingActive(boolean active){
        renderingActive=active;
        if(active)scheduleFrame();
        else{Choreographer.getInstance().removeFrameCallback(tick);scheduled=false;}
    }
    private final Choreographer.FrameCallback tick=this::renderFrame;
    private void renderFrame(long frameTimeNanos){
        scheduled=false;
        // Include the settling frame: advanceFrame can make busy() false and
        // enable the backdrop hardware cache in this same VSYNC. That cache must
        // contain the exact endpoint, never the preceding fractional dock position.
        boolean sceneWasMoving=sceneMoving();
        advanceFrame(frameTimeNanos);
        // Child bounds become the damage region. Never invalidate the entire
        // window for a popup/press animation: old TV GPUs pay for every pixel.
        if(sceneWasMoving||sceneMoving())scene.invalidate();
        if(layoutOverlay())overlay.invalidate();
    }
    private boolean layoutOverlay(){
        Rect area=overlayViewport();
        if(area==null || area.isEmpty()){overlay.setVisibility(INVISIBLE);return false;}
        overlay.setVisibility(VISIBLE);
        overlay.layout(area.left,area.top,area.right,area.bottom);return true;
    }
    private final java.util.ArrayList<View> captureNodes=new java.util.ArrayList<>();
    private final RectF captureBounds=new RectF();
    private static final java.util.Comparator<View> NODE_Z=(a,b)->Float.compare(a.getZ(),b.getZ());
    final void drawBackdropNodes(Canvas canvas){
        // Draw the actual node transforms, excluding the scene and overlay to
        // avoid recursive toast capture. Z order includes the selected tile.
        java.util.ArrayList<View> nodes=captureNodes;nodes.clear();
        for(int i=0;i<backdrop.getChildCount();i++){
            View child=backdrop.getChildAt(i);
            if(child!=scene && child.getVisibility()==VISIBLE)nodes.add(child);
        }
        java.util.Collections.sort(nodes,NODE_Z);
        RectF bounds=captureBounds;
        try{for(View child:nodes)drawBackdropNode(canvas,child,bounds);}
        finally{nodes.clear();}
    }
    private void drawBackdropNode(Canvas canvas,View child,RectF bounds){
        if(child.getAlpha()<=0f)return;
        bounds.set(0,0,child.getWidth(),child.getHeight());
        child.getMatrix().mapRect(bounds);bounds.offset(child.getLeft(),child.getTop());
        // Offscreen material captures are small. Do not execute onDraw for
        // unrelated header/text/artwork nodes, even when their pixels clip away.
        if(!canvas.quickReject(bounds,Canvas.EdgeType.AA))drawChild(canvas,child,getDrawingTime());
    }
    final void attachSceneNode(View node,int width,int height){
        backdrop.addView(node,new LayoutParams(width,height));
        // Nodes are attached during VSYNC, after the root's layout pass may have run.
        // Give them bounds before the same frame can record an empty display list.
        measureNode(node);layoutNode(node);node.invalidate();
    }
    final void removeSceneNode(View node){backdrop.removeView(node);}
    final void cacheStaticScene(boolean cache){
        // Cache only wallpaper/glass. Tile transforms and focus never dirty this
        // texture or require reparenting a View during input dispatch.
        int layer=cache?View.LAYER_TYPE_HARDWARE:View.LAYER_TYPE_NONE;
        if(scene.getLayerType()!=layer)scene.setLayerType(layer,null);
    }

    private void scheduleFrame(){
        if(renderingActive && !scheduled && isAttachedToWindow()){
            scheduled=true;Choreographer.getInstance().postFrameCallback(tick);
        }
    }
    @Override public void invalidate(){if(scene!=null)scheduleFrame();}
    @Override public void postInvalidateOnAnimation(){scheduleFrame();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();scheduleFrame();}
    @Override protected void onDetachedFromWindow(){
        Choreographer.getInstance().removeFrameCallback(tick);scheduled=false;super.onDetachedFromWindow();
    }
    final void invalidateScene(){scene.invalidate();invalidate();}

    final void configureDisplay() {
        try {
        Display display=getDisplay();
        if(display==null)display=((Activity)getContext()).getWindowManager().getDefaultDisplay();
        Display.Mode selected=selectMode(display);
        Activity activity=(Activity)getContext();
        WindowManager.LayoutParams params=activity.getWindow().getAttributes();
        if(params.preferredDisplayModeId!=selected.getModeId()){
            params.preferredDisplayModeId=selected.getModeId();
            activity.getWindow().setAttributes(params);
        }
        Log.i("pdmvOS.Display","requested="+selected.getPhysicalWidth()+"x"+selected.getPhysicalHeight()+" @"+selected.getRefreshRate());
        } catch(RuntimeException unavailableMode){Log.w("pdmvOS.Display","Keeping system display mode",unavailableMode);}
    }

    static Display.Mode selectMode(Display display) {
        Display.Mode[] modes=display.getSupportedModes();
        int[] widths=new int[modes.length], heights=new int[modes.length];
        float[] rates=new float[modes.length];
        int current=0;
        for(int i=0;i<modes.length;i++) {
            widths[i]=modes[i].getPhysicalWidth(); heights[i]=modes[i].getPhysicalHeight();
            rates[i]=modes[i].getRefreshRate();
            if(modes[i].getModeId()==display.getMode().getModeId())current=i;
        }
        return modes[DisplayPolicy.select(widths,heights,rates,current)];
    }

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if(scene!=null)invalidateScene();
        Log.i("pdmvOS.Display","window="+w+"x"+h+" renderer=HWUI");
    }
    protected abstract void advanceFrame(long frameTimeNanos);
    protected abstract Rect overlayViewport();
    protected abstract boolean sceneMoving();
    protected abstract void drawScene(Canvas canvas);
    protected abstract void drawOverlay(Canvas canvas);
}
