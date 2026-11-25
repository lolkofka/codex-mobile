package local.avr.codex.copy;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials stay behind the app UID's Android Keystore key. Only metadata leaves this class. */
final class ServerStore {
    static final String ALIAS="avr-codex-worker-servers-v1";
    final File directory,file;
    final String keyAlias;
    volatile long revision;
    ServerStore(File directory){this(directory,ALIAS);}
    ServerStore(File directory,String alias){this.directory=directory;directory.mkdirs();file=new File(directory,"profiles.enc");keyAlias=alias;}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(keyAlias))return ((KeyStore.SecretKeyEntry)store.getEntry(keyAlias,null)).getSecretKey();
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return g.generateKey();
    }
    private JSONArray read()throws Exception{
        if(!file.isFile())return new JSONArray();byte[] blob=Files.readAllBytes(file.toPath());
        if(blob.length<28||blob.length>2*1024*1024)throw new IOException("Некорректное хранилище серверов");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(blob,12)));
        byte[] plain=c.doFinal(blob,12,blob.length-12);
        try{return new JSONArray(new String(plain,StandardCharsets.UTF_8));}finally{Arrays.fill(plain,(byte)0);}
    }
    private void write(JSONArray data)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] plain=data.toString().getBytes(StandardCharsets.UTF_8);byte[] encrypted;
        if(plain.length>2*1024*1024-28){Arrays.fill(plain,(byte)0);throw new IOException("Хранилище серверов заполнено; старые профили сохранены");}
        try{encrypted=c.doFinal(plain);}finally{Arrays.fill(plain,(byte)0);}
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(c.getIV());b.write(encrypted);
        File temp=new File(directory,"profiles.tmp");Files.write(temp.toPath(),b.toByteArray());temp.setReadable(false,false);temp.setWritable(false,false);temp.setReadable(true,true);temp.setWritable(true,true);
        if(!temp.renameTo(file))throw new IOException("Не удалось сохранить серверы");revision++;
    }
    static JSONObject metadata(JSONObject p)throws Exception{
        JSONObject out=new JSONObject();for(String name:new String[]{"id","name","host","port","user","hostKeyFingerprint","lastConnected"})if(p.has(name))out.put(name,p.get(name));
        return out.put("hasKey",!p.optString("privateKey").isEmpty()).put("hasPassword",!p.optString("password").isEmpty()).put("hasPassphrase",!p.optString("passphrase").isEmpty());
    }
    synchronized JSONArray list()throws Exception{JSONArray out=new JSONArray(),all=read();for(int i=0;i<all.length();i++)out.put(metadata(all.getJSONObject(i)));return out;}
    synchronized JSONObject find(String value)throws Exception{
        JSONArray all=read();for(int i=0;i<all.length();i++){JSONObject p=all.getJSONObject(i);if(value.equals(p.optString("id"))||value.equalsIgnoreCase(p.optString("name")))return p;}
        throw new IOException("Сервер с таким именем не найден");
    }
    synchronized JSONObject save(JSONObject fields)throws Exception{
        JSONArray all=read();String id=fields.optString("id");JSONObject p=new JSONObject();int index=-1;
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id"))){p=all.getJSONObject(i);index=i;break;}
        if(index<0){if(all.length()>=100)throw new IOException("Можно сохранить до 100 серверов");id=UUID.randomUUID().toString();}
        String name=fields.optString("name").trim(),host=fields.optString("host").trim(),user=fields.optString("user").trim();int port=fields.optInt("port",22);
        if(name.isEmpty()||name.length()>80||name.matches(".*[\\p{Cntrl}].*"))throw new IOException("Укажи короткое название сервера");
        if(!host.matches("[A-Za-z0-9._:-]{1,253}")||port<1||port>65535)throw new IOException("Проверь адрес и порт SSH");
        if(!user.matches("[A-Za-z0-9_.@-]{1,80}"))throw new IOException("Проверь логин SSH");
        for(int i=0;i<all.length();i++)if(i!=index&&name.equalsIgnoreCase(all.getJSONObject(i).optString("name")))throw new IOException("Такое название уже используется");
        if(!host.equals(p.optString("host"))||port!=p.optInt("port")){p.remove("hostKey");p.remove("hostKeyFingerprint");p.remove("hostKeyAlgorithm");}
        p.put("id",id).put("name",name).put("host",host).put("port",port).put("user",user);
        if(fields.has("privateKey"))p.put("privateKey",fields.getString("privateKey"));
        if(fields.has("passphrase"))p.put("passphrase",fields.getString("passphrase"));
        if(fields.has("password"))p.put("password",fields.getString("password"));
        String secret=p.optString("password"),key=p.optString("privateKey");
        if(secret.length()>4096||secret.indexOf('\n')>=0||secret.indexOf('\r')>=0||secret.indexOf('\0')>=0)throw new IOException("Некорректный пароль");
        if(key.length()>65536||(!key.isEmpty()&&!key.contains("PRIVATE KEY")))throw new IOException("Нужен закрытый SSH-ключ, а не публичный");
        if(key.isEmpty()&&secret.isEmpty())throw new IOException("Добавь SSH-ключ или пароль");
        WorkerSsh.validateIdentity(p);
        if(index<0)all.put(p);else all.put(index,p);write(all);return metadata(p);
    }
    synchronized void remove(String id)throws Exception{JSONArray all=read(),next=new JSONArray();for(int i=0;i<all.length();i++)if(!id.equals(all.getJSONObject(i).optString("id")))next.put(all.getJSONObject(i));write(next);}
    synchronized void pin(String id,String host,int port,byte[] hostKey,String algorithm)throws Exception{
        JSONArray all=read();for(int i=0;i<all.length();i++){JSONObject p=all.getJSONObject(i);if(!id.equals(p.optString("id")))continue;
            if(!host.equals(p.optString("host"))||port!=p.optInt("port"))throw new IOException("Адрес профиля изменён во время подключения");
            String existing=p.optString("hostKey"),encoded=Base64.getEncoder().encodeToString(hostKey);
            if(!existing.isEmpty()&&!existing.equals(encoded))throw new IOException("Ключ сервера изменился. Соединение отклонено.");
            p.put("hostKey",encoded).put("hostKeyAlgorithm",algorithm).put("hostKeyFingerprint","SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))).put("lastConnected",System.currentTimeMillis());write(all);return;
        }throw new IOException("Профиль удалён во время подключения");
    }
}
