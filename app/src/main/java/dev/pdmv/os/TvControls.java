package dev.pdmv.os;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import java.util.ArrayList;
import java.util.List;

/** Reads platform state instead of optimistically painting a successful toggle. */
final class TvControls {
    final Context context;
    int wifi; // 0 off, 1 enabled without connection, 2 connected
    final ArrayList<TvInputInfo> inputs=new ArrayList<>();
    TvControls(Context context){this.context=context;refresh();}
    void refresh(){
        try{
            WifiManager wm=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni=cm==null?null:cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if(wm==null)return;
            if(wm.isWifiEnabled()){if(cm==null)return;wifi=ni!=null&&ni.isConnected()?2:1;}else wifi=0;
        }catch(RuntimeException ignored){/* Keep the last known state; a read failure is not a disconnection. */}
    }
    boolean toggleWifi(){
        try{WifiManager wm=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);return wm!=null&&wm.setWifiEnabled(!wm.isWifiEnabled());}
        catch(RuntimeException error){return false;}
    }
    List<TvInputInfo> discoverInputs(){
        inputs.clear();
        try{TvInputManager manager=(TvInputManager)context.getSystemService(Context.TV_INPUT_SERVICE);
            if(manager!=null)for(TvInputInfo input:manager.getTvInputList()){
                try{if(input!=null&&input.isPassthroughInput()&&!input.isHidden(context))inputs.add(input);}
                catch(RuntimeException unavailableInput){}
            }
        }catch(RuntimeException ignored){}
        return inputs;
    }
    Intent inputIntent(TvInputInfo input){return new Intent(Intent.ACTION_VIEW,TvContract.buildChannelUriForPassthroughInput(input.getId()));}
}
