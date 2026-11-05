package dev.pdmv.os;

public final class CheckSurfaceMorph {
    private static void near(float actual,float target,String message) {
        if(Math.abs(actual-target)>.0005f)throw new AssertionError(message+" got="+actual+" expected="+target);
    }
    public static void main(String[] args) {
        for(int i=0;i<=100;i++) {
            float p=i/100f;
            SurfaceMorph.Bounds opening=SurfaceMorph.between(560,650,812,792,0,0,1920,1080,p);
            SurfaceMorph.Bounds closing=SurfaceMorph.between(0,0,1920,1080,560,650,812,792,1-p);
            near(opening.left,closing.left,"morph symmetry left");
            near(opening.bottom,closing.bottom,"morph symmetry bottom");
            if(opening.width()<=0 || opening.height()<=0)throw new AssertionError("collapsed morph");
        }
        SurfaceMorph.Bounds begin=SurfaceMorph.between(560,650,812,792,0,0,1920,1080,0);
        SurfaceMorph.Bounds finish=SurfaceMorph.between(560,650,812,792,0,0,1920,1080,1);
        near(begin.top,650,"start source");near(finish.right,1920,"end destination");
        near(SurfaceMorph.ease(-1),0,"clamp below");near(SurfaceMorph.ease(2),1,"clamp above");
        near(SurfaceMorph.artworkProgress(-.007f),-.007f,"return must preserve signed momentum");
        near(SurfaceMorph.artworkCornerExponent(0f),3.05f,"endpoint must match native tile shape");
        near(SurfaceMorph.artworkCornerExponent(.18f),2f,"large surface stays round-rect");
        float last=3.05f;
        for(int i=0;i<=180;i++){
            float exponent=SurfaceMorph.artworkCornerExponent(i/1000f);
            if(exponent>last+.00001f||exponent<2f)throw new AssertionError("corner discontinuity");
            last=exponent;
        }
        float referenceMin=0;
        for(int hz:new int[]{30,60,120}){
            SpringValue spring=new SpringValue(1f,MotionProfile.RETURN_K,MotionProfile.RETURN_D);spring.aim(0);
            float minimum=1f;boolean crossed=false,rebounded=false;
            for(int i=0;i<hz*3&&spring.busy();i++){
                spring.update(1f/hz);float p=SurfaceMorph.artworkProgress(spring.value);
                minimum=Math.min(minimum,p);if(p<0)crossed=true;if(crossed&&spring.velocity>0)rebounded=true;
                if(265f+(1920f-265f)*p<=0||149f+(1080f-149f)*p<=0)throw new AssertionError("collapsed return surface");
            }
            if(!crossed||!rebounded||spring.busy()||minimum>-.005f||minimum<-.03f)throw new AssertionError("return is not a bounded dissipating oscillator");
            if(hz==30)referenceMin=minimum;else if(Math.abs(referenceMin-minimum)>.001f)throw new AssertionError("frame-rate-dependent return rebound");
        }
        System.out.println("PASS: source-anchored morph symmetry, bounds, endpoint clamping");
    }
}
