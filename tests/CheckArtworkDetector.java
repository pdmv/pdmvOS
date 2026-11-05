package dev.pdmv.os;

public final class CheckArtworkDetector {
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args){
        int w=576,h=324;
        check(!ArtworkDetector.hasDetail(w,h,(x,y)->0xffe8e8e8),"flat white is not a logo");
        check(!ArtworkDetector.hasDetail(w,h,(x,y)->0xffd21422),"flat red is not a logo");
        check(!ArtworkDetector.hasDetail(w,h,(x,y)->{
            int v=50+(x*150/w);return 0xff000000|v<<16|v<<8|v;
        }),"smooth gradient is not a logo");
        check(ArtworkDetector.hasDetail(w,h,(x,y)->{
            boolean letter=x>190&&x<390&&y>115&&y<205;
            return letter?0xffffffff:0xff172536;
        }),"high contrast centered logo must be detected");
        check(ArtworkDetector.hasDetail(w,h,(x,y)->{
            boolean wordmark=x>180&&x<400&&y>148&&y<172;
            return wordmark?0xff101010:0xffeeeeee;
        }),"narrow central wordmark must be detected");
        check(ArtworkDetector.fallbackTextColor(0xffffffff)==0xff16161b,
            "white application banner needs dark fallback text");
        check(ArtworkDetector.fallbackTextColor(0xff101010)==0xfff8f8fa,
            "dark application banner needs light fallback text");
        System.out.println("PASS: blank banners, smooth backgrounds, central logos, wordmarks, fallback contrast");
    }
}
