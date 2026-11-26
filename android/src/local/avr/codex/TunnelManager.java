package local.avr.codex.copy;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** An app-owned Xray process. In strict mode, errors never return a direct route. */
final class TunnelManager implements EngineNetworkProxy.Route,Closeable {
    final Context context;final TunnelStore store;
    final ExecutorService tasks=Executors.newSingleThreadExecutor();
    final Runnable changed,disconnect;
    volatile boolean enabled,working,closed;
    volatile String error="",state="Системная сеть",selectedName="";
    volatile JSONObject data;
    volatile Process core;int port;String password="";
    TunnelManager(Context context,Runnable changed,Runnable disconnect){
        this.context=context;this.changed=changed;this.disconnect=disconnect;store=new TunnelStore(new File(context.getFilesDir(),"xray"));
        try{reload();}catch(Exception e){enabled=true;error="Не удалось прочитать настройки Xray. Прямое подключение заблокировано.";state="Ошибка Xray";data=new JSONObject();}
    }
    void reload()throws Exception{
        data=store.read();enabled=data.optBoolean("enabled");selectedName="";
        if(enabled)try{selectedName=TunnelStore.find(data,data.optString("selected")).getString("name");}catch(Exception e){error="Выбранный сервер отсутствует. Выбери хост в подписке Xray.";}
        state=enabled?(selectedName.isEmpty()?"Нужен сервер Xray":"Xray · "+selectedName):"Системная сеть";
    }
    JSONObject packet(){
        JSONObject result=new JSONObject();try{
            JSONArray subscriptions=new JSONArray();JSONObject snapshot=data;
            JSONArray all=snapshot==null?new JSONArray():snapshot.optJSONArray("subscriptions");if(all==null)all=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject sub=all.getJSONObject(i);JSONArray nodes=new JSONArray(),profiles=sub.getJSONArray("profiles");
                for(int j=0;j<profiles.length();j++){JSONObject p=profiles.getJSONObject(j),o=p.getJSONObject("outbound"),endpoint=XrayProfiles.endpoint(o);nodes.put(XrayProfiles.obj("id",sub.getString("id")+":"+p.getString("id"),"name",p.getString("name"),"host",endpoint.optString("address"),"port",endpoint.optInt("port"),"protocol",o.optString("protocol")));}
                subscriptions.put(XrayProfiles.obj("id",sub.getString("id"),"name",sub.getString("name"),"refreshable",!sub.optString("url").isEmpty(),"updated",sub.optLong("updated"),"warning",sub.optString("warning"),"hosts",nodes));
            }
            result=XrayProfiles.obj("enabled",enabled,"working",working,"error",error,"status",state,"selectedName",selectedName,"selected",snapshot==null?"":snapshot.optString("selected"),"subscriptions",subscriptions);
        }catch(Exception ignored){}return result;
    }
    void report(String message){error=message;changed.run();}
    @Override public void failed(){if(enabled&&!closed)report("Ошибка Xray: выбранный сервер недоступен или отклонил запрос. Прямое подключение заблокировано.");}
    @Override public void connected(){if(enabled&&!closed&&!error.isEmpty())report("");}
    static String proxyAuth(String password){return "Proxy-Authorization: Basic "+Base64.getEncoder().encodeToString(("codex:"+password).getBytes(StandardCharsets.US_ASCII))+"\r\n";}
    @Override public synchronized EngineNetworkProxy.Upstream open()throws IOException{
        if(closed)throw new IOException("Tunnel closed");if(!enabled)return null;
        try{
            if(core==null||!core.isAlive())start();
            Socket socket=new Socket();try{socket.connect(new InetSocketAddress("127.0.0.1",port),3000);}catch(IOException e){socket.close();throw e;}
            return new EngineNetworkProxy.Upstream(socket,proxyAuth(password));
        }catch(Exception e){failed();throw new IOException("Xray unavailable; direct route blocked");}
    }
    private void start()throws Exception{
        stopCore();JSONObject profile=TunnelStore.find(data,data.getString("selected"));
        JSONObject outbound=new JSONObject(profile.getJSONObject("outbound").toString()),endpoint=XrayProfiles.endpoint(outbound);String host=endpoint.getString("address");
        prepareStream(outbound.optJSONObject("streamSettings"),host);
        // Android resolves only the proxy endpoint. Destination names travel inside Xray, to the selected server.
        if(!host.matches("[0-9.]+")&&!host.contains(":"))endpoint.put("address",InetAddress.getAllByName(host)[0].getHostAddress());
        try(ServerSocket reserve=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reserve.getLocalPort();}
        byte[] secret=new byte[24];new SecureRandom().nextBytes(secret);password=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libxray.so");
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"run","-config","stdin:","-format","json");builder.redirectErrorStream(true);core=builder.start();final Process child=core;
        try(OutputStream out=child.getOutputStream()){out.write(XrayProfiles.config(outbound,port,password).toString().getBytes(StandardCharsets.UTF_8));}
        Thread drain=new Thread(()->{try(InputStream in=child.getInputStream()){byte[] b=new byte[4096];while(in.read(b)!=-1){/* Never log config, URLs, credentials or destination traffic. */}}catch(IOException ignored){}if(!closed&&core==child)failed();},"xray-output");drain.setDaemon(true);drain.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){
            if(!child.isAlive())throw new IOException("Core exited");
            try(Socket ready=new Socket()){ready.connect(new InetSocketAddress("127.0.0.1",port),100);return;}catch(IOException e){Thread.sleep(50);}
        }
        stopCore();throw new IOException("Core startup timeout");
    }
    static void prepareStream(JSONObject stream,String host)throws Exception{
        if(stream==null)return;
        if(stream.optString("security").equals("tls")&&!stream.has("tlsSettings"))stream.put("tlsSettings",new JSONObject());
        for(String key:new String[]{"tlsSettings","realitySettings"}){JSONObject tls=stream.optJSONObject(key);if(tls!=null&&tls.optString("serverName").isEmpty())tls.put("serverName",host);}
        JSONObject xhttp=stream.optJSONObject("xhttpSettings");if(xhttp==null)return;
        JSONObject extra=xhttp.optJSONObject("extra");
        for(JSONObject settings:new JSONObject[]{xhttp,extra}){
            if(settings==null)continue;JSONObject download=settings.optJSONObject("downloadSettings");if(download==null)continue;
            String address=download.optString("address",host);if(address.isEmpty())address=host;
            prepareStream(download,address);
            download.put("address",!address.matches("[0-9.]+")&&!address.contains(":")?InetAddress.getAllByName(address)[0].getHostAddress():address);
        }
    }
    void stopCore(){Process old=core;core=null;if(old!=null){old.destroy();try{if(!old.waitFor(1,TimeUnit.SECONDS))old.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();old.destroyForcibly();}}password="";}
    interface Work{void run()throws Exception;}
    void change(Work work){
        if(working)return;working=true;changed.run();tasks.execute(()->{try{
            synchronized(this){work.run();disconnect.run();stopCore();error="";reload();}
        }catch(Exception e){report(e instanceof IOException?e.getMessage():"Не удалось изменить настройки Xray");}finally{working=false;changed.run();}});
    }
    void select(String id){change(()->store.select(id));}
    void enable(boolean value){change(()->store.enabled(value));}
    void remove(String id){change(()->store.remove(id));}
    static String fetch(String address)throws Exception{
        URL url=new URL(address);if(!url.getProtocol().equals("https")||url.getUserInfo()!=null)throw new IOException("Подписка должна быть ссылкой HTTPS без логина в адресе");
        for(int redirect=0;redirect<6;redirect++){
            String host=url.getHost().toLowerCase(Locale.ROOT);if(host.matches("(.+\\.)?(chatgpt\\.com|openai\\.com|oaistatic\\.com|oaiusercontent\\.com|chatgpt\\.live)"))throw new IOException("Это адрес ChatGPT, а не подписки");
            HttpURLConnection c=(HttpURLConnection)url.openConnection(Proxy.NO_PROXY);c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","CodexMobile/0.6.3 (Xray subscription)");c.setRequestProperty("Accept","text/plain,application/json");
            try{int status=c.getResponseCode();if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Подписка вернула некорректное перенаправление");url=new URL(url,location);if(!url.getProtocol().equals("https")||url.getUserInfo()!=null)throw new IOException("Подписка перенаправляет на незащищённый адрес");continue;}if(status!=200)throw new IOException("Не удалось загрузить подписку: HTTP "+status);try(InputStream in=c.getInputStream()){return new String(MediaFiles.read(in,2*1024*1024),StandardCharsets.UTF_8);}}finally{c.disconnect();}
        }throw new IOException("Слишком много перенаправлений подписки");
    }
    void importSubscription(String id,String name,String source,boolean url,java.util.function.Consumer<String> success,java.util.function.Consumer<String> failure){
        if(working)return;working=true;changed.run();tasks.execute(()->{
            try{String body=url?fetch(source):source;XrayProfiles.ImportResult imported=XrayProfiles.parseSubscription(body);
                synchronized(this){store.save(id,name,url?source:"",imported.profiles,imported.warning);disconnect.run();stopCore();error="";reload();}success.accept(imported.warning);
            }catch(Exception e){failure.accept(e instanceof IOException?e.getMessage():"Не удалось загрузить или разобрать подписку. Прежняя версия сохранена.");}finally{working=false;changed.run();}
        });
    }
    void refresh(String id){
        try{JSONObject s=store.subscription(id);if(s.optString("url").isEmpty())throw new IOException("Вставь обновлённые ссылки через редактирование подписки");importSubscription(id,s.getString("name"),s.getString("url"),true,warning->report(""),this::report);}catch(Exception e){report("Не удалось обновить подписку");}
    }
    void test(){
        if(working)return;working=true;changed.run();tasks.execute(()->{
            try{
                if(!enabled)throw new IOException("Сначала включи Xray и выбери сервер");
                EngineNetworkProxy.Upstream up=open();
                try(Socket raw=up.socket){
                    raw.setSoTimeout(20000);raw.getOutputStream().write(("CONNECT chatgpt.com:443 HTTP/1.1\r\nHost: chatgpt.com:443\r\n"+up.authorization+"\r\n").getBytes(StandardCharsets.US_ASCII));
                    if(!EngineNetworkProxy.headers(raw.getInputStream()).startsWith("HTTP/1.1 200"))throw new IOException("Выбранный сервер не установил соединение с ChatGPT");
                    try(SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,"chatgpt.com",443,true)){
                        SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
                        tls.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: chatgpt.com\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        String status=EngineNetworkProxy.headers(tls.getInputStream()).split("\r\n",2)[0];
                        if(!status.matches("HTTP/1\\.[01] [234][0-9][0-9].*"))throw new IOException("ChatGPT вернул неожиданный ответ");
                        error="";state="Xray · "+selectedName+" · TLS OK · "+status.split(" ")[1];
                        if(status.split(" ")[1].equals("403"))error="Туннель и TLS работают, но ChatGPT вернул HTTP 403. Проверь доступность через этот хост.";
                    }
                }
            }catch(Exception e){error="Ошибка проверки Xray: нет подтверждённого соединения с ChatGPT. Прямое подключение заблокировано.";}
            finally{working=false;changed.run();}
        });
    }
    @Override public synchronized void close(){closed=true;tasks.shutdownNow();stopCore();}
}
