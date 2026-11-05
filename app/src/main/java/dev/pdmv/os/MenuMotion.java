package dev.pdmv.os;

/** Allocation-free source-anchored popup geometry driven by a physical spring. */
public final class MenuMotion {
    private MenuMotion(){}
    public static final float OPEN_K=420f,OPEN_D=30f,CLOSE_K=500f,CLOSE_D=36f;
    public static final class Frame {
        public float x,y,scaleX,scaleY,alpha,clarity;
    }
    public static void resolve(float progress,float velocity,
            float sourceX,float sourceY,float targetX,float targetY,float height,Frame out){
        resolveFromScale(progress,velocity,sourceX,sourceY,targetX,targetY,Math.min(.32f,126f/Math.max(1f,height)),out);
    }
    public static void resolveFromScale(float progress,float velocity,
            float sourceX,float sourceY,float targetX,float targetY,float start,Frame out){
        float p=Math.max(0f,Math.min(1.05f,progress));
        float visible=Math.min(1f,p);
        float scale=start+(1f-start)*p;
        // A small gravity sag gives the return a falling trajectory. Its weight
        // starts at zero speed, so reversing an opening never teleports the card.
        float fall=Math.max(0f,(float)Math.tanh(-velocity/5f));
        float strain=MotionProfile.strain(velocity,8f,.035f);
        out.x=sourceX+(targetX-sourceX)*p;
        out.y=sourceY+(targetY-sourceY)*p+64f*visible*(1f-visible)*fall;
        out.scaleX=scale*(1f-.35f*strain);
        out.scaleY=scale*(1f+strain);
        out.alpha=visible;
        out.clarity=Math.max(0f,Math.min(1f,(visible-.18f)/.82f));
    }
}
