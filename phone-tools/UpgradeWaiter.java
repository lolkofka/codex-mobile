import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Wait for saved turn completion before replacing the client which carries this conversation. */
public final class UpgradeWaiter {
  static final class Cursor {
    final File file;long offset;String started="",finished="";boolean targetComplete;
    Cursor(File f){file=f;}
    void update(String target)throws Exception {
      try(RandomAccessFile r=new RandomAccessFile(file,"r")){
        if(r.length()<offset){offset=0;started="";finished="";targetComplete=false;}
        r.seek(offset);long position=offset;byte[] block=new byte[65536];ByteArrayOutputStream line=new ByteArrayOutputStream();int count;
        while((count=r.read(block))!=-1){
          int begin=0;
          for(int i=0;i<count;i++)if(block[i]=='\n'){
            line.write(block,begin,i-begin);offset=position+i+1;consume(line.toString("UTF-8"),target);line.reset();begin=i+1;
          }
          line.write(block,begin,count-begin);position+=count;
        }
      }
    }
    void consume(String line,String target){
      if(!line.contains("task_started")&&!line.contains("task_complete")&&!line.contains("turn_aborted"))return;
      try{JSONObject event=new JSONObject(line);if(!event.optString("type").equals("event_msg"))return;
        JSONObject p=event.optJSONObject("payload");if(p==null)return;
        String type=p.optString("type"),id=p.optString("turn_id");
        if(type.equals("task_started"))started=id;
        if(type.equals("task_complete")||type.equals("turn_aborted")){if(id.isEmpty())id=started;finished=id;if(type.equals("task_complete")&&id.equals(target))targetComplete=true;}
      }catch(Exception ignored){}
    }
    boolean active(){return !started.isEmpty()&&!started.equals(finished)&&System.currentTimeMillis()-file.lastModified()<1800000;}
  }
  static void discover(File dir,Map<String,Cursor> files){
    File[] children=dir.listFiles();if(children==null)return;
    for(File f:children)if(f.isDirectory())discover(f,files);else if(f.getName().endsWith(".jsonl"))files.putIfAbsent(f.getAbsolutePath(),new Cursor(f));
  }
  public static void main(String[] args)throws Exception{
    File file=new File(args[0]).getCanonicalFile();String turn=args[1];
    if(!turn.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid turn");
    boolean test=args.length>2&&args[2].equals("selftest");
    if(!test&&!file.getPath().startsWith("/data/adb/avr-codex-copy/home/sessions/"))throw new IllegalArgumentException("Unexpected session path");
    Map<String,Cursor> files=new LinkedHashMap<>();Cursor selected=new Cursor(file);files.put(file.getPath(),selected);
    long deadline=android.os.SystemClock.uptimeMillis()+(test?15000:7200000),idleSince=0;int last=-1;
    while(android.os.SystemClock.uptimeMillis()<deadline){
      if(!test)discover(new File("/data/adb/avr-codex-copy/home/sessions"),files);
      int active=0;
      for(Cursor cursor:files.values()){if(!cursor.file.isFile())continue;try{cursor.update(turn);}catch(FileNotFoundException removed){continue;}if(cursor.active())active++;}
      if(selected.targetComplete&&active==0){if(idleSince==0)idleSince=android.os.SystemClock.uptimeMillis();if(android.os.SystemClock.uptimeMillis()-idleSince>2500){System.out.println("SAVED_TURN_COMPLETE_AND_IDLE");return;}}else idleSince=0;
      if(active!=last){System.out.println("WAITING_ACTIVE_TURNS="+active);last=active;}
      Thread.sleep(500);
    }
    throw new IOException("Timed out waiting for active work to finish; APK was not installed");
  }
}
