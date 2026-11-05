package dev.pdmv.os;

/**
 * pdmvOS motion language.
 *
 * Values intentionally differ by interaction context. Frequent focus movement starts
 * immediately but has a visible soft settle; press feedback is quicker; large spatial
 * transitions are longer so users can track where content moved. Every spring is
 * interruptible and retains its velocity when the target changes.
 */
public final class MotionProfile {
    private MotionProfile(){}
    public static float arrangeAngle(double seconds,int identity,boolean selected){
        double phase=(identity&1023)*.013;
        return (selected?.8f:.65f)*(float)Math.sin(seconds*21.36+phase);
    }
    /** Inertial lean opposes actual travel speed, not the requested DPAD direction. */
    public static float reorderTilt(float vx,float vy,boolean selected){
        if(vx==0f&&vy==0f)return 0f;
        return -(selected?3f:1.6f)*(float)Math.tanh(vx/1700f)
            +.45f*(float)Math.tanh(vy/1300f);
    }
    /** Smooth bounded strain from kinetic energy; zero speed means no deformation. */
    public static float strain(float velocity,float referenceSpeed,float maximum){
        if(velocity==0f)return 0f;
        float ratio=velocity/referenceSpeed;
        return maximum*(float)Math.tanh(ratio*ratio);
    }

    // Focus: prompt response with a restrained elastic settle.
    public static final float FOCUS_IN_K=420f, FOCUS_IN_D=30f;
    public static final float FOCUS_OUT_K=480f, FOCUS_OUT_D=36f;

    // Labels are quieter and slightly more damped than tiles.
    public static final float LABEL_IN_K=440f, LABEL_IN_D=40f;
    public static final float LABEL_OUT_K=520f, LABEL_OUT_D=44f;

    // Reorder neighbors should glide into vacancies, not snap.
    public static final float REORDER_K=320f, REORDER_D=29f;

    // Large surfaces carry more mass than selection and press feedback.
    public static final float DRAWER_OPEN_K=360f, DRAWER_OPEN_D=38f;
    public static final float DRAWER_CLOSE_K=440f,DRAWER_CLOSE_D=42f;
    public static final float SCROLL_K=400f, SCROLL_D=40f;
    public static final float SETTINGS_OPEN_K=340f, SETTINGS_OPEN_D=36f;
    public static final float SETTINGS_CLOSE_K=420f,SETTINGS_CLOSE_D=40f;

    // Press engages immediately but releases with a softer rebound.
    public static final float PRESS_IN_K=900f, PRESS_IN_D=52f;
    public static final float PRESS_OUT_K=520f,PRESS_OUT_D=30f;

    public static final float HEADER_IN_K=420f, HEADER_IN_D=33f;
    public static final float HEADER_OUT_K=480f,HEADER_OUT_D=37f;

    // Menus are light surfaces; travel is short and response is immediate.
    public static final float MENU_OPEN_K=600f, MENU_OPEN_D=46f;
    public static final float MENU_CLOSE_K=760f,MENU_CLOSE_D=54f;
    public static final float MENU_SELECTION_K=700f,MENU_SELECTION_D=40f;
    public static final float LAUNCH_K=480f,LAUNCH_D=40f;
    // Unit-mass underdamped return: momentum crosses equilibrium, then dissipates.
    public static final float RETURN_K=240f,RETURN_D=25f;

    public static final float SELECTION_K=620f, SELECTION_D=38f;

    // Initial velocity makes the first frame perceptible; no waiting for focus-out.
    public static final float FOCUS_ENTER_VELOCITY=1.4f;
    public static final float FOCUS_LEAVE_VELOCITY=1.7f;
    public static final float LABEL_ENTER_VELOCITY=.6f;
    public static final float LABEL_LEAVE_VELOCITY=.8f;
    public static final float FOCUS_LIFT=3.4f;
    public static final float FOCUS_GROWTH=0.115f;
    public static final float PRESS_DEPTH=0.030f;
    public static final float MENU_START_SCALE=.966f;
    public static final float MENU_START_Y=10f;
    public static final float LABEL_START_Y=-14f; // labels descend into place
}
