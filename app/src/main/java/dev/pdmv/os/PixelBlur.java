package dev.pdmv.os;

/** Same three box passes, with exact integer division shared by all pixels. */
final class PixelBlur {
    private PixelBlur(){}
    static void apply(int[] pixels,int[] scratch,int width,int height,int radius){
        NativeCore.pixelBlurApply(pixels,scratch,width,height,radius);
    }
    static void blend(int[] low,int[] high,int[] out,int length,int alpha){
        NativeCore.pixelBlurBlend(low,high,out,length,alpha);
    }
}
