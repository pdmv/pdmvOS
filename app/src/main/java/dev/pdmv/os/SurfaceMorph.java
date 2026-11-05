package dev.pdmv.os;

/** Shared material-space interpolation for launcher, menu and settings surfaces.
 * Geometry only: no Android dependencies, allocations of paths or bitmap work.
 * Do not tie this to a particular component's spring; each context owns timing.
 */
public final class SurfaceMorph {
    private SurfaceMorph() {}

    /** Preserve the signed oscillator displacement through the equilibrium point. */
    public static float artworkProgress(float progress){return Math.min(1f,progress);}
    /** Match the prepared tile's superellipse continuously over the final 18%. */
    public static float artworkCornerExponent(float progress){return 2f+1.05f*(1f-ease(artworkProgress(progress)/.18f));}

    public static final class Bounds {
        public final float left, top, right, bottom;
        private Bounds(float l, float t, float r, float b) {
            left=l; top=t; right=r; bottom=b;
        }
        public float width() { return right-left; }
        public float height() { return bottom-top; }
    }

    public static float ease(float progress) {
        float p=Math.max(0f,Math.min(1f,progress));
        // Smoothstep gives continuous zero-velocity endpoint joins without
        // introducing extra simulated lag to spring-driven animation.
        return p*p*(3f-2f*p);
    }
    public static Bounds between(float l0,float t0,float r0,float b0,
                                 float l1,float t1,float r1,float b1,float progress) {
        float p=Math.max(0f,Math.min(1f,progress));
        // Caller already supplies a spring value; a second smoothstep reduces detail.
        return new Bounds(l0+(l1-l0)*p,t0+(t1-t0)*p,
                          r0+(r1-r0)*p,b0+(b1-b0)*p);
    }
}
