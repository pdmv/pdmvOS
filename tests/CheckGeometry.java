package dev.pdmv.os;

public final class CheckGeometry {
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args){
        UiGeometry.assertInvariants();
        check(UiGeometry.VISIBLE_ROWS==3,"three app-list rows expected");
        float peek=1080f-(UiGeometry.rowTop(0,0)+UiGeometry.gridTransitionOffset(0)+(UiGeometry.TILE_MAX_HEIGHT-UiGeometry.TILE_IDLE_HEIGHT)/2f);
        check(peek<=0f,"Home must not show app-list edges");
        check(UiGeometry.APP_LABEL_SIZE==25f,"app label size");
        check(UiGeometry.LABEL_BASELINE-171f==8f,"label must move down 8px, not change font size");
        float bottom=UiGeometry.rowTop(UiGeometry.VISIBLE_ROWS-1,0)+(UiGeometry.TILE_MAX_HEIGHT+UiGeometry.TILE_IDLE_HEIGHT)/2f;
        check(Math.abs(1080-bottom-UiGeometry.ROW_GAP)<.001f,"bottom gap differs from top/row gaps");
        check((UiGeometry.LABEL_BASELINE-UiGeometry.APP_LABEL_SIZE-UiGeometry.TILE_MAX_HEIGHT)*(1+UiGeometry.FOCUS_GAIN)>=12f,"label too close to enlarged tile");
        check(UiGeometry.rowTop(2,0)+71f+(UiGeometry.LABEL_BASELINE-71f+8f)*(1+UiGeometry.FOCUS_GAIN)<UiGeometry.GRID_CLIP_BOTTOM,"last label clipped");
        float focusedWidth=UiGeometry.TILE_MAX_WIDTH*(1f+UiGeometry.FOCUS_GAIN);
        float focusedHeight=UiGeometry.TILE_MAX_HEIGHT*(1f+UiGeometry.FOCUS_GAIN);
        check(focusedWidth<UiGeometry.PITCH,"enlarged focus overlaps next cell");
        for(int i=0;i<=1025;i++){
            float z=i/1000f;
            float width=(UiGeometry.TILE_IDLE_WIDTH+(UiGeometry.TILE_MAX_WIDTH-UiGeometry.TILE_IDLE_WIDTH)*z)*(1f+UiGeometry.FOCUS_GAIN*z);
            check(width<UiGeometry.PITCH,"spring overshoot overlaps next cell");
        }
        check((UiGeometry.DOCK_HEIGHT-focusedHeight)/2f>=16f,"dock cannot contain enlarged focus ");
        check(UiGeometry.DOCK_PADDING-(focusedWidth-UiGeometry.TILE_MAX_WIDTH)/2f>=16f,"dock horizontal focus padding");
        float idleTop=UiGeometry.DOCK_APPS_TOP+UiGeometry.DOCK_PADDING+(UiGeometry.TILE_MAX_HEIGHT-UiGeometry.TILE_IDLE_HEIGHT)/2;
        check(Math.abs(idleTop-UiGeometry.ROW_GAP)<.001f,"top margin must equal row gap");
        check(Math.abs(UiGeometry.ROW_PITCH-UiGeometry.TILE_IDLE_HEIGHT-idleTop)<.001f,"visible row gap must equal top margin");
        for(int count=1;count<100;count++)for(int row=0;row<17;row++)for(int column=0;column<6;column++){
            int expected=Math.min(count-1,Math.min(row,(count-1)/6)*6+column);
            check(UiGeometry.belowDock(column,row,count)==expected,"dock down must target visible row/column");
        }
        check(UiGeometry.belowDock(0,0,0)==-1,"empty list has no target");
        check(UiGeometry.DOCK_RADIUS==UiGeometry.TILE_RADIUS+UiGeometry.DOCK_PADDING,"outer radius");
        check(UiGeometry.TILE_RADIUS>=58f,"tile corners regressed to square");
        check(UiGeometry.DOCK_RADIUS>=62f,"dock corners regressed to square");
        check(UiGeometry.TILE_MAX_WIDTH<UiGeometry.PITCH,"focus can overlap");
        for(int i=0;i<6;i++){
            float cx=UiGeometry.centerX(i);
            check(cx-UiGeometry.TILE_MAX_WIDTH/2f>=0,"left overflow");
            check(cx+UiGeometry.TILE_MAX_WIDTH/2f<=1920,"right overflow");
            if(i>0)check(cx-UiGeometry.centerX(i-1)>=UiGeometry.TILE_MAX_WIDTH+10,"tile spacing");
        }
        for(int r=0;r<UiGeometry.VISIBLE_ROWS;r++){
            float top=UiGeometry.rowTop(r,0);
            check(top+UiGeometry.TILE_MAX_HEIGHT+30<1080,"label clipped at row "+r);
        }
        check(UiGeometry.HEIGHT-UiGeometry.DOCK_HOME_TOP-UiGeometry.DOCK_HEIGHT==UiGeometry.HEADER_TOP,"home dock bottom margin must match header top margin");
        // Rows enter at the screen edge; resting layout keeps its existing gap.
        for(int i=0;i<=100;i++){
            float progress=i/100f;
            float dockBottom=UiGeometry.dockTop(progress)+UiGeometry.DOCK_HEIGHT;
            float gridFirstRowTop=UiGeometry.GRID_TOP+UiGeometry.gridTransitionOffset(progress);
            float visibleDockBottom=dockBottom-UiGeometry.DOCK_PADDING;
            float rowGap=UiGeometry.ROW_PITCH-UiGeometry.TILE_MAX_HEIGHT;
            check(UiGeometry.gridClipBottom(progress)==UiGeometry.HEIGHT,"motion clipped above screen edge");
            if(i==0)check(Math.abs(gridFirstRowTop-UiGeometry.HEIGHT)<.005f,"first row must start at bottom edge");
            if(i==100)check(Math.abs(gridFirstRowTop-visibleDockBottom-rowGap)<.005f,"resting row gap changed");
            check(Math.abs(UiGeometry.drawerGridClipTop(progress)-(dockBottom-UiGeometry.DOCK_PADDING)-12f)<.005f,"grid clip mismatch");
        }
        check(UiGeometry.POPUP_RADIUS==UiGeometry.POPUP_ROW_RADIUS+UiGeometry.POPUP_PADDING,
            "popup radius invariant");
        check(UiGeometry.popupItemScale(1,0)==1f && UiGeometry.popupItemScale(0,0)<UiGeometry.popupItemScale(1,1),
            "focused popup item must fit the inset and keep its press travel");
        check(UiGeometry.PREVIEW_OUTER_RADIUS==UiGeometry.PREVIEW_INNER_RADIUS+UiGeometry.PREVIEW_PADDING,
            "preview radius invariant");
        for(int rows=1;rows<=12;rows++){
            float lastBottom=UiGeometry.POPUP_HEADER+(rows-1)*UiGeometry.POPUP_PITCH+
                UiGeometry.POPUP_ROW_HEIGHT;
            check(Math.abs(UiGeometry.popupHeight(rows)-lastBottom-UiGeometry.POPUP_PADDING)<.0001f,
                "popup bottom padding at "+rows+" rows");
        }
        SpringValue x=new SpringValue(0,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        x.aim(1);
        for(int i=0;i<100;i++)x.update(1f/60f);
        check(Math.abs(x.value-1)<0.005f,"spring convergence");
        x.aim(0);
        for(int i=0;i<100;i++)x.update(1f/60f);
        check(Math.abs(x.value)<0.005f,"reverse spring convergence");
        for(int i=0;i<50;i++)x.aim(i%2==0?1:0);
        check(!Float.isNaN(x.value),"spring remains stable when direction changes");
        System.out.println("PASS: geometry, safe areas, glass radii, synchronized drawer/grid, spring convergence/reversal");
    }
}
