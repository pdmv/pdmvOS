package dev.pdmv.os;

/** Contextual springs must be stable, interruptible and nearly frame-rate independent. */
public final class CheckMotion {
    private static void require(boolean condition,String description){
        if(!condition)throw new AssertionError(description);
    }
    private static SpringValue simulate(float hz,float seconds,float stiffness,float damping){
        SpringValue spring=new SpringValue(0f,stiffness,damping);
        spring.aim(1f);
        float elapsed=0f;
        while(elapsed<seconds-0.000001f){
            float dt=Math.min(1f/hz,seconds-elapsed);
            spring.update(dt);elapsed+=dt;
        }
        return spring;
    }
    public static void main(String[] args){
        float[][] cases={
            {MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D},
            {MotionProfile.FOCUS_OUT_K,MotionProfile.FOCUS_OUT_D},
            {MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D},
            {MotionProfile.DRAWER_CLOSE_K,MotionProfile.DRAWER_CLOSE_D},
            {MotionProfile.REORDER_K,MotionProfile.REORDER_D},
            {MotionProfile.PRESS_IN_K,MotionProfile.PRESS_IN_D},
            {MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D},
            {MotionProfile.MENU_OPEN_K,MotionProfile.MENU_OPEN_D},
            {MotionProfile.MENU_CLOSE_K,MotionProfile.MENU_CLOSE_D}
        };
        for(float[] c:cases){
            for(float seconds:new float[]{.05f,.1f,.2f,.3f,.45f}){
                SpringValue a=simulate(60f,seconds,c[0],c[1]);
                SpringValue b=simulate(30f,seconds,c[0],c[1]);
                require(Math.abs(a.value-b.value)<.04f,"30/60 Hz mismatch: "+c[0]+" "+seconds);
                require(Float.isFinite(a.value)&&Float.isFinite(a.velocity),"non-finite animation");
            }
            SpringValue a=simulate(60f,.9f,c[0],c[1]);
            require(Math.abs(1f-a.value)<.01f,"slow settling: "+c[0]);
            a.aim(0f,c[0],c[1]);
            for(int i=0;i<60;i++)a.update(1f/60f);
            require(Math.abs(a.value)<.01f,"reverse settling: "+c[0]);
        }
        // A 140ms rendering stall must not turn a 300ms focus transition into a
        // sluggish multi-frame sequence; analytic integration catches up safely.
        SpringValue stalled=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        SpringValue steady=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        stalled.aim(1f);steady.aim(1f);
        stalled.update(.14f);
        for(int i=0;i<14;i++)steady.update(.01f);
        require(Math.abs(stalled.value-steady.value)<.001f,"spring lost time during a dropped frame");
        // Retargeting a moving object changes force, never its instantaneous velocity.
        SpringValue reversal=simulate(60f,.05f,MotionProfile.MENU_OPEN_K,MotionProfile.MENU_OPEN_D);
        float v=reversal.velocity;
        reversal.aim(0f,MotionProfile.MENU_CLOSE_K,MotionProfile.MENU_CLOSE_D);
        require(reversal.velocity==v,"menu reversal discarded momentum");
        SpringValue menu=simulate(60f,.20f,MotionProfile.MENU_OPEN_K,MotionProfile.MENU_OPEN_D);
        require(menu.value>.94f,"menu takes too long to become usable");
        SpringValue repeated=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        for(int i=0;i<90;i++){
            if(i%2==0)repeated.aim(1f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
            else repeated.aim(0f,MotionProfile.FOCUS_OUT_K,MotionProfile.FOCUS_OUT_D);
            repeated.update(1f/60f);
            require(Float.isFinite(repeated.value)&&Float.isFinite(repeated.velocity),
                "rapid D-pad direction change destabilized spring");
        }
        require(MotionProfile.reorderTilt(0,0,true)==0f,"idle tile must not lean");
        require(MotionProfile.reorderTilt(1500,0,true)<0f && MotionProfile.reorderTilt(-1500,0,true)>0f,"lean must oppose velocity");
        require(Math.abs(MotionProfile.reorderTilt(100000,100000,true))<=3.45f,"unbounded travel lean");
        System.out.println("PASS: contextual in/out springs are stable, reversible and 30/60Hz tolerant");
    }
}
