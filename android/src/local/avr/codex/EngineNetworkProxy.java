package local.avr.codex;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated loopback proxy: Android DNS/VPN for the static Linux engine.
 * HTTPS stays encrypted between Codex and its server. No certificates or tokens
 * are inspected. Happ is selected anew for every connection, never mid-request.
 */
final class EngineNetworkProxy implements Closeable {
    private final ServerSocket server;
    private final String password,authorization;
    private final int happPort;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Semaphore connections=new Semaphore(16);
    private volatile boolean closed;

    EngineNetworkProxy()throws IOException{this(10809);}
    EngineNetworkProxy(int happPort)throws IOException{
        this.happPort=happPort;
        byte[] random=new byte[24];new SecureRandom().nextBytes(random);
        password=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        authorization="Basic "+Base64.getEncoder().encodeToString(("codex:"+password).getBytes(StandardCharsets.US_ASCII));
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        daemon(()->accept(),"codex-network");
    }
    String address(){return "http://codex:"+password+"@127.0.0.1:"+server.getLocalPort();}
    private static void daemon(Runnable task,String name){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
    private void accept(){
        while(!closed)try{
            Socket client=server.accept();
            if(!connections.tryAcquire()){client.close();continue;}
            sockets.add(client);daemon(()->serve(client),"codex-network-request");
        }catch(IOException e){if(!closed)close();}
    }
    private static String headers(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int matched=0;
        while(bytes.size()<16384){
            int next=input.read();if(next<0)throw new EOFException();bytes.write(next);
            if(next==(matched==0||matched==2?'\r':'\n'))matched++;else matched=next=='\r'?1:0;
            if(matched==4)return new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);
        }throw new IOException("Proxy header too large");
    }
    private static void reply(Socket client,String status)throws IOException{
        client.getOutputStream().write(("HTTP/1.1 "+status+"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }
    private Socket happ(){
        if(happPort<=0)return null;
        Socket socket=new Socket();
        try{socket.connect(new InetSocketAddress("127.0.0.1",happPort),400);return socket;}
        catch(IOException e){try{socket.close();}catch(IOException ignored){}return null;}
    }
    private void serve(Socket client){
        Socket upstream=null;
        try{
            client.setSoTimeout(10000);
            String[] lines=headers(client.getInputStream()).split("\r\n");
            String[] request=lines[0].split(" ",3);if(request.length!=3){reply(client,"400 Bad Request");return;}
            boolean authenticated=false;
            for(int i=1;i<lines.length;i++)if(lines[i].toLowerCase(Locale.ROOT).startsWith("proxy-authorization:"))
                authenticated=java.security.MessageDigest.isEqual(authorization.getBytes(StandardCharsets.US_ASCII),lines[i].substring(lines[i].indexOf(':')+1).trim().getBytes(StandardCharsets.US_ASCII));
            if(!authenticated){reply(client,"407 Proxy Authentication Required");return;}
            boolean connect="CONNECT".equals(request[0]);
            URI uri=new URI(connect?"https://"+request[1]:request[1]);
            if(uri.getHost()==null||uri.getUserInfo()!=null||(!connect&&!"http".equalsIgnoreCase(uri.getScheme()))){reply(client,"400 Bad Request");return;}
            int port=uri.getPort()<0?(connect?443:80):uri.getPort();
            if(port<1||port>65535){reply(client,"400 Bad Request");return;}
            upstream=happ();boolean viaHapp=upstream!=null;
            if(!viaHapp){upstream=new Socket();upstream.connect(new InetSocketAddress(uri.getHost(),port),15000);}
            sockets.add(upstream);upstream.setSoTimeout(15000);
            if(closed)return;
            if(connect){
                if(viaHapp){
                    String target=request[1];
                    upstream.getOutputStream().write(("CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String response=headers(upstream.getInputStream());String[] status=response.split(" ",3);
                    if(status.length<2||!"200".equals(status[1])){reply(client,"502 Bad Gateway");return;}
                }
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }else{
                String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";
                if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                StringBuilder forward=new StringBuilder(request[0]+" "+(viaHapp?request[1]:path)+" HTTP/1.1\r\n");
                for(int i=1;i<lines.length;i++){
                    String key=lines[i].split(":",2)[0].toLowerCase(Locale.ROOT);
                    if(!key.equals("proxy-authorization")&&!key.equals("proxy-connection")&&!key.equals("connection"))forward.append(lines[i]).append("\r\n");
                }
                forward.append("Connection: close\r\n\r\n");
                upstream.getOutputStream().write(forward.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            client.setSoTimeout(0);upstream.setSoTimeout(0);
            final Socket target=upstream;
            daemon(()->copy(client,target),"codex-network-upload");
            copy(upstream,client);
        }catch(Exception e){try{reply(client,"502 Bad Gateway");}catch(IOException ignored){}}
        finally{dispose(client);if(upstream!=null)dispose(upstream);connections.release();}
    }
    private static void copy(Socket from,Socket to){
        try{byte[] bytes=new byte[32768];InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);to.shutdownOutput();}catch(IOException ignored){}
    }
    private void dispose(Socket socket){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    @Override public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket socket:sockets)dispose(socket);}
}
