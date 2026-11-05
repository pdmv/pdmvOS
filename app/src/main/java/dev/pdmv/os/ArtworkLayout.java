package dev.pdmv.os;

/** Pure geometry for composing Android TV banner/icon without cropping any logo.
 * Bitmap bounds stay within the output canvas, including unusually wide or tall resources.
 */
public final class ArtworkLayout {
    private ArtworkLayout() {}
    public static final class Bounds {
        public final float left,top,right,bottom;
        Bounds(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
        public float width(){return right-left;}
        public float height(){return bottom-top;}
    }
    public static Bounds fit(int sourceWidth,int sourceHeight,float boxWidth,float boxHeight,boolean banner){
        if(sourceWidth<=0)sourceWidth=128;
        if(sourceHeight<=0)sourceHeight=128;
        if(boxWidth<=0||boxHeight<=0)throw new IllegalArgumentException("invalid canvas size");
        // Banner includes its full logo and border (CENTER_INSIDE, never CENTER_CROP).
        // Square Android launcher icons are isolated and centered in a consistent area.
        float maxWidth=banner?boxWidth:boxHeight*.82f;
        float maxHeight=banner?boxHeight:boxHeight*.82f;
        float scale=Math.min(maxWidth/sourceWidth,maxHeight/sourceHeight);
        float w=sourceWidth*scale,h=sourceHeight*scale;
        float left=(boxWidth-w)*.5f,top=(boxHeight-h)*.5f;
        return new Bounds(left,top,left+w,top+h);
    }
}
