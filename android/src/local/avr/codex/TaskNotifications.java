package local.avr.codex;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class TaskNotifications {
    final AgentService service;
    final NotificationManager manager;
    TaskNotifications(AgentService s){service=s;manager=s.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("results","Результаты задач",NotificationManager.IMPORTANCE_DEFAULT));manager.createNotificationChannel(new NotificationChannel("agent","Локальный Codex",NotificationManager.IMPORTANCE_LOW));}
    PendingIntent open(){Intent i=new Intent(service,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("notificationThread",service.threadId);return PendingIntent.getActivity(service,service.threadId.hashCode(),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    Notification ongoing(){boolean busy=service.busy;PendingIntent stop=PendingIntent.getService(service,2,new Intent(service,AgentService.class).setAction(busy?"INTERRUPT":"STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=new Notification.Builder(service,"agent").setSmallIcon(R.drawable.icon).setContentTitle("Codex Mobile").setContentText(!service.approvals.isEmpty()?"Нужно твоё подтверждение":busy?"Выполняю задачу…":service.ready?"Готов к работе":"Подключаюсь…").setContentIntent(open()).setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,busy?"Остановить задачу":"Остановить помощника",stop).build());if(busy)b.setProgress(0,0,true);return b.build();}
    void update(){manager.notify(1,ongoing());}
    void result(String turn,String status,String body){if(Build.VERSION.SDK_INT>=33&&service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;String title=status.equals("completed")?"Задача выполнена":status.equals("interrupted")?"Задача остановлена":"Не удалось завершить задачу";if(body==null||body.trim().isEmpty())body=service.currentTitle;if(body.length()>1000)body=body.substring(0,1000)+"…";manager.notify("turn-"+turn,2,new Notification.Builder(service,"results").setSmallIcon(R.drawable.icon).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open()).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).setGroup("codex-results").setGroupAlertBehavior(service.visibleClients>0?Notification.GROUP_ALERT_SUMMARY:Notification.GROUP_ALERT_ALL).build());}
}
