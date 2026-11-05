package dev.pdmv.os;

public final class CheckElasticBoundary {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static ElasticBoundary.Axis simulate(int hz){
        ElasticBoundary.Axis a=new ElasticBoundary.Axis();a.aim(1);
        for(int i=0;i<hz/2;i++)a.update(1f/hz);
        return a;
    }
    private static float collisionPeak(float speed,int hz){
        ElasticBoundary.Axis a=new ElasticBoundary.Axis();a.press(1,speed);a.aim(0);
        float peak=0;
        for(int i=0;i<hz*3;i++){a.update(1f/hz);peak=Math.max(peak,a.value);}
        check(!a.busy()&&a.value==0,"impact did not settle");
        return peak;
    }
    public static void main(String[] args){
        ElasticBoundary.Approach approach=new ElasticBoundary.Approach();
        approach.moved(22,1000);approach.moved(22,1080);
        float fast=approach.speed(22,1100);
        check(fast>8f,"rapid approach did not carry speed");
        check(approach.speed(21,1100)==0f,"opposite direction inherited approach speed");
        check(approach.speed(22,1700)==0f,"stale approach retained speed");
        approach.moved(22,1800);approach.moved(22,2200);
        check(approach.speed(22,2220)<fast,"slow approach was as strong as fast approach");
        for(int hz:new int[]{30,60,120}){
            float slow=collisionPeak(0,hz),quick=collisionPeak(12,hz);
            check(quick>slow*2f&&quick<.5f,"collision does not respond proportionally to speed");
            check(Math.abs(quick-collisionPeak(12,240))<.015f,"impact depends on display refresh rate");
            ElasticBoundary.Axis a=new ElasticBoundary.Axis();a.press(1,12);
            float velocity=a.velocity;
            for(int i=0;i<100;i++)a.press(1,12);
            check(a.velocity==velocity,"held remote repeats pumped collision energy");
            a.update(.1f);a.aim(0);float value=a.value;velocity=a.velocity;
            a.press(1,0);
            check(a.value==value&&a.velocity>velocity,"retap discarded displacement or momentum");
            for(int i=0;i<1000;i++){a.aim(0);a.press(1,16);}
            check(Float.isFinite(a.velocity)&&Math.abs(a.velocity)<=14,"rapid input created unbounded energy");
        }
        for(int hz:new int[]{30,60,120}){
            ElasticBoundary.Axis a=new ElasticBoundary.Axis();
            a.aim(1);a.aim(0); // both events before the first VSYNC
            check(a.value==0 && a.velocity>0,"tap must impart momentum without teleporting");
            float peak=0;
            for(int i=0;i<hz*2;i++){a.update(1f/hz);peak=Math.max(peak,a.value);}
            check(peak>.05f && peak<.2f,"tap should have a small visible response");
            check(!a.busy() && a.value==0,"tap should return to rest");
            a.aim(1);peak=0;
            for(int i=0;i<hz*3;i++){
                float v=a.velocity;a.aim(1);check(a.velocity==v,"repeat added energy");
                a.update(1f/hz);peak=Math.max(peak,a.value);
            }
            check(peak<1.1f && a.value>.7f && a.value<1f,"rubber resistance must bound extension");
            check(!a.busy(),"held force must settle without perpetual redraw");
            a.aim(-1);float x=a.value,v=a.velocity;
            a.aim(1);check(a.value==x && a.velocity==v,"reversal discarded momentum");
            a.aim(0);
            boolean rebound=false;
            for(int i=0;i<hz*3;i++){a.update(1f/hz);if(a.value<-.001f)rebound=true;}
            check(rebound,"release should retain restrained elastic recoil");
            check(!a.busy() && a.value==0,"release must stop rendering");
        }
        check(Math.abs(simulate(30).value-simulate(120).value)<.003f,"30/120Hz drift");
        ElasticBoundary.Axis stall=new ElasticBoundary.Axis(),steady=new ElasticBoundary.Axis();
        stall.aim(1);steady.aim(1);stall.update(.2f);
        for(int i=0;i<48;i++)steady.update(1f/240);
        check(Math.abs(stall.value-steady.value)<.003f,"dropped frame changed physical time");
        check(MotionProfile.strain(0,100,.02f)==0,"idle deformation");
        check(MotionProfile.strain(100,100,.02f)<MotionProfile.strain(200,100,.02f),"strain must depend on speed");
        check(MotionProfile.strain(10000,100,.02f)<=.02f,"strain must remain bounded");
        System.out.println("PASS: speed-driven collision, stale/directional approach rejection, repeat-independent force, bounded retaps, progressive resistance, recoil and 30/60/120Hz parity");
    }
}
