package dev.pdmv.os;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Fixed app-owned staging path; never accepts a filesystem path from an Intent. */
final class WallpaperCommand {
    static final String ACTION="dev.pdmv.os.action.SET_WALLPAPER";
    static void accept(Context context,WallpaperStore store,Intent intent,Consumer<String> toast){
        if(intent==null||!ACTION.equals(intent.getAction())||intent.getComponent()==null
                ||!"dev.pdmv.os.WallpaperImportActivity".equals(intent.getComponent().getClassName()))return;
        String id=intent.getStringExtra("upload_id"),sha=intent.getStringExtra("sha256");
        if(id==null||!id.matches("[a-f0-9]{32}"))return;
        File directory=context.getExternalFilesDir("wallpaper-import");
        if(directory==null){toast.accept("Không truy cập được nơi nhận ảnh");return;}
        File status=new File(directory,id+".json");
        if(!intent.getBooleanExtra("reset",false)&&(sha==null||!sha.matches("[a-f0-9]{64}"))){write(status,"error","Mã kiểm tra ảnh không hợp lệ");return;}
        write(status,"processing",null);
        Consumer<String> done=error->{write(status,error==null?"applied":"error",error);toast.accept(error==null?"Đã đổi hình nền":"Không đổi được hình nền · "+error);};
        if(intent.getBooleanExtra("reset",false))store.restoreDefault(done);
        else store.replace(new File(directory,id+".image"),sha,done);
    }
    private static void write(File target,String state,String error){
        AtomicFile file=new AtomicFile(target);FileOutputStream output=null;
        try{
            JSONObject result=new JSONObject().put("state",state);
            if(error!=null)result.put("message",error);
            output=file.startWrite();output.write(result.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        }catch(Exception failure){if(output!=null)file.failWrite(output);Log.w("pdmvOS.Wallpaper","Cannot acknowledge wallpaper request",failure);}
    }
}
