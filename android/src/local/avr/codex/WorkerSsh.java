package local.avr.codex.copy;

import com.jcraft.jsch.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/** Named SSH operations; saved root passwords are never used for key authentication. */
final class WorkerSsh implements Closeable {
    final ServerStore store;
    final Set<Session> live=ConcurrentHashMap.newKeySet();
    final ScheduledExecutorService timeouts=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ssh-file-timeout");t.setDaemon(true);return t;});
    WorkerSsh(ServerStore store){this.store=store;}
    static void addIdentity(JSch j,JSONObject p)throws Exception{
        byte[] key=p.getString("privateKey").getBytes(StandardCharsets.UTF_8),phrase=p.optString("passphrase").getBytes(StandardCharsets.UTF_8);
        try{j.addIdentity("saved-worker-key",key,null,phrase.length==0?null:phrase);}finally{Arrays.fill(key,(byte)0);Arrays.fill(phrase,(byte)0);}
    }
    static void validateIdentity(JSONObject p)throws Exception{
        if(p.optString("privateKey").isEmpty())return;JSch j=new JSch();
        try{addIdentity(j,p);for(Identity identity:j.getIdentityRepository().getIdentities())if(identity.isEncrypted())throw new IOException("Укажи пароль зашифрованного ключа отдельно от пароля root");}
        catch(JSchException e){throw new IOException("Не удалось прочитать SSH-ключ или пароль ключа",e);}finally{j.removeAllIdentity();}
    }
    Session connect(JSONObject p)throws Exception{
        JSch j=new JSch();boolean byKey=!p.optString("privateKey").isEmpty();if(byKey)addIdentity(j,p);
        final byte[][] candidate={null};final String[] algorithm={""};String saved=p.optString("hostKey");
        j.setHostKeyRepository(new HostKeyRepository(){
            public int check(String host,byte[] key){candidate[0]=key.clone();try{algorithm[0]=new HostKey(host,key).getType();}catch(Exception ignored){}
                return saved.isEmpty()||java.security.MessageDigest.isEqual(Base64.getDecoder().decode(saved),key)?OK:CHANGED;}
            public void add(HostKey key,UserInfo info){}public void remove(String h,String t){}public void remove(String h,String t,byte[] k){}
            public String getKnownHostsRepositoryID(){return "encrypted-worker-profile";}public HostKey[] getHostKey(){return new HostKey[0];}public HostKey[] getHostKey(String h,String t){return new HostKey[0];}
        });
        Session session=j.getSession(p.getString("user"),p.getString("host"),p.getInt("port"));
        session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications",byKey?"publickey":"password,keyboard-interactive");
        if(!byKey){String password=p.optString("password");session.setPassword(password);session.setUserInfo(new PasswordOnly(password));}
        session.setServerAliveInterval(15000);session.setServerAliveCountMax(3);
        try{session.connect(15000);if(candidate[0]==null)throw new IOException("Не получен ключ сервера");store.pin(p.getString("id"),p.getString("host"),p.getInt("port"),candidate[0],algorithm[0]);live.add(session);return session;}
        catch(Exception e){session.disconnect();j.removeAllIdentity();throw e;}
    }
    static final class PasswordOnly implements UserInfo,UIKeyboardInteractive {
        final String password;PasswordOnly(String s){password=s;}public String getPassword(){return password;}public String getPassphrase(){return null;}
        public boolean promptPassword(String m){return true;}public boolean promptPassphrase(String m){return false;}public boolean promptYesNo(String m){return false;}public void showMessage(String m){}
        public String[] promptKeyboardInteractive(String d,String n,String i,String[] p,boolean[] e){return p.length==1&&!e[0]?new String[]{password}:null;}
    }
    static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    static void drain(InputStream in,ByteArrayOutputStream out)throws IOException{byte[] b=new byte[8192];while(in.available()>0){int n=in.read(b);if(n<0)return;if(out.size()<524288)out.write(b,0,Math.min(n,524288-out.size()));}}
    JSONObject execute(String name,String command,boolean asRoot,int timeoutSeconds)throws Exception{
        if(command.isEmpty()||command.length()>65536||command.indexOf('\0')>=0)throw new IOException("Некорректная команда");
        if(timeoutSeconds<1||timeoutSeconds>600)throw new IOException("Тайм-аут: 1–600 секунд");
        JSONObject p=store.find(name);boolean elevate=asRoot&&!"root".equals(p.getString("user"));
        if(elevate&&(p.optString("privateKey").isEmpty()||p.optString("password").isEmpty()))throw new IOException("Для su нужен ключ SSH и отдельный пароль root; либо подключайся логином root");
        Session session=connect(p);ChannelExec channel=null;
        try{
            channel=(ChannelExec)session.openChannel("exec");channel.setPty(elevate);
            channel.setCommand(elevate?"stty -echo || exit 125; LC_ALL=C exec su - root -c "+quote(command):command);
            InputStream stdout=channel.getInputStream(),stderr=channel.getErrStream();OutputStream input=channel.getOutputStream();
            channel.connect(15000);ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
            boolean passwordSent=false;long started=System.currentTimeMillis(),deadline=started+timeoutSeconds*1000L;
            while(true){drain(stdout,out);drain(stderr,err);
                if(elevate&&!passwordSent){String prompt=out.toString("UTF-8")+err.toString("UTF-8");
                    if(prompt.toLowerCase(Locale.ROOT).contains("password:")){byte[] password=(p.getString("password")+"\n").getBytes(StandardCharsets.UTF_8);try{input.write(password);input.flush();passwordSent=true;}finally{Arrays.fill(password,(byte)0);}}
                    else if(System.currentTimeMillis()-started>10000&&!channel.isClosed())throw new IOException("su не запросил пароль; пароль не отправлен");
                }
                if(channel.isClosed()){drain(stdout,out);drain(stderr,err);break;}
                if(System.currentTimeMillis()>deadline)throw new IOException("Команда остановлена по тайм-ауту");Thread.sleep(20);
            }
            return new JSONObject().put("ok",true).put("server",p.getString("name")).put("user",asRoot?"root":p.getString("user")).put("exitCode",channel.getExitStatus()).put("stdout",out.toString("UTF-8").replace("Password:","")).put("stderr",err.toString("UTF-8").replace("Password:","")).put("truncated",out.size()>=524288||err.size()>=524288);
        }finally{if(channel!=null)channel.disconnect();session.disconnect();live.remove(session);}
    }
    JSONObject transfer(String name,String action,String path,byte[] bytes)throws Exception{
        if(path.isEmpty()||path.length()>4096||path.indexOf('\0')>=0)throw new IOException("Некорректный путь");
        JSONObject p=store.find(name);Session session=connect(p);ChannelSftp channel=null;
        ScheduledFuture<?> timeout=timeouts.schedule(session::disconnect,120,TimeUnit.SECONDS);
        try{channel=(ChannelSftp)session.openChannel("sftp");channel.connect(15000);
            if(action.equals("put")){if(bytes.length>10*1024*1024)throw new IOException("Файл больше 10 МБ");channel.put(new ByteArrayInputStream(bytes),path);return new JSONObject().put("ok",true).put("bytes",bytes.length).put("path",path);}
            ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=channel.get(path)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(data.size()+n>10*1024*1024)throw new IOException("Файл больше 10 МБ");data.write(b,0,n);}}
            return new JSONObject().put("ok",true).put("bytes",data.size()).put("data",Base64.getEncoder().encodeToString(data.toByteArray()));
        }finally{timeout.cancel(false);if(channel!=null)channel.disconnect();session.disconnect();live.remove(session);}
    }
    public void close(){for(Session session:live)session.disconnect();live.clear();timeouts.shutdownNow();}
}
