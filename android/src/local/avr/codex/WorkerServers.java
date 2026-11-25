package local.avr.codex.copy;

import android.content.Context;
import android.net.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** App-UID credential broker. The root CLI receives results, never saved credentials. */
final class WorkerServers implements Closeable {
    static final String SOCKET="avr.codex.copy.servers.v1";
    final ServerStore store;
    final WorkerSsh ssh;
    final ExecutorService requests=Executors.newFixedThreadPool(4);
    final Map<String,Object> profileLocks=new ConcurrentHashMap<>();
    LocalServerSocket server;
    volatile boolean closed;
    String error="";
    private JSONArray cachedMetadata=new JSONArray();
    private long cachedRevision=-1;
    WorkerServers(Context context){this(new File(context.getFilesDir(),"work-servers"),ServerStore.ALIAS,SOCKET);}
    WorkerServers(File directory,String alias,String socket){
        store=new ServerStore(directory,alias);ssh=new WorkerSsh(store);
        try{server=new LocalServerSocket(socket);new Thread(this::accept,"named-server-broker").start();}catch(Exception e){error="Не удалось запустить доступ к серверам";}
    }
    private void accept(){
        while(!closed)try{LocalSocket socket=server.accept();int uid=socket.getPeerCredentials().getUid();
            if(uid!=0&&uid!=android.os.Process.myUid()){socket.close();continue;}requests.execute(()->serve(socket));
        }catch(Exception e){if(!closed)error="Доступ к серверам остановлен";}
    }
    private void serve(LocalSocket socket){
        try(LocalSocket client=socket){client.setSoTimeout(15000);InputStream in=new BufferedInputStream(client.getInputStream());ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
            while((b=in.read())!=-1&&b!='\n'){if(bytes.size()>16*1024*1024)throw new IOException("Запрос слишком большой");bytes.write(b);}
            JSONObject req=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)),result;
            try{result=handle(req);}catch(Exception e){result=new JSONObject().put("ok",false).put("error",safeError(e));}
            client.getOutputStream().write((result.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }
    static String safeError(Exception e){
        String message=e.getMessage();if(message==null||message.length()>1500)return "Ошибка подключения: "+e.getClass().getSimpleName();
        if(message.contains("PRIVATE KEY"))return "Не удалось прочитать SSH-ключ";return message;
    }
    JSONObject handle(JSONObject request)throws Exception{
        String action=request.optString("action");
        if(action.equals("list"))return new JSONObject().put("ok",true).put("servers",store.list());
        String name=request.getString("server");JSONObject p=store.find(name);
        synchronized(profileLocks.computeIfAbsent(p.getString("id"),id->new Object())){
            if(action.equals("exec"))return ssh.execute(name,request.getString("command"),request.optBoolean("root"),request.optInt("timeout",120));
            if(action.equals("put"))return ssh.transfer(name,action,request.getString("path"),Base64.getDecoder().decode(request.getString("data")));
            if(action.equals("get"))return ssh.transfer(name,action,request.getString("path"),null);
        }
        throw new IOException("Неизвестная операция с сервером");
    }
    synchronized JSONArray metadata(){if(cachedRevision==store.revision)return cachedMetadata;try{cachedMetadata=store.list();}catch(Exception e){error="Не удалось открыть зашифрованные профили серверов";cachedMetadata=new JSONArray();}cachedRevision=store.revision;return cachedMetadata;}
    public void close(){closed=true;ssh.close();requests.shutdownNow();try{if(server!=null)server.close();}catch(Exception ignored){}}
    static String instructions(){return " Рабочие SSH-серверы владелец задаёт в меню аккаунта → Рабочие серверы. Используй codex-servers list для списка названий, затем codex-servers exec 'Название' [--root] [--timeout СЕКУНДЫ] -- 'команда'. Модель и локальные команды остаются на телефоне; удалённую команду выполняет именно codex-servers. put/get передают файлы до 10 МБ. Пути сервера не являются локальными путями телефона: для открытия файла скачай его через get и дай ссылку на локальную копию. С ключом сохранённый пароль означает пароль root для su, не пароль SSH и не пароль ключа. По умолчанию работай под SSH-логином; --root используй только когда задача требует root. Не запрашивай и не выводи сохранённые ключи и пароли: брокер использует их сам. Ключ сервера закрепляется при первом успешном входе, изменение блокирует соединение.";}
}
