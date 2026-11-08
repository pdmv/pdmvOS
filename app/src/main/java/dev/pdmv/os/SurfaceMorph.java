package dev.pdmv.os;

/** Shared material-space interpolation for launcher, menu and settings surfaces.
 * Geometry only: no Android dependencies, allocations of paths or bitmap work.
 * Do not tie this to a particular component's spring; each context owns timing.
 */
public final class SurfaceMorph {
    private SurfaceMorph() {}

    /** Preserve the signed oscillator displacement through the equilibrium point. */
    public static float artworkProgress(float progress){return NativeCore.surfaceMorphArtworkProgress(progress);}
    /** Match the prepared tile's superellipse continuously over the final 18%. */
    public static float artworkCornerExponent(float progress){return NativeCore.surfaceMorphArtworkCornerExponent(progress);}

    public static final class Bounds {
        public final float left, top, right, bottom;
        private Bounds(float l, float t, float r, float b) {
            left=l; top=t; right=r; bottom=b;
        }
        public float width() { return right-left; }
        public float height() { return bottom-top; }
    }

    public static float ease(float progress) {
        // Smoothstep gives continuous zero-velocity endpoint joins without
        // introducing extra simulated lag to spring-driven animation.
        return NativeCore.surfaceMorphEase(progress);
    }
    public static Bounds between(float l0,float t0,float r0,float b0,
                                 float l1,float t1,float r1,float b1,float progress) {
        // Caller already supplies a spring value; a second smoothstep reduces detail.
        float[] out=new float[4];
        NativeCore.surfaceMorphBetween(l0,t0,r0,b0,l1,t1,r1,b1,progress,out);
        return new Bounds(out[0],out[1],out[2],out[3]);
    }
    public static void resolveRenderFrame(
            float originL,float originT,float originR,float originB,
            float progress,boolean opening,int morphFill,
            float imageW,float imageH,float[] out12){
        NativeCore.surfaceMorphResolveRenderFrame(
                originL,originT,originR,originB,progress,opening,morphFill,imageW,imageH,out12);
    }
}
