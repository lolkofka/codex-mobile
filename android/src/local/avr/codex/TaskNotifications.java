package local.avr.codex.copy;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

final class TaskNotifications {
    final AgentService service;
    final NotificationManager manager;
    TaskNotifications(AgentService service){
        this.service=service;
        manager=service.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("results","Результаты задач",NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel("agent","Локальный Codex",NotificationManager.IMPORTANCE_LOW));
    }
    private PendingIntent open(String chat){
        Intent intent=new Intent(service,MainActivity.class)
            .setData(Uri.parse("codex-mobile:chat/"+Uri.encode(chat)))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("notificationThread",chat);
        return PendingIntent.getActivity(service,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    Notification ongoing(){
        AgentService.ChatSession chat=service.notificationChat();
        long count=service.busyCount();
        boolean busy=count>0;
        Intent stopIntent=new Intent(service,AgentService.class).setAction(busy?"INTERRUPT":"STOP").putExtra("thread",chat.listId());
        PendingIntent stop=PendingIntent.getService(service,2,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String status=!service.approvals.isEmpty()?"Нужно твоё подтверждение":count>1?"Активных задач: "+count:busy?"Выполняю задачу…":service.ready?"Готов к работе":"Подключаюсь…";
        Notification.Builder notification=new Notification.Builder(service,"agent").setSmallIcon(R.drawable.ic_notification).setLargeIcon(NotificationArtwork.logo(service))
            .setContentTitle("Codex Mobile").setContentText(status).setContentIntent(open(chat.listId()))
            .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,busy?"Остановить задачу":"Остановить помощника",stop).build());
        if(busy)notification.setProgress(0,0,true);
        return notification.build();
    }
    void update(){manager.notify(1,ongoing());}
    void result(String chat,String chatTitle,String turn,String status,String body){
        if(Build.VERSION.SDK_INT>=33&&service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String title=status.equals("completed")?"Задача выполнена":status.equals("interrupted")?"Задача остановлена":"Не удалось завершить задачу";
        if(body==null||body.trim().isEmpty())body=chatTitle;
        if(body.length()>1000)body=body.substring(0,1000)+"…";
        manager.notify("turn-"+turn,2,new Notification.Builder(service,"results").setSmallIcon(R.drawable.ic_notification).setLargeIcon(NotificationArtwork.logo(service))
            .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(open(chat)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setGroup("codex-results")
            .setGroupAlertBehavior(service.visibleClients>0?Notification.GROUP_ALERT_SUMMARY:Notification.GROUP_ALERT_ALL).build());
    }
}
