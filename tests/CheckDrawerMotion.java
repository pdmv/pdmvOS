package dev.pdmv.os;

public final class CheckDrawerMotion {
    static void ensure(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args){
        SpringValue open=new SpringValue(0f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);
        open.aim(1f);
        float previous=0f;
        for(int i=0;i<120;i++){
            open.update(1f/60f);
            ensure(open.value>=previous-.00005f,"drawer bounced backward when opening at frame "+i);
            ensure(open.value<=1.00005f,"drawer overshot target and pulled grid below dock");
            previous=open.value;
        }
        ensure(Math.abs(open.value-1f)<.001f,"drawer failed to settle");
        open.aim(0f,MotionProfile.DRAWER_CLOSE_K,MotionProfile.DRAWER_CLOSE_D);
        previous=open.value;
        for(int i=0;i<120;i++){
            open.update(1f/60f);
            ensure(open.value<=previous+.00005f,"drawer bounced forward when closing at frame "+i);
            ensure(open.value>=-.00005f,"drawer undershot closed target");
            previous=open.value;
        }
        ensure(Math.abs(open.value)<.001f,"drawer failed to close");
        System.out.println("PASS: monotonic, no-overshoot spatial drawer open and close");
    }
}
