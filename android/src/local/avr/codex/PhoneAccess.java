package local.avr.codex.copy;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Visible, revocable access to the owner's optional phone tools. */
final class PhoneAccess {
    static final String CHANNEL="phone-access";
    final AgentService service;
    final Handler main=new Handler(Looper.getMainLooper());
    final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    final File directory;
    boolean background,traffic,changing,closed;
    JSONObject status=new JSONObject();
    String error="",lastStatus="";
    PhoneAccess(AgentService service){
        this.service=service;directory=new File(service.getFilesDir(),"phone-access");directory.mkdirs();
        SharedPreferences p=service.getSharedPreferences("phone-access",0);
        // The owner explicitly requested both capabilities for this personal build.
        background=p.getBoolean("background",true);traffic=p.getBoolean("traffic",true);
        service.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Доступ к приложениям и HTTPS",NotificationManager.IMPORTANCE_LOW));
        configure(null,false);main.post(poll);
    }
    JSONObject packet(){return AgentService.j("background",background,"traffic",traffic,"changing",changing,"status",status,"error",error);}
    void change(String key,boolean enabled){if(changing||(!key.equals("background")&&!key.equals("traffic")))return;configure(key,enabled);}
    private void configure(String key,boolean value){
        final boolean b="background".equals(key)?value:background,t="traffic".equals(key)?value:traffic;
        changing=true;service.changed();
        io.execute(()->{
            String problem="";
            try{root("configure "+(b?"1":"0")+" "+(t?"1":"0"));}catch(Exception e){problem="Не удалось изменить доступ: "+e.getMessage();}
            final String result=problem;
            main.post(()->{changing=false;error=result;if(result.isEmpty()||(key!=null&&!value)){background=b;traffic=t;service.getSharedPreferences("phone-access",0).edit().putBoolean("background",b).putBoolean("traffic",t).apply();}service.changed();});
        });
    }
    void stop(){io.execute(()->{try{root("stop");}catch(Exception ignored){}});}
    private void root(String command)throws Exception{
        java.lang.Process p=new ProcessBuilder("su","-c",AgentService.BASE+"/tools/bin/codex-phone-access "+command).redirectErrorStream(true).start();
        byte[] out=MediaFiles.read(p.getInputStream(),32000);
        if(!p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){p.destroy();throw new IOException("Тайм-аут root");}
        if(p.exitValue()!=0)throw new IOException(new String(out,StandardCharsets.UTF_8));
    }
    final Runnable poll=new Runnable(){public void run(){
        if(closed)return;
        try{
            NotificationManager nm=service.getSystemService(NotificationManager.class);
            NotificationChannel channel=nm.getNotificationChannel(CHANNEL);
            boolean visible=nm.areNotificationsEnabled()&&channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
            JSONObject heartbeat=AgentService.j("pid",android.os.Process.myPid(),"updated",System.currentTimeMillis(),"notificationsVisible",visible,"backgroundAllowed",background,"trafficAllowed",traffic,"busy",service.busyCount(),"notifiedSession",visible&&status.optBoolean("active")?status.optString("id"):"");
            File temp=new File(directory,"heartbeat.tmp");Files.write(temp.toPath(),heartbeat.toString().getBytes(StandardCharsets.UTF_8));temp.renameTo(new File(directory,"heartbeat.json"));
            File file=new File(directory,"status.json");
            JSONObject next=file.isFile()?new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):new JSONObject();
            if(!next.toString().equals(lastStatus)){status=next;lastStatus=next.toString();service.changed();}
            boolean active=status.optBoolean("active");
            if(active){
                boolean https="https".equals(status.optString("mode"));
                String title=https?"Используется временный HTTPS-сертификат":"Codex анализирует трафик";
                String text=status.optString("package")+" · до "+new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(new java.util.Date(status.optLong("expires")));
                Intent stop=new Intent(service,AgentService.class).setAction("STOP_PHONE_ACCESS");
                PendingIntent action=PendingIntent.getService(service,701,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification n=new Notification.Builder(service,CHANNEL).setSmallIcon(android.R.drawable.ic_secure).setLargeIcon(NotificationArtwork.logo(service)).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setColor(0xffefaa64).addAction(new Notification.Action.Builder(null,"Остановить и убрать сертификат",action).build()).build();
                nm.notify(701,n);
                // No visible notification means no interception is allowed to continue.
                if(!visible)stop();
            }else nm.cancel(701);
        }catch(Exception ignored){}
        main.postDelayed(this,1000);
    }};
    void close(){closed=true;main.removeCallbacks(poll);new File(directory,"heartbeat.json").delete();stop();io.shutdown();}
    static String instructions(){return " Дополнительные возможности телефона доступны только через /data/adb/avr-codex-copy/tools/bin/codex-apps и codex-traffic. Перед использованием читай их help/status: владелец управляет доступом тумблерами. Для запуска без помех используй codex-apps launch PACKAGE, screenshot, tap/swipe/keyevent/text: это отдельный виртуальный экран, не основной. Не переноси открытое у владельца приложение. Для анализа HTTP/HTTPS используй codex-traffic https PACKAGE SECONDS, только когда это необходимо задаче. Он ограничивает захват приложением, показывает владельцу уведомление, временно добавляет CA лишь в его пространство монтирования и автоматически убирает CA и правила. Не устанавливай постоянные корневые сертификаты, не отключай проверку TLS, не обходи certificate pinning. Не собирай посторонние приложения и не выводи токены/пароли из захвата. После работы выполняй codex-phone-access stop. Чтение трафика не гарантирует ускорения.";}
}
