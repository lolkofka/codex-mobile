import android.content.Context;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import local.avr.codex.copy.BackgroundDisplayPolicy;

/** Root-only local socket, fixed command set, no shell evaluation. */
public final class PhoneTools {
  static final File ROOT = new File("/data/adb/avr-codex-copy/tools");
  static final String SOCKET = "avr.codex.copy.background.v1";
  static final int WIDTH=720, HEIGHT=1280;
  static final long IDLE_MS=300000;
  // Android system flags: own content, touch, cleanup, trusted, separate power/keyguard group,
  // own focus, no sound/haptics, and never steal the owner's top focus.
  static final int DISPLAY_FLAGS=1|8|(1<<6)|(1<<8)|(1<<10)|(1<<11)|(1<<12)|(1<<13)|(1<<14)|(1<<16);
  static VirtualDisplay display;
  static PowerManager.WakeLock displayWakeLock;
  static HandlerThread frames;
  static int displayGroup;
  static ImageReader reader;
  static Image latestImage;
  static volatile long lastUse,lastFrameAt,frameSequence;
  static void cleanup(){
    synchronized(PhoneTools.class){
      if(displayWakeLock!=null&&displayWakeLock.isHeld())displayWakeLock.release();
      if(display!=null){display.release();display=null;}
      if(latestImage!=null){latestImage.close();latestImage=null;}
      if(reader!=null){reader.close();reader=null;}
      if(frames!=null)frames.quitSafely();
    }
  }
  static void active(){
    lastUse=SystemClock.elapsedRealtime();
    // A bounded, display-scoped lock; never use the global two-argument newWakeLock API.
    displayWakeLock.acquire(IDLE_MS+2000);
  }
  static void independentPower(Context ctx)throws Exception{
    android.view.Display screen=display.getDisplay();int id=screen.getDisplayId();
    Class<?> infoClass=Class.forName("android.view.DisplayInfo");Object info=infoClass.getConstructor().newInstance();
    if(!((Boolean)android.view.Display.class.getMethod("getDisplayInfo",infoClass).invoke(screen,info)))throw new IOException("Background display information unavailable");
    displayGroup=infoClass.getField("displayGroupId").getInt(info);
    int unlocked=android.view.Display.class.getField("FLAG_ALWAYS_UNLOCKED").getInt(null);
    if(id<=0||displayGroup<=0||(screen.getFlags()&unlocked)==0)throw new IOException("Independent background display unavailable; primary screen untouched");
    PowerManager power=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
    displayWakeLock=(PowerManager.WakeLock)PowerManager.class.getMethod("newWakeLock",int.class,String.class,int.class)
      .invoke(power,PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"Codex:BackgroundDisplay",id);
    displayWakeLock.setReferenceCounted(false);active();
  }
  static JSONObject json(Object... p) throws Exception {JSONObject j=new JSONObject();for(int i=0;i<p.length;i+=2)j.put((String)p[i],p[i+1]);return j;}
  static String exec(String... argv) throws Exception {
    Process p=new ProcessBuilder(argv).redirectErrorStream(true).start();
    ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
    while((n=p.getInputStream().read(buf))!=-1)if(b.size()<200000)b.write(buf,0,n);
    if(!p.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Command timed out");}
    String s=b.toString("UTF-8");if(p.exitValue()!=0)throw new IOException(s);return s;
  }
  static void enabled() throws Exception {
    File heartbeat=new File("/data/user/0/local.avr.codex.copy/files/phone-access/heartbeat.json");
    if(!new File(ROOT,"permissions/background").isFile()||!heartbeat.isFile()||System.currentTimeMillis()-heartbeat.lastModified()>6000||!new JSONObject(new String(Files.readAllBytes(heartbeat.toPath()),"UTF-8")).optBoolean("backgroundAllowed"))throw new SecurityException("Фоновый доступ выключен или Codex закрыт");
  }
  static JSONObject request(String[] argv) throws Exception {
    LocalSocket socket=new LocalSocket();socket.connect(new LocalSocketAddress(SOCKET));socket.setSoTimeout(25000);
    JSONArray args=new JSONArray();for(int i=1;i<argv.length;i++)args.put(argv[i]);
    socket.getOutputStream().write((json("command",argv[0],"args",args).toString()+"\n").getBytes("UTF-8"));
    String line=new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();socket.close();
    if(line==null)throw new IOException("Background service closed");return new JSONObject(line);
  }
  static JSONObject handle(JSONObject req) throws Exception {
    String cmd=req.getString("command");JSONArray a=req.getJSONArray("args");
    if(cmd.equals("stop")){new Handler(Looper.getMainLooper()).postDelayed(()->System.exit(0),150);return json("ok",true,"stopped",true);}
    enabled();int id=display.getDisplay().getDisplayId();
    if(cmd.equals("status"))return json("ok",true,"active",true,"display",id,"displayGroup",displayGroup,"width",WIDTH,"height",HEIGHT,"ownFocus",true,
      "alwaysUnlocked",true,"independentPower",true,"wakeLockHeld",displayWakeLock.isHeld(),"displayState",display.getDisplay().getState(),
      "frameSequence",frameSequence,"lastFrameAgeMs",lastFrameAt==0?-1:SystemClock.elapsedRealtime()-lastFrameAt,"idleRemainingMs",Math.max(0,IDLE_MS-(SystemClock.elapsedRealtime()-lastUse)));
    active();
    if(cmd.equals("screenshot")){
      File out=new File(ROOT,"background/screen.png");out.getParentFile().mkdirs();
      synchronized(PhoneTools.class){
        if(latestImage==null)throw new IOException("No frame yet; launch an application first");
        Image.Plane plane=latestImage.getPlanes()[0];int pixel=plane.getPixelStride(),stride=plane.getRowStride();
        ByteBuffer data=plane.getBuffer();data.rewind();Bitmap large=Bitmap.createBitmap(stride/pixel,HEIGHT,Bitmap.Config.ARGB_8888);large.copyPixelsFromBuffer(data);
        Bitmap crop=Bitmap.createBitmap(large,0,0,WIDTH,HEIGHT);try(OutputStream stream=new FileOutputStream(out)){crop.compress(Bitmap.CompressFormat.PNG,100,stream);}
        if(crop!=large)crop.recycle();large.recycle();
      }
      return json("ok",true,"path",out.getPath(),"display",id);
    }
    if(cmd.equals("launch")){
      String pkg=a.getString(0);if(!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("Invalid package");
      if(BackgroundDisplayPolicy.ownerHasPackage(exec("/system/bin/dumpsys","window","displays"),pkg))throw new IOException("Приложение сейчас открыто у владельца. Не переношу его с основного экрана.");
      String component=exec("/system/bin/cmd","package","resolve-activity","--brief","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER",pkg).trim();
      String[] lines=component.split("\n");component=lines[lines.length-1].trim();
      if(!component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))throw new IOException("Launcher activity not found");
      String output=exec("/system/bin/am","start","--display",String.valueOf(id),"-f","0x18000000","-n",component);
      if(output.contains("Error:")||output.contains("Exception"))throw new IOException(output);
      return json("ok",true,"display",id,"package",pkg,"result",output);
    }
    ArrayList<String> args=new ArrayList<>(Arrays.asList("/system/bin/input","-d",String.valueOf(id)));
    if(cmd.equals("tap")){args.add("tap");for(int i=0;i<2;i++)args.add(String.valueOf(Integer.parseInt(a.getString(i))));}
    else if(cmd.equals("swipe")){args.add("swipe");for(int i=0;i<4;i++)args.add(String.valueOf(Integer.parseInt(a.getString(i))));args.add("300");}
    else if(cmd.equals("keyevent")){String key=a.getString(0);if(!key.matches("KEYCODE_(BACK|ENTER|TAB|ESCAPE|DPAD_(UP|DOWN|LEFT|RIGHT|CENTER)|DEL|FORWARD_DEL|MOVE_HOME|MOVE_END|PAGE_UP|PAGE_DOWN)"))throw new IllegalArgumentException("Only navigation keys for the background display are allowed; global hardware keys are blocked");args.add("keyevent");args.add(key);}
    else if(cmd.equals("text")){String value=a.getString(0);if(value.length()>2000)throw new IllegalArgumentException("Text too long");args.add("text");args.add(value);}
    else throw new IllegalArgumentException("Use: status | launch PACKAGE | screenshot | tap X Y | swipe X1 Y1 X2 Y2 | keyevent KEY | text TEXT | stop");
    return json("ok",true,"display",id,"result",exec(args.toArray(new String[0])));
  }
  public static void main(String[] args) throws Exception {
    if(android.os.Process.myUid()!=0)throw new SecurityException("Root required");
    if(args.length==0)throw new IllegalArgumentException("Command required");
    if(!args[0].equals("server")){JSONObject r=request(args);System.out.println(r);if(!r.optBoolean("ok"))System.exit(1);return;}
    enabled();Looper.prepareMainLooper();
    Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
    Context ctx=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
    Runtime.getRuntime().addShutdownHook(new Thread(PhoneTools::cleanup));
    frames=new HandlerThread("background-frames");frames.start();
    try{
    reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,3);
    reader.setOnImageAvailableListener(r->{try{
      Image image=r.acquireLatestImage();if(image==null)return;
      synchronized(PhoneTools.class){if(latestImage!=null)latestImage.close();latestImage=image;lastFrameAt=SystemClock.elapsedRealtime();frameSequence++;}
    }catch(Exception ignored){}},new Handler(frames.getLooper()));
    // Own focus + no top-focus stealing; destroy content rather than move it to the owner's screen.
    display=((DisplayManager)ctx.getSystemService(Context.DISPLAY_SERVICE)).createVirtualDisplay("Codex Mobile Background",WIDTH,HEIGHT,240,reader.getSurface(),DISPLAY_FLAGS);
    if(display==null)throw new IOException("Virtual display unavailable");
    independentPower(ctx);
    LocalServerSocket server=new LocalServerSocket(SOCKET);
    new Thread(()->{while(true){try(LocalSocket client=server.accept()){
      if(client.getPeerCredentials().getUid()!=0)continue;client.setSoTimeout(10000);
      String line=new BufferedReader(new InputStreamReader(client.getInputStream())).readLine();JSONObject result;
      try{if(line==null||line.length()>10000)throw new IOException("Invalid request");result=handle(new JSONObject(line));}
      catch(Exception e){result=json("ok",false,"error",e.getMessage());}
      client.getOutputStream().write((result+"\n").getBytes("UTF-8"));
    }catch(Exception ignored){}}},"root-local-control").start();
    Handler timer=new Handler(Looper.getMainLooper());timer.postDelayed(new Runnable(){public void run(){
      try{enabled();}catch(Exception e){System.exit(0);}
      if(SystemClock.elapsedRealtime()-lastUse>IDLE_MS)System.exit(0);
      timer.postDelayed(this,1000);
    }},1000);
    System.out.println("DISPLAY="+display.getDisplay().getDisplayId());Looper.loop();
    }catch(Exception e){cleanup();throw e;}
  }
}
