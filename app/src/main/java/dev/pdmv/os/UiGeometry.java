package dev.pdmv.os;

/** Reference viewport sizes in design pixels. Android Canvas scales to the true view surface. */
public final class UiGeometry {
    private UiGeometry() { }
    public static final float WIDTH = 1920f, HEIGHT = 1080f;
    public static final float POPUP_PADDING=20f, POPUP_ROW_HEIGHT=60f;
    public static final float POPUP_GAP=POPUP_PADDING;
    public static final float POPUP_ROW_RADIUS=POPUP_ROW_HEIGHT/2f, POPUP_TEXT_PADDING=20f;
    public static final float POPUP_TITLE_INSET=POPUP_PADDING+10f;
    public static final float POPUP_RADIUS=POPUP_ROW_RADIUS+POPUP_PADDING;
    public static final float POPUP_HEADER=84f, POPUP_PITCH=POPUP_ROW_HEIGHT+POPUP_GAP;
    public static float popupItemScale(float focus,float press){
        // The focused footprint is the layout size, so it keeps the panel inset.
        return NativeCore.geometryPopupItemScale(focus,press);
    }
    public static final float PREVIEW_PADDING=10f, PREVIEW_INNER_RADIUS=32f;
    public static final float PREVIEW_OUTER_RADIUS=PREVIEW_INNER_RADIUS+PREVIEW_PADDING;
    public static float popupHeight(int count){
        return NativeCore.geometryPopupHeight(count);
    }
    public static final int COLUMNS = 6, VISIBLE_ROWS = 3;
    public static final float PITCH = 284f;
    public static final float TILE_MAX_WIDTH = 252f, TILE_MAX_HEIGHT = 142f;
    public static final float TILE_IDLE_WIDTH = 228f, TILE_IDLE_HEIGHT = 128f;
    public static final float TILE_RADIUS = 59f;
    public static final float FOCUS_GAIN = .05f;
    public static final float DOCK_PADDING = 24f;
    public static final float DOCK_RADIUS = TILE_RADIUS + DOCK_PADDING; // invariant: outer r = inner r + padding
    public static final float DOCK_HEIGHT = TILE_MAX_HEIGHT + 2f * DOCK_PADDING;
    public static final float DOCK_WIDTH = TILE_MAX_WIDTH + (COLUMNS - 1) * PITCH + 2f * DOCK_PADDING;
    public static final float DOCK_LEFT = (WIDTH - DOCK_WIDTH) / 2f;
    // Equal visible gaps at the top, between idle artwork rows and at the bottom.
    public static final float ROW_GAP = (HEIGHT-(VISIBLE_ROWS+1)*TILE_IDLE_HEIGHT)/(VISIBLE_ROWS+2);
    public static final float DOCK_APPS_TOP = ROW_GAP-(TILE_MAX_HEIGHT-TILE_IDLE_HEIGHT)/2f-DOCK_PADDING;
    public static final float ROW_PITCH = TILE_IDLE_HEIGHT+ROW_GAP;
    public static final int TILE_NODE_HEIGHT=212;
    public static final float LABEL_BASELINE=179f, APP_LABEL_SIZE=25f;
    public static final float HEADER_TOP=53f;
    public static final float DOCK_HOME_TOP=HEIGHT-HEADER_TOP-DOCK_HEIGHT;
    public static final float GRID_TOP = DOCK_APPS_TOP+DOCK_PADDING+ROW_PITCH;
    public static final float GRID_CLIP_TOP = GRID_TOP-20f;
    public static final float GRID_CLIP_BOTTOM = HEIGHT;
    public static int belowDock(int column,int firstVisibleRow,int itemCount){
        return NativeCore.geometryBelowDock(column,firstVisibleRow,itemCount);
    }
    public static float centerX(int col) {
        return NativeCore.geometryCenterX(col);
    }
    public static float rowTop(int row, float scrollRows) { return NativeCore.geometryRowTop(row, scrollRows); }
    public static float gridTransitionOffset(float drawerProgress){
        return NativeCore.geometryGridTransitionOffset(drawerProgress);
    }
    public static float drawerGridClipTop(float drawerProgress){
        return NativeCore.geometryDrawerGridClipTop(drawerProgress);
    }
    public static float gridClipBottom(float drawerProgress){return NativeCore.geometryGridClipBottom(drawerProgress);}
    public static float dockTop(float drawerProgress) {
        return NativeCore.geometryDockTop(drawerProgress);
    }
    public static float clamp(float value,float low,float high) { return NativeCore.geometryClamp(value,low,high); }
    public static void squircleVertices(float left,float top,float right,float bottom,float radius,float n,float[] out266){
        NativeCore.geometrySquircleVertices(left,top,right,bottom,radius,n,out266);
    }
    public static void menuRect(int itemCount,int sourceHeader,boolean nearTile,float anchorX,float anchorY,float[] out4){
        NativeCore.geometryMenuRect(itemCount,sourceHeader,nearTile,anchorX,anchorY,out4);
    }
    public static void dialogRect(int lineCount,boolean hasProgress,float[] out4){
        NativeCore.geometryDialogRect(lineCount,hasProgress,out4);
    }
    public static void dialogButtonRect(float panelLeft,float panelRight,float panelBottom,boolean twoButtons,int index,float[] out4){
        NativeCore.geometryDialogButtonRect(panelLeft,panelRight,panelBottom,twoButtons,index,out4);
    }
    public static void assertInvariants() {
        String error=NativeCore.geometryCheckInvariants();
        if(error!=null)throw new AssertionError(error);
    }
}
