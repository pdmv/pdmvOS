package dev.pdmv.os;

/** Detect meaningful high-frequency foreground detail in a TV banner.
 * A flat brand-color background or a smooth gradient is not a visible logo.
 * This is pure Java and can be unit-tested without Android or bitmap allocations.
 */
public final class ArtworkDetector {
    private ArtworkDetector() {}
    public interface PixelSource { int get(int x,int y); }

    private static int contrast(int a,int b){
        return Math.abs(((a>>>16)&255)-((b>>>16)&255))
            +Math.abs(((a>>>8)&255)-((b>>>8)&255))
            +Math.abs((a&255)-(b&255));
    }

    /** Contrasting text for emergency banners; independent of global light/dark theme. */
    public static int fallbackTextColor(int backgroundArgb){
        float luminance=.2126f*((backgroundArgb>>>16)&255)
            +.7152f*((backgroundArgb>>>8)&255)+.0722f*(backgroundArgb&255);
        return luminance>=150f?0xff16161b:0xfff8f8fa;
    }

    public static boolean hasDetail(int width,int height,PixelSource source){
        if(width<32||height<32||source==null)return false;
        // Exclude edges and rounded-background framing, concentrate on where a logo lives.
        int left=width/5, right=width*4/5,top=height/5,bottom=height*4/5;
        int step=Math.max(5,Math.min(width,height)/40);
        int edges=0,visited=0;
        for(int y=top;y+step<bottom;y+=step){
            for(int x=left;x+step<right;x+=step){
                int here=source.get(x,y);
                if(contrast(here,source.get(x+step,y))>88 ||
                   contrast(here,source.get(x,y+step))>88)edges++;
                visited++;
            }
        }
        // A flat fill/gradient never reaches this threshold, but wordmarks do.
        return edges>=Math.max(3,visited/100);
    }
}
