package dev.pdmv.os;

/** Event-driven focus transfers must start both transitions without sequencing. */
public final class CheckFocusMotion {
    static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        SpringValue a=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        SpringValue b=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        SpringValue c=new SpringValue(0f,MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D);
        FocusMotion motion=new FocusMotion(MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D,
            MotionProfile.FOCUS_OUT_K,MotionProfile.FOCUS_OUT_D,
            MotionProfile.FOCUS_ENTER_VELOCITY,MotionProfile.FOCUS_LEAVE_VELOCITY);
        ok(motion.select(a),"initial focus event should change state");
        for(int i=0;i<40;i++)a.update(1f/60f);
        float before=a.value;
        ok(before>.95f,"first icon did not reach active focus");
        ok(motion.select(b),"transfer should be immediate");
        ok(a.target==0f && b.target==1f,"both targets must update in the same event");
        ok(a.velocity<0f && b.velocity>0f,"outgoing/incoming impulses should be concurrent");
        a.update(1f/60f);b.update(1f/60f);
        ok(a.value<before && b.value>0f,"both icons must move on first transition frame");
        float velocity=b.velocity;
        ok(!motion.select(b),"unchanged focus must not reconfigure spring every frame");
        ok(b.velocity==velocity,"stable focus must keep its existing velocity");
        ok(motion.select(c),"rapid navigation must not wait for the previous icon");
        ok(b.target==0 && c.target==1 && c.velocity>0,"rapid navigation must retarget immediately");
        for(int i=0;i<120;i++){a.update(1f/60);b.update(1f/60);c.update(1f/60);}
        ok(Math.abs(a.value)<.002f && Math.abs(b.value)<.002f && Math.abs(c.value-1f)<.002f,
            "all springs must settle independently");
        // Reverse a tile while it is still entering: the spring must retain its
        // forward velocity and brake naturally, rather than teleport its momentum.
        motion.select(a);
        a.update(.035f);
        float entering=a.velocity;
        ok(entering>0f,"missing incoming momentum");
        motion.select(b);
        ok(a.velocity==entering,"focus transfer overwrote moving tile velocity");
        System.out.println("PASS: simultaneous focus transfer, first-frame feedback, reversal and settled velocities");
    }
}
