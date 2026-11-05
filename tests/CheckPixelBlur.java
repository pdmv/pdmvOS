package dev.pdmv.os;
import java.util.*;
public final class CheckPixelBlur {
    static int[] reference(int[] source,int w,int h,int r){
        int[] a=source.clone(),b=new int[a.length];int d=2*r+1;
        for(int pass=0;pass<3;pass++){
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int red=0,green=0,blue=0;
                for(int i=-r;i<=r;i++){int c=a[y*w+Math.max(0,Math.min(w-1,x+i))];red+=(c>>>16)&255;green+=(c>>>8)&255;blue+=c&255;}
                b[y*w+x]=0xff000000|((red/d)<<16)|((green/d)<<8)|(blue/d);
            }
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int red=0,green=0,blue=0;
                for(int i=-r;i<=r;i++){int c=b[Math.max(0,Math.min(h-1,y+i))*w+x];red+=(c>>>16)&255;green+=(c>>>8)&255;blue+=c&255;}
                a[y*w+x]=0xff000000|((red/d)<<16)|((green/d)<<8)|(blue/d);
            }
        }return a;
    }
    public static void main(String[] args){
        Random random=new Random(64);int cases=0;
        for(int w:new int[]{1,2,7,32,65})for(int h:new int[]{1,3,19})for(int r:new int[]{0,1,3,6,12}){
            int[] a=new int[w*h];for(int i=0;i<a.length;i++)a[i]=random.nextInt();int[] expected=reference(a,w,h,r);
            PixelBlur.apply(a,new int[a.length],w,h,r);
            if(!Arrays.equals(a,expected))throw new AssertionError("blur changed pixels "+w+"x"+h+" r="+r);cases++;
        }System.out.println("PASS: exact blur pixels across "+cases+" cases");
    }
}
