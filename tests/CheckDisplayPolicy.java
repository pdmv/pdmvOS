package dev.pdmv.os;

public final class CheckDisplayPolicy {
    public static void main(String[] args) {
        check(1,new int[]{1920,3840,3840},new int[]{1080,2160,2160},new float[]{60,59.94f,30},0);
        check(0,new int[]{1920,3840},new int[]{1080,2160},new float[]{60,30},0);
        check(1,new int[]{1920,3840},new int[]{1080,2160},new float[]{50,50},0);
        check(0,new int[]{1920,4096},new int[]{1080,2160},new float[]{60,60},0);
        check(1,new int[]{3840,1920},new int[]{2160,1080},new float[]{30,60},0);
        check(0,new int[]{3840},new int[]{2160},new float[]{60},0);
        System.out.println("PASS: native resolution selection, refresh and aspect constraints");
    }
    private static void check(int expected,int[] w,int[] h,float[] hz,int current) {
        if(DisplayPolicy.select(w,h,hz,current)!=expected)throw new AssertionError("mode selection");
    }
}
