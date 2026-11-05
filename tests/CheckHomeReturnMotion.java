package dev.pdmv.os;
public final class CheckHomeReturnMotion {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int n=0;n<6;n++){
            float sx=UiGeometry.centerX(n),sy=UiGeometry.DOCK_HOME_TOP+UiGeometry.DOCK_HEIGHT/2f;
            check(HomeReturnMotion.position(sx,960f,0f)==sx,"source X preserved");
            check(HomeReturnMotion.position(sy,540f,0f)==sy,"source Y preserved");
            check(HomeReturnMotion.position(sx,960f,1f)==960f,"full-screen X");
            check(HomeReturnMotion.position(sy,540f,1f)==540f,"full-screen Y");
            for(int i=1;i<=100;i++){
                float p=i/100f,prior=(i-1)/100f;
                float cur=HomeReturnMotion.position(sx,960f,p);
                float before=HomeReturnMotion.position(sx,960f,prior);
                check(Math.abs(cur-960f)<=Math.abs(before-960f)+.002f,"monotonic return X");
                check(HomeReturnMotion.scale(p)>=HomeReturnMotion.scale(prior),"monotonic scale");
            }
        }
        check(HomeReturnMotion.scale(0)==1f,"final scale");
        check(HomeReturnMotion.scale(1)==HomeReturnMotion.MAX_SCALE,"start scale");
        System.out.println("PASS: tvOS-inspired return geometry, consistent scale and translation");
    }
}
