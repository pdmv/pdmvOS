package dev.pdmv.os;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Resolution-independent default artwork, with no decoded assets or idle animation. */
final class AmbientBackground {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Shader[] dark={
        new LinearGradient(0,0,1920,1080,0xff10162f,0xff080b13,Shader.TileMode.CLAMP),
        new RadialGradient(420,170,1100,new int[]{0x99674b9d,0x003a345e},null,Shader.TileMode.CLAMP),
        new RadialGradient(1600,700,950,new int[]{0x807d3a5d,0x007d3a5d},null,Shader.TileMode.CLAMP),
        new RadialGradient(900,1100,850,new int[]{0x60446d94,0x00446d94},null,Shader.TileMode.CLAMP)
    };
    private final Shader[] light={
        new LinearGradient(0,0,1920,1080,0xffe7eafb,0xfff4eee9,Shader.TileMode.CLAMP),
        new RadialGradient(420,170,1100,new int[]{0x807f9fda,0x007f9fda},null,Shader.TileMode.CLAMP),
        new RadialGradient(1600,700,950,new int[]{0x80e0a4ba,0x00e0a4ba},null,Shader.TileMode.CLAMP),
        new RadialGradient(900,1100,850,new int[]{0x60f0cfaf,0x00f0cfaf},null,Shader.TileMode.CLAMP)
    };
    void draw(Canvas canvas,boolean isLight) {
        for(Shader shader:isLight?light:dark) {
            paint.setShader(shader);
            canvas.drawRect(0,0,UiGeometry.WIDTH,UiGeometry.HEIGHT,paint);
        }
        paint.setShader(null);
    }
}
