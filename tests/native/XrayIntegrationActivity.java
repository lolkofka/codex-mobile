package local.avr.codex.copy;

import android.app.Activity;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Disposable app UID, synthetic subscription/credentials and loopback endpoints only. */
public final class XrayIntegrationActivity extends Activity {
    public static final class Hold extends android.app.Service {
        public android.os.IBinder onBind(android.content.Intent intent){return null;}
        public void onCreate(){super.onCreate();String channel="xray-synthetic-tests";android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);nm.createNotificationChannel(new android.app.NotificationChannel(channel,"Xray tests",android.app.NotificationManager.IMPORTANCE_LOW));startForeground(7001,new android.app.Notification.Builder(this,channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Проверка XHTTP").setContentText("Локальные тестовые соединения").build());}
    }
    final JSONArray checks=new JSONArray();final List<Process> children=new ArrayList<>();
    static final String UUID="d223067b-4650-4280-9d0c-0d483f04bc31";
    void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks.put(name);}
    String binary(){return getApplicationInfo().nativeLibraryDir+"/libxray.so";}
    Process launch(JSONObject config,boolean test)throws Exception{
        List<String> command=new ArrayList<>(Arrays.asList(binary(),"run","-config","stdin:","-format","json"));if(test)command.add("-test");
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();children.add(p);try(OutputStream out=p.getOutputStream()){out.write(config.toString().getBytes(StandardCharsets.UTF_8));}return p;
    }
    int free()throws Exception{try(ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return s.getLocalPort();}}
    String authority(EngineNetworkProxy proxy){return Base64.getEncoder().encodeToString(URI.create(proxy.address()).getUserInfo().getBytes(StandardCharsets.US_ASCII));}
    Socket connect(EngineNetworkProxy proxy,int origin)throws Exception{
        URI uri=URI.create(proxy.address());Socket s=new Socket(uri.getHost(),uri.getPort());s.setSoTimeout(10000);s.getOutputStream().write(("CONNECT 127.0.0.1:"+origin+" HTTP/1.1\r\nHost: test\r\nProxy-Authorization: Basic "+authority(proxy)+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));return s;
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);startForegroundService(new android.content.Intent(this,Hold.class));new Thread(()->{
        JSONObject report=new JSONObject();try{runChecks();report.put("status","PASS");}catch(Throwable e){try{report.put("status","FAIL").put("error",e.toString());}catch(Exception ignored){}}
        finally{for(Process p:children)p.destroyForcibly();try{report.put("checks",checks);Files.write(new File(getFilesDir(),"xray-test-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}stopService(new android.content.Intent(this,Hold.class));}
    },"xray-native-tests").start();}
    void runChecks()throws Exception{
        Process version=new ProcessBuilder(binary(),"version").start();check(version.waitFor(10,TimeUnit.SECONDS)&&version.exitValue()==0,"bundled Android ARM64 Xray executes in app UID");
        String base="vless://"+UUID+"@127.0.0.1:443";
        String[] samples={base+"?security=none&type=tcp#RAW",base+"?security=tls&sni=example.invalid&type=ws&host=example.invalid&path=%2Fx#WS",base+"?security=tls&sni=example.invalid&type=grpc&serviceName=sample#GRPC",base+"?security=tls&sni=example.invalid&type=xhttp&path=%2Fx#XHTTP",base+"?security=tls&sni=example.invalid&type=httpupgrade&path=%2Fx#UPGRADE",base+"?security=reality&sni=example.invalid&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=12345678&flow=xtls-rprx-vision#REALITY","trojan://synthetic-pass@127.0.0.1:443?sni=example.invalid#Trojan","ss://"+Base64.getEncoder().encodeToString("aes-128-gcm:synthetic-pass".getBytes(StandardCharsets.UTF_8))+"@127.0.0.1:443#SS","vmess://"+Base64.getEncoder().encodeToString(XrayProfiles.obj("v","2","ps","VMess","add","127.0.0.1","port",443,"id",UUID,"aid",0,"net","tcp","tls","").toString().getBytes(StandardCharsets.UTF_8))};
        for(String sample:samples){JSONObject profile=XrayProfiles.parse(sample).getJSONObject(0);Process p=launch(XrayProfiles.config(profile.getJSONObject("outbound"),free(),"synthetic-loopback"),true);check(p.waitFor(10,TimeUnit.SECONDS)&&p.exitValue()==0,"Xray accepts imported "+profile.getString("name")+" profile");}
        check(XrayProfiles.parse(Base64.getEncoder().encodeToString((samples[0]+"\n"+samples[1]).getBytes(StandardCharsets.UTF_8))).length()==2,"Base64 subscription imports two hosts");
        check(XrayProfiles.parse(samples[0]+"\n"+samples[0]).length()==1,"duplicate hosts deduplicate");
        boolean rejected=false;try{XrayProfiles.parse("hysteria2://unsupported@127.0.0.1:443");}catch(IOException expected){rejected=true;}check(rejected,"unsupported protocols produce explicit import error");
        rejected=false;try{XrayProfiles.parse(base+"?security=tls&allowInsecure=1");}catch(IOException expected){rejected=true;}check(rejected,"insecure TLS profiles rejected");
        int serverPort=free();
        JSONObject inbound=XrayProfiles.obj("listen","127.0.0.1","port",serverPort,"protocol","vless","settings",XrayProfiles.obj("decryption","none","clients",new JSONArray().put(XrayProfiles.obj("id",UUID))));
        JSONObject server=XrayProfiles.obj("log",XrayProfiles.obj("loglevel","none"),"inbounds",new JSONArray().put(inbound),"outbounds",new JSONArray().put(XrayProfiles.obj("protocol","freedom","settings",XrayProfiles.obj("finalRules",new JSONArray().put(XrayProfiles.obj("action","allow","network","tcp","ip",new JSONArray().put("127.0.0.1/32")))))));
        Process peer=launch(server,false);Thread.sleep(300);check(peer.isAlive(),"synthetic VLESS peer starts");
        TunnelManager manager=new TunnelManager(this,()->{},()->{});
        try{
            String link="vless://"+UUID+"@127.0.0.1:"+serverPort+"?security=none&type=tcp#Local-host";
            manager.store.save("","Synthetic","https://subscription.invalid/private-token",XrayProfiles.parse(link));manager.reload();
            check(manager.enabled,"first import enables strict Xray");String selected=manager.data.getString("selected");
            check(!manager.packet().toString().contains(UUID)&&!manager.packet().toString().contains("private-token"),"metadata excludes subscription tokens and proxy credentials");
            String blob=new String(Files.readAllBytes(manager.store.file.toPath()),StandardCharsets.ISO_8859_1);check(!blob.contains(UUID)&&!blob.contains("private-token"),"Keystore encrypted subscription storage");
            check(manager.store.read().getString("selected").equals(selected),"selection survives encrypted reload");
            try(ServerSocket origin=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));EngineNetworkProxy bridge=new EngineNetworkProxy(manager)){
                CompletableFuture<Boolean> echoed=new CompletableFuture<>();new Thread(()->{try(Socket s=origin.accept()){byte[] bytes=new byte[6];new DataInputStream(s.getInputStream()).readFully(bytes);s.getOutputStream().write(bytes);echoed.complete(true);}catch(Exception e){echoed.completeExceptionally(e);}}).start();
                try(Socket s=connect(bridge,origin.getLocalPort())){check(EngineNetworkProxy.headers(s.getInputStream()).contains("200"),"engine bridge connects through real Xray VLESS tunnel");s.getOutputStream().write("OPAQUE".getBytes(StandardCharsets.US_ASCII));byte[] bytes=new byte[6];new DataInputStream(s.getInputStream()).readFully(bytes);check(new String(bytes,StandardCharsets.US_ASCII).equals("OPAQUE"),"tunnel preserves opaque destination traffic");}check(echoed.get(5,TimeUnit.SECONDS),"selected VLESS peer carries bytes");
                peer.destroyForcibly();peer.waitFor(3,TimeUnit.SECONDS);manager.stopCore();origin.setSoTimeout(1500);
                try(Socket s=connect(bridge,origin.getLocalPort())){String failedHead=EngineNetworkProxy.headers(s.getInputStream());boolean failed=failedHead.contains("502");if(failedHead.contains("200")){s.getOutputStream().write("OPAQUE".getBytes(StandardCharsets.US_ASCII));failed=s.getInputStream().read()==-1;}check(failed,"unavailable chosen host returns proxy error or closed tunnel");}
                boolean leaked=false;try(Socket unexpected=origin.accept()){leaked=true;}catch(SocketTimeoutException expected){}check(!leaked,"failed VLESS has no direct fallback to reachable origin");
                for(int i=0;i<30&&manager.error.isEmpty();i++)Thread.sleep(50);check(!manager.error.isEmpty(),"tunnel failure is visible to UI");
                String subId=selected.split(":",2)[0];manager.store.save(subId,"Synthetic","",XrayProfiles.parse(samples[0]));manager.reload();manager.stopCore();boolean missing=false;try{manager.open();}catch(IOException expected){missing=true;}check(missing&&manager.enabled&&manager.data.getString("selected").equals(selected),"refresh losing chosen host retains strict mode and selection");
                manager.store.remove(subId);manager.reload();manager.stopCore();missing=false;try{manager.open();}catch(IOException expected){missing=true;}check(missing&&manager.enabled,"deleting selected subscription cannot enable direct route");
            }
        }finally{manager.close();}
        xhttpChecks();
        // Leave a harmless core alive to verify Android kills native children on force-stop.
        JSONObject idle=XrayProfiles.config(XrayProfiles.parse(samples[0]).getJSONObject(0).getJSONObject("outbound"),free(),"synthetic");Process child=launch(idle,false);Thread.sleep(200);check(child.isAlive(),"app-owned native process lifecycle ready");children.remove(child);
    }
    void accepted(JSONObject outbound,String message)throws Exception{
        Process p=launch(XrayProfiles.config(outbound,free(),"synthetic-only"),true);
        check(p.waitFor(10,TimeUnit.SECONDS)&&p.exitValue()==0,message);
    }
    JSONObject xhttp(JSONObject profile)throws Exception{return profile.getJSONObject("outbound").getJSONObject("streamSettings").getJSONObject("xhttpSettings");}
    void xhttpChecks()throws Exception{
        JSONObject extra=XrayProfiles.obj("xPaddingBytes","32-64","xPaddingKey","probe-pad","xPaddingHeader","X-Probe-Padding","xPaddingPlacement","header","noGRPCHeader",true,"scMinPostsIntervalMs","0-1","headers",XrayProfiles.obj("X-Probe","value+plus"),"xmux",XrayProfiles.obj("maxConcurrency",1,"hMaxRequestTimes","2-4"));
        String prefix="vless://"+UUID+"@127.0.0.1:443?security=none&type=xhttp&mode=packet-up&path=%2Fxhttp-test&extra=";
        String link=prefix+URLEncoder.encode(extra.toString(),"UTF-8")+"#XHTTP-extra";
        JSONObject imported=XrayProfiles.parse(link).getJSONObject(0),settings=xhttp(imported);
        check(settings.getJSONObject("extra").getJSONObject("headers").getString("X-Probe").equals("value+plus"),"URI XHTTP extra decodes once and preserves plus signs");
        check(settings.getJSONObject("extra").getJSONObject("xmux").getInt("maxConcurrency")==1,"XMUX parameters reach imported profile");
        accepted(imported.getJSONObject("outbound"),"bundled Xray accepts XHTTP extra from share link");
        JSONObject json=XrayProfiles.parse(imported.getJSONObject("outbound").toString()).getJSONObject(0);
        check(xhttp(json).getJSONObject("extra").getString("xPaddingBytes").equals("32-64"),"Xray JSON subscription retains padding extra");
        JSONObject encoded=XrayProfiles.parse(Base64.getEncoder().encodeToString(link.getBytes(StandardCharsets.UTF_8))).getJSONObject(0);
        check(xhttp(encoded).getJSONObject("extra").getBoolean("noGRPCHeader"),"Base64 subscription supports XHTTP extra");
        JSONObject stringConfig=new JSONObject(imported.getJSONObject("outbound").toString());stringConfig.getJSONObject("streamSettings").getJSONObject("xhttpSettings").put("extra",extra.toString());
        check(xhttp(XrayProfiles.parse(stringConfig.toString()).getJSONObject(0)).getJSONObject("extra").has("xmux"),"string-valued JSON extra normalizes to object");
        JSONObject download=XrayProfiles.obj("address","127.0.0.1","port",443,"network","xhttp","security","tls","tlsSettings",XrayProfiles.obj("serverName","example.invalid","masterKeyLog","/must-not-write"),"sockopt",XrayProfiles.obj("dialerProxy","direct"),"xhttpSettings",XrayProfiles.obj("path","/xhttp-test","mode","packet-up","extra",extra));
        JSONObject splitExtra=new JSONObject(extra.toString()).put("downloadSettings",download);
        JSONObject split=XrayProfiles.parse(prefix+URLEncoder.encode(splitExtra.toString(),"UTF-8")).getJSONObject(0),down=xhttp(split).getJSONObject("extra").getJSONObject("downloadSettings");
        check(down.getInt("port")==443&&down.getString("address").equals("127.0.0.1")&&down.getJSONObject("xhttpSettings").getJSONObject("extra").has("xmux"),"nested downloadSettings and its XHTTP extra survive import");
        check(!down.has("sockopt")&&!down.getJSONObject("tlsSettings").has("masterKeyLog"),"subscription cannot add direct dialer or write TLS key logs");
        accepted(split.getJSONObject("outbound"),"bundled Xray accepts split downloadSettings extra");
        JSONObject implicitTLS=XrayProfiles.obj("network","xhttp","security","tls","xhttpSettings",XrayProfiles.obj("extra",XrayProfiles.obj("downloadSettings",XrayProfiles.obj("address","localhost","port",443,"network","xhttp","security","tls"))));
        TunnelManager.prepareStream(implicitTLS,"localhost");JSONObject resolved=implicitTLS.getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("downloadSettings");
        check(implicitTLS.getJSONObject("tlsSettings").getString("serverName").equals("localhost")&&resolved.getJSONObject("tlsSettings").getString("serverName").equals("localhost")&&!resolved.getString("address").equals("localhost"),"Android resolves split endpoint while preserving implicit TLS SNI");
        JSONObject legacy=new JSONObject(imported.getJSONObject("outbound").toString()),stream=legacy.getJSONObject("streamSettings");stream.put("network","splithttp").put("splithttpSettings",stream.remove("xhttpSettings"));
        accepted(XrayProfiles.parse(legacy.toString()).getJSONObject(0).getJSONObject("outbound"),"legacy SplitHTTP JSON alias supports extra");
        for(String bad:new String[]{"[1,2]","{broken","{} trailing"}){boolean rejected=false;try{XrayProfiles.parse(prefix+URLEncoder.encode(bad,"UTF-8"));}catch(IOException e){rejected=e.getMessage().contains("extra");}check(rejected,"invalid XHTTP extra gives a bounded clear error: "+bad.charAt(0));}
        int peerPort=free();JSONObject inbound=XrayProfiles.obj("listen","127.0.0.1","port",peerPort,"protocol","vless","settings",XrayProfiles.obj("decryption","none","clients",new JSONArray().put(XrayProfiles.obj("id",UUID))),"streamSettings",XrayProfiles.obj("network","xhttp","security","none","xhttpSettings",XrayProfiles.obj("path","/xhttp-test","mode","auto","extra",extra)));
        JSONObject freedom=XrayProfiles.obj("protocol","freedom","settings",XrayProfiles.obj("finalRules",new JSONArray().put(XrayProfiles.obj("action","allow","network","tcp","ip",new JSONArray().put("127.0.0.1/32")))));
        Process peer=launch(XrayProfiles.obj("log",XrayProfiles.obj("loglevel","none"),"inbounds",new JSONArray().put(inbound),"outbounds",new JSONArray().put(freedom)),false);Thread.sleep(250);check(peer.isAlive(),"synthetic XHTTP server starts with matching required extra");
        TunnelManager manager=new TunnelManager(this,()->{},()->{});
        try{
            JSONObject actualExtra=new JSONObject(extra.toString());
            // Exercise XHTTP's separate download channel on the same selected synthetic host.
            actualExtra.put("downloadSettings",XrayProfiles.obj("address","127.0.0.1","port",peerPort,"network","xhttp","security","none","xhttpSettings",XrayProfiles.obj("path","/xhttp-test","mode","packet-up","extra",extra)));
            String actual="vless://"+UUID+"@127.0.0.1:"+peerPort+"?type=xhttp&security=none&mode=packet-up&path=%2Fxhttp-test&extra="+URLEncoder.encode(actualExtra.toString(),"UTF-8")+"#Native-XHTTP";
            JSONArray profiles=XrayProfiles.parse(actual);manager.store.save("","XHTTP fixture","",profiles);JSONArray all=manager.store.read().getJSONArray("subscriptions");String selected=all.getJSONObject(all.length()-1).getString("id")+":"+profiles.getJSONObject(0).getString("id");manager.store.select(selected);manager.reload();
            try(ServerSocket origin=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));EngineNetworkProxy bridge=new EngineNetworkProxy(manager)){
                origin.setSoTimeout(15000);CompletableFuture<Boolean> echoed=new CompletableFuture<>();new Thread(()->{try(Socket s=origin.accept()){s.setSoTimeout(10000);byte[] payload=new byte[6];new DataInputStream(s.getInputStream()).readFully(payload);s.getOutputStream().write(payload);echoed.complete(true);}catch(Exception e){echoed.completeExceptionally(e);}}).start();
                try(Socket s=connect(bridge,origin.getLocalPort())){check(EngineNetworkProxy.headers(s.getInputStream()).contains("200"),"engine bridge opens an XHTTP extra tunnel");s.getOutputStream().write("OPAQUE".getBytes(StandardCharsets.US_ASCII));byte[] payload=new byte[6];new DataInputStream(s.getInputStream()).readFully(payload);check(new String(payload,StandardCharsets.US_ASCII).equals("OPAQUE"),"real XHTTP carries bytes with extra padding, XMUX and split download");}check(echoed.get(5,TimeUnit.SECONDS),"XHTTP split upload/download completes through selected peer");
                peer.destroyForcibly();peer.waitFor(3,TimeUnit.SECONDS);manager.stopCore();origin.setSoTimeout(1000);
                try(Socket s=connect(bridge,origin.getLocalPort())){String head=EngineNetworkProxy.headers(s.getInputStream());boolean failed=head.contains("502");if(head.contains("200")){s.getOutputStream().write("OPAQUE".getBytes(StandardCharsets.US_ASCII));failed=s.getInputStream().read()==-1;}check(failed,"failed XHTTP peer closes tunnel instead of bypassing it");}
                boolean leaked=false;try(Socket s=origin.accept()){leaked=true;}catch(SocketTimeoutException expected){}check(!leaked,"XHTTP extra never falls back to direct reachable origin");
            }
        }finally{manager.close();}
    }
}
