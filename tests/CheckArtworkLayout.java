package dev.pdmv.os;

/** No banner may crop to a logo fragment when source aspect ratio changes. */
public final class CheckArtworkLayout {
    static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
    static void caseFor(int w,int h,boolean banner){
        ArtworkLayout.Bounds b=ArtworkLayout.fit(w,h,576,324,banner);
        ok(b.left>=-.005f && b.top>=-.005f && b.right<=576.005f && b.bottom<=324.005f,
            "artwork cropped outside tile: "+w+"x"+h);
        float expected=(float)w/h, actual=b.width()/b.height();
        ok(Math.abs(actual/expected-1f)<.0001f,"aspect ratio stretched: "+w+"x"+h);
    }
    public static void main(String[] args){
        caseFor(1920,1080,true);caseFor(480,270,true);caseFor(1200,200,true);
        caseFor(320,320,false);caseFor(96,128,false);caseFor(300,800,true);
        ArtworkLayout.Bounds wide=ArtworkLayout.fit(1200,200,576,324,true);
        ok(wide.width()>=575.9f && wide.height()<120,"extra-wide banners must letterbox, never crop");
        ArtworkLayout.Bounds square=ArtworkLayout.fit(512,512,576,324,false);
        ok(square.height()<300f,"square icons should have safe visual breathing room");
        System.out.println("PASS: center-inside banner artwork, aspect preservation, square icons and extreme aspect ratios");
    }
}
