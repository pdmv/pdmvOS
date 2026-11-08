package dev.pdmv.os;

/** Analytical damped oscillator: frame-rate independent and interruptible. */
public final class SpringValue {
    public float value, target, velocity;
    private float stiffness,damping;
    public SpringValue(float start,float stiffness,float damping){
        this.value=start;this.target=start;this.stiffness=stiffness;this.damping=damping;
    }
    public void setDynamics(float nextStiffness,float nextDamping){
        stiffness=nextStiffness;damping=nextDamping;
    }
    public void aim(float next){target=next;}
    /** Change destination and motion profile without resetting velocity. */
    public void aim(float next,float nextStiffness,float nextDamping){
        stiffness=nextStiffness;damping=nextDamping;target=next;
    }
    public boolean update(float dt){
        if(value==target&&velocity==0f)return false;
        if(dt<=0f)return busy();
        // The solution is analytical, so dropped frames do not require small Euler steps.
        // Catch up real elapsed time after a slow 100–200 ms TV frame instead of
        // artificially extending the animation when FPS dips. Cap huge pause gaps.
        long packed=NativeCore.springUpdate(value,target,velocity,stiffness,damping,dt);
        value=Float.intBitsToFloat((int)(packed>>>32));
        velocity=Float.intBitsToFloat((int)packed);
        return busy();
    }
    public boolean busy(){return value!=target||velocity!=0f;}
}
