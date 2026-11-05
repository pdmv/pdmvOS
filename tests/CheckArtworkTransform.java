package dev.pdmv.os;

public final class CheckArtworkTransform {
    private static void near(float got, float expected, String label) {
        if(Math.abs(got-expected)>.005f)throw new AssertionError(label+" got "+got);
    }
    public static void main(String[] args) {
        final float sourceWidth=768f,sourceHeight=432f,corner=59f;
        for(float[] dest: new float[][] {{228,128},{236,133},{252,142},{246,139}}) {
            float sx=ArtworkTransform.scale(dest[0],sourceWidth);
            float sy=ArtworkTransform.scale(dest[1],sourceHeight);
            float sourceRadius=ArtworkTransform.sourceRadius(corner,sourceWidth,dest[0]);
            near(sx*sourceWidth,dest[0],"image fills width");
            near(sy*sourceHeight,dest[1],"image fills height");
            near(sourceRadius*sx,corner,"consistent squircle corner while focusing");
            if(Math.abs(sx-sy)>.003f)throw new AssertionError("tile artwork aspect ratio mismatch");
        }
        System.out.println("PASS: image/path common coordinates, stable radius across focus frames");
    }
}
