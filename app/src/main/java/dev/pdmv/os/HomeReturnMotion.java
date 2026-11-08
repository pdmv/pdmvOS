package dev.pdmv.os;

/**
 * Normalized return-to-Home geometry inspired by the visual relationship of
 * an Apple TV icon and its home position. This remains strictly launcher-side:
 * Android owns the actual cross-process window animation.
 *
 * p=1: large centered tile; p=0: the tile's original grid/dock position.
 * Both scale and translation use the SAME progress from a single spring.
 */
public final class HomeReturnMotion {
    private HomeReturnMotion() { }
    public static final float MAX_SCALE=3.25f;
    public static final float DEST_X=960f, DEST_Y=540f;

    public static float position(float source, float destination, float p) {
        return NativeCore.homeReturnPosition(source, destination, p);
    }
    public static float scale(float p) {
        return NativeCore.homeReturnScale(p);
    }
    public static float backdropOpacity(float p) {
        // Stronger early separation, with no secondary easing of position/scale.
        return NativeCore.homeReturnBackdropOpacity(p);
    }
}
