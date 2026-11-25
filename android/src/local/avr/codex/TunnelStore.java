package local.avr.codex.copy;

import android.security.keystore.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Subscription URLs and proxy secrets never enter the WebView or chat. */
final class TunnelStore {
    final File file;
    TunnelStore(File directory){directory.mkdirs();file=new File(directory,"subscriptions.enc");}
    SecretKey key()throws Exception{
        String alias="avr-codex-xray-v1";KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(alias))return ((KeyStore.SecretKeyEntry)ks.getEntry(alias,null)).getSecretKey();
        KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build());return g.generateKey();
    }
    synchronized JSONObject read()throws Exception{
        if(!file.isFile())return XrayProfiles.obj("enabled",false,"selected","","subscriptions",new JSONArray());
        byte[] bytes=Files.readAllBytes(file.toPath());if(bytes.length<28||bytes.length>8*1024*1024)throw new IOException("Не удалось прочитать подписки Xray");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));byte[] plain=c.doFinal(bytes,12,bytes.length-12);
        try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}finally{Arrays.fill(plain,(byte)0);}
    }
    synchronized void write(JSONObject data)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] plain=data.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>8*1024*1024-28)throw new IOException("Хранилище подписок заполнено");byte[] encrypted;try{encrypted=c.doFinal(plain);}finally{Arrays.fill(plain,(byte)0);}
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(c.getIV());b.write(encrypted);File tmp=new File(file.getParentFile(),"subscriptions.tmp");Files.write(tmp.toPath(),b.toByteArray());if(!tmp.renameTo(file))throw new IOException("Не удалось сохранить подписки");
    }
    synchronized void save(String id,String name,String url,JSONArray profiles)throws Exception{
        JSONObject data=read();JSONArray all=data.getJSONArray("subscriptions");int at=-1;
        for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))at=i;
        if(at<0){if(all.length()>=30)throw new IOException("Можно добавить до 30 подписок");id=UUID.randomUUID().toString();}
        if(name.trim().isEmpty()||name.length()>80)throw new IOException("Укажи название подписки (до 80 символов)");
        JSONObject sub=XrayProfiles.obj("id",id,"name",name.trim(),"url",url,"profiles",profiles,"updated",System.currentTimeMillis());if(at<0)all.put(sub);else all.put(at,sub);
        // First import enables strict mode. Refresh never switches a lost selection to a different host.
        if(data.optString("selected").isEmpty()){data.put("selected",id+":"+profiles.getJSONObject(0).getString("id"));data.put("enabled",true);}
        write(data);
    }
    synchronized void select(String selected)throws Exception{JSONObject data=read();find(data,selected);data.put("selected",selected).put("enabled",true);write(data);}
    synchronized void enabled(boolean enabled)throws Exception{JSONObject d=read();if(enabled)find(d,d.optString("selected"));d.put("enabled",enabled);write(d);}
    synchronized void remove(String id)throws Exception{
        JSONObject d=read();JSONArray before=d.getJSONArray("subscriptions"),after=new JSONArray();for(int i=0;i<before.length();i++)if(!before.getJSONObject(i).getString("id").equals(id))after.put(before.get(i));d.put("subscriptions",after);
        // Retain enabled + missing selection: deletion must block traffic, never revert to system routing.
        write(d);
    }
    static JSONObject find(JSONObject data,String selected)throws Exception{
        JSONArray all=data.getJSONArray("subscriptions");for(int i=0;i<all.length();i++){JSONObject sub=all.getJSONObject(i);JSONArray nodes=sub.getJSONArray("profiles");for(int j=0;j<nodes.length();j++){JSONObject p=nodes.getJSONObject(j);if((sub.getString("id")+":"+p.getString("id")).equals(selected))return p;}}
        throw new IOException("Выбранный сервер отсутствует. Выбери хост в подписке Xray.");
    }
    synchronized JSONObject subscription(String id)throws Exception{JSONArray a=read().getJSONArray("subscriptions");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IOException("Подписка не найдена");}
}
