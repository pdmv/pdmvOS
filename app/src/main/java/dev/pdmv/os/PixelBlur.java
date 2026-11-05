package dev.pdmv.os;

/** Same three box passes, with exact integer division shared by all pixels. */
final class PixelBlur {
    private PixelBlur(){}
    static void apply(int[] pixels,int[] scratch,int width,int height,int radius){
        int diameter=radius*2+1;
        int[] divided=new int[256*diameter];
        for(int i=0;i<divided.length;i++)divided[i]=i/diameter;
        for(int pass=0;pass<3;pass++){
            for(int y=0;y<height;y++){
                int red=0,green=0,blue=0,row=y*width;
                for(int i=-radius;i<=radius;i++){
                    int color=pixels[row+Math.max(0,Math.min(width-1,i))];
                    red+=(color>>>16)&255;green+=(color>>>8)&255;blue+=color&255;
                }
                for(int x=0;x<width;x++){
                    scratch[row+x]=0xff000000|(divided[red]<<16)|(divided[green]<<8)|divided[blue];
                    int before=pixels[row+(x>radius?x-radius:0)];
                    int next=pixels[row+(x+radius+1<width?x+radius+1:width-1)];
                    red+=((next>>>16)&255)-((before>>>16)&255);
                    green+=((next>>>8)&255)-((before>>>8)&255);blue+=(next&255)-(before&255);
                }
            }
            for(int x=0;x<width;x++){
                int red=0,green=0,blue=0;
                for(int i=-radius;i<=radius;i++){
                    int color=scratch[Math.max(0,Math.min(height-1,i))*width+x];
                    red+=(color>>>16)&255;green+=(color>>>8)&255;blue+=color&255;
                }
                for(int y=0;y<height;y++){
                    pixels[y*width+x]=0xff000000|(divided[red]<<16)|(divided[green]<<8)|divided[blue];
                    int before=scratch[(y>radius?y-radius:0)*width+x];
                    int next=scratch[(y+radius+1<height?y+radius+1:height-1)*width+x];
                    red+=((next>>>16)&255)-((before>>>16)&255);
                    green+=((next>>>8)&255)-((before>>>8)&255);blue+=(next&255)-(before&255);
                }
            }
        }
    }
}
