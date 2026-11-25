package local.avr.codex.copy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;

/** Synthetic local endpoints only; never uses a real login or VPN subscription. */
public final class EngineNetworkProxyTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static String head(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())>=0){out.write(b);String s=out.toString("ISO-8859-1");if(s.endsWith("\r\n\r\n"))return s;if(out.size()>16384)throw new IOException();}
        throw new EOFException();
    }
    static Socket client(EngineNetworkProxy p)throws IOException{URI u=URI.create(p.address());Socket s=new Socket(u.getHost(),u.getPort());s.setSoTimeout(5000);return s;}
    static String auth(EngineNetworkProxy p){return "Proxy-Authorization: Basic "+Base64.getEncoder().encodeToString(URI.create(p.address()).getUserInfo().getBytes(StandardCharsets.US_ASCII))+"\r\n";}
    static void send(Socket s,String text)throws IOException{s.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));}
    static Thread worker(Runnable task){Thread t=new Thread(task);t.setDaemon(true);t.start();return t;}
    static ServerSocket listener(int port)throws IOException{return new ServerSocket(port,2,InetAddress.getByName("127.0.0.1"));}
    static void tunnel(EngineNetworkProxy proxy,String target,byte[] bytes)throws Exception{
        try(Socket s=client(proxy)){
            send(s,"CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n"+auth(proxy)+"\r\n");
            check(head(s.getInputStream()).startsWith("HTTP/1.1 200"),"CONNECT accepted");
            s.getOutputStream().write(bytes);byte[] echoed=new byte[bytes.length];new DataInputStream(s.getInputStream()).readFully(echoed);
            check(java.util.Arrays.equals(bytes,echoed),"opaque tunnel preserves every byte");
        }
    }
    static void directEcho(EngineNetworkProxy proxy,byte[] bytes)throws Exception{
        try(ServerSocket origin=listener(0)){
            CompletableFuture<Void> done=new CompletableFuture<>();
            worker(()->{try(Socket s=origin.accept()){byte[] b=new byte[bytes.length];new DataInputStream(s.getInputStream()).readFully(b);s.getOutputStream().write(b);done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});
            tunnel(proxy,"127.0.0.1:"+origin.getLocalPort(),bytes);done.get(5,TimeUnit.SECONDS);
        }
    }
    public static void main(String[] args)throws Exception{
        int port;try(ServerSocket unused=listener(0)){port=unused.getLocalPort();}
        byte[] payload=new byte[256];for(int i=0;i<payload.length;i++)payload[i]=(byte)i;
        try(EngineNetworkProxy proxy=new EngineNetworkProxy(port)){
            check(URI.create(proxy.address()).getHost().equals("127.0.0.1"),"listener bound to loopback");
            try(Socket s=client(proxy)){send(s,"CONNECT example.invalid:443 HTTP/1.1\r\n\r\n");check(head(s.getInputStream()).contains("407"),"untrusted local apps need ephemeral proxy authentication");}
            directEcho(proxy,payload);
            try(ServerSocket origin=listener(0)){
                CompletableFuture<String> received=new CompletableFuture<>();
                worker(()->{try(Socket s=origin.accept()){String h=head(s.getInputStream());byte[] body=new byte[4];new DataInputStream(s.getInputStream()).readFully(body);received.complete(h+new String(body,StandardCharsets.US_ASCII));send(s,"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK");}catch(Throwable e){received.completeExceptionally(e);}});
                try(Socket s=client(proxy)){
                    send(s,"POST http://127.0.0.1:"+origin.getLocalPort()+"/oauth/test?q=1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n"+auth(proxy)+"\r\nTEST");
                    check(head(s.getInputStream()).contains("200"),"HTTP response forwarded");byte[] body=new byte[2];new DataInputStream(s.getInputStream()).readFully(body);check(new String(body,StandardCharsets.US_ASCII).equals("OK"),"HTTP response body forwarded");
                }
                String request=received.get(5,TimeUnit.SECONDS);
                check(request.startsWith("POST /oauth/test?q=1 HTTP/1.1")&&request.endsWith("TEST"),"absolute proxy URI becomes origin path and body is preserved");
                check(!request.toLowerCase().contains("proxy-authorization"),"local proxy password never reaches HTTP origin");
            }
            // The same live bridge picks up Happ started after Codex and falls
            // back to Android sockets after Happ stops. No engine restart.
            try(ServerSocket happ=listener(port)){
                CompletableFuture<String> received=new CompletableFuture<>();
                worker(()->{try(Socket s=happ.accept()){String h=head(s.getInputStream());send(s,"HTTP/1.1 200 Connection Established\r\n\r\n");byte[] b=new byte[payload.length];new DataInputStream(s.getInputStream()).readFully(b);s.getOutputStream().write(b);received.complete(h);}catch(Throwable e){received.completeExceptionally(e);}});
                tunnel(proxy,"vpn-resolves-this.invalid:443",payload);
                String request=received.get(5,TimeUnit.SECONDS);
                check(request.startsWith("CONNECT vpn-resolves-this.invalid:443"),"Happ handles remote DNS when it starts later");
                check(!request.toLowerCase().contains("proxy-authorization"),"local proxy password never reaches Happ or TLS origin");
            }
            directEcho(proxy,payload);
            Socket open=client(proxy);proxy.close();boolean terminated=false;
            try{terminated=open.getInputStream().read()==-1;}catch(SocketException reset){terminated=true;}
            check(terminated,"closing bridge terminates pending clients");open.close();
        }
        // A configured tunnel must never use the available direct origin, even when its core is down.
        try(ServerSocket origin=listener(0)){
            origin.setSoTimeout(500);java.util.concurrent.atomic.AtomicInteger errors=new java.util.concurrent.atomic.AtomicInteger();
            EngineNetworkProxy.Route strict=new EngineNetworkProxy.Route(){public EngineNetworkProxy.Upstream open()throws IOException{throw new IOException("Xray down");}public void failed(){errors.incrementAndGet();}};
            try(EngineNetworkProxy proxy=new EngineNetworkProxy(strict);Socket s=client(proxy)){
                send(s,"CONNECT 127.0.0.1:"+origin.getLocalPort()+" HTTP/1.1\r\n"+auth(proxy)+"\r\n");check(head(s.getInputStream()).contains("502"),"strict tunnel failure returns error");
                boolean leaked=false;try(Socket unexpected=origin.accept()){leaked=true;}catch(SocketTimeoutException expected){}
                check(!leaked,"strict tunnel never reaches an available direct destination");check(errors.get()>0,"strict failure reaches visible error handler");
            }
        }
        try(ServerSocket upstream=listener(0)){
            CompletableFuture<String> received=new CompletableFuture<>();
            worker(()->{try(Socket s=upstream.accept()){received.complete(head(s.getInputStream()));send(s,"HTTP/1.1 200 Connection Established\r\n\r\n");s.getOutputStream().write(s.getInputStream().read());}catch(Throwable e){received.completeExceptionally(e);}});
            EngineNetworkProxy.Route strict=new EngineNetworkProxy.Route(){public EngineNetworkProxy.Upstream open()throws IOException{return new EngineNetworkProxy.Upstream(new Socket("127.0.0.1",upstream.getLocalPort()),"Proxy-Authorization: Basic test-secret\r\n");}public void failed(){}};
            try(EngineNetworkProxy proxy=new EngineNetworkProxy(strict)){tunnel(proxy,"unresolved-chatgpt-subdomain.invalid:443",new byte[]{42});check(received.get(5,TimeUnit.SECONDS).contains("Proxy-Authorization: Basic test-secret"),"authenticated Xray upstream receives its own credentials and remote DNS target");}
        }
        System.out.println("PASS: "+checks+" engine network checks");
    }
}
