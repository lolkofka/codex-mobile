package local.avr.codex.copy;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.jcraft.jsch.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** SSH identity stays encrypted in app-private storage; only a pinned host is accepted. */
final class RemotePc implements Closeable {
    private static final String ALIAS="avr-codex-pc-ssh-v1";
    final Session session;
    final ChannelExec channel;
    final InputStream input;
    final OutputStream output;
    private RemotePc(Session s,ChannelExec c,InputStream i,OutputStream o){session=s;channel=c;input=i;output=o;}
    static File directory(Context context){File d=new File(context.getFilesDir(),"remote-pc");d.mkdirs();return d;}
    static JSONObject profile(Context context)throws Exception{
        JSONObject p=new JSONObject(new String(Files.readAllBytes(new File(directory(context),"profile.json").toPath()),StandardCharsets.UTF_8));
        if(!p.getString("host").matches("[A-Za-z0-9.:-]{1,253}")||p.getInt("port")<1||p.getInt("port")>65535)throw new IOException("Некорректный адрес ПК");
        if(!p.getString("user").matches("[A-Za-z0-9_.@-]{1,80}"))throw new IOException("Некорректный пользователь SSH");
        String[] k=p.getString("hostKey").trim().split("\\s+");
        if(k.length<2||!k[0].equals("ecdsa-sha2-nistp256"))throw new IOException("Не задан закреплённый ключ ПК");
        return p;
    }
    static boolean configured(Context context){try{profile(context);return new File(directory(context),"identity.enc").isFile();}catch(Exception e){return false;}}
    private static SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return gen.generateKey();
    }
    static synchronized void prepareIdentity(Context context)throws Exception{
        File dir=directory(context),encrypted=new File(dir,"identity.enc"),pub=new File(dir,"identity.pub");
        if(encrypted.isFile()&&pub.isFile())return;
        KeyPair pair=KeyPair.genKeyPair(new JSch(),KeyPair.ECDSA,256);
        ByteArrayOutputStream privateKey=new ByteArrayOutputStream(),publicKey=new ByteArrayOutputStream();
        pair.writePrivateKey(privateKey);pair.writePublicKey(publicKey,"avr-codex-mobile-pc");pair.dispose();
        byte[] raw=privateKey.toByteArray();
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            ByteArrayOutputStream blob=new ByteArrayOutputStream();blob.write(cipher.getIV());blob.write(cipher.doFinal(raw));
            Files.write(encrypted.toPath(),blob.toByteArray());Files.write(pub.toPath(),publicKey.toByteArray());
        }finally{Arrays.fill(raw,(byte)0);privateKey.reset();}
    }
    static RemotePc connect(Context context)throws Exception{
        prepareIdentity(context);JSONObject p=profile(context);JSch jsch=new JSch();
        byte[] blob=Files.readAllBytes(new File(directory(context),"identity.enc").toPath());
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(blob,12)));
        byte[] raw=cipher.doFinal(blob,12,blob.length-12);
        try{jsch.addIdentity("avr-mobile-pc",raw,null,null);}finally{Arrays.fill(raw,(byte)0);}
        String host=p.getString("host");int port=p.getInt("port");
        String known=(port==22?host:"["+host+"]:"+port)+" "+p.getString("hostKey").trim()+"\n";
        jsch.setKnownHosts(new ByteArrayInputStream(known.getBytes(StandardCharsets.US_ASCII)));
        Session session=jsch.getSession(p.getString("user"),host,port);
        session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","publickey");
        session.setConfig("server_host_key","ecdsa-sha2-nistp256");
        session.setServerAliveInterval(20000);session.setServerAliveCountMax(3);
        try{
            session.connect(15000);ChannelExec channel=(ChannelExec)session.openChannel("exec");
            channel.setCommand("avr-codex-pc");channel.setPty(false);
            // Remote stderr can contain account/network details; never put it into logcat or WebView.
            channel.setErrStream(new OutputStream(){@Override public void write(int b){}},true);
            InputStream input=channel.getInputStream();OutputStream output=channel.getOutputStream();channel.connect(15000);
            return new RemotePc(session,channel,input,output);
        }catch(Exception e){session.disconnect();jsch.removeAllIdentity();throw e;}
    }
    @Override public void close(){channel.disconnect();session.disconnect();}
}
