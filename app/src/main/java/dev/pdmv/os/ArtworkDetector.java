package dev.pdmv.os;

/** Detect meaningful high-frequency foreground detail in a TV banner.
 * A flat brand-color background or a smooth gradient is not a visible logo.
 * This is pure Java and can be unit-tested without Android or bitmap allocations.
 */
public final class ArtworkDetector {
    private ArtworkDetector() {}
    public interface PixelSource { int get(int x,int y); }

    /** Contrasting text for emergency banners; independent of global light/dark theme. */
    public static int fallbackTextColor(int backgroundArgb){
        return NativeCore.artworkFallbackTextColor(backgroundArgb);
    }

    public static boolean hasDetail(int width,int height,PixelSource source){
        if(width<32||height<32||source==null)return false;
        // Exclude edges and rounded-background framing, concentrate on where a logo lives.
        int left=width/5, right=width*4/5,top=height/5,bottom=height*4/5;
        int step=NativeCore.artworkStep(width,height);
        if(step<=0)return false;
        int rows=Math.max(0,(bottom-1-top)/step);
        int cols=Math.max(0,(right-1-left)/step);
        if(rows==0||cols==0)return false;
        int[] triplets=new int[rows*cols*3];
        int idx=0;
        for(int y=top;y+step<bottom;y+=step){
            for(int x=left;x+step<right;x+=step){
                triplets[idx++]=source.get(x,y);
                triplets[idx++]=source.get(x+step,y);
                triplets[idx++]=source.get(x,y+step);
            }
        }
        // A flat fill/gradient never reaches this threshold, but wordmarks do.
        return NativeCore.artworkHasDetailTriplets(triplets);
    }

    public static int dominantFillColor(int[] samples,int fallback){
        return NativeCore.artworkDominantFillColor(samples,fallback);
    }

    public static int controlFocusTint(int[] samples){
        return NativeCore.artworkControlFocusTint(samples);
    }
}
