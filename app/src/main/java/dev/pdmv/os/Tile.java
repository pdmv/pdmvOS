package dev.pdmv.os;

import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;

final class Tile {
    final String id;String label;
    long packageRevision=-1;
    boolean retired,installPlaceholder;
    int installSessionId=-1;float installProgress;
    boolean installCompleting;
    final SpringValue installSweep=new SpringValue(0f,180f,27f);
    final SpringValue installReveal=new SpringValue(0f,230f,31f);
    String packageName, tvInputId;
    Drawable artwork;
    boolean banner, uninstallable;
    volatile Bitmap image;
    BitmapShader shader;
    volatile boolean preparationAttempted;
    void dispose(){
        if(image!=null && !image.isRecycled())image.recycle();
        image=null;shader=null;preparationAttempted=false;
    }
    final SpringValue scale=new SpringValue(0f, MotionProfile.FOCUS_IN_K, MotionProfile.FOCUS_IN_D);
    final SpringValue labelAlpha=new SpringValue(0f, MotionProfile.LABEL_IN_K, MotionProfile.LABEL_IN_D);
    // Spring offsets animate neighboring tiles into newly vacated slots during reordering.
    final SpringValue offsetX=new SpringValue(0f, MotionProfile.REORDER_K, MotionProfile.REORDER_D);
    final SpringValue offsetY=new SpringValue(0f, MotionProfile.REORDER_K, MotionProfile.REORDER_D);
    final SpringValue tilt=new SpringValue(0f,260f,23f);
    boolean traveling, exiting, exitDock;
    float exitX,exitY;
    final SpringValue presence=new SpringValue(1f,420f,40f);
    final SpringValue ring=new SpringValue(0f,1600f,34f);
    final SpringValue editLift=new SpringValue(0f,400f,36f);
    Tile(String id,String label){this.id=id;this.label=label;}
    boolean isEmpty(){return id.startsWith("empty:");}
    boolean isShortcut(){return id.startsWith("hdmi:")||id.equals("tv")||id.equals("settings");}
}
