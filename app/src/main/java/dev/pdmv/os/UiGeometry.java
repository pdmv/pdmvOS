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
        float selected=clamp(focus,0f,1f);
        // The focused footprint is the layout size, so it keeps the panel inset.
        return 1f-(.12f*(1f-selected)+.07f*clamp(press,0f,1f)*selected)/1.06f;
    }
    public static final float PREVIEW_PADDING=10f, PREVIEW_INNER_RADIUS=32f;
    public static final float PREVIEW_OUTER_RADIUS=PREVIEW_INNER_RADIUS+PREVIEW_PADDING;
    public static float popupHeight(int count){
        return POPUP_HEADER + (Math.max(1,count)-1)*POPUP_PITCH+POPUP_ROW_HEIGHT+POPUP_PADDING;
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
        if(itemCount<=0)return -1;
        int row=Math.max(0,Math.min(firstVisibleRow,(itemCount-1)/COLUMNS));
        return Math.min(itemCount-1,row*COLUMNS+Math.max(0,Math.min(COLUMNS-1,column)));
    }
    public static float centerX(int col) {
        if (col < 0 || col >= COLUMNS) throw new IllegalArgumentException("col " + col);
        return DOCK_LEFT + DOCK_PADDING + TILE_MAX_WIDTH/2f + col * PITCH;
    }
    public static float rowTop(int row, float scrollRows) { return GRID_TOP + (row-scrollRows) * ROW_PITCH; }
    public static float gridTransitionOffset(float drawerProgress){
        return (HEIGHT-GRID_TOP)*(1f-drawerProgress);
    }
    public static float drawerGridClipTop(float drawerProgress){
        return dockTop(drawerProgress)+DOCK_PADDING+TILE_MAX_HEIGHT+12f;
    }
    public static float gridClipBottom(float drawerProgress){return HEIGHT;}
    public static float dockTop(float drawerProgress) {
        return DOCK_HOME_TOP + (DOCK_APPS_TOP-DOCK_HOME_TOP)*drawerProgress;
    }
    public static float clamp(float value,float low,float high) { return Math.max(low,Math.min(high,value)); }
    public static void assertInvariants() {
        if (DOCK_RADIUS != TILE_RADIUS+DOCK_PADDING) throw new AssertionError("radii");
        if (POPUP_RADIUS != POPUP_ROW_RADIUS+POPUP_PADDING) throw new AssertionError("popup radius");
        if (PREVIEW_OUTER_RADIUS != PREVIEW_INNER_RADIUS+PREVIEW_PADDING) throw new AssertionError("preview radius");
        if (DOCK_LEFT < 0 || DOCK_LEFT + DOCK_WIDTH > WIDTH) throw new AssertionError("dock offscreen");
        if (TILE_MAX_WIDTH*(1f+FOCUS_GAIN) >= PITCH) throw new AssertionError("focus overlaps neighboring cell");
        if (TILE_RADIUS<=0 || TILE_RADIUS>=TILE_IDLE_HEIGHT/2f) throw new AssertionError("tile corner radius exceeds half idle height");
        if (DOCK_RADIUS>=DOCK_HEIGHT/2f) throw new AssertionError("dock should remain a softened rectangle, not a full capsule");
        if (DOCK_APPS_TOP < 32 || DOCK_HOME_TOP+DOCK_HEIGHT > HEIGHT-48) throw new AssertionError("vertical safe zone");
        if (rowTop(VISIBLE_ROWS-1, 0f)+TILE_MAX_HEIGHT+33f > GRID_CLIP_BOTTOM) throw new AssertionError("grid offscreen");
        for (int i=0;i<COLUMNS;i++) {
            float x=centerX(i);
            if (x-TILE_MAX_WIDTH/2f < DOCK_LEFT+DOCK_PADDING-0.1f) throw new AssertionError("tile left");
            if (x+TILE_MAX_WIDTH/2f > DOCK_LEFT+DOCK_WIDTH-DOCK_PADDING+0.1f) throw new AssertionError("tile right");
        }
    }
}
