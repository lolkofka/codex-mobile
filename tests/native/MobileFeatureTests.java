package local.avr.codex.copy;

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
    ExecutorService wireIo;
    AgentService fake;
    int offset;
    boolean real,media,notifyOnly,authOnly,migrationOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);migrationOnly=args!=null&&"true".equals(args.getString("migrationOnly"));real=args!=null&&"true".equals(args.getString("real"));media=args!=null&&"true".equals(args.getString("media"));notifyOnly=args!=null&&"true".equals(args.getString("notifyOnly"));authOnly=args!=null&&"true".equals(args.getString("authOnly"));start();}
    void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    void main(Runnable action){runOnMainSync(action);}
    void pass(String name){passed.add(name);Bundle b=new Bundle();b.putString("check",name);sendStatus(0,b);}
    JSONObject model(String name,boolean def){return AgentService.j("model",name,"id",name,"displayName",name,"isDefault",def,"defaultReasoningEffort","low","supportedReasoningEfforts",new JSONArray().put(AgentService.j("reasoningEffort","low")).put(AgentService.j("reasoningEffort","high")));}
    void fresh(){
        main(()->{
            if(fake!=null)fake.onDestroy();
            fake=new AgentService();
            Context isolated=new ContextWrapper(getTargetContext()){
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return getContext().getSharedPreferences("isolated-"+name,mode);}
            };
            try{
                Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(fake,isolated);
                Field writer=AgentService.class.getDeclaredField("writer");writer.setAccessible(true);wire=new StringWriter();writer.set(fake,new BufferedWriter(wire));
                Field executor=AgentService.class.getDeclaredField("wireIo");executor.setAccessible(true);wireIo=(ExecutorService)executor.get(fake);
            }catch(Exception e){throw new RuntimeException(e);}
            fake.ready=true;fake.loggedIn=true;fake.models=new JSONArray().put(model("one",true)).put(model("two",false));offset=0;
        });
    }
    List<JSONObject> requests(){ArrayList<JSONObject> a=new ArrayList<>();String snapshot=wire.toString();for(String s:snapshot.substring(0,snapshot.lastIndexOf('\n')+1).split("\n"))if(!s.isEmpty())try{a.add(new JSONObject(s));}catch(Exception e){throw new RuntimeException(e);}return a;}
    JSONObject next(String method){
        long deadline=SystemClock.elapsedRealtime()+5000;
        do{
            List<JSONObject> a=requests();
            for(;offset<a.size();offset++){
                JSONObject request=a.get(offset);
                if(request.optString("method").equals(method)){offset++;return request;}
            }
            SystemClock.sleep(15);
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("Missing request "+method+"; "+wire);
    }
    int count(String method){
        try{wireIo.submit(()->{}).get(2,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("Wire did not flush",e);}
        int n=0;for(JSONObject r:requests())if(r.optString("method").equals(method))n++;return n;}
    void reply(JSONObject request,JSONObject result){main(()->fake.handle(AgentService.j("id",request.opt("id"),"result",result)));}
    void error(JSONObject request){main(()->fake.handle(AgentService.j("id",request.opt("id"),"error",AgentService.j("message","test rejection"))));}
    void event(String method,String id,String status){main(()->fake.handle(AgentService.j("method",method,"params",AgentService.j("threadId","thread-test","turn",AgentService.j("id",id,"status",status)))));waitForIdleSync();}
    JSONObject beginFake(){main(()->fake.submitText("first","queue"));JSONObject thread=next("thread/start");reply(thread,AgentService.j("thread",AgentService.j("id","thread-test")));return next("turn/start");}
    void authMockTests(){
        fresh();main(()->{fake.login(true);fake.login(true);});
        check(count("account/login/start")==1,"double tap cannot start parallel login");
        JSONObject start=next("account/login/start");reply(start,AgentService.j("loginId","old-login","verificationUrl","https://auth.openai.com/codex/device","userCode","TEST-0001"));
        main(()->fake.login(true));check(count("account/login/start")==1&&count("account/login/cancel")==0,"ordinary login reuses pending code");
        main(()->fake.restartLogin(true));JSONObject cancel=next("account/login/cancel");
        check(count("account/login/start")==1,"new login waits for cancellation");reply(cancel,AgentService.j("status","canceled"));
        start=next("account/login/start");reply(start,AgentService.j("loginId","new-login","verificationUrl","https://auth.openai.com/codex/device","userCode","TEST-0002"));
        main(()->fake.handle(AgentService.j("method","account/login/completed","params",AgentService.j("loginId","old-login","success",false,"error","cancelled"))));
        check(fake.loginFlow.id.equals("new-login")&&fake.loginFlow.code.equals("TEST-0002"),"late failure cannot erase new login");
        main(()->fake.handle(AgentService.j("method","account/login/completed","params",AgentService.j("loginId","new-login","success",true))));
        JSONObject account=next("account/read");reply(account,AgentService.j("account",AgentService.j("type","chatgpt","email","synthetic@example.invalid","planType","plus")));
        check(fake.loggedIn&&!fake.loginFlow.pending()&&fake.loginError.isEmpty(),"success rereads managed account and clears login state");
        main(()->fake.login(true));start=next("account/login/start");error(start);
        check(!fake.loginFlow.pending()&&!fake.loginError.isEmpty(),"start failure releases login and surfaces error");
        pass("native login double-tap, explicit restart, cancellation race, completion and failure");
    }
    void mockTests()throws Exception{
        authMockTests();
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
        check(count("turn/start")==1&&fake.chat.outbox.paused&&fake.chat.outbox.entries.size()==1,"stop pauses remaining messages");
        main(()->fake.pauseQueue(false));next("turn/start");pass("stop pauses queue; explicit resume restarts delivery");

        fresh();first=beginFake();reply(first,AgentService.j("turn",AgentService.j("id","turn-3","status","inProgress")));event("turn/started","turn-3","inProgress");
        main(()->fake.submitText("race","steer"));steer=next("turn/steer");
        event("turn/completed","turn-3","completed");
        check(count("turn/start")==1,"completion waits for pending steer response");error(steer);
        check(fake.chat.outbox.entries.size()==1&&fake.chat.outbox.entries.get(0).status.equals("failed")&&fake.chat.outbox.paused,"rejected steer remains visible, not silently resent");
        main(()->fake.removeQueued(fake.chat.outbox.entries.get(0).id));check(fake.chat.outbox.entries.isEmpty(),"failed entry removable");pass("completion/steer race and rejection retain user text");

        fresh();first=beginFake();error(first);
        check(!fake.chat.busy&&fake.chat.outbox.entries.size()==1&&fake.chat.outbox.paused,"failed start keeps message");
        main(()->{fake.chat.outbox.entries.get(0).status="sending";fake.chat.outbox.recover();});
        check(fake.chat.outbox.entries.get(0).status.equals("failed")&&fake.chat.outbox.paused,"uncertain delivery never auto repeats after reconnect");pass("failed start and interrupted transport preserve outbox");

        fresh();main(()->fake.refreshModels());JSONObject page=next("model/list");reply(page,AgentService.j("data",new JSONArray().put(model("one",true)),"nextCursor","page2"));page=next("model/list");check(page.optJSONObject("params").optString("cursor").equals("page2"),"pagination cursor");reply(page,AgentService.j("data",new JSONArray().put(model("two",false)),"nextCursor",JSONObject.NULL));
        check(fake.models.length()==2&&!fake.modelsLoading,"all model pages loaded");main(()->{fake.selectModel("two");fake.selectEffort("invalid");});check(fake.model.equals("two")&&fake.effort.isEmpty(),"unsupported effort not sent");pass("model pagination and reasoning compatibility");

        fresh();first=beginFake();error(first);
        check(fake.getSharedPreferences("outbox",0).getString("phone","").contains("first"),"outbox was persisted");
        fresh();main(()->{try{Method restore=AgentService.class.getDeclaredMethod("restoreOutbox");restore.setAccessible(true);restore.invoke(fake);}catch(Exception e){throw new RuntimeException(e);}});
        check(fake.chat.outbox.paused&&fake.chat.outbox.entries.size()==1&&fake.chat.outbox.entries.get(0).text.equals("first"),"restored delivery must wait for user resume");pass("service recreation restores pending text on pause");

        fresh();main(()->{try{Field f=AgentService.class.getDeclaredField("writer");f.setAccessible(true);f.set(fake,new BufferedWriter(new Writer(){public void write(char[] c,int o,int n)throws IOException{throw new IOException("disconnected");}public void flush(){}public void close(){}}));fake.submitText("keep this","queue");}catch(Exception e){throw new RuntimeException(e);}});
        until(()->!fake.chat.busy&&fake.chat.outbox.entries.size()==1&&fake.chat.outbox.entries.get(0).status.equals("failed"),2000,"write failure must keep the message and release busy");pass("transport write failure retains unsent message");
    }
    void parallelChatTests(){
        fresh();
        main(()->fake.submitText("Первая задача","queue"));
        JSONObject first=next("thread/start");
        AgentService.ChatSession firstChat=fake.chat;
        main(()->{fake.submitText("Первая очередь","queue");fake.newThread();fake.submitText("Вторая задача","queue");});
        JSONObject second=next("thread/start");
        AgentService.ChatSession secondChat=fake.chat;
        reply(first,AgentService.j("thread",AgentService.j("id","first")));
        JSONObject firstTurn=next("turn/start");
        reply(second,AgentService.j("thread",AgentService.j("id","second")));
        JSONObject secondTurn=next("turn/start");
        check(fake.chat==secondChat&&fake.busyCount()==2,"both tasks start before either finishes");
        check(firstTurn.optJSONObject("params").optString("threadId").equals("first")&&secondTurn.optJSONObject("params").optString("threadId").equals("second"),"thread/start replies stay with the chat that submitted them");
        reply(firstTurn,AgentService.j("turn",AgentService.j("id","turn-first")));
        reply(secondTurn,AgentService.j("turn",AgentService.j("id","turn-second")));
        main(()->{
            fake.handle(AgentService.j("method","item/agentMessage/delta","params",AgentService.j("threadId","first","itemId","common","delta","первый")));
            fake.handle(AgentService.j("method","item/agentMessage/delta","params",AgentService.j("threadId","second","itemId","common","delta","второй")));
        });
        check(firstChat.messages.get("common").text.equals("первый")&&secondChat.messages.get("common").text.equals("второй"),"streamed items are isolated by chat even with matching item IDs");
        main(()->{fake.resume("first");fake.interrupt();});
        JSONObject stop=next("turn/interrupt");
        check(stop.optJSONObject("params").optString("threadId").equals("first")&&stop.optJSONObject("params").optString("turnId").equals("turn-first"),"interrupt targets the selected chat");
        main(()->fake.handle(AgentService.j("method","turn/completed","params",AgentService.j("threadId","first","turn",AgentService.j("id","turn-first","status","interrupted")))));
        check(firstChat.outbox.paused&&firstChat.outbox.entries.size()==1&&secondChat.busy&&!secondChat.outbox.paused,"stopping one chat leaves the other busy and its queue unpaused");
        main(()->{fake.resume("second");fake.submitText("Вторая очередь","queue");fake.resume("first");});
        main(()->fake.handle(AgentService.j("method","turn/completed","params",AgentService.j("threadId","second","turn",AgentService.j("id","turn-second","status","completed")))));
        JSONObject queued=next("turn/start");
        check(fake.chat==firstChat&&queued.optJSONObject("params").optString("threadId").equals("second"),"background queue advances without changing the selected chat");
        pass("parallel chat start, event routing, interrupt isolation and background queue delivery");
    }

    void pinTests()throws Exception{
        fresh();
        main(()->fake.queryThreads("",false));
        JSONObject list=next("thread/list");
        reply(list,AgentService.j("data",new JSONArray().put(AgentService.j("id","recent","name","Recent")).put(AgentService.j("id","pinned","name","Pinned"))));
        main(()->fake.togglePin("pinned"));
        check(fake.chatList().optJSONObject(0).optBoolean("pinned"),"pin moves the chat to the top");
        fresh();
        Method restore=AgentService.class.getDeclaredMethod("restorePins");restore.setAccessible(true);
        main(()->{try{restore.invoke(fake);fake.queryThreads("",false);}catch(Exception e){throw new RuntimeException(e);}});
        list=next("thread/list");
        reply(list,AgentService.j("data",new JSONArray().put(AgentService.j("id","recent","name","Recent"))));
        check(fake.chatList().length()==2&&fake.chatList().optJSONObject(0).optString("id").equals("pinned"),"pin survives recreation and a page without that chat");
        main(()->fake.togglePin("pinned"));
        check(fake.chatList().length()==1,"unpin removes out-of-page pin");
        pass("pin order, persistence and unpin outside the first history page");
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
        main(()->{fake.chat.busy=true;check(fake.submitText("","queue",new JSONArray().put(id)),"image-only submission accepted");});
        check(fake.chat.outbox.entries.get(0).images.contains(id),"outbox keeps image identity");
        main(()->{fake.chat.outbox.entries.clear();try{Method restore=AgentService.class.getDeclaredMethod("restoreOutbox");restore.setAccessible(true);restore.invoke(fake);}catch(Exception e){throw new RuntimeException(e);}});
        check(fake.chat.outbox.entries.get(0).images.contains(id)&&fake.chat.outbox.paused,"images survive service recreation");
        boolean blocked=false;try{fake.getContentResolver().openFileDescriptor(android.net.Uri.parse("content://local.avr.codex.copy.files/00000000-0000-0000-0000-000000000001/%2E%2E%2F%2E%2E%2Ffiles%2Fsecret"),"r");}catch(FileNotFoundException e){blocked=true;}check(blocked,"provider denies traversal");
        check(MediaFiles.localPath("sandbox:/sdcard/My Folder/report.pdf:12").equals("/sdcard/My Folder/report.pdf"),"sandbox link preserves spaces and strips line number");
        check(MediaFiles.quote("a'b").equals("'a'\\''b'"),"root path is shell quoted");
        final byte[] fixtureBytes=java.nio.file.Files.readAllBytes(fixture.toPath());image.delete();fixture.delete();dir.delete();pass("native image import, local/remote input, persistence, provider traversal and paths");
        fresh();final String data=android.util.Base64.encodeToString(fixtureBytes,android.util.Base64.NO_WRAP);
        main(()->{fake.chat.id="media-test";fake.showItem(fake.chat,AgentService.j("id","generated-test","type","imageGeneration","status","completed","result",data),false,true);});
        until(()->fake.chat.messages.get("generated-test").images.length()==1,10000,"generated image was not decoded");
        File generated=MediaFiles.image(fake,fake.chat.messages.get("generated-test").images.getJSONObject(0).getString("id"));check(generated.length()>0,"generation result file saved");generated.delete();pass("native generated image event is decoded and rendered");
        TaskNotifications notification=new TaskNotifications(fake);android.app.Notification ongoing=notification.ongoing();check(ongoing.getChannelId().equals("agent")&&(ongoing.flags&android.app.Notification.FLAG_ONGOING_EVENT)!=0,"native foreground channel and ongoing flag");check(notification.manager.getNotificationChannel("results")!=null,"completion channel exists");pass("Android task notification channels and foreground notification");
    }
    void documentTests()throws Exception{
        fresh();
        File dir=new File(fake.getCacheDir(),"shared/"+UUID.randomUUID());dir.mkdirs();
        File fixture=new File(dir,"Отчёт ноябрь.pdf");
        byte[] bytes={0,37,80,68,70,-1};
        java.nio.file.Files.write(fixture.toPath(),bytes);
        JSONObject imported=AttachmentFiles.importUri(fake,MediaFiles.shareUri(fake,fixture));
        String id=imported.getString("id");
        File stored=AttachmentFiles.file(fake,id);
        check(stored.getName().equals(fixture.getName())&&Arrays.equals(bytes,java.nio.file.Files.readAllBytes(stored.toPath())),"file picker import preserves bytes and filename");
        check(Arrays.equals(bytes,java.nio.file.Files.readAllBytes(AttachmentFiles.shareCopy(fake,id).toPath())),"sent document can be copied for external viewing");
        try{AttachmentFiles.file(fake,"../"+id);throw new AssertionError("invalid attachment ID accepted");}catch(IOException expected){}
        startDocument(id,false);
        JSONObject turn=next("turn/start");
        String input=turn.optJSONObject("params").optJSONArray("input").optJSONObject(0).getString("text");
        check(input.contains(stored.getAbsolutePath()),"file-only local turn references the private copy");
        reply(turn,AgentService.j("turn",AgentService.j("id","file-turn")));
        check(fake.chat.messages.values().stream().anyMatch(m->m.files.length()==1&&m.text.isEmpty()),"file-only message retains the attachment chip");
        AgentService.Message history=new AgentService.Message("file","user",input);
        AttachmentFiles.restoreMessage(fake,history);
        check(history.text.isEmpty()&&history.files.length()==1,"history restores file-only message chip");
        fresh();
        startDocument(id,true);
        JSONObject mkdir=next("fs/createDirectory"),params=mkdir.optJSONObject("params");
        check(params.optString("path").equals("C:/Work folder/.codex-mobile-uploads/"+id),"PC upload uses a unique directory in the configured workspace");
        reply(mkdir,AgentService.j());
        JSONObject write=next("fs/writeFile");params=write.optJSONObject("params");
        String remotePath=params.optString("path");
        check(remotePath.endsWith("/"+fixture.getName())&&Arrays.equals(bytes,android.util.Base64.decode(params.getString("dataBase64"),android.util.Base64.DEFAULT)),"PC upload sends the original bytes");
        check(count("turn/start")==0,"PC turn waits for file upload confirmation");
        reply(write,AgentService.j());
        turn=next("turn/start");
        check(turn.optJSONObject("params").optJSONArray("input").optJSONObject(0).getString("text").contains(remotePath),"PC turn references the uploaded file");
        pass("document import, file-only message and history, and PC upload ordering");
    }
    void startDocument(String id,boolean remote){
        main(()->check(fake.submitText("","queue",new JSONArray(),new JSONArray().put(id)),"file-only submission accepted"));
        JSONObject start=next("thread/start");
        main(()->fake.remoteMode=remote);
        reply(start,AgentService.j("thread",AgentService.j("id","file-chat","cwd","C:\\Work folder")));
    }

    void notificationTest()throws Exception{
        fresh();fake.visibleClients=1;TaskNotifications n=new TaskNotifications(fake);
        try{n.result("test-chat","Проверка","media-delivery-check","completed","Проверка уведомления Codex Mobile");
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
        activity=(MainActivity)startActivitySync(new Intent().setClassName("local.avr.codex.copy","local.avr.codex.copy.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->activity.agent!=null&&activity.agent.ready&&activity.agent.loggedIn&&activity.agent.models.length()>0,25000,"Live engine/account/catalog not ready");
        Thread.sleep(1200);waitForIdleSync();
        main(()->{live=activity.agent;try{Field f=WebChatActivity.class.getDeclaredField("web");f.setAccessible(true);web=(WebView)f.get(activity);}catch(Exception e){throw new RuntimeException(e);}});
        // A previous failed test may have left only its synthetic queued marker.
        main(()->{for(TurnOutbox.Entry e:new ArrayList<>(live.chat.outbox.entries))if(e.text.startsWith("Сообщение из очереди. Не вызывай инструменты"))live.removeQueued(e.id);});
        check(live.busyCount()==0&&live.chat.outbox.entries.isEmpty(),"User task/outbox active; leaving it untouched");
        final String oldThread=live.chat.id,oldModel=live.model,oldEffort=live.effort,oldDraft=activity.input.getText().toString();
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
            untilJs("state.chatKey==="+JSONObject.quote(live.chat.key),"New chat did not reach the WebView");
            int initial=live.chat.completedTurns;
            sendUI("Техническая проверка мобильного интерфейса. Ничего не меняй, не вызывай инструменты. Обдумай различия между очередью сообщений и уточнением уже выполняемой задачи. Дай краткий ответ по-русски.","queue");
            until(()->live.chat.busy&&!live.chat.turnId.isEmpty(),20000,"First turn did not start");diagnostic[0]=live.chat.id;
            sendUI("Сообщение из очереди. Не вызывай инструменты, ничего не меняй. Ответь только QUEUE_OK.","queue");
            untilJs("!$('outbox').hidden&&$('queued-messages').children.length>0","Pending message panel missing");
            js("$('delivery-button').click();true");
            check(js("Array.from(document.querySelectorAll('#sheet-content .option')).some(b=>b.textContent.includes('Уточнить сейчас'))").equals("true"),"Steer option missing");
            js("Array.from(document.querySelectorAll('#sheet-content .option')).find(b=>b.querySelector('strong')?.textContent==='Уточнить сейчас').click();true");
            sendUI("Уточнение ТЕКУЩЕЙ задачи: закончи текущий ответ словом STEER_OK. Не используй инструменты, ничего не меняй.","steer");
            until(()->live.chat.messages.values().stream().anyMatch(m->m.kind.equals("user")&&m.text.startsWith("Уточнение ТЕКУЩЕЙ")),20000,"Steer was not accepted by live server");
            pass("live model picker and turn/steer accepted through UI bridge");
            until(()->live.chat.completedTurns>=initial+2&&!live.chat.busy,140000,"Queued response did not finish");
            check(live.chat.outbox.entries.isEmpty(),"Live outbox not drained");
            check(live.chat.lastAnswer.contains("QUEUE_OK"),"Queued response marker missing");
            check(live.chat.messages.values().stream().anyMatch(m->m.kind.equals("assistant")&&m.text.contains("STEER_OK")),"Steering was accepted but not reflected in response");
            pass("live queue starts after current turn; both response markers verified");
            // Read only our synthetic test UI. Check a phone-width layout as well.
            js("document.documentElement.style.width='360px';document.getElementById('app').style.width='360px';true");
            String layout=js("JSON.stringify({model:$('composer-model').getBoundingClientRect().toJSON(),effort:$('effort-button').getBoundingClientRect().toJSON(),send:$('send').getBoundingClientRect().toJSON()})");
            Bundle b=new Bundle();b.putString("layout360",layout);sendStatus(0,b);
            js("document.documentElement.style.width='';document.getElementById('app').style.width='';true");
            pass("phone-width model and reasoning controls rendered");
        }finally{
            if(live.chat.busy&&!diagnostic[0].isEmpty()&&live.chat.id.equals(diagnostic[0])){main(()->live.interrupt());until(()->!live.chat.busy,12000,"Synthetic test did not stop");}
            main(()->{for(TurnOutbox.Entry e:new ArrayList<>(live.chat.outbox.entries))live.removeQueued(e.id);});
            main(()->{live.selectModel(oldModel);live.selectEffort(oldEffort);if(!live.chat.busy){if(!diagnostic[0].isEmpty())live.archiveThread(diagnostic[0]);if(!oldThread.isEmpty())live.resume(oldThread);else live.newThread();}activity.input.setText(oldDraft);activity.setRequestedOrientation(oldOrientation);});
            if(!oldThread.isEmpty()&&!live.chat.busy)until(()->live.chat.id.equals(oldThread),10000,"Original chat did not reopen");
        }
    }

    void authTest()throws Exception{
        activity=(MainActivity)startActivitySync(new Intent().setClassName("local.avr.codex.copy","local.avr.codex.copy.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->activity.agent!=null&&activity.agent.ready,25000,"Live engine not ready");
        main(()->live=activity.agent);check(live.busyCount()==0&&live.chat.outbox.entries.isEmpty(),"User task active; leaving it untouched");
        CountDownLatch refreshed=new CountDownLatch(1);String[] failure={""};boolean[] signedIn={false};
        main(()->live.readAccount(true,ok->{signedIn[0]=ok;failure[0]=live.loginError;refreshed.countDown();}));
        check(refreshed.await(45,TimeUnit.SECONDS),"Managed token refresh timed out");
        check(failure[0].isEmpty(),"Managed token refresh failed: "+failure[0]);check(signedIn[0],"No saved ChatGPT account; browser login required");
        check(live.loginError.isEmpty(),"Successful refresh must clear old browser failure banner");
        pass("live managed token refresh through app network bridge; account remains signed in");
        CountDownLatch checked=new CountDownLatch(1);String[] result={""};
        main(()->live.request("command/exec",AgentService.j("command",new JSONArray().put("/system/bin/sh").put("-c").put("curl -sS --connect-timeout 8 --max-time 15 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/x-www-form-urlencoded' --data 'grant_type=connectivity_test' https://auth.openai.com/oauth/token"),"cwd",AgentService.BASE+"/workspace","sandboxPolicy",AgentService.j("type","dangerFullAccess"),"timeoutMs",20000),r->{result[0]=r.optInt("exitCode",-1)+":"+r.optString("stdout");checked.countDown();},e->{result[0]=LoginFlow.safeError(e);checked.countDown();}));
        check(checked.await(25,TimeUnit.SECONDS),"Engine network probe timed out");check(result[0].equals("0:400"),"Token endpoint not reachable from engine: "+result[0]);
        pass("live engine HTTPS reaches token endpoint; synthetic grant rejected with expected HTTP 400");
    }

    void migrationTest()throws Exception{
        activity=(MainActivity)startActivitySync(new Intent().setClassName("local.avr.codex.copy","local.avr.codex.copy.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->activity.agent!=null&&activity.agent.ready,30000,"Copied engine did not start");
        main(()->live=activity.agent);
        until(()->live.loggedIn&&live.models.length()>0&&live.threads.length()>=13,30000,"Account, model catalog, or copied history not ready");
        check(AgentService.BASE.equals("/data/adb/avr-codex-copy"),"Independent runtime path missing");
        String previous=getTargetContext().getSharedPreferences("settings",0).getString("lastThread","");
        // The engine's account/read response establishes sign-in; no credentials are inspected.
        pass("copied ChatGPT sign-in confirmed by account/read; model catalog loaded");
        pass("copied thread list loaded: "+live.threads.length()+" unarchived chats");
        until(()->live.chat.loaded&&live.chat.messages.size()>0,20000,"Copied conversation did not resume");
        pass("copied conversation resumed with message history");
        check(new File(RemotePc.directory(getTargetContext()),"profile.json").isFile()&&new File(RemotePc.directory(getTargetContext()),"identity.enc").isFile(),"Copied PC profile or identity missing");
        java.lang.reflect.Method keyMethod=RemotePc.class.getDeclaredMethod("key");keyMethod.setAccessible(true);
        javax.crypto.SecretKey migratedKey=(javax.crypto.SecretKey)keyMethod.invoke(null);
        byte[] identityBlob=java.nio.file.Files.readAllBytes(new File(RemotePc.directory(getTargetContext()),"identity.enc").toPath());
        javax.crypto.Cipher identityCipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        identityCipher.init(javax.crypto.Cipher.DECRYPT_MODE,migratedKey,new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOf(identityBlob,12)));
        byte[] identityPlain=identityCipher.doFinal(identityBlob,12,identityBlob.length-12);
        try{check(identityPlain.length>100,"Migrated identity is empty");}finally{Arrays.fill(identityPlain,(byte)0);}
        pass("migrated SSH identity decrypts in the real app Android Keystore");
        Field field=WebChatActivity.class.getDeclaredField("web");field.setAccessible(true);web=(WebView)field.get(activity);
        until(()->web!=null&&web.getProgress()==100,12000,"Bundled WebView did not load");
        String result=js("JSON.stringify({ready:!!document.querySelector('textarea'),scripts:document.scripts.length})");
        check(result.contains("true"),"Composer missing in copied UI");
        pass("new bundled interface rendered; composer available");
    }

    @Override public void onStart(){
        Bundle result=new Bundle();
        try{if(migrationOnly)migrationTest();else if(authOnly)authTest();else if(notifyOnly)notificationTest();else if(real)realTests();else{mockTests();parallelChatTests();pinTests();if(media){mediaTests();documentTests();}}result.putString("passed",String.join("; ",passed));result.putString("status","PASS");finish(Activity.RESULT_OK,result);}
        catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));result.putString("failure",s.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
