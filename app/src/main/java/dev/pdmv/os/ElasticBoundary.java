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
            speed=NativeCore.elasticApproachMoved(direction,lastMove,speed,next,time);
            direction=next;lastMove=time;
        }
        public float speed(int next,long time){
            return NativeCore.elasticApproachSpeed(direction,lastMove,speed,next,time);
        }
    }
    public static final class Axis {
        public float value,velocity;
        private float direction;
        public void aim(float next){
            // A short tap still transfers momentum even if key-up precedes VSYNC.
            long packed=NativeCore.elasticAxisAim(value,velocity,direction,next);
            velocity=Float.intBitsToFloat((int)(packed>>>32));
            direction=Float.intBitsToFloat((int)packed);
        }
        public void press(float next,float approachSpeed){
            // One collision impulse, attenuated as the rubber is already stretched.
            // Keep existing velocity so rapid taps can reinforce or arrest recoil.
            long packed=NativeCore.elasticAxisPress(value,velocity,direction,next,approachSpeed);
            velocity=Float.intBitsToFloat((int)(packed>>>32));
            direction=Float.intBitsToFloat((int)packed);
        }
        public boolean update(float dt){
            if(direction==0f&&value==0f&&velocity==0f)return false;
            if(dt<=0f)return busy();
            // Midpoint integration resolves progressive rubber force at 240 Hz,
            // independently of the display's frame rate and dropped frames.
            long packed=NativeCore.elasticAxisUpdate(value,velocity,direction,dt);
            value=Float.intBitsToFloat((int)(packed>>>32));
            velocity=Float.intBitsToFloat((int)packed);
            return busy();
        }
        public boolean busy(){return NativeCore.elasticAxisBusy(value,velocity,direction);}
    }
}
