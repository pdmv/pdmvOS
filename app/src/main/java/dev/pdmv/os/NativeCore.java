package dev.pdmv.os;

/** JNI bridge to the native Rust pdmvos_core library. */
final class NativeCore {
    static {
        System.loadLibrary("pdmvos_core");
    }

    private NativeCore() {}

    static native void pixelBlurApply(int[] pixels, int[] scratch, int width, int height, int radius);
    static native void pixelBlurBlend(int[] low, int[] high, int[] out, int length, int alpha);

    static native int artworkFallbackTextColor(int backgroundArgb);
    static native int artworkStep(int width, int height);
    static native boolean artworkHasDetailTriplets(int[] triplets);
    static native int artworkDominantFillColor(int[] samples, int fallback);
    static native int artworkControlFocusTint(int[] samples);

    static native void artworkLayoutFit(int sourceWidth, int sourceHeight, float boxWidth, float boxHeight, boolean banner, float[] out4);
    static native void artworkCenterCropRect(int srcW, int srcH, float dstW, float dstH, int[] out4);

    static native float artworkTransformScale(float destinationSize, float sourceSize);
    static native float artworkTransformSourceRadius(float designRadius, float sourceWidth, float destinationWidth);
    static native void artworkSmoothCorners(int[] source, int[] masked, int width, int height, int gutter, float radius, float n);

    static native int displayPolicySelect(int[] widths, int[] heights, float[] rates, int current);

    static native long springUpdate(float value, float target, float velocity, float stiffness, float damping, float dt);

    static native boolean drawerFlowUpdate(float[] state8, float target, float elapsed);
    static native float drawerFlowRowProgress(float r0, float r1, float r2, float relativeRow);

    static native float elasticApproachMoved(int direction, long lastMove, float speed, int next, long time);
    static native float elasticApproachSpeed(int direction, long lastMove, float speed, int next, long time);
    static native long elasticAxisAim(float value, float velocity, float direction, float next);
    static native long elasticAxisPress(float value, float velocity, float direction, float next, float approachSpeed);
    static native long elasticAxisUpdate(float value, float velocity, float direction, float dt);
    static native boolean elasticAxisBusy(float value, float velocity, float direction);

    static native long focusDepthKickX(float value, float velocity, int dx);
    static native long focusDepthKickY(float value, float velocity, int dy);

    static native float motionArrangeAngle(double seconds, int identity, boolean selected);
    static native float motionReorderTilt(float vx, float vy, boolean selected);
    static native float motionStrain(float velocity, float referenceSpeed, float maximum);
    static native float motionStepExponential(float value, float target, float dt, float tau, float epsilon);
    static native void motionClockCharTransition(float progress, int slot, int totalSlots, float travelY, float[] out7);
    static native void motionResolveTileTransform(
            float x, float y, float scaleVal, float pushed, float presenceVal,
            boolean retired, boolean exiting, boolean selected, boolean traveling,
            boolean dock, boolean edgeStretched,
            float canvasLeft, float canvasTop, float canvasScale,
            float edgeX, float edgeY, float edgeAnchorX, float edgeAnchorY,
            float vx, float vy, float scrollStrain,
            float offsetX, float offsetY, float drawerVal, boolean hiddenForMorph,
            float[] out8);

    static native void menuMotionResolve(float progress, float velocity, float sourceX, float sourceY, float targetX, float targetY, float height, float[] out6);
    static native void menuMotionResolveFromScale(float progress, float velocity, float sourceX, float sourceY, float targetX, float targetY, float start, float[] out6);

    static native float homeReturnPosition(float source, float destination, float p);
    static native float homeReturnScale(float p);
    static native float homeReturnBackdropOpacity(float p);

    static native float surfaceMorphArtworkProgress(float progress);
    static native float surfaceMorphArtworkCornerExponent(float progress);
    static native float surfaceMorphEase(float progress);
    static native void surfaceMorphBetween(float l0, float t0, float r0, float b0, float l1, float t1, float r1, float b1, float progress, float[] out4);
    static native void surfaceMorphResolveRenderFrame(float originL, float originT, float originR, float originB, float progress, boolean opening, int morphFill, float imageW, float imageH, float[] out12);

    static native float geometryPopupItemScale(float focus, float press);
    static native float geometryPopupHeight(int count);
    static native void geometryMenuRect(int itemCount, int sourceHeader, boolean nearTile, float anchorX, float anchorY, float[] out4);
    static native void geometryDialogRect(int lineCount, boolean hasProgress, float[] out4);
    static native void geometryDialogButtonRect(float panelLeft, float panelRight, float panelBottom, boolean twoButtons, int index, float[] out4);
    static native int geometryBelowDock(int column, int firstVisibleRow, int itemCount);
    static native float geometryCenterX(int col);
    static native float geometryRowTop(int row, float scrollRows);
    static native float geometryGridTransitionOffset(float drawerProgress);
    static native float geometryDrawerGridClipTop(float drawerProgress);
    static native float geometryGridClipBottom(float drawerProgress);
    static native float geometryDockTop(float drawerProgress);
    static native float geometryClamp(float value, float low, float high);
    static native void geometrySquircleVertices(float left, float top, float right, float bottom, float radius, float n, float[] out266);
    static native String geometryCheckInvariants();

    static native int wifiFeedbackObserve(int[] stateAnnounced, long[] statePendingLoss, int current, boolean visible, long now);

    static native int returnGateRequest(int bits);
    static native int returnGateSetActive(int bits, boolean active);
    static native boolean returnGatePending(int bits);
    static native boolean returnGateCanPlay(int bits, boolean loaded, boolean enabled);
    static native long returnGateRetry(int bits);
    static native int returnGateConsume(int bits);

    static native int slotLayoutOp(int op, int arg1, int arg2, int[] dock6, int[] gridBuf, int gridLen);

    // AirPlay Receiver native bindings
    static native byte[] airplayStart(int mode, byte[] serverName, byte[] deviceId);
    static native void airplayStop();
    static native void airplayStatus(int[] result);
    static native void airplaySetSurface(Object surface);
}
