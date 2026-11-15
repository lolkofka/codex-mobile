package local.avr.codex;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Temporary, separately signed instrumentation. Never shipped in the user APK. */
public final class MobileFeatureTests extends Instrumentation {
    final ArrayList<String> passed=new ArrayList<>();
    StringWriter wire;
    AgentService fake;
    int offset;
    boolean real,media,notifyOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);real=args!=null&&"true".equals(args.getString("real"));media=args!=null&&"true".equals(args.getString("media"));notifyOnly=args!=null&&"true".equals(args.getString("notifyOnly"));start();}
    void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    void main(Runnable action){runOnMainSync(action);}
    void pass(String name){passed.add(name);Bundle b=new Bundle();b.putString("check",name);sendStatus(0,b);}
    JSONObject model(String name,boolean def){return AgentService.j("model",name,"id",name,"displayName",name,"isDefault",def,"defaultReasoningEffort","low","supportedReasoningEfforts",new JSONArray().put(AgentService.j("reasoningEffort","low")).put(AgentService.j("reasoningEffort","high")));}
    void fresh(){
        main(()->{
            fake=new AgentService();
            Context isolated=new ContextWrapper(getTargetContext()){
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return getContext().getSharedPreferences("isolated-"+name,mode);}
            };
            try{
                Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(fake,isolated);
                Field writer=AgentService.class.getDeclaredField("writer");writer.setAccessible(true);wire=new StringWriter();writer.set(fake,new BufferedWriter(wire));
            }catch(Exception e){throw new RuntimeException(e);}
            fake.ready=true;fake.loggedIn=true;fake.models=new JSONArray().put(model("one",true)).put(model("two",false));offset=0;
        });
    }
    List<JSONObject> requests(){ArrayList<JSONObject> a=new ArrayList<>();for(String s:wire.toString().split("\n"))if(!s.isEmpty())try{a.add(new JSONObject(s));}catch(Exception e){throw new RuntimeException(e);}return a;}
    JSONObject next(String method){List<JSONObject> a=requests();for(;offset<a.size();offset++){JSONObject m=a.get(offset);if(m.optString("method").equals(method)){offset++;return m;}}throw new AssertionError("Missing request "+method+"; "+wire);}
    int count(String method){int n=0;for(JSONObject r:requests())if(r.optString("method").equals(method))n++;return n;}
    void reply(JSONObject request,JSONObject result){main(()->fake.handle(AgentService.j("id",request.opt("id"),"result",result)));}
    void error(JSONObject request){main(()->fake.handle(AgentService.j("id",request.opt("id"),"error",AgentService.j("message","test rejection"))));}
    void event(String method,String id,String status){main(()->fake.handle(AgentService.j("method",method,"params",AgentService.j("threadId","thread-test","turn",AgentService.j("id",id,"status",status)))));waitForIdleSync();}
    JSONObject beginFake(){main(()->fake.submitText("first","queue"));JSONObject thread=next("thread/start");reply(thread,AgentService.j("thread",AgentService.j("id","thread-test")));return next("turn/start");}
    void mockTests(){
        fresh();JSONObject first=beginFake();
        main(()->{fake.selectModel("two");fake.selectEffort("high");check(fake.submitText("queued","queue"),"queue accepted while start pending");check(fake.submitText("steered","steer"),"steer accepted while start pending");fake.selectModel("one");});
        check(count("turn/start")==1&&count("turn/steer")==0,"no premature delivery without turn id");
        event("turn/started","turn-1","inProgress");
        check(count("turn/steer")==0,"start must be acknowledged before another delivery");
        reply(first,AgentService.j("turn",AgentService.j("id","turn-1","status","inProgress")));
        JSONObject steer=next("turn/steer"),p=steer.optJSONObject("params");
        check(p.optString("expectedTurnId").equals("turn-1")&&!p.has("model")&&!p.has("effort"),"steer pins active turn without overrides");
        reply(steer,AgentService.j("turnId","turn-1"));
        check(count("turn/start")==1,"queued message waits for completed event");
        event("turn/completed","turn-1","completed");
        JSONObject queued=next("turn/start");p=queued.optJSONObject("params");
        check(p.optString("model").equals("two")&&p.optString("effort").equals("high"),"queue preserves chosen model and effort");
        check(p.optJSONArray("input").optJSONObject(0).optString("text").equals("queued"),"queue is delivered after steering");
        pass("pending-start steering, expectedTurnId, FIFO queue and model snapshot");

        fresh();first=beginFake();reply(first,AgentService.j("turn",AgentService.j("id","turn-2","status","inProgress")));event("turn/started","turn-2","inProgress");
        main(()->{fake.submitText("later","queue");fake.interrupt();});
        next("turn/interrupt");event("turn/completed","turn-2","interrupted");
        check(count("turn/start")==1&&fake.outbox.paused&&fake.outbox.entries.size()==1,"stop pauses remaining messages");
        main(()->fake.pauseQueue(false));next("turn/start");pass("stop pauses queue; explicit resume restarts delivery");

        fresh();first=beginFake();reply(first,AgentService.j("turn",AgentService.j("id","turn-3","status","inProgress")));event("turn/started","turn-3","inProgress");
        main(()->fake.submitText("race","steer"));steer=next("turn/steer");
        event("turn/completed","turn-3","completed");
        check(count("turn/start")==1,"completion waits for pending steer response");error(steer);
        check(fake.outbox.entries.size()==1&&fake.outbox.entries.get(0).status.equals("failed")&&fake.outbox.paused,"rejected steer remains visible, not silently resent");
        main(()->fake.removeQueued(fake.outbox.entries.get(0).id));check(fake.outbox.entries.isEmpty(),"failed entry removable");pass("completion/steer race and rejection retain user text");

        fresh();first=beginFake();error(first);
        check(!fake.busy&&fake.outbox.entries.size()==1&&fake.outbox.paused,"failed start keeps message");
        main(()->{fake.outbox.entries.get(0).status="sending";fake.outbox.recover();});
        check(fake.outbox.entries.get(0).status.equals("failed")&&fake.outbox.paused,"uncertain delivery never auto repeats after reconnect");pass("failed start and interrupted transport preserve outbox");

        fresh();main(()->fake.refreshModels());JSONObject page=next("model/list");reply(page,AgentService.j("data",new JSONArray().put(model("one",true)),"nextCursor","page2"));page=next("model/list");check(page.optJSONObject("params").optString("cursor").equals("page2"),"pagination cursor");reply(page,AgentService.j("data",new JSONArray().put(model("two",false)),"nextCursor",JSONObject.NULL));
        check(fake.models.length()==2&&!fake.modelsLoading,"all model pages loaded");main(()->{fake.selectModel("two");fake.selectEffort("invalid");});check(fake.model.equals("two")&&fake.effort.isEmpty(),"unsupported effort not sent");pass("model pagination and reasoning compatibility");

        fresh();first=beginFake();error(first);
        check(fake.getSharedPreferences("outbox",0).getString("phone","").contains("first"),"outbox was persisted");
        fresh();main(()->{try{Method restore=AgentService.class.getDeclaredMethod("restoreOutbox");restore.setAccessible(true);restore.invoke(fake);}catch(Exception e){throw new RuntimeException(e);}});
        check(fake.outbox.paused&&fake.outbox.entries.size()==1&&fake.outbox.entries.get(0).text.equals("first"),"restored delivery must wait for user resume");pass("service recreation restores pending text on pause");

        fresh();main(()->{try{Field f=AgentService.class.getDeclaredField("writer");f.setAccessible(true);f.set(fake,new BufferedWriter(new Writer(){public void write(char[] c,int o,int n)throws IOException{throw new IOException("disconnected");}public void flush(){}public void close(){}}));fake.submitText("keep this","queue");}catch(Exception e){throw new RuntimeException(e);}});
        check(!fake.busy&&fake.outbox.entries.size()==1&&fake.outbox.entries.get(0).status.equals("failed"),"write failure must keep the message and release busy");pass("transport write failure retains unsent message");
    }
    void mediaTests()throws Exception{
        fresh();
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(80,40,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff336699);
        File dir=new File(fake.getCacheDir(),"shared/"+UUID.randomUUID());dir.mkdirs();File fixture=new File(dir,"fixture.png");
        try(OutputStream out=new FileOutputStream(fixture)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        android.net.Uri uri=MediaFiles.shareUri(fake,fixture);
        JSONObject imported=MediaFiles.importUri(fake,uri);String id=imported.getString("id");
        File image=MediaFiles.image(fake,id);check(image.length()>0,"image import saved normalized content");
        TurnOutbox.Entry entry=new TurnOutbox.Entry("test","","thread","queue","one","low");entry.images=new JSONArray().put(id).toString();
        JSONArray local=MediaFiles.input(fake,entry,false),remote=MediaFiles.input(fake,entry,true);
        check(local.length()==1&&local.getJSONObject(0).getString("type").equals("localImage"),"phone gets a local image input");
        check(remote.getJSONObject(0).getString("url").startsWith("data:image/"),"remote engine gets inline image bytes");
        main(()->{fake.busy=true;fake.threadId="thread";check(fake.submitText("","queue",new JSONArray().put(id)),"image-only submission accepted");});
        check(fake.outbox.entries.get(0).images.contains(id),"outbox keeps image identity");
        main(()->{fake.outbox.entries.clear();try{Method restore=AgentService.class.getDeclaredMethod("restoreOutbox");restore.setAccessible(true);restore.invoke(fake);}catch(Exception e){throw new RuntimeException(e);}});
        check(fake.outbox.entries.get(0).images.contains(id)&&fake.outbox.paused,"images survive service recreation");
        boolean blocked=false;try{fake.getContentResolver().openFileDescriptor(android.net.Uri.parse("content://local.avr.codex.files/00000000-0000-0000-0000-000000000001/%2E%2E%2F%2E%2E%2Ffiles%2Fsecret"),"r");}catch(FileNotFoundException e){blocked=true;}check(blocked,"provider denies traversal");
        check(MediaFiles.localPath("sandbox:/sdcard/My Folder/report.pdf:12").equals("/sdcard/My Folder/report.pdf"),"sandbox link preserves spaces and strips line number");
        check(MediaFiles.quote("a'b").equals("'a'\\''b'"),"root path is shell quoted");
        final byte[] fixtureBytes=java.nio.file.Files.readAllBytes(fixture.toPath());image.delete();fixture.delete();dir.delete();pass("native image import, local/remote input, persistence, provider traversal and paths");
        fresh();final String data=android.util.Base64.encodeToString(fixtureBytes,android.util.Base64.NO_WRAP);
        main(()->{fake.threadId="media-test";fake.showItem(AgentService.j("id","generated-test","type","imageGeneration","status","completed","result",data),false,true);});
        until(()->fake.messages.get("generated-test").images.length()==1,10000,"generated image was not decoded");
        File generated=MediaFiles.image(fake,fake.messages.get("generated-test").images.getJSONObject(0).getString("id"));check(generated.length()>0,"generation result file saved");generated.delete();pass("native generated image event is decoded and rendered");
        TaskNotifications notification=new TaskNotifications(fake);android.app.Notification ongoing=notification.ongoing();check(ongoing.getChannelId().equals("agent")&&(ongoing.flags&android.app.Notification.FLAG_ONGOING_EVENT)!=0,"native foreground channel and ongoing flag");check(notification.manager.getNotificationChannel("results")!=null,"completion channel exists");pass("Android task notification channels and foreground notification");
    }
    void notificationTest()throws Exception{
        fresh();fake.visibleClients=1;TaskNotifications n=new TaskNotifications(fake);
        try{n.result("media-delivery-check","completed","Проверка уведомления Codex Mobile");
            boolean found=false;for(android.service.notification.StatusBarNotification item:n.manager.getActiveNotifications())if("turn-media-delivery-check".equals(item.getTag())){found=true;check(item.getNotification().getChannelId().equals("results"),"result channel");check(item.getNotification().contentIntent!=null,"notification opens app");check((item.getNotification().flags&android.app.Notification.FLAG_AUTO_CANCEL)!=0,"result notification auto cancels");}
            check(found,"Android did not register the completion notification");pass("completion notification delivered to Android with tap action");
        }finally{n.manager.cancel("turn-media-delivery-check",2);}
    }

    MainActivity activity;
    WebView web;
    AgentService live;
    interface Condition {boolean get();}
    void until(Condition condition,long timeout,String message)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){final boolean[] ok={false};main(()->ok[0]=condition.get());if(ok[0])return;Thread.sleep(80);}
        throw new AssertionError(message);
    }
    String js(String source)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] value={null};
        main(()->web.evaluateJavascript(source,r->{value[0]=r;done.countDown();}));
        check(done.await(8,TimeUnit.SECONDS),"WebView callback timed out");return value[0];
    }
    void untilJs(String source,String message)throws Exception{long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end){if(js(source).equals("true"))return;Thread.sleep(60);}throw new AssertionError(message);}
    void sendUI(String text,String mode)throws Exception{
        js("deliveryMode="+JSONObject.quote(mode)+";$('prompt').value="+JSONObject.quote(text)+";$('prompt').dispatchEvent(new Event('input'));$('send').click();true");
        until(()->!activity.input.getText().toString().equals(text),8000,"UI did not acknowledge submission");
    }
    void realTests()throws Exception{
        activity=(MainActivity)startActivitySync(new Intent().setClassName("local.avr.codex","local.avr.codex.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->activity.agent!=null&&activity.agent.ready&&activity.agent.loggedIn&&activity.agent.models.length()>0,25000,"Live engine/account/catalog not ready");
        Thread.sleep(1200);waitForIdleSync();
        main(()->{live=activity.agent;try{Field f=WebChatActivity.class.getDeclaredField("web");f.setAccessible(true);web=(WebView)f.get(activity);}catch(Exception e){throw new RuntimeException(e);}});
        // A previous failed test may have left only its synthetic queued marker.
        main(()->{for(TurnOutbox.Entry e:new ArrayList<>(live.outbox.entries))if(e.text.startsWith("Сообщение из очереди. Не вызывай инструменты"))live.removeQueued(e.id);});
        check(!live.busy&&live.outbox.entries.isEmpty(),"User task/outbox active; leaving it untouched");
        final String oldThread=live.threadId,oldModel=live.model,oldEffort=live.effort,oldDraft=activity.input.getText().toString();
        final int oldOrientation=activity.getRequestedOrientation();
        String[] diagnostic={""};
        try{
            // Exercise the bundled UI and native bridge, not only service methods.
            until(()->web.getProgress()==100,8000,"UI not ready");
            check(js("!!document.getElementById('composer-model')").equals("true"),"Explicit model control missing");
            js("$('composer-model').click();true");
            check(js("document.querySelectorAll('#sheet-content .option').length>2").equals("true"),"Picker model rows missing");
            String name=live.models.optJSONObject(0).optString("displayName"),slug=live.models.optJSONObject(0).optString("model");
            js("Array.from(document.querySelectorAll('#sheet-content .option')).find(b=>b.querySelector('strong')?.textContent==="+JSONObject.quote(name)+").click();true");
            until(()->live.model.equals(slug),5000,"Model selection did not reach service");
            main(()->{live.selectEffort("high");live.newThread();});
            int initial=live.completedTurns;
            sendUI("Техническая проверка мобильного интерфейса. Ничего не меняй, не вызывай инструменты. Обдумай различия между очередью сообщений и уточнением уже выполняемой задачи. Дай краткий ответ по-русски.","queue");
            until(()->live.busy&&!live.turnId.isEmpty(),20000,"First turn did not start");diagnostic[0]=live.threadId;
            sendUI("Сообщение из очереди. Не вызывай инструменты, ничего не меняй. Ответь только QUEUE_OK.","queue");
            untilJs("!$('outbox').hidden&&$('queued-messages').children.length>0","Pending message panel missing");
            js("$('delivery-button').click();true");
            check(js("Array.from(document.querySelectorAll('#sheet-content .option')).some(b=>b.textContent.includes('Уточнить сейчас'))").equals("true"),"Steer option missing");
            js("Array.from(document.querySelectorAll('#sheet-content .option')).find(b=>b.querySelector('strong')?.textContent==='Уточнить сейчас').click();true");
            sendUI("Уточнение ТЕКУЩЕЙ задачи: закончи текущий ответ словом STEER_OK. Не используй инструменты, ничего не меняй.","steer");
            until(()->live.messages.values().stream().anyMatch(m->m.kind.equals("user")&&m.text.startsWith("Уточнение ТЕКУЩЕЙ")),20000,"Steer was not accepted by live server");
            pass("live model picker and turn/steer accepted through UI bridge");
            until(()->live.completedTurns>=initial+2&&!live.busy,140000,"Queued response did not finish");
            check(live.outbox.entries.isEmpty(),"Live outbox not drained");
            check(live.lastAnswer.contains("QUEUE_OK"),"Queued response marker missing");
            check(live.messages.values().stream().anyMatch(m->m.kind.equals("assistant")&&m.text.contains("STEER_OK")),"Steering was accepted but not reflected in response");
            pass("live queue starts after current turn; both response markers verified");
            // Read only our synthetic test UI. Check a phone-width layout as well.
            js("document.documentElement.style.width='360px';document.getElementById('app').style.width='360px';true");
            String layout=js("JSON.stringify({model:$('composer-model').getBoundingClientRect().toJSON(),effort:$('effort-button').getBoundingClientRect().toJSON(),send:$('send').getBoundingClientRect().toJSON()})");
            Bundle b=new Bundle();b.putString("layout360",layout);sendStatus(0,b);
            js("document.documentElement.style.width='';document.getElementById('app').style.width='';true");
            pass("phone-width model and reasoning controls rendered");
        }finally{
            if(live.busy&&!diagnostic[0].isEmpty()&&live.threadId.equals(diagnostic[0])){main(()->live.interrupt());until(()->!live.busy,12000,"Synthetic test did not stop");}
            main(()->{for(TurnOutbox.Entry e:new ArrayList<>(live.outbox.entries))live.removeQueued(e.id);});
            main(()->{live.selectModel(oldModel);live.selectEffort(oldEffort);if(!live.busy){if(!diagnostic[0].isEmpty())live.archiveThread(diagnostic[0]);if(!oldThread.isEmpty())live.resume(oldThread);else live.newThread();}activity.input.setText(oldDraft);activity.setRequestedOrientation(oldOrientation);});
            if(!oldThread.isEmpty()&&!live.busy)until(()->live.threadId.equals(oldThread),10000,"Original chat did not reopen");
        }
    }

    @Override public void onStart(){
        Bundle result=new Bundle();
        try{if(notifyOnly)notificationTest();else if(real)realTests();else{mockTests();if(media)mediaTests();}result.putString("passed",String.join("; ",passed));result.putString("status","PASS");finish(Activity.RESULT_OK,result);}
        catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));result.putString("failure",s.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
