package dev.pdmv.os;

/**
 * Geometry for drawing an unmasked artwork texture and its squircle together.
 * The bitmap stays in source coordinates. One Canvas transform scales BOTH the
 * image shader and the path; no BitmapShader local matrix is required.
 */
public final class ArtworkTransform {
    private ArtworkTransform() {}

    public static float scale(float destinationSize, float sourceSize) {
        return NativeCore.artworkTransformScale(destinationSize, sourceSize);
    }

    /** Correct for the horizontal scale so the visible corner radius remains constant. */
    public static float sourceRadius(float designRadius, float sourceWidth, float destinationWidth) {
        return NativeCore.artworkTransformSourceRadius(designRadius, sourceWidth, destinationWidth);
    }

    public static void smoothCorners(int[] source, int[] masked, int width, int height, int gutter, float radius, float n) {
        NativeCore.artworkSmoothCorners(source, masked, width, height, gutter, radius, n);
    }
}
