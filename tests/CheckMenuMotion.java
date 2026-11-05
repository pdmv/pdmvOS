package dev.pdmv.os;

public final class CheckMenuMotion {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static MenuMotion.Frame frame(float p,float v){
        MenuMotion.Frame f=new MenuMotion.Frame();
        MenuMotion.resolve(p,v,250,930,300,580,400,f);return f;
    }
    public static void main(String[] args){
        MenuMotion.Frame closed=frame(0,0),open=frame(1,0);
        check(closed.x==250&&closed.y==930&&closed.scaleX<.33f&&closed.alpha==0,"closed menu misses source tile");
        check(open.x==300&&open.y==580&&open.scaleX==1&&open.scaleY==1&&open.alpha==1&&open.clarity==1,"settled menu changed geometry");
        check(frame(.4f,5).clarity<frame(.8f,5).clarity,"clarity does not follow reveal");
        check(frame(.5f,-5).y>frame(.5f,5).y,"closing has no falling arc");
        MenuMotion.Frame pill=new MenuMotion.Frame();
        MenuMotion.resolveFromScale(0,0,1700,88,960,540,.1f,pill);
        check(pill.x==1700&&pill.y==88&&pill.scaleX==.1f&&pill.alpha==0,"input menu does not begin inside its button");
        MenuMotion.resolveFromScale(1,0,1700,88,960,540,.1f,pill);
        check(pill.x==960&&pill.y==540&&pill.scaleX==1f&&pill.clarity==1,"input menu does not settle in screen center");
        for(int hz:new int[]{30,60,120}){
            SpringValue spring=new SpringValue(0,MenuMotion.OPEN_K,MenuMotion.OPEN_D);spring.aim(1);
            float peak=0;
            for(int i=0;i<hz;i++){spring.update(1f/hz);peak=Math.max(peak,spring.value);}
            check(peak>1&&peak<1.05f,"menu lacks restrained inertial overshoot");
            check(!spring.busy()&&spring.value==1,"opening does not settle");
            spring.value=.55f;spring.velocity=5;
            MenuMotion.Frame before=frame(spring.value,spring.velocity);
            spring.aim(0,MenuMotion.CLOSE_K,MenuMotion.CLOSE_D);
            MenuMotion.Frame after=frame(spring.value,spring.velocity);
            check(before.x==after.x&&before.y==after.y&&before.scaleX==after.scaleX,"reversal teleported menu");
            for(int i=0;i<hz*2;i++){
                spring.update(1f/hz);MenuMotion.Frame f=frame(spring.value,spring.velocity);
                check(Float.isFinite(f.x)&&f.scaleX>0&&f.scaleY>0&&f.alpha>=0&&f.alpha<=1,"invalid geometry during return");
            }
            check(!spring.busy()&&spring.value==0,"closing does not stop rendering");
        }
        System.out.println("PASS: tile-anchored menu, inertial overshoot, falling return, clarity, continuous reversal and idle settling");
    }
}
