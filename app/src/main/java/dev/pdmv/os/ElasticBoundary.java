package dev.pdmv.os;

/** Unit-mass rubber body: external force, progressive stiffness and viscous drag.
 * Input never teleports displacement or discards momentum. Repeats hold a force,
 * rather than adding energy at a remote-specific repeat rate.
 */
public final class ElasticBoundary {
    public final Axis x=new Axis(),y=new Axis();
    public boolean update(float dt){return x.update(dt)|y.update(dt);}
    public void release(){x.aim(0f);y.aim(0f);}
    /** Successful navigation supplies incoming speed; held boundary repeats do not. */
    public static final class Approach {
        private int direction;
        private long lastMove;
        private float speed;
        public void moved(int next,long time){
            long interval=time-lastMove;
            if(next==direction&&interval>0&&interval<500){
                float measured=Math.min(16f,1000f/Math.max(40,interval));
                speed=speed==0f?measured:speed*.35f+measured*.65f;
            }else speed=0f;
            direction=next;lastMove=time;
        }
        public float speed(int next,long time){
            long age=time-lastMove;
            if(next!=direction||age<0||age>500)return 0f;
            return speed*(float)Math.exp(-age/180f);
        }
    }
    public static final class Axis {
        public float value,velocity;
        private float direction;
        public void aim(float next){
            next=Math.max(-1f,Math.min(1f,next));
            // A short tap still transfers momentum even if key-up precedes VSYNC.
            if(next!=0f && direction==0f && Math.abs(velocity)<.1f)velocity+=next*2.4f;
            direction=next;
        }
        public void press(float next,float approachSpeed){
            next=Math.max(-1f,Math.min(1f,next));
            if(next!=0f&&direction==0f){
                // One collision impulse, attenuated as the rubber is already stretched.
                // Keep existing velocity so rapid taps can reinforce or arrest recoil.
                float incoming=Float.isFinite(approachSpeed)?Math.max(0f,Math.min(16f,approachSpeed)):0f;
                velocity=Math.max(-14f,Math.min(14f,velocity+next*(2.4f+incoming*.45f)/(1f+2f*Math.abs(value))));
            }
            direction=next;
        }
        private float acceleration(float position,float speed){
            // Loaded rubber settles firmly; unloaded rubber releases stored energy
            // with a small damped recoil instead of following a fixed easing curve.
            float drag=direction==0f?15f:19f;
            return 140f*direction-110f*position-90f*position*position*position-drag*speed;
        }
        public boolean update(float dt){
            if(direction==0f&&value==0f&&velocity==0f)return false;
            if(dt<=0f)return busy();
            float elapsed=Math.min(.25f,dt);
            int steps=Math.max(1,(int)Math.ceil(elapsed*240f));float h=elapsed/steps;
            for(int i=0;i<steps;i++){
                // Midpoint integration resolves progressive rubber force at 240 Hz,
                // independently of the display's frame rate and dropped frames.
                float a=acceleration(value,velocity);
                float midV=velocity+a*h*.5f,midX=value+velocity*h*.5f;
                value+=midV*h;velocity+=acceleration(midX,midV)*h;
            }
            if(!busy() && direction==0f){value=0f;velocity=0f;}
            return busy();
        }
        public boolean busy(){return Math.abs(velocity)>.008f||Math.abs(acceleration(value,0f))>.035f;}
    }
}
