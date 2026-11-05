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
        double t=Math.min(.25,dt), x=value-target, v=velocity;
        double gamma=damping*.5, w0=Math.sqrt(stiffness);
        double nx,nv;
        if(gamma<w0-1e-5){
            double w=Math.sqrt(w0*w0-gamma*gamma), e=Math.exp(-gamma*t);
            double a=x,b=(v+gamma*x)/w;
            double cs=Math.cos(w*t),sn=Math.sin(w*t);
            nx=e*(a*cs+b*sn);
            nv=e*((b*w-gamma*a)*cs+(-a*w-gamma*b)*sn);
        }else if(gamma>w0+1e-5){
            double s=Math.sqrt(gamma*gamma-w0*w0),r1=-gamma+s,r2=-gamma-s;
            double c1=(v-r2*x)/(r1-r2),c2=x-c1;
            nx=c1*Math.exp(r1*t)+c2*Math.exp(r2*t);
            nv=r1*c1*Math.exp(r1*t)+r2*c2*Math.exp(r2*t);
        }else{
            double e=Math.exp(-gamma*t),b=v+gamma*x;
            nx=e*(x+b*t);nv=e*(v-gamma*b*t);
        }
        value=target+(float)nx;velocity=(float)nv;
        if(Math.abs(value-target)<.0005f && Math.abs(velocity)<.012f){
            value=target;velocity=0f;return false;
        }
        return true;
    }
    public boolean busy(){return value!=target||velocity!=0f;}
}
