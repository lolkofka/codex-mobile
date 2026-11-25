import android.net.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Root-only CLI to the app's credential broker; no credential getter exists. */
public final class ServerCli {
  public static void main(String[] args){
    try{run(args);}catch(Exception e){try{System.out.println(new JSONObject().put("ok",false).put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}catch(Exception ignored){}System.exit(1);}
  }
  static void run(String[] args)throws Exception{
    if(android.os.Process.myUid()!=0)throw new SecurityException("Root required");
    if(args.length==0||args[0].equals("help")||args[0].equals("--help")){
      System.out.println("list | exec NAME [--root] [--timeout SECONDS] -- COMMAND | put NAME LOCAL REMOTE | get NAME REMOTE LOCAL\nProfiles: Codex Mobile → account → Рабочие серверы. With a key, the saved password is only for root via su. Credentials are never returned.");return;
    }
    String action=args[0],socketName="avr.codex.copy.servers.v1";JSONObject request=new JSONObject().put("action",action);Path target=null;
    int timeout=120;
    if(!action.equals("list")){
      if(args.length<2)throw new IllegalArgumentException("Server name required");request.put("server",args[1]);
      if(action.equals("exec")){
        boolean root=false;int at=2;
        while(at<args.length&&!args[at].equals("--")){
          if(args[at].equals("--root")){root=true;at++;}
          else if(args[at].equals("--timeout")&&at+1<args.length){timeout=Integer.parseInt(args[at+1]);at+=2;}
          else break;
        }
        if(at<args.length&&args[at].equals("--"))at++;
        if(at>=args.length)throw new IllegalArgumentException("Command required");
        String command=String.join(" ",Arrays.copyOfRange(args,at,args.length));request.put("command",command).put("root",root).put("timeout",timeout);
      }else if(action.equals("put")){
        if(args.length!=4)throw new IllegalArgumentException("put NAME LOCAL REMOTE");Path file=Paths.get(args[2]);
        if(Files.size(file)>10*1024*1024)throw new IOException("File exceeds 10 MiB");request.put("path",args[3]).put("data",Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
      }else if(action.equals("get")){if(args.length!=4)throw new IllegalArgumentException("get NAME REMOTE LOCAL");request.put("path",args[2]);target=Paths.get(args[3]);}
      else throw new IllegalArgumentException("Unknown operation");
    }
    LocalSocket socket=new LocalSocket();
    try{socket.connect(new LocalSocketAddress(socketName));}catch(IOException e){throw new IOException("Открой Codex Mobile: брокер рабочих серверов недоступен");}
    socket.setSoTimeout((Math.max(120,Math.min(timeout,600))+30)*1000);
    socket.getOutputStream().write((request.toString()+"\n").getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=new BufferedInputStream(socket.getInputStream());int b;
    while((b=in.read())!=-1&&b!='\n'){if(bytes.size()>32*1024*1024)throw new IOException("Response too large");bytes.write(b);}socket.close();
    JSONObject result=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
    if(target!=null&&result.optBoolean("ok")){byte[] data=Base64.getDecoder().decode(result.getString("data"));Files.write(target,data);result.remove("data");result.put("localPath",target.toString());}
    System.out.println(result.toString());if(!result.optBoolean("ok"))System.exit(1);
    if(action.equals("exec")&&result.optInt("exitCode")!=0)System.exit(Math.max(1,Math.min(255,result.optInt("exitCode"))));
  }
}
