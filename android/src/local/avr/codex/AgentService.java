package local.avr.codex.copy;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class AgentService extends Service {
    static final String BASE = "/data/adb/avr-codex-copy";
    final Handler main = new Handler(Looper.getMainLooper());
    final ArrayList<Runnable> listeners = new ArrayList<>();
    final ArrayList<JSONObject> approvals = new ArrayList<>();
    final ConcurrentHashMap<Long, Consumer<JSONObject>> callbacks = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long, Consumer<String>> failures = new ConcurrentHashMap<>();
    private final Map<String, ChatSession> chats = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> pins = new LinkedHashMap<>();
    ChatSession chat = new ChatSession("");
    boolean modelsLoading;
    String modelsError="";
    JSONArray threads = new JSONArray(), models = new JSONArray();
    String state = "Запускаю локальный движок…", account = "Войти с ChatGPT";
    String model="", effort="", threadCursor="", threadSearch="";
    private long listQuery;
    final LoginFlow loginFlow=new LoginFlow();
    String loginError="";
    private boolean engineStarting;
    boolean ready, loggedIn;
    int visibleClients;
    TaskNotifications notifications;
    PhoneAccess phoneAccess;
    WorkerServers workerServers;
    TunnelManager tunnel;
    private final Set<String> notifiedTurns=new HashSet<>();
    private boolean changeQueued;
    private final ExecutorService wireIo = Executors.newSingleThreadExecutor();
    private final ExecutorService attachmentIo = Executors.newSingleThreadExecutor();
    private long nextId = 1;
    private Process process;
    private volatile BufferedWriter writer;
    private boolean stopping;
    boolean remoteMode;
    private volatile int engineGeneration;
    private volatile RemotePc remoteConnection;
    private volatile EngineNetworkProxy networkProxy;
    private final IBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder { AgentService get() { return AgentService.this; } }
    static final class ChatSession {
        final String key=UUID.randomUUID().toString();
        final String draftId;
        final TurnOutbox outbox=new TurnOutbox();
        LinkedHashMap<String, Message> messages=new LinkedHashMap<>();
        LinkedHashMap<String, Message> history;
        String id, title="Новый чат", cwd="", turnId="", lastCompletedTurn="", lastAnswer="";
        String finalAnswer="", streamingAnswer="";
        boolean busy, loaded, loading, stopRequested, interruptSent, removing;
        int completedTurns, textDeltaCount, reasoningDeltaCount;
        long turnStartedAt;

        ChatSession(String id) { this(id, id.isEmpty() ? "draft-"+UUID.randomUUID() : ""); }
        ChatSession(String id,String draftId) { this.id=id;this.draftId=draftId; }
        String listId(){return id.isEmpty()?draftId:id;}
    }
    static final class Message {
        final String id;
        String kind,text,phase="",status="",title="";
        boolean complete, loadingImage;
        JSONArray images=new JSONArray(), files=new JSONArray();
        long startedAt=SystemClock.elapsedRealtime(),durationMs;
        int revision;
        Message(String i, String k, String t) { id=i; kind=k; text=t; }
    }
    static JSONObject j(Object... pairs) {
        JSONObject o = new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2) o.put((String)pairs[i],pairs[i+1]); }
        catch(JSONException e) { throw new IllegalArgumentException(e); }
        return o;
    }
    @Override public IBinder onBind(Intent i) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        model=getSharedPreferences("settings",0).getString("model","");
        effort=getSharedPreferences("settings",0).getString("effort","");
        remoteMode=getSharedPreferences("settings",0).getBoolean("remoteMode",false);
        loginError=getSharedPreferences("runtime",0).getString("lastLoginError","");
        getSharedPreferences("runtime",0).edit().putBoolean("authPending",false).apply();
        restoreOutbox();
        restorePins();
        notifications=new TaskNotifications(this);startForeground(1,notifications.ongoing());
        phoneAccess=new PhoneAccess(this);
        workerServers=new WorkerServers(this);
        tunnel=new TunnelManager(this,()->main.post(()->changed()),()->{EngineNetworkProxy proxy=networkProxy;if(proxy!=null)proxy.closeConnections();});
        startEngine();
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null && "STOP_PHONE_ACCESS".equals(i.getAction())&&phoneAccess!=null)phoneAccess.stop();
        if(i!=null && "STOP".equals(i.getAction())) stopSelf();
        if(i!=null && "INTERRUPT".equals(i.getAction()))interrupt(chats.get(i.getStringExtra("thread")));
        if(i!=null && "DIAGNOSTICS".equals(i.getAction())) diagnoseWhenReady(20);
        if(i!=null && "VOICE_DIAGNOSTICS".equals(i.getAction())) voiceDiagnostics();
        if(i!=null && "LOGIN_DEVICE".equals(i.getAction())) loginWhenReady(20);
        if(i!=null && "STREAM_DIAGNOSTICS".equals(i.getAction())) streamWhenReady(20);
        if(i!=null && "PREPARE_PC".equals(i.getAction())) new Thread(()->{try{RemotePc.prepareIdentity(this);getSharedPreferences("runtime",0).edit().putBoolean("pcIdentityReady",true).apply();}catch(Exception e){main.post(()->notice("Не удалось подготовить ключ ПК: "+e.getClass().getSimpleName()));}},"pc-key").start();
        if(i!=null && "CONNECT_PC".equals(i.getAction())) switchTarget(true);
        if(i!=null && "CONNECT_PHONE".equals(i.getAction())) switchTarget(false);
        if(i!=null && "PC_DIAGNOSTICS".equals(i.getAction())) diagnoseWhenReady(40);
        return START_NOT_STICKY;
    }
    private void diagnoseWhenReady(int remaining) {
        if(ready)diagnostics();else if(remaining>0)main.postDelayed(()->diagnoseWhenReady(remaining-1),500);
    }
    private void loginWhenReady(int remaining){if(ready)login(true);else if(remaining>0)main.postDelayed(()->loginWhenReady(remaining-1),500);}
    private void streamWhenReady(int remaining){
        if(ready&&loggedIn&&!chat.busy){newThread();sendText("Проверка приложения: коротко сообщай о ходе работы, выполни только id, uname -r и docker ps -a через инструменты. Ничего не изменяй. Затем кратко подведи итог.");}
        else if(remaining>0)main.postDelayed(()->streamWhenReady(remaining-1),500);
    }
    private void voiceDiagnostics() {
        final android.content.SharedPreferences prefs=getSharedPreferences("runtime",0);
        prefs.edit().putBoolean("recognitionAvailable",android.speech.SpeechRecognizer.isRecognitionAvailable(this)).apply();
        final android.speech.tts.TextToSpeech[] holder=new android.speech.tts.TextToSpeech[1];
        holder[0]=new android.speech.tts.TextToSpeech(this,status->{
            if(status!=android.speech.tts.TextToSpeech.SUCCESS){prefs.edit().putBoolean("ttsReady",false).apply();return;}
            android.speech.tts.TextToSpeech t=holder[0];int language=t.setLanguage(new java.util.Locale("ru","RU"));
            prefs.edit().putInt("ruTtsLanguageStatus",language).putBoolean("ttsReady",language>=0).apply();
            if(language<0){t.shutdown();return;}
            final java.io.File audio=new java.io.File(getCacheDir(),"voice-check.wav");
            t.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){prefs.edit().putLong("ttsAudioBytes",audio.length()).apply();t.shutdown();}
                public void onError(String id){prefs.edit().putLong("ttsAudioBytes",0).apply();t.shutdown();}
            });
            t.synthesizeToFile("Голосовой режим готов.",new Bundle(),audio,"selftest");
        });
    }
    void changed() {
        if(changeQueued)return;changeQueued=true;
        main.postDelayed(()->{changeQueued=false;for(Runnable r:new ArrayList<>(listeners))r.run();},24);
    }
    private Message add(Map<String,Message> messages,String id,String kind,String text) {
        if(text.length()>48000) text=text.substring(0,48000)+"\n…вывод сокращён в интерфейсе";
        Message m=messages.get(id);
        if(m==null){m=new Message(id,kind,text);messages.put(id,m);}else{m.text=text;m.kind=kind;}
        m.revision++;
        while(messages.size()>250) messages.remove(messages.keySet().iterator().next());
        changed();
        return m;
    }
    void notice(String text) { notice(chat,text); }
    private void notice(ChatSession owner,String text) { add(owner.messages,"notice-"+System.nanoTime(),"notice",text); }
    long busyCount(){return chats.values().stream().filter(c->c.busy).count();}
    JSONObject currentApproval(){
        for(JSONObject request:approvals){
            if(chat.id.equals(request.optJSONObject("params").optString("threadId")))return request;
        }
        return null;
    }
    ChatSession notificationChat(){
        if(!approvals.isEmpty()){
            ChatSession owner=chats.get(approvals.get(0).optJSONObject("params").optString("threadId"));
            if(owner!=null)return owner;
        }
        if(chat.busy)return chat;
        for(ChatSession owner:chats.values())if(owner.busy)return owner;
        return chat;
    }
    void startEngine() {
        if(ready||engineStarting)return;
        engineStarting=true;
        final int generation=++engineGeneration;
        final boolean pc=remoteMode;
        stopping=false; ready=false; state=pc?"Подключаюсь к ПК по SSH…":"Запускаю локальный движок…"; changed();
        new Thread(()->{
            Process child=null;RemotePc remote=null;EngineNetworkProxy bridge=null;
            try {
                final InputStream input;
                final OutputStream output;
                if(pc){
                    remote=RemotePc.connect(this);
                    if(generation!=engineGeneration){remote.close();return;}
                    input=remote.input;output=remote.output;
                }else{
                    bridge=new EngineNetworkProxy(tunnel);
                    child=new ProcessBuilder("su","-c","/system/bin/sh "+BASE+"/run "+MediaFiles.quote(bridge.address())).start();
                    if(generation!=engineGeneration){child.destroy();return;}
                    input=child.getInputStream();output=child.getOutputStream();
                }
                final Process local=child;
                final RemotePc remoteTransport=remote;
                final EngineNetworkProxy localProxy=bridge;
                final BufferedWriter transport=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
                if(local!=null)
                new Thread(()->{
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(local.getErrorStream(),StandardCharsets.UTF_8))) {
                        String line; StringBuilder tail=new StringBuilder();
                        while((line=r.readLine())!=null) {
                            // Never mirror raw diagnostics or authentication payloads into system logcat.
                            tail.append(line).append('\n'); if(tail.length()>3000) tail.delete(0,tail.length()-3000);
                        }
                        String detail=tail.toString();
                        if(!detail.isEmpty()) main.post(()->{ if(generation==engineGeneration&&!ready && !stopping) notice("Движок: "+detail); });
                    } catch(IOException ignored) {}
                },"codex-stderr").start();
                main.post(()->{
                    if(generation!=engineGeneration){
                        if(local!=null)local.destroy();
                        if(remoteTransport!=null)remoteTransport.close();
                        if(localProxy!=null)localProxy.close();
                        return;
                    }
                    process=local;remoteConnection=remoteTransport;networkProxy=localProxy;writer=transport;
                    request("initialize",j("clientInfo",j("name","avr_codex_mobile","title","Codex Mobile AVR","version","0.6.1")),r->{
                        notifyServer("initialized",j()); engineStarting=false;ready=true; state=readyLabel();if(notifications!=null)notifications.update();
                        getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",pc).putString("connectionError","").apply();
                        readAccount(); refreshThreads(); refreshModels();
                        String last=getSharedPreferences("settings",0).getString(lastThreadKey(),"");
                        if(last.isEmpty()&&!chats.isEmpty())last=chats.keySet().iterator().next();
                        if(last.startsWith("draft-")&&!chats.containsKey(last))selectChat(new ChatSession("",last));
                        else if(!last.isEmpty())resume(last);
                        else selectChat(chat);
                        changed();
                    });
                });
                try(BufferedReader r=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                    String line;
                    while((line=r.readLine())!=null) {
                        try { JSONObject msg=new JSONObject(line); main.post(()->{if(generation==engineGeneration)handle(msg);}); }
                        catch(JSONException ignored) {}
                    }
                }
                if(child!=null)child.waitFor();
                main.post(()->{if(generation!=engineGeneration)return;engineStarting=false;ready=false;writer=null;connectionLost();state=pc?"Соединение с ПК закрыто":"Движок остановлен";changed();});
            } catch(Exception e) {
                main.post(()->{if(generation!=engineGeneration)return;engineStarting=false;ready=false;writer=null;connectionLost();
                    state=pc?"Ошибка подключения к ПК":"Не удалось запустить: проверь root-доступ";
                    String detail=LoginFlow.safeError(e.toString());
                    getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",false).putString("connectionError",detail).apply();
                    notice(pc?"ПК недоступен. Проверь адрес, Wi-Fi и разрешение SSH. "+detail:detail);changed();});
            }finally{if(remote!=null)remote.close();if(bridge!=null){bridge.close();if(networkProxy==bridge)networkProxy=null;}}
        },"codex-stdio").start();
    }
    String readyLabel(){return remoteMode?"Готов · команды на ПК":"Готов · root на телефоне";}
    String lastThreadKey(){return remoteMode?"lastThreadPc":"lastThread";}
    void switchTarget(boolean pc){
        if(busyCount()>0||!approvals.isEmpty()||chats.values().stream().anyMatch(c->!c.outbox.entries.isEmpty())){notice("Сначала заверши задачу и отправь или удали сообщения из очереди.");return;}
        if(pc&&!RemotePc.configured(this)){notice("Сначала настрой подключение ПК в меню устройств.");return;}
        ++engineGeneration;
        writer=null;
        if(remoteConnection!=null){remoteConnection.close();remoteConnection=null;}
        if(process!=null){process.destroy();process=null;}
        if(networkProxy!=null){networkProxy.close();networkProxy=null;}
        callbacks.clear();failures.clear();chats.clear();chat=new ChatSession("");approvals.clear();models=new JSONArray();threads=new JSONArray();modelsLoading=false;modelsError="";
        remoteMode=pc;ready=false;loggedIn=false;account="";threadCursor="";threadSearch="";
        loginFlow.reset();loginError="";engineStarting=false;
        getSharedPreferences("settings",0).edit().putBoolean("remoteMode",pc).apply();
        getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",false).apply();
        restoreOutbox();
        restorePins();
        startEngine();
    }
    private void write(JSONObject message,Consumer<String> failure){
        BufferedWriter output=writer;
        if(output==null){failure.accept("Движок не подключён");return;}
        int generation=engineGeneration;
        wireIo.execute(()->{
            if(generation!=engineGeneration)return;
            try{output.write(message.toString());output.newLine();output.flush();}
            catch(IOException e){main.post(()->{if(generation==engineGeneration)failure.accept("Не удалось отправить запрос: "+e.getMessage());});}
        });
    }
    void send(JSONObject msg) { write(msg,this::notice); }
    void request(String method,JSONObject params,Consumer<JSONObject> callback) {
        ChatSession owner=chat;
        request(method,params,callback,error->notice(owner,error));
    }
    void request(String method,JSONObject params,Consumer<JSONObject> callback,Consumer<String> failure) {
        long id=nextId++;callbacks.put(id,callback);failures.put(id,failure);
        write(j("id",id,"method",method,"params",params),error->{
            callbacks.remove(id);
            Consumer<String> pending=failures.remove(id);
            if(pending!=null)pending.accept(error);
        });
    }
    void notifyServer(String method,JSONObject params) { send(j("method",method,"params",params)); }
    void handle(JSONObject m) {
        if(m.has("id") && !m.has("method")) {
            Consumer<JSONObject> callback=callbacks.remove(m.optLong("id"));
            Consumer<String> failure=failures.remove(m.optLong("id"));
            if(m.has("error")) {
                String error=m.optJSONObject("error").optString("message","Ошибка Codex");
                if(failure!=null)failure.accept(error);
            }
            else if(callback!=null) callback.accept(m.optJSONObject("result"));
            changed(); return;
        }
        String method=m.optString("method"); JSONObject p=m.optJSONObject("params"); if(p==null) p=j();
        ChatSession owner=chats.get(p.optString("threadId"));
        if(m.has("id")) {
            if(owner!=null&&(method.equals("item/commandExecution/requestApproval") || method.equals("item/fileChange/requestApproval") || method.equals("item/tool/requestUserInput"))) {
                approvals.add(m);if(notifications!=null)notifications.update();changed();return;
            }
            send(j("id",m.opt("id"),"error",j("code",-32601,"message","This mobile client does not support this request")));return;
        }
        if(method.equals("account/login/completed")) {
            if(!loginFlow.complete(p.optString("loginId")))return;
            if(p.optBoolean("success")) { setLoginError("");readAccount();refreshModels();state="Вход выполнен"; }
            else {setLoginError(p.optString("error","Попробуй новый вход"));state="Ошибка входа";notice("Вход не завершён: "+loginError);}
        } else if(method.equals("account/updated")) readAccount();
        else if(method.equals("serverRequest/resolved")) {
            Object id=p.opt("requestId");
            approvals.removeIf(a->String.valueOf(a.opt("id")).equals(String.valueOf(id)));
            if(notifications!=null)notifications.update();
        } else if(owner!=null)handleChatEvent(owner,method,p);
        else if(p.optString("threadId").isEmpty()&&method.equals("warning"))notice(p.optString("message"));
        changed();
    }
    private void handleChatEvent(ChatSession owner,String method,JSONObject p){
        Map<String,Message> messages=owner.messages;
        String itemId=p.optString("itemId");
        Message old=messages.get(itemId);
        switch(method){
            case "turn/started": {
                JSONObject turn=p.optJSONObject("turn");
                if(turn==null)return;
                String id=turn.optString("id");
                if(id.isEmpty()||id.equals(owner.lastCompletedTurn))return;
                owner.turnId=id;owner.busy=true;
                owner.finalAnswer="";owner.streamingAnswer="";
                owner.turnStartedAt=SystemClock.elapsedRealtime();owner.textDeltaCount=0;owner.reasoningDeltaCount=0;
                if(owner.stopRequested)interrupt(owner);else drainOutbox(owner);
                if(notifications!=null)notifications.update();
                break;
            }
            case "turn/completed": {
                JSONObject turn=p.optJSONObject("turn");
                if(turn==null)return;
                String id=turn.optString("id"),status=turn.optString("status");
                if(id.isEmpty()||id.equals(owner.lastCompletedTurn)||(!owner.turnId.isEmpty()&&!id.equals(owner.turnId)))return;
                owner.lastCompletedTurn=id;owner.busy=false;owner.turnId="";
                owner.lastAnswer=owner.finalAnswer.isEmpty()?owner.streamingAnswer:owner.finalAnswer;
                owner.completedTurns++;
                if(owner.turnStartedAt!=0)getSharedPreferences("runtime",0).edit().putInt("textDeltaCount",owner.textDeltaCount).putInt("reasoningDeltaCount",owner.reasoningDeltaCount).putLong("lastTurnDurationMs",SystemClock.elapsedRealtime()-owner.turnStartedAt).apply();
                if(notifications!=null){
                    if(notifiedTurns.add(owner.id+"/"+id))notifications.result(owner.id,owner.title,id,status,owner.lastAnswer);
                    if(notifiedTurns.size()>100)notifiedTurns.clear();
                    notifications.update();
                }
                JSONObject error=turn.optJSONObject("error");
                if(error!=null)notice(owner,error.optString("message"));
                if(owner.stopRequested||!status.equals("completed")){owner.outbox.paused=true;saveOutbox();}
                owner.stopRequested=false;owner.interruptSent=false;
                refreshThreads();
                main.post(()->drainOutbox(owner));
                break;
            }
            case "item/started":
            case "item/completed":
                JSONObject item=p.optJSONObject("item");
                if(item!=null)showItem(owner,item,false,method.equals("item/completed"));
                break;
            case "item/agentMessage/delta":
            case "item/commandExecution/outputDelta":
            case "item/plan/delta":
                add(messages,itemId,method.contains("commandExecution")?"command":"assistant",(old==null?"":old.text)+p.optString("delta"));
                if(method.equals("item/agentMessage/delta"))owner.textDeltaCount++;
                break;
            case "item/reasoning/summaryTextDelta":
                add(messages,itemId,"reasoning",(old==null?"":old.text)+p.optString("delta"));
                owner.reasoningDeltaCount++;
                break;
            case "item/reasoning/summaryPartAdded":
                if(old!=null&&!old.text.isEmpty())add(messages,itemId,"reasoning",old.text+"\n\n");
                break;
            case "turn/plan/updated":
                JSONArray plan=p.optJSONArray("plan");if(plan!=null)add(messages,p.optString("turnId")+"-plan","plan",plan.toString());
                break;
            case "item/mcpToolCall/progress":
                if(old!=null)add(messages,itemId,"command",old.text+"\n"+p.optString("message",p.optString("text")));
                break;
            case "error":
                JSONObject error=p.optJSONObject("error");if(error!=null)notice(owner,error.optString("message"));
                break;
            case "warning": notice(owner,p.optString("message"));break;
        }
    }
    void showItem(ChatSession owner,JSONObject item,boolean history,boolean done) {
        Map<String,Message> messages=history?owner.history:owner.messages;
        String type=item.optString("type"), id=item.optString("id",UUID.randomUUID().toString());
        if(type.equals("agentMessage") || type.equals("plan")) {
            add(messages,id,"assistant",item.optString("text"));
            if(type.equals("agentMessage") && !history) {
                owner.streamingAnswer=item.optString("text");
                if(item.optString("phase").equals("final_answer"))owner.finalAnswer=owner.streamingAnswer;
            }
        }
        else if(type.equals("commandExecution")) add(messages,id,"command","$ "+item.optString("command")+"\n"+item.optString("aggregatedOutput")+
            (item.has("exitCode")&&!item.isNull("exitCode")?"\n[exit "+item.optInt("exitCode")+"]":""));
        else if(type.equals("fileChange")) add(messages,id,"command","Изменения файлов\n"+item.optJSONArray("changes"));
        else if(type.equals("imageGeneration")) {
            add(messages,id,"image",done?"Изображение готово":"Генерирую изображение…");
            JSONObject failure=item.optJSONObject("failure");
            if(failure!=null){add(messages,id,"notice","Генерация недоступна: "+failure.optString("type"));}
            else if(done&&messages.get(id).images.length()==0&&!messages.get(id).loadingImage)loadGeneratedImage(owner,messages.get(id),item);
        }
        else if(type.equals("userMessage") && history) {
            JSONArray content=item.optJSONArray("content");StringBuilder text=new StringBuilder();
            if(content!=null)for(int i=0;i<content.length();i++) {JSONObject c=content.optJSONObject(i);if(c!=null)text.append(c.optString("text"));}
            Message user=add(messages,id,"user",text.toString());
            AttachmentFiles.restoreMessage(this,user);
            if(content!=null)for(int i=0;i<content.length();i++){JSONObject c=content.optJSONObject(i);if(c!=null&&c.optString("type").equals("localImage")){try{File f=new File(c.optString("path"));if(f.getParentFile()!=null&&f.getParentFile().getCanonicalFile().equals(MediaFiles.directory(this).getCanonicalFile()))user.images.put(MediaFiles.describe(MediaFiles.image(this,f.getName())));}catch(Exception ignored){}}}
        } else if(type.equals("reasoning")) {
            JSONArray summary=item.optJSONArray("summary");StringBuilder text=new StringBuilder();
            if(summary!=null)for(int i=0;i<summary.length();i++){
                Object part=summary.opt(i);String value=part instanceof JSONObject?((JSONObject)part).optString("text"):String.valueOf(part);
                if(!value.isEmpty()){if(text.length()>0)text.append("\n\n");text.append(value);}
            }
            Message previous=messages.get(id);
            add(messages,id,"reasoning",text.length()>0?text.toString():previous==null?"":previous.text);
        } else if(type.equals("mcpToolCall")) add(messages,id,"command",item.optString("server")+" / "+item.optString("tool")+"\n"+item.optString("status")+"\n"+item.optString("result"));
        else if(type.equals("dynamicToolCall"))add(messages,id,"command",item.optString("tool")+"\n"+item.optString("arguments")+"\n"+item.optString("contentItems"));
        else if(type.equals("webSearch"))add(messages,id,"command",item.optString("query"));
        Message message=messages.get(id);
        if(message!=null){
            message.phase=item.optString("phase",message.phase);message.status=item.optString("status",done?"completed":"inProgress");
            message.complete=done;if(done&&!history)message.durationMs=SystemClock.elapsedRealtime()-message.startedAt;
            message.title=type.equals("webSearch")?"Поиск в интернете":type.equals("fileChange")?"Изменения файлов":type.equals("reasoning")?"Размышление":type.equals("commandExecution")?"Команда":type.equals("mcpToolCall")?item.optString("tool"):type.equals("dynamicToolCall")?item.optString("tool"):"";
            if(type.equals("commandExecution")){
                String command=item.optString("command").replaceFirst("^\\S*/(?:ba|z|k)?sh -[a-z]*c\\s+","").trim();
                if(command.length()>1&&((command.startsWith("'")&&command.endsWith("'"))||(command.startsWith("\"")&&command.endsWith("\""))))command=command.substring(1,command.length()-1);
                message.title=command.length()>65?command.substring(0,65)+"…":command;
            }
            message.revision++;
        }
    }
    private void loadGeneratedImage(ChatSession owner,Message message,JSONObject item){
        message.loadingImage=true;boolean remote=remoteMode;
        new Thread(()->{try{
            String result=item.optString("result"),path=item.optString("savedPath");byte[] bytes;
            if(!result.isEmpty()){if(result.startsWith("data:"))result=result.substring(result.indexOf(',')+1);if(result.length()>36*1024*1024)throw new IOException("Изображение слишком большое");bytes=android.util.Base64.decode(result,android.util.Base64.DEFAULT);}
            else if(!path.isEmpty()&&!path.equals("null")&&!remote)bytes=MediaFiles.rootRead(MediaFiles.localPath(path),25*1024*1024);
            else throw new IOException("Движок не передал доступный файл изображения");
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0)throw new IOException("Некорректное изображение");
            String extension="image/jpeg".equals(options.outMimeType)?".jpg":".png";
            File f=new File(MediaFiles.directory(this),UUID.nameUUIDFromBytes((owner.id+"/"+message.id).getBytes(StandardCharsets.UTF_8))+extension);java.nio.file.Files.write(f.toPath(),bytes);
            main.post(()->{message.loadingImage=false;message.images=new JSONArray().put(MediaFiles.describe(f));message.revision++;changed();});
        }catch(Exception e){main.post(()->{message.loadingImage=false;message.kind="notice";message.text="Не удалось показать изображение: "+e.getMessage();message.revision++;changed();});}},"generated-image").start();
    }
    void readAccount() {readAccount(false);}
    void readAccount(boolean refresh) {readAccount(refresh,ignored->{});}
    void readAccount(boolean refresh,Consumer<Boolean> complete) {
        request("account/read",j("refreshToken",refresh),r->{
            JSONObject a=r.optJSONObject("account");loggedIn=a!=null;
            if(refresh&&loggedIn){setLoginError("");if(busyCount()==0&&!loginFlow.pending())state=readyLabel();}
            getSharedPreferences("runtime",0).edit().putBoolean("loggedIn",loggedIn).apply();
            account=a==null?"Войти с ChatGPT":a.optString("email","ChatGPT")+" · "+a.optString("planType","");changed();complete.accept(loggedIn);
        },error->{setLoginError(error);state="Ошибка проверки входа";notice("Не удалось проверить вход: "+loginError);changed();complete.accept(false);});
    }
    void refreshThreads() { queryThreads(threadSearch,false); }
    void queryThreads(String search,boolean more) {
        if(more&&threadCursor.isEmpty())return;
        boolean changedSearch=!search.equals(threadSearch);
        threadSearch=search;
        if(changedSearch){threads=new JSONArray();threadCursor="";changed();}
        long query=++listQuery;
        JSONObject p=j("limit",50,"sortDirection","desc");
        try { if(!search.trim().isEmpty())p.put("searchTerm",search.trim());if(more)p.put("cursor",threadCursor); }
        catch(JSONException ignored){}
        request("thread/list",p,r->{
            if(query!=listQuery)return;
            JSONArray data=r.optJSONArray("data");if(data==null)data=new JSONArray();
            if(!more)threads=new JSONArray();
            boolean pinsUpdated=false;
            for(int i=0;i<data.length();i++){
                JSONObject row=data.optJSONObject(i);
                if(row==null||!canOpen(row.optString("id")))continue;
                threads.put(row);
                String id=row.optString("id");
                if(pins.containsKey(id)){pins.put(id,pinRow(row));pinsUpdated=true;}
            }
            if(pinsUpdated)savePins();
            threadCursor=r.isNull("nextCursor")?"":r.optString("nextCursor");changed();
        });
    }
    JSONArray chatList(){
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        for(JSONObject row:pins.values())if(matchesSearch(row))rows.put(row.optString("id"),row);
        for(int i=0;i<threads.length();i++){
            JSONObject row=threads.optJSONObject(i);
            rows.put(row.optString("id"),row);
        }
        for(ChatSession owner:chats.values()){
            JSONObject row=j("id",owner.listId(),"name",owner.title);
            if((owner.busy||!owner.outbox.entries.isEmpty())&&matchesSearch(row))rows.putIfAbsent(owner.listId(),row);
        }
        Set<String> pendingApprovals=new HashSet<>();
        for(JSONObject request:approvals)pendingApprovals.add(request.optJSONObject("params").optString("threadId"));
        JSONArray result=new JSONArray();
        for(Map.Entry<String,JSONObject> item:rows.entrySet()){
            String id=item.getKey();JSONObject row=item.getValue();ChatSession owner=chats.get(id);
            result.put(j("id",id,"name",row.isNull("name")?"":row.optString("name"),"preview",row.optString("preview"),
                "pinned",pins.containsKey(id),"busy",owner!=null&&owner.busy,"needsApproval",pendingApprovals.contains(id),
                "draft",owner!=null&&owner.id.isEmpty()));
        }
        return result;
    }
    private boolean matchesSearch(JSONObject row){
        String search=threadSearch.trim().toLowerCase(Locale.ROOT);
        return row.optString("name").toLowerCase(Locale.ROOT).contains(search)||row.optString("preview").toLowerCase(Locale.ROOT).contains(search);
    }
    private JSONObject pinRow(JSONObject row){
        return j("id",row.optString("id"),"name",row.isNull("name")?"":row.optString("name"),"preview",row.optString("preview"));
    }
    void togglePin(String id){
        if(id.isEmpty()||id.startsWith("draft-")||!canOpen(id))return;
        if(pins.remove(id)==null){
            JSONArray list=chatList();
            for(int i=0;i<list.length();i++){
                JSONObject row=list.optJSONObject(i);
                if(id.equals(row.optString("id"))){pins.put(id,pinRow(row));break;}
            }
        }
        savePins();changed();
    }
    private void savePins(){
        getSharedPreferences("pins",0).edit().putString(targetKey(),new JSONArray(pins.values()).toString()).apply();
    }
    private void restorePins(){
        pins.clear();
        try{
            JSONArray rows=new JSONArray(getSharedPreferences("pins",0).getString(targetKey(),"[]"));
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String id=row.optString("id");
                if(canOpen(id))pins.put(id,row);
            }
        }catch(JSONException ignored){}
    }
    private String targetKey(){return remoteMode?"pc":"phone";}
    private boolean canOpen(String id){
        return !id.isEmpty()&&(!remoteMode||getSharedPreferences("settings",0).getStringSet("pcThreads",Collections.emptySet()).contains(id));
    }
    void selectModel(String value) {
        if(!value.isEmpty()){boolean found=false;for(int i=0;i<models.length();i++){JSONObject m=models.optJSONObject(i);if(m!=null&&(value.equals(m.optString("model"))||value.equals(m.optString("id"))))found=true;}if(!found){notice("Обнови список моделей и выбери доступную модель.");return;}}
        model=value;effort="";
        getSharedPreferences("settings",0).edit().putString("model",model).putString("effort",effort).apply();changed();
    }
    void selectEffort(String value) {
        if(!value.isEmpty()){JSONObject m=selectedModel();JSONArray options=m==null?null:m.optJSONArray("supportedReasoningEfforts");boolean found=false;if(options!=null)for(int i=0;i<options.length();i++)if(value.equals(options.optJSONObject(i).optString("reasoningEffort")))found=true;if(!found)return;}
        effort=value;getSharedPreferences("settings",0).edit().putString("effort",effort).apply();changed();
    }
    JSONObject selectedModel() {
        JSONObject fallback=null;
        for(int i=0;i<models.length();i++){
            JSONObject m=models.optJSONObject(i);if(m==null)continue;
            if(m.optString("model").equals(model)||m.optString("id").equals(model))return m;
            if(m.optBoolean("isDefault"))fallback=m;
        }return fallback;
    }
    void refreshModels() {if(!ready||modelsLoading)return;modelsLoading=true;modelsError="";changed();modelPage(new JSONArray(),null,new HashSet<>());}
    private void modelPage(JSONArray all,String cursor,Set<String> seen){
        JSONObject params=j("limit",100,"includeHidden",false);if(cursor!=null)try{params.put("cursor",cursor);}catch(JSONException ignored){}
        request("model/list",params,r->{
            JSONArray data=r.optJSONArray("data");if(data!=null)for(int i=0;i<data.length();i++)all.put(data.opt(i));
            String next=r.isNull("nextCursor")?"":r.optString("nextCursor");
            if(!next.isEmpty()&&seen.add(next)){modelPage(all,next,seen);return;}
            models=all;modelsLoading=false;changed();
        },error->{modelsLoading=false;modelsError=error;changed();});
    }
    void login(boolean device) {
        if(remoteMode){notice("На ПК используется его текущий вход в Codex. Войди через настольное приложение ПК.");return;}
        if(!ready){notice("Дождись запуска движка.");return;}
        if(loginFlow.pending()){changed();return;}
        beginLogin(device);
    }
    void restartLogin(boolean device){
        if(remoteMode||!ready||loginFlow.working()){login(device);return;}
        if(!loginFlow.waiting()){login(device);return;}
        final String previous=loginFlow.id;final long ticket=loginFlow.cancel();
        state="Отменяю предыдущий вход…";changed();
        request("account/login/cancel",j("loginId",previous),r->{
            if(!loginFlow.cancelling(ticket))return;
            loginFlow.reset();beginLogin(device);
        },error->{if(!loginFlow.cancelling(ticket))return;loginFlow.cancelFailed(ticket);setLoginError(error);state="Не удалось отменить вход";changed();});
        main.postDelayed(()->{if(loginFlow.cancelling(ticket)){loginFlow.cancelFailed(ticket);setLoginError("Сервер не подтвердил отмену. Проверь соединение и повтори.");state="Ошибка входа";changed();}},60000);
    }
    private void setLoginError(String error){
        loginError=LoginFlow.safeError(error);
        getSharedPreferences("runtime",0).edit().putBoolean("authPending",loginFlow.pending()).putString("lastLoginError",loginError).apply();
    }
    private void beginLogin(boolean device) {
        final long ticket=loginFlow.begin();if(ticket==0)return;
        setLoginError("");
        state="Подготавливаю вход…";changed();
        request("account/login/start",device?j("type","chatgptDeviceCode"):j("type","chatgpt","useHostedLoginSuccessPage",true,"appBrand","codex"),r->{
            String id=r.optString("loginId");
            if(!loginFlow.starting(ticket)){if(!id.isEmpty())request("account/login/cancel",j("loginId",id),ignored->{},ignored->{});return;}
            try{loginFlow.accept(ticket,id,r.optString("authUrl",r.optString("verificationUrl")),r.optString("userCode"));state="Заверши вход в браузере";changed();}
            catch(IllegalArgumentException e){loginFlow.reset();setLoginError("Движок не вернул адрес входа. Начни новую попытку.");state="Ошибка входа";changed();}
        },error->{if(!loginFlow.starting(ticket))return;loginFlow.reset();setLoginError(error);state="Ошибка входа";notice(loginError);changed();});
        main.postDelayed(()->{if(loginFlow.starting(ticket)){loginFlow.reset();setLoginError("Не удалось получить ссылку за минуту. Проверь интернет/VPN и повтори вход.");state="Ошибка входа";changed();}},60000);
    }
    void logout() { if(remoteMode){notice("Выход из аккаунта ПК выполняется на самом ПК.");return;} if(loginFlow.pending()){notice("Сначала заверши текущий вход.");return;}request("account/logout",j(),r->{loggedIn=false;loginFlow.reset();setLoginError("");getSharedPreferences("runtime",0).edit().putBoolean("loggedIn",false).apply();account="Войти с ChatGPT";changed();}); }
    JSONObject threadOptions() {
        JSONObject p;
        if(remoteMode){
            String cwd;try{cwd=RemotePc.profile(this).getString("cwd");}catch(Exception e){throw new IllegalStateException("Не настроена рабочая папка ПК");}
            p=j("cwd",cwd,"approvalPolicy","on-request","sandbox","workspace-write",
                "developerInstructions","Ты работаешь на ПК владельца с Windows через личный мобильный клиент. Команды выполняются на ПК, а не на Android. Учитывай реальную ОС и PowerShell. Сохраняй пользовательские данные; для опасных изменений сначала получи явное разрешение. Не изменяй загрузчик или прошивку подключенного телефона без отдельной команды.");
        }else p=j("cwd",BASE+"/workspace","approvalPolicy","on-request","sandbox","danger-full-access","developerInstructions","Это личный мобильный Codex на Android. Для создания и редактирования картинок используй встроенный image_gen.imagegen, если он доступен в этой сессии; показывай фактический результат и сообщай о недоступности или лимите сервиса, не подменяй генерацию описанием. Ссылки на файлы оформляй как [название](/абсолютный/путь), пути с пробелами как [название](</абсолютный/путь с пробелами>); веб-ссылки оформляй с https://. Не выдавай локальный путь за веб-адрес.");
        if(!remoteMode)try{p.put("developerInstructions",p.getString("developerInstructions")+PhoneAccess.instructions()+WorkerServers.instructions());}catch(JSONException ignored){}
        try{p.put("config",j("features",j("image_generation",true)));}catch(JSONException ignored){}
        if(!model.isEmpty())try{p.put("model",model);}catch(JSONException ignored){}return p;
    }
    void newThread(){
        selectChat(new ChatSession(""));
    }
    private void selectChat(ChatSession owner){
        chat=owner;
        getSharedPreferences("settings",0).edit().putString(lastThreadKey(),owner.listId()).apply();
        if(notifications!=null)notifications.update();
        changed();
    }
    void sendText(String text){submitText(text,"queue");}
    boolean submitText(String text,String mode){return submitText(text,mode,new JSONArray());}
    boolean submitText(String text,String mode,JSONArray images){return submitText(text,mode,images,new JSONArray());}
    boolean submitText(String text,String mode,JSONArray images,JSONArray files){
        text=text.trim();
        ChatSession owner=chat;
        if(text.isEmpty()&&images.length()==0&&files.length()==0)return false;
        if(!ready||owner.loading||owner.removing||(!owner.id.isEmpty()&&!owner.loaded)){
            notice("Дождись подключения чата.");return false;
        }
        if(!loggedIn){notice("Сначала войди через аккаунт ChatGPT.");return false;}
        if(owner.outbox.entries.size()>=50){notice("В очереди этого чата уже 50 сообщений.");return false;}
        try{
            if(images.length()+files.length()>6)throw new IOException("Можно добавить до 6 вложений");
            for(int i=0;i<images.length();i++)MediaFiles.image(this,images.getString(i));
            for(int i=0;i<files.length();i++)AttachmentFiles.file(this,files.getString(i));
        }catch(Exception e){notice(e.getMessage());return false;}
        JSONObject selected=selectedModel();
        String useModel=model.isEmpty()&&selected!=null?selected.optString("model",selected.optString("id")):model;
        String useEffort=effort.isEmpty()&&selected!=null?selected.optString("defaultReasoningEffort"):effort;
        if(images.length()>0&&selected!=null){
            JSONArray modes=selected.optJSONArray("inputModalities");
            if(modes!=null&&!modes.toString().contains("\"image\"")){notice("Выбранная модель не поддерживает изображения.");return false;}
        }
        if(!owner.busy&&owner.outbox.entries.isEmpty())owner.outbox.paused=false;
        TurnOutbox.Entry entry=owner.outbox.add(text,owner.id,"steer".equals(mode)?"steer":"queue",useModel,useEffort);
        entry.images=images.toString();entry.files=files.toString();
        chats.putIfAbsent(owner.listId(),owner);
        if(owner.title.equals("Новый чат")){
            String title=text.isEmpty()?"Вложения":text;
            owner.title=title.substring(0,Math.min(title.length(),55));
        }
        saveOutbox();changed();drainOutbox(owner);return true;
    }
    private void drainOutbox(ChatSession owner){
        if(!ready||!loggedIn||owner.loading||owner.removing||owner.stopRequested||(!owner.id.isEmpty()&&!owner.loaded))return;
        TurnOutbox.Entry entry=owner.outbox.next(owner.busy,owner.id,owner.turnId);
        if(entry==null)return;
        entry.status="sending";saveOutbox();changed();
        if(owner.busy){
            String expected=owner.turnId;
            Consumer<String> failure=error->{
                owner.outbox.failed(entry,"Уточнение не принято: "+error);saveOutbox();
                notice(owner,"Уточнение осталось в очереди. Его можно отправить повторно или удалить.");
            };
            prepareInput(owner,entry,input->{
                if(owner.stopRequested){entry.status="waiting";saveOutbox();changed();return;}
                request("turn/steer",j("threadId",owner.id,"expectedTurnId",expected,"input",input),r->{
                    accepted(owner,entry);drainOutbox(owner);
                },failure);
            },failure);
        }else startText(owner,entry);
    }
    private void accepted(ChatSession owner,TurnOutbox.Entry entry){
        owner.outbox.entries.remove(entry);
        Message message=add(owner.messages,"local-"+entry.id,"user",entry.text);
        message.images=MediaFiles.describeIds(this,entry.images);
        message.files=AttachmentFiles.describeIds(this,entry.files);
        saveOutbox();changed();
    }
    private void startText(ChatSession owner,TurnOutbox.Entry entry){
        owner.busy=true;owner.turnId="";owner.turnStartedAt=0;owner.stopRequested=false;owner.interruptSent=false;
        if(notifications!=null)notifications.update();
        Consumer<String> failure=error->{
            owner.busy=false;owner.turnId="";owner.stopRequested=false;owner.interruptSent=false;
            owner.outbox.failed(entry,error);saveOutbox();notice(owner,error+"\nСообщение сохранено в очереди.");
            if(notifications!=null)notifications.update();
        };
        Runnable start=()->prepareInput(owner,entry,input->{
            if(owner.stopRequested){
                owner.busy=false;owner.stopRequested=false;entry.status="waiting";saveOutbox();changed();
                if(notifications!=null)notifications.update();return;
            }
            JSONObject p=j("threadId",owner.id,"input",input,"summary","detailed",
                "model",entry.model.isEmpty()?JSONObject.NULL:entry.model,"effort",entry.effort.isEmpty()?JSONObject.NULL:entry.effort);
            request("turn/start",p,r->{
                JSONObject turn=r.optJSONObject("turn");
                if(turn!=null&&owner.busy&&owner.turnId.isEmpty()&&!turn.optString("id").equals(owner.lastCompletedTurn))owner.turnId=turn.optString("id");
                accepted(owner,entry);
                if(owner.stopRequested)interrupt(owner);else drainOutbox(owner);
            },failure);
        },failure);
        if(!owner.id.isEmpty()){start.run();return;}
        JSONObject options;
        try{options=threadOptions();}catch(IllegalStateException e){failure.accept(e.getMessage());return;}
        request("thread/start",options,r->{
            JSONObject thread=r.optJSONObject("thread");
            String id=thread==null?"":thread.optString("id");
            if(id.isEmpty()){failure.accept("Движок не вернул идентификатор чата.");return;}
            chats.remove(owner.draftId);owner.id=id;owner.loaded=true;
            owner.cwd=thread.optString("cwd",options.optString("cwd"));
            chats.put(id,owner);owner.outbox.bindThread(id);saveOutbox();rememberPcThread(id);
            if(chat==owner)selectChat(owner);
            else if(notifications!=null)notifications.update();
            changed();start.run();
        },failure);
    }
    private void prepareInput(ChatSession owner,TurnOutbox.Entry entry,Consumer<JSONArray> complete,Consumer<String> failure){
        if(!remoteMode){
            try{
                JSONArray input=MediaFiles.input(this,entry,false);
                complete.accept(AttachmentFiles.appendInput(entry,input,AttachmentFiles.files(this,entry.files),null));
            }
            catch(Exception e){failure.accept(e.getMessage());}
            return;
        }
        int generation=engineGeneration;
        attachmentIo.execute(()->{
            try{
                List<File> files=AttachmentFiles.files(this,entry.files);
                String directory=owner.cwd.replace('\\','/').replaceFirst("/+$","")+"/.codex-mobile-uploads";
                JSONArray input=AttachmentFiles.appendInput(entry,MediaFiles.input(this,entry,true),files,directory);
                main.post(()->{if(ready&&generation==engineGeneration)uploadNext(files,0,directory,generation,()->complete.accept(input),failure);});
            }catch(Exception e){main.post(()->{if(ready&&generation==engineGeneration)failure.accept(e.getMessage());});}
        });
    }
    private void uploadNext(List<File> files,int index,String directory,int generation,Runnable complete,Consumer<String> failure){
        if(index==files.size()){complete.run();return;}
        File file=files.get(index);
        String parent=directory+"/"+file.getParentFile().getName();
        request("fs/createDirectory",j("path",parent,"recursive",true),r->attachmentIo.execute(()->{
            try{
                String encoded=AttachmentFiles.encode(file);
                main.post(()->{
                    if(!ready||generation!=engineGeneration)return;
                    request("fs/writeFile",j("path",parent+"/"+file.getName(),"dataBase64",encoded),
                        written->uploadNext(files,index+1,directory,generation,complete,failure),failure);
                });
            }catch(IOException e){main.post(()->{if(ready&&generation==engineGeneration)failure.accept(e.getMessage());});}
        }),failure);
    }
    JSONArray outboxState(){return outboxState(chat);}
    private JSONArray outboxState(ChatSession owner){
        JSONArray result=new JSONArray();
        for(TurnOutbox.Entry e:owner.outbox.entries)result.put(queuedEntry(owner,e));
        return result;
    }
    private JSONObject queuedEntry(ChatSession owner,TurnOutbox.Entry e){
        return j("id",e.id,"text",e.text,"thread",e.thread,"draft",owner.draftId,
            "mode",e.mode,"status",e.status,"error",e.error,"model",e.model,"effort",e.effort,
            "images",e.images,"files",e.files,"attachmentCount",countAttachments(e));
    }
    private int countAttachments(TurnOutbox.Entry entry){
        try{return new JSONArray(entry.images).length()+new JSONArray(entry.files).length();}
        catch(JSONException e){return 0;}
    }
    private void saveOutbox(){
        JSONArray result=new JSONArray();
        for(ChatSession owner:chats.values())for(TurnOutbox.Entry e:owner.outbox.entries)result.put(queuedEntry(owner,e));
        getSharedPreferences("outbox",0).edit().putString(targetKey(),result.toString()).apply();
    }
    private void restoreOutbox(){
        try{
            JSONArray data=new JSONArray(getSharedPreferences("outbox",0).getString(targetKey(),"[]"));
            for(int i=0;i<data.length();i++){
                JSONObject row=data.getJSONObject(i);
                String id=row.optString("thread"),draft=row.optString("draft");
                if(id.isEmpty()&&draft.isEmpty())draft=chat.draftId;
                String key=id.isEmpty()?draft:id;
                ChatSession owner=chats.get(key);
                if(owner==null){
                    owner=key.equals(chat.listId())?chat:new ChatSession(id,draft);
                    chats.put(key,owner);
                }
                TurnOutbox.Entry entry=new TurnOutbox.Entry(row.getString("id"),row.getString("text"),id,row.optString("mode","queue"),row.optString("model"),row.optString("effort"));
                entry.images=row.optString("images","[]");entry.files=row.optString("files","[]");
                entry.status=row.optString("status","waiting");entry.error=row.optString("error");
                owner.outbox.entries.add(entry);owner.outbox.recover();
                if(owner.title.equals("Новый чат")){
                    String title=entry.text.isEmpty()?"Вложения":entry.text;
                    owner.title=title.substring(0,Math.min(title.length(),55));
                }
                if(chat.id.isEmpty()&&chat.outbox.entries.isEmpty())chat=owner;
            }
        }catch(JSONException ignored){}
    }
    private void connectionLost(){
        if(loginFlow.pending()){loginFlow.reset();setLoginError("Движок отключился во время входа. Начни новую попытку.");}
        modelsLoading=false;
        for(ChatSession owner:chats.values()){
            owner.outbox.recover();owner.busy=false;owner.turnId="";owner.loaded=false;owner.loading=false;
            owner.history=null;owner.stopRequested=false;owner.interruptSent=false;owner.removing=false;
        }
        saveOutbox();callbacks.clear();failures.clear();approvals.clear();
        if(notifications!=null)notifications.update();
    }
    void removeQueued(String id){if(chat.outbox.remove(id)){saveOutbox();changed();}}
    void steerQueued(String id){
        TurnOutbox.Entry e=chat.outbox.get(id);if(e==null||e.status.equals("sending"))return;
        e.mode="steer";e.status="waiting";e.error="";saveOutbox();changed();drainOutbox(chat);
    }
    void pauseQueue(boolean paused){
        chat.outbox.paused=paused;
        if(!paused)for(TurnOutbox.Entry e:chat.outbox.entries)if(e.status.equals("failed")){e.status="waiting";e.mode="queue";e.error="";}
        saveOutbox();changed();drainOutbox(chat);
    }
    void rememberPcThread(String id){if(!remoteMode)return;Set<String> ids=new HashSet<>(getSharedPreferences("settings",0).getStringSet("pcThreads",Collections.emptySet()));ids.add(id);getSharedPreferences("settings",0).edit().putStringSet("pcThreads",ids).apply();}
    void resume(String id){
        ChatSession existing=chats.get(id);
        if(existing!=null&&existing.removing)return;
        if(existing!=null&&(existing.id.isEmpty()||existing.loaded||existing.loading)){selectChat(existing);return;}
        if(id.startsWith("draft-"))return;
        if(!canOpen(id)){notice("Здесь открываются отдельные чаты Mobile на ПК.");return;}
        ChatSession owner=existing==null?new ChatSession(id):existing;
        JSONObject p;
        try{p=threadOptions();}catch(IllegalStateException e){notice(e.getMessage());return;}
        chats.put(id,owner);owner.loading=true;selectChat(owner);
        try{p.put("threadId",id);p.put("excludeTurns",true);}catch(JSONException ignored){}
        request("thread/resume",p,r->{
            JSONObject thread=r.optJSONObject("thread");
            if(thread!=null){
                String name=thread.isNull("name")?"":thread.optString("name");
                String preview=thread.isNull("preview")?"":thread.optString("preview");
                owner.title=!name.isEmpty()?name:!preview.isEmpty()?preview:"Чат";
                owner.cwd=thread.optString("cwd",p.optString("cwd"));
                if(pins.containsKey(id)){pins.put(id,pinRow(thread));savePins();}
            }else owner.cwd=p.optString("cwd");
            owner.loaded=true;owner.messages.clear();owner.history=new LinkedHashMap<>();
            loadTurns(owner,null);
        },error->{owner.loading=false;notice(owner,error);});
    }
    void renameThread(String id,String name){
        String value=name.trim();if(value.isEmpty())return;
        request("thread/name/set",j("threadId",id,"name",value),r->{
            ChatSession owner=chats.get(id);if(owner!=null)owner.title=value;
            JSONObject pinned=pins.get(id);
            if(pinned!=null){pins.put(id,j("id",id,"name",value,"preview",pinned.optString("preview")));savePins();}
            changed();refreshThreads();
        });
    }
    void archiveThread(String id){removeThread(id,"thread/archive");}
    void deleteThread(String id){removeThread(id,"thread/delete");}
    private void removeThread(String id,String method){
        ChatSession owner=chats.computeIfAbsent(id,ChatSession::new);
        if(owner.busy||owner.loading||!owner.outbox.entries.isEmpty()){notice("Сначала заверши задачу и освободи очередь этого чата.");return;}
        if(owner.removing)return;
        owner.removing=true;changed();
        request(method,j("threadId",id),r->{
            chats.remove(id);
            if(pins.remove(id)!=null)savePins();
            for(int i=threads.length()-1;i>=0;i--)if(id.equals(threads.optJSONObject(i).optString("id")))threads.remove(i);
            if(chat==owner)newThread();
            changed();refreshThreads();
        },error->{owner.removing=false;notice(error);});
    }
    private void loadTurns(ChatSession owner,String cursor){
        JSONObject params=j("threadId",owner.id,"itemsView","full","limit",50,"sortDirection","asc");
        if(cursor!=null)try{params.put("cursor",cursor);}catch(JSONException ignored){}
        request("thread/turns/list",params,r->{
            JSONArray turns=r.optJSONArray("data");
            if(turns!=null)for(int i=0;i<turns.length();i++){
                JSONArray items=turns.optJSONObject(i).optJSONArray("items");
                if(items!=null)for(int k=0;k<items.length();k++)showItem(owner,items.optJSONObject(k),true,true);
            }
            String next=r.isNull("nextCursor")?"":r.optString("nextCursor");
            if(next.isEmpty()||next.equals(cursor))finishHistory(owner);
            else loadTurns(owner,next);
        },error->{finishHistory(owner);notice(owner,error);});
    }
    private void finishHistory(ChatSession owner){
        owner.history.putAll(owner.messages);
        while(owner.history.size()>250)owner.history.remove(owner.history.keySet().iterator().next());
        owner.messages=owner.history;owner.history=null;owner.loading=false;changed();drainOutbox(owner);
    }
    void interrupt(){interrupt(chat);}
    void interruptApproval(JSONObject req){interrupt(chats.get(req.optJSONObject("params").optString("threadId")));}
    String approvalTitle(JSONObject req){
        ChatSession owner=chats.get(req.optJSONObject("params").optString("threadId"));
        return owner==null?"Чат":owner.title;
    }
    Message approvalMessage(JSONObject req){
        JSONObject p=req.optJSONObject("params");ChatSession owner=chats.get(p.optString("threadId"));
        return owner==null?null:owner.messages.get(p.optString("itemId"));
    }
    private void interrupt(ChatSession owner){
        if(owner==null||!owner.busy)return;
        owner.stopRequested=true;owner.outbox.paused=true;saveOutbox();changed();
        if(owner.turnId.isEmpty()||owner.interruptSent)return;
        owner.interruptSent=true;
        request("turn/interrupt",j("threadId",owner.id,"turnId",owner.turnId),r->{},error->{
            owner.stopRequested=false;owner.interruptSent=false;notice(owner,error);
        });
    }
    void answer(JSONObject req,JSONObject result){
        approvals.remove(req);
        send(j("id",req.opt("id"),"result",result));
        if(notifications!=null)notifications.update();
        changed();
    }
    void diagnostics() {
        if(!ready || chat.busy)return;
        if(remoteMode){
            request("command/exec",j("command",new JSONArray().put("powershell.exe").put("-NoProfile").put("-NonInteractive").put("-Command").put("Write-Output $env:COMPUTERNAME; whoami; Get-Location; $PSVersionTable.PSVersion.ToString()"),"cwd",threadOptions().optString("cwd"),"sandboxPolicy",j("type","dangerFullAccess"),"timeoutMs",15000),r->{String out=r.optString("stdout")+r.optString("stderr");getSharedPreferences("runtime",0).edit().putString("pcDiagnostics",out).putInt("pcDiagnosticExitCode",r.optInt("exitCode",-1)).apply();notice(out);});return;
        }
        request("command/exec",j("command",new JSONArray().put("/system/bin/sh").put("-c").put(
            "id; uname -r; getenforce; docker status; docker ps -a"),"cwd",BASE+"/workspace",
            "sandboxPolicy",j("type","dangerFullAccess"),"timeoutMs",15000),r->{
                String output=r.optString("stdout")+r.optString("stderr");
                getSharedPreferences("runtime",0).edit().putString("diagnostics",output).putInt("diagnosticExitCode",r.optInt("exitCode",-1)).apply();
                notice(output);
            });
    }
    @Override public void onDestroy() {
        if(tunnel!=null)tunnel.close();
        if(workerServers!=null)workerServers.close();
        if(phoneAccess!=null)phoneAccess.close();
        stopping=true;++engineGeneration;
        loginFlow.reset();getSharedPreferences("runtime",0).edit().putBoolean("authPending",false).apply();
        if(networkProxy!=null)networkProxy.close();
        if(remoteConnection!=null)remoteConnection.close();
        writer=null;wireIo.shutdownNow();attachmentIo.shutdownNow();
        if(process!=null)process.destroy();
        ready=false;super.onDestroy();
    }
}
