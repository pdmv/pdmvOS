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
        // Banner includes its full logo and border (CENTER_INSIDE, never CENTER_CROP).
        // Square Android launcher icons are isolated and centered in a consistent area.
        float[] out=new float[4];
        NativeCore.artworkLayoutFit(sourceWidth,sourceHeight,boxWidth,boxHeight,banner,out);
        return new Bounds(out[0],out[1],out[2],out[3]);
    }
    public static void centerCropRect(int srcW,int srcH,float dstW,float dstH,int[] out4){
        NativeCore.artworkCenterCropRect(srcW,srcH,dstW,dstH,out4);
    }
}
