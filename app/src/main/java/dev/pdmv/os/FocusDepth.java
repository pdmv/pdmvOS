package dev.pdmv.os;

/**
 * Directional illumination impulse for Android TV DPAD focus.
 * Apple TV's touch parallax cannot be copied literally with a DPAD-only remote,
 * so this class supplies a restrained, event-driven analogue. It NEVER moves
 * the tile layout or changes the focus hit box; only a subtle highlight shifts.
 * No perpetual animation: springs settle and the View stops invalidating.
 */
public final class FocusDepth {
    private final SpringValue horizontal=new SpringValue(0f,180f,23f);
    private final SpringValue vertical=new SpringValue(0f,180f,23f);

    /** Kick light opposite the incoming navigation direction, then let it settle. */
    public void navigate(int dx,int dy){
        if(dx!=0){
            long packed=NativeCore.focusDepthKickX(horizontal.value,horizontal.velocity,dx);
            horizontal.value=Float.intBitsToFloat((int)(packed>>>32));
            horizontal.velocity=Float.intBitsToFloat((int)packed);
            horizontal.aim(0f);
        }
        if(dy!=0){
            long packed=NativeCore.focusDepthKickY(vertical.value,vertical.velocity,dy);
            vertical.value=Float.intBitsToFloat((int)(packed>>>32));
            vertical.velocity=Float.intBitsToFloat((int)packed);
            vertical.aim(0f);
        }
    }
    public boolean update(float dt){
        boolean a=horizontal.update(dt);
        boolean b=vertical.update(dt);
        return a||b;
    }
    public float x(){return horizontal.value;}
    public float y(){return vertical.value;}
    public boolean active(){return horizontal.busy()||vertical.busy();}
}
