package dev.pdmv.os;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/** Reads platform state instead of optimistically painting a successful toggle. */
final class TvControls {
    final Context context;
    int wifi; // 0 off, 1 enabled without connection, 2 connected
    int connectedWifiId=-1,connectingWifiId=-1;
    String connectedWifiName="";
    private int requestedWifiId=-1;
    private long wifiDeadline;
    boolean wifiConnectFailed;
    boolean wifiTogglePending,wifiToggleFailed;
    private int wifiToggleTarget=-1;
    private long wifiToggleDeadline;
    private int connectedWifiSignal;
    record SavedWifi(int id,String name){}
    record NearbyWifi(String name,int security,int savedId,boolean connected,int signal){}
    private static final int OPEN=1,WEP=2,PSK=4,EAP=8,SAE=16,OWE=32,OTHER=64;
    final ArrayList<TvInputInfo> inputs=new ArrayList<>();
    TvControls(Context context){this.context=context;refresh();}
    void refresh(){
        try{
            WifiManager wm=wifiManager();
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni=cm==null?null:cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if(wm==null)return;
            int radio=wm.getWifiState();
            long now=SystemClock.elapsedRealtime();
            if(radio==WifiManager.WIFI_STATE_ENABLED)wifi=ni!=null&&ni.isConnected()?2:1;
            else if(radio==WifiManager.WIFI_STATE_DISABLED)wifi=0;
            if(wifiToggleTarget>=0&&(radio==wifiToggleTarget||now>=wifiToggleDeadline)){
                wifiToggleFailed=radio!=wifiToggleTarget;
                wifiToggleTarget=-1;
            }
            wifiTogglePending=wifiToggleTarget>=0;
            WifiInfo info=wifi==0?null:wm.getConnectionInfo();
            connectedWifiSignal=wifi==2&&info!=null?signalLevel(info.getRssi()):0;
            int id=info==null?-1:info.getNetworkId();
            int previousId=connectedWifiId;
            connectedWifiId=wifi==2?id:-1;
            String name=wifi==2?wifiName(info==null?null:info.getSSID()):"";
            if(wifi==2&&name.isEmpty()&&ni!=null)name=wifiName(ni.getExtraInfo());
            if(wifi==2&&name.isEmpty()&&id>=0){
                if(previousId==id)name=connectedWifiName;
                else for(SavedWifi saved:savedWifi())if(saved.id()==id){name=saved.name();break;}
            }
            connectedWifiName=name;
            if(wifi==0||(requestedWifiId>=0&&connectedWifiId==requestedWifiId))requestedWifiId=-1;
            if(requestedWifiId>=0&&SystemClock.elapsedRealtime()>=wifiDeadline){
                requestedWifiId=-1;
                wifiConnectFailed=true;
            }
            connectingWifiId=requestedWifiId>=0?requestedWifiId:ni!=null&&wifi==1&&ni.isConnectedOrConnecting()?id:-1;
        }catch(RuntimeException ignored){/* Keep the last known state; a read failure is not a disconnection. */}
    }
    private WifiManager wifiManager(){return context.getApplicationContext().getSystemService(WifiManager.class);}
    private static String wifiName(String value){
        if(value==null||"<unknown ssid>".equalsIgnoreCase(value))return "";
        return value.length()>=2&&value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;
    }
    List<SavedWifi> savedWifi(){
        ArrayList<SavedWifi> saved=new ArrayList<>();
        try{
            WifiManager manager=wifiManager();
            List<WifiConfiguration> configs=manager==null?null:manager.getConfiguredNetworks();
            if(configs!=null)for(WifiConfiguration config:configs){
                String name=wifiName(config.SSID);
                if(config.networkId>=0&&!name.isEmpty())saved.add(new SavedWifi(config.networkId,name));
            }
        }catch(RuntimeException ignored){/* Some Android versions restrict access to saved networks. */}
        saved.sort(java.util.Comparator.comparing(SavedWifi::name,String.CASE_INSENSITIVE_ORDER));
        return saved;
    }
    private static int security(ScanResult scan){
        String caps=scan.capabilities==null?"":scan.capabilities;
        int kind=(caps.contains("WEP")?WEP:0)|(caps.contains("PSK")?PSK:0)
            |(caps.contains("EAP")?EAP:0)|(caps.contains("SAE")?SAE:0)|(caps.contains("OWE")?OWE:0);
        if(kind!=0)return kind;
        return caps.contains("WPA")||caps.contains("RSN")||caps.contains("PRIVACY")||caps.contains("WAPI")?OTHER:OPEN;
    }
    private static int security(WifiConfiguration config){
        java.util.BitSet keys=config.allowedKeyManagement;
        int kind=(keys.get(WifiConfiguration.KeyMgmt.WPA_PSK)||keys.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
            ||keys.get(WifiConfiguration.KeyMgmt.FT_PSK)||keys.get(WifiConfiguration.KeyMgmt.WPA_PSK_SHA256)?PSK:0)
            |(keys.get(WifiConfiguration.KeyMgmt.WPA_EAP)||keys.get(WifiConfiguration.KeyMgmt.IEEE8021X)?EAP:0)
            |(keys.get(WifiConfiguration.KeyMgmt.SAE)?SAE:0)|(keys.get(WifiConfiguration.KeyMgmt.OWE)?OWE:0);
        if(kind!=0)return kind;
        if(keys.nextSetBit(1)>=0)return OTHER;
        if(config.wepKeys!=null)for(String key:config.wepKeys)if(key!=null)return WEP;
        return OPEN;
    }
    private static int signalLevel(int rssi){return rssi<=-127?0:rssi>=-62?3:rssi>=-76?2:1;}
    List<NearbyWifi> nearbyWifi(){
        ArrayList<NearbyWifi> result=new ArrayList<>();
        if(wifi==0)return result;
        try{
            WifiManager manager=wifiManager();
            if(manager==null)return result;
            List<WifiConfiguration> saved=manager.getConfiguredNetworks();
            List<ScanResult> scans=new ArrayList<>(manager.getScanResults());
            scans.sort(java.util.Comparator.comparingInt((ScanResult scan)->signalLevel(scan.level)).reversed()
                .thenComparing(scan->scan.SSID==null?"":scan.SSID,String.CASE_INSENSITIVE_ORDER));
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(ScanResult scan:scans){
                String name=scan.SSID==null?"":scan.SSID;
                int kind=security(scan);
                if(name.isEmpty()||!seen.add(kind+":"+name))continue;
                int id=-1;
                if(saved!=null&&kind!=OTHER)for(WifiConfiguration config:saved){
                    if(config.networkId>=0&&name.equals(wifiName(config.SSID))&&(kind&security(config))!=0){
                        if(id<0||config.networkId==connectedWifiId)id=config.networkId;
                    }
                }
                result.add(new NearbyWifi(name,kind,id,id>=0&&id==connectedWifiId,signalLevel(scan.level)));
            }
        }catch(RuntimeException ignored){/* Location permission or device policy can restrict scan results. */}
        // Keep the active connection visible even when a scan omits its access point.
        if(wifi==2&&!connectedWifiName.isEmpty()&&result.stream().noneMatch(NearbyWifi::connected)){
            int index=-1;
            if(connectedWifiId<0){
                for(int i=0;i<result.size();i++)if(connectedWifiName.equals(result.get(i).name())){
                    if(index>=0){index=-1;break;}
                    index=i;
                }
            }
            if(index>=0){
                NearbyWifi network=result.remove(index);
                result.add(0,new NearbyWifi(network.name(),network.security(),network.savedId(),true,network.signal()));
            }else result.add(0,new NearbyWifi(connectedWifiName,0,connectedWifiId,true,connectedWifiSignal));
        }
        result.sort(java.util.Comparator.comparing(NearbyWifi::connected).reversed());
        return result;
    }
    boolean scanWifi(){
        try{WifiManager manager=wifiManager();return manager!=null&&manager.startScan();}
        catch(RuntimeException unavailable){return false;}
    }
    boolean failWifiConnection(){
        if(requestedWifiId<0)return false;
        requestedWifiId=connectingWifiId=-1;
        wifiConnectFailed=true;
        return true;
    }
    boolean connectWifi(int id){
        try{
            WifiManager manager=wifiManager();
            if(manager==null||!manager.enableNetwork(id,true))return false;
            requestedWifiId=connectingWifiId=id;
            wifiDeadline=SystemClock.elapsedRealtime()+20000;
            wifiConnectFailed=false;
            return true;
        }catch(RuntimeException error){return false;}
    }
    boolean setWifiEnabled(boolean enabled){
        if(wifiTogglePending)return false;
        try{
            WifiManager wm=wifiManager();
            int target=enabled?WifiManager.WIFI_STATE_ENABLED:WifiManager.WIFI_STATE_DISABLED;
            if(wm==null)return false;
            if(wm.getWifiState()==target)return true;
            if(!wm.setWifiEnabled(enabled))return false;
            wifiToggleTarget=target;
            wifiToggleDeadline=SystemClock.elapsedRealtime()+8000;
            wifiTogglePending=true;
            wifiToggleFailed=false;
            return true;
        }
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
