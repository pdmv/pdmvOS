package dev.pdmv.os;

/** Allocation-free source-anchored popup geometry driven by a physical spring. */
public final class MenuMotion {
    private MenuMotion(){}
    public static final float OPEN_K=420f,OPEN_D=30f,CLOSE_K=500f,CLOSE_D=36f;
    public static final class Frame {
        public float x,y,scaleX,scaleY,alpha,clarity;
        private final float[] scratch=new float[6];
    }
    public static void resolve(float progress,float velocity,
            float sourceX,float sourceY,float targetX,float targetY,float height,Frame out){
        NativeCore.menuMotionResolve(progress,velocity,sourceX,sourceY,targetX,targetY,height,out.scratch);
        out.x=out.scratch[0];out.y=out.scratch[1];
        out.scaleX=out.scratch[2];out.scaleY=out.scratch[3];
        out.alpha=out.scratch[4];out.clarity=out.scratch[5];
    }
    public static void resolveFromScale(float progress,float velocity,
            float sourceX,float sourceY,float targetX,float targetY,float start,Frame out){
        // A small gravity sag gives the return a falling trajectory. Its weight
        // starts at zero speed, so reversing an opening never teleports the card.
        NativeCore.menuMotionResolveFromScale(progress,velocity,sourceX,sourceY,targetX,targetY,start,out.scratch);
        out.x=out.scratch[0];out.y=out.scratch[1];
        out.scaleX=out.scratch[2];out.scaleY=out.scratch[3];
        out.alpha=out.scratch[4];out.clarity=out.scratch[5];
    }
}
