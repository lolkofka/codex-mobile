package local.avr.codex;

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
    static final String BASE = "/data/adb/avr-codex";
    final Handler main = new Handler(Looper.getMainLooper());
    final ArrayList<Runnable> listeners = new ArrayList<>();
    final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    final ArrayList<JSONObject> approvals = new ArrayList<>();
    final ConcurrentHashMap<Long, Consumer<JSONObject>> callbacks = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long,String> requestMethods=new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long,Consumer<String>> failures=new ConcurrentHashMap<>();
    final TurnOutbox outbox=new TurnOutbox();
    boolean modelsLoading;
    String modelsError="";
    private boolean stopRequested;
    JSONArray threads = new JSONArray(), models = new JSONArray();
    String state = "Запускаю локальный движок…", account = "Войти с ChatGPT";
    String threadId = "", turnId = "", model = "", effort = "", authUrl = "", userCode = "";
    String currentTitle="Новый чат", threadCursor="", threadSearch="", lastAnswer="";
    int completedTurns;
    private String activeFinalAnswer="", activeLastAnswer="";
    private String loginId="";
    boolean ready, loggedIn, busy;
    int visibleClients;
    TaskNotifications notifications;
    private final Set<String> notifiedTurns=new HashSet<>(),loadingImages=new HashSet<>();
    private boolean changeQueued;
    long turnStartedAt;
    int textDeltaCount,reasoningDeltaCount;
    private long nextId = 1;
    private Process process;
    private BufferedWriter writer;
    private boolean stopping;
    boolean remoteMode;
    private volatile int engineGeneration;
    private volatile RemotePc remoteConnection;
    private final IBinder binder = new LocalBinder();

    public final class LocalBinder extends Binder { AgentService get() { return AgentService.this; } }
    static final class Message {
        final String id;
        String kind,text,phase="",status="",title="";
        boolean complete;
        JSONArray images=new JSONArray();
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
        restoreOutbox();
        notifications=new TaskNotifications(this);startForeground(1,notifications.ongoing());
        startEngine();
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null && "STOP".equals(i.getAction())) stopSelf();
        if(i!=null && "INTERRUPT".equals(i.getAction())&&busy)interrupt();
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
        if(ready&&loggedIn&&!busy){newThread();sendText("Проверка приложения: коротко сообщай о ходе работы, выполни только id, uname -r и docker ps -a через инструменты. Ничего не изменяй. Затем кратко подведи итог.");}
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
    void add(String id,String kind,String text) {
        if(text.length()>48000) text=text.substring(0,48000)+"\n…вывод сокращён в интерфейсе";
        Message m=messages.get(id);
        if(m==null){m=new Message(id,kind,text);messages.put(id,m);}else{m.text=text;m.kind=kind;}
        m.revision++;
        while(messages.size()>250) messages.remove(messages.keySet().iterator().next());
        changed();
    }
    void notice(String text) { add("notice-"+System.nanoTime(),"notice",text); }
    void startEngine() {
        final int generation=++engineGeneration;
        final boolean pc=remoteMode;
        stopping=false; ready=false; state=pc?"Подключаюсь к ПК по SSH…":"Запускаю локальный движок…"; changed();
        new Thread(()->{
            Process child=null;RemotePc remote=null;
            try {
                final InputStream input;
                final OutputStream output;
                if(pc){
                    remote=RemotePc.connect(this);
                    if(generation!=engineGeneration){remote.close();return;}
                    remoteConnection=remote;input=remote.input;output=remote.output;
                }else{
                    child=new ProcessBuilder("su","-c","/system/bin/sh "+BASE+"/run").start();
                    if(generation!=engineGeneration){child.destroy();return;}
                    process=child;input=child.getInputStream();output=child.getOutputStream();
                }
                final Process local=child;
                writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
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
                main.post(()->{if(generation!=engineGeneration)return;request("initialize",j("clientInfo",j("name","avr_codex_mobile","title","Codex Mobile AVR","version","0.6.0")),r->{
                    notifyServer("initialized",j()); ready=true; state=readyLabel();if(notifications!=null)notifications.update();
                    getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",pc).putString("connectionError","").apply();
                    readAccount(); refreshThreads(); refreshModels();
                    String last=getSharedPreferences("settings",0).getString(lastThreadKey(),"");
                    if(!outbox.entries.isEmpty()&&!outbox.entries.get(0).thread.isEmpty())last=outbox.entries.get(0).thread;
                    if(!last.isEmpty())resume(last);
                    changed();
                });});
                try(BufferedReader r=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                    String line;
                    while((line=r.readLine())!=null) {
                        try { JSONObject msg=new JSONObject(line); main.post(()->{if(generation==engineGeneration)handle(msg);}); }
                        catch(JSONException ignored) {}
                    }
                }
                if(child!=null)child.waitFor();
                main.post(()->{if(generation!=engineGeneration)return;ready=false;busy=false;turnId="";writer=null;connectionLost();state=pc?"Соединение с ПК закрыто":"Движок остановлен";changed();});
            } catch(Exception e) {
                main.post(()->{if(generation!=engineGeneration)return;ready=false;busy=false;turnId="";writer=null;connectionLost();
                    state=pc?"Ошибка подключения к ПК":"Не удалось запустить: проверь root-доступ";
                    String detail=pc?e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()).replaceAll("https?://\\S+","<url>"):e.toString();
                    getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",false).putString("connectionError",detail).apply();
                    notice(pc?"ПК недоступен. Проверь адрес, Wi-Fi и разрешение SSH. "+detail:detail);changed();});
            }finally{if(remote!=null)remote.close();}
        },"codex-stdio").start();
    }
    String readyLabel(){return remoteMode?"Готов · команды на ПК":"Готов · root на телефоне";}
    String lastThreadKey(){return remoteMode?"lastThreadPc":"lastThread";}
    void switchTarget(boolean pc){
        if(busy||!approvals.isEmpty()||!outbox.entries.isEmpty()){notice("Сначала заверши задачу и отправь или удали сообщения из очереди.");return;}
        if(pc&&!RemotePc.configured(this)){notice("Сначала настрой подключение ПК в меню устройств.");return;}
        ++engineGeneration;
        try{if(writer!=null)writer.close();}catch(IOException ignored){}writer=null;
        if(remoteConnection!=null){remoteConnection.close();remoteConnection=null;}
        if(process!=null){process.destroy();process=null;}
        callbacks.clear();failures.clear();requestMethods.clear();messages.clear();approvals.clear();models=new JSONArray();threads=new JSONArray();modelsLoading=false;modelsError="";
        remoteMode=pc;ready=false;loggedIn=false;account="";threadId="";turnId="";threadCursor="";threadSearch="";currentTitle="Новый чат";
        authUrl="";userCode="";loginId="";
        getSharedPreferences("settings",0).edit().putBoolean("remoteMode",pc).apply();
        getSharedPreferences("runtime",0).edit().putBoolean("connectedToPc",false).apply();
        restoreOutbox();
        startEngine();
    }
    synchronized void send(JSONObject msg) {
        try { if(writer==null) throw new IOException("Движок ещё не запущен"); writer.write(msg.toString());writer.newLine();writer.flush(); }
        catch(IOException e) { main.post(()->notice(e.getMessage())); }
    }
    void request(String method,JSONObject params,Consumer<JSONObject> callback) {
        request(method,params,callback,error->notice(error));
    }
    void request(String method,JSONObject params,Consumer<JSONObject> callback,Consumer<String> failure) {
        long id=nextId++;callbacks.put(id,callback);requestMethods.put(id,method);failures.put(id,failure);
        try{
            if(writer==null)throw new IOException("Движок не подключён");
            writer.write(j("id",id,"method",method,"params",params).toString());writer.newLine();writer.flush();
        }catch(IOException e){callbacks.remove(id);requestMethods.remove(id);failures.remove(id);failure.accept("Не удалось отправить запрос: "+e.getMessage());}
    }
    void notifyServer(String method,JSONObject params) { send(j("method",method,"params",params)); }
    void handle(JSONObject m) {
        if(m.has("id") && !m.has("method")) {
            Consumer<JSONObject> callback=callbacks.remove(m.optLong("id"));
            String requestMethod=requestMethods.remove(m.optLong("id"));
            Consumer<String> failure=failures.remove(m.optLong("id"));
            if(m.has("error")) {
                state="Ошибка запроса";
                if("turn/start".equals(requestMethod)||"thread/start".equals(requestMethod))busy=false;
                String error=m.optJSONObject("error").optString("message","Ошибка Codex");if(failure!=null)failure.accept(error);else notice(error);
                if("account/login/start".equals(requestMethod))getSharedPreferences("runtime",0).edit().putBoolean("authPending",false).putString("lastLoginError",error.replaceAll("https?://\\S+","<url>")).apply();
            }
            else if(callback!=null) callback.accept(m.optJSONObject("result"));
            changed(); return;
        }
        String method=m.optString("method"); JSONObject p=m.optJSONObject("params"); if(p==null) p=j();
        if(m.has("id")) {
            if(method.equals("item/commandExecution/requestApproval") || method.equals("item/fileChange/requestApproval") || method.equals("item/tool/requestUserInput")) {
                approvals.add(m);if(notifications!=null)notifications.update();changed();return;
            }
            send(j("id",m.opt("id"),"error",j("code",-32601,"message","This mobile client does not support this request")));return;
        }
        if(method.equals("account/login/completed")) {
            String completedId=p.optString("loginId");
            if(!loginId.isEmpty()&&!completedId.isEmpty()&&!completedId.equals(loginId))return;
            loginId="";
            authUrl="";userCode="";
            getSharedPreferences("runtime",0).edit().putBoolean("authPending",false)
                .putString("lastLoginError",p.optBoolean("success")?"":p.optString("error","").replaceAll("https?://\\S+","<url>")).apply();
            if(p.optBoolean("success")) { readAccount();refreshModels();state="Вход выполнен"; }
            else notice("Вход не завершён: "+p.optString("error","попробуй снова"));
        } else if(method.equals("account/updated")) readAccount();
        else if(method.equals("turn/started")) {
            JSONObject turn=p.optJSONObject("turn");if(turn!=null)turnId=turn.optString("id");busy=true;state="Codex работает…";
            activeFinalAnswer="";activeLastAnswer="";
            turnStartedAt=SystemClock.elapsedRealtime();textDeltaCount=0;reasoningDeltaCount=0;
            if(stopRequested)interrupt();else drainOutbox();
            if(notifications!=null)notifications.update();
        } else if(method.equals("turn/completed")) {
            busy=false;turnId="";state=readyLabel();refreshThreads();
            lastAnswer=activeFinalAnswer.isEmpty()?activeLastAnswer:activeFinalAnswer;completedTurns++;
            getSharedPreferences("runtime",0).edit().putInt("textDeltaCount",textDeltaCount).putInt("reasoningDeltaCount",reasoningDeltaCount).putLong("lastTurnDurationMs",SystemClock.elapsedRealtime()-turnStartedAt).apply();
            JSONObject turn=p.optJSONObject("turn");
            if(notifications!=null&&turn!=null){String id=turn.optString("id");if(!id.isEmpty()&&notifiedTurns.add(id)){if(notifiedTurns.size()>100)notifiedTurns.clear();notifications.result(id,turn.optString("status"),lastAnswer);}notifications.update();}
            if(turn!=null && turn.optJSONObject("error")!=null) notice(turn.optJSONObject("error").optString("message"));
            if(stopRequested||turn==null||!turn.optString("status").equals("completed")){outbox.paused=true;saveOutbox();}
            stopRequested=false;
            main.post(this::drainOutbox);
        } else if(method.equals("item/started") || method.equals("item/completed")) {
            JSONObject item=p.optJSONObject("item");if(item!=null)showItem(item,false,method.equals("item/completed"));
        } else if(method.equals("item/agentMessage/delta") || method.equals("item/commandExecution/outputDelta") || method.equals("item/plan/delta")) {
            String id=p.optString("itemId"); Message old=messages.get(id);
            add(id,method.contains("commandExecution")?"command":"assistant",(old==null?"":old.text)+p.optString("delta"));
            if(method.equals("item/agentMessage/delta"))textDeltaCount++;
        } else if(method.equals("item/reasoning/summaryTextDelta")) {
            String id=p.optString("itemId");Message old=messages.get(id);
            add(id,"reasoning",(old==null?"":old.text)+p.optString("delta"));
            reasoningDeltaCount++;
        } else if(method.equals("item/reasoning/summaryPartAdded")) {
            String id=p.optString("itemId");Message old=messages.get(id);if(old!=null&&!old.text.isEmpty())add(id,"reasoning",old.text+"\n\n");
        } else if(method.equals("turn/plan/updated")) {
            JSONArray plan=p.optJSONArray("plan");if(plan!=null)add(p.optString("turnId")+"-plan","plan",plan.toString());
        } else if(method.equals("item/mcpToolCall/progress")) {
            String id=p.optString("itemId");Message old=messages.get(id);if(old!=null)add(id,"command",old.text+"\n"+p.optString("message",p.optString("text")));
        } else if(method.equals("error")) {
            JSONObject error=p.optJSONObject("error");if(error!=null)notice(error.optString("message"));
        } else if(method.equals("serverRequest/resolved")) {
            Object id=p.opt("requestId"); approvals.removeIf(a->String.valueOf(a.opt("id")).equals(String.valueOf(id)));
        } else if(method.equals("warning")){notice(p.optString("message"));}
        else return;
        changed();
    }
    void showItem(JSONObject item,boolean history){showItem(item,history,history);}
    void showItem(JSONObject item,boolean history,boolean done) {
        String type=item.optString("type"), id=item.optString("id",UUID.randomUUID().toString());
        if(type.equals("agentMessage") || type.equals("plan")) {
            add(id,"assistant",item.optString("text"));
            if(type.equals("agentMessage") && !history) {
                activeLastAnswer=item.optString("text");
                if(item.optString("phase").equals("final_answer"))activeFinalAnswer=activeLastAnswer;
            }
        }
        else if(type.equals("commandExecution")) add(id,"command","$ "+item.optString("command")+"\n"+item.optString("aggregatedOutput")+
            (item.has("exitCode")&&!item.isNull("exitCode")?"\n[exit "+item.optInt("exitCode")+"]":""));
        else if(type.equals("fileChange")) add(id,"command","Изменения файлов\n"+item.optJSONArray("changes"));
        else if(type.equals("imageGeneration")) {
            add(id,"image",done?"Изображение готово":"Генерирую изображение…");
            JSONObject failure=item.optJSONObject("failure");
            if(failure!=null){add(id,"notice","Генерация недоступна: "+failure.optString("type"));}
            else if(done&&messages.get(id).images.length()==0&&!loadingImages.contains(id))loadGeneratedImage(id,item);
        }
        else if(type.equals("userMessage") && history) {
            JSONArray content=item.optJSONArray("content");StringBuilder text=new StringBuilder();
            if(content!=null)for(int i=0;i<content.length();i++) {JSONObject c=content.optJSONObject(i);if(c!=null)text.append(c.optString("text"));}
            add(id,"user",text.toString());
            if(content!=null)for(int i=0;i<content.length();i++){JSONObject c=content.optJSONObject(i);if(c!=null&&c.optString("type").equals("localImage")){try{File f=new File(c.optString("path"));if(f.getParentFile()!=null&&f.getParentFile().getCanonicalFile().equals(MediaFiles.directory(this).getCanonicalFile()))messages.get(id).images.put(MediaFiles.describe(MediaFiles.image(this,f.getName())));}catch(Exception ignored){}}}
        } else if(type.equals("reasoning")) {
            JSONArray summary=item.optJSONArray("summary");StringBuilder text=new StringBuilder();
            if(summary!=null)for(int i=0;i<summary.length();i++){
                Object part=summary.opt(i);String value=part instanceof JSONObject?((JSONObject)part).optString("text"):String.valueOf(part);
                if(!value.isEmpty()){if(text.length()>0)text.append("\n\n");text.append(value);}
            }
            Message previous=messages.get(id);
            add(id,"reasoning",text.length()>0?text.toString():previous==null?"":previous.text);
        } else if(type.equals("mcpToolCall")) add(id,"command",item.optString("server")+" / "+item.optString("tool")+"\n"+item.optString("status")+"\n"+item.optString("result"));
        else if(type.equals("dynamicToolCall"))add(id,"command",item.optString("tool")+"\n"+item.optString("arguments")+"\n"+item.optString("contentItems"));
        else if(type.equals("webSearch"))add(id,"command",item.optString("query"));
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
    private void loadGeneratedImage(String id,JSONObject item){
        loadingImages.add(id);String owner=threadId;
        new Thread(()->{try{
            String result=item.optString("result"),path=item.optString("savedPath");byte[] bytes;
            if(!result.isEmpty()){if(result.startsWith("data:"))result=result.substring(result.indexOf(',')+1);if(result.length()>36*1024*1024)throw new IOException("Изображение слишком большое");bytes=android.util.Base64.decode(result,android.util.Base64.DEFAULT);}
            else if(!path.isEmpty()&&!path.equals("null")&&!remoteMode)bytes=MediaFiles.rootRead(MediaFiles.localPath(path),25*1024*1024);
            else throw new IOException("Движок не передал доступный файл изображения");
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0)throw new IOException("Некорректное изображение");
            String extension="image/jpeg".equals(options.outMimeType)?".jpg":".png";
            File f=new File(MediaFiles.directory(this),UUID.nameUUIDFromBytes((owner+"/"+id).getBytes(StandardCharsets.UTF_8))+extension);java.nio.file.Files.write(f.toPath(),bytes);
            main.post(()->{loadingImages.remove(id);Message m=messages.get(id);if(m!=null&&owner.equals(threadId)){m.images=new JSONArray().put(MediaFiles.describe(f));m.revision++;changed();}});
        }catch(Exception e){main.post(()->{loadingImages.remove(id);if(owner.equals(threadId)){add(id,"notice","Не удалось показать изображение: "+e.getMessage());}});}},"generated-image").start();
    }
    void readAccount() {
        request("account/read",j("refreshToken",false),r->{
            JSONObject a=r.optJSONObject("account");loggedIn=a!=null;
            getSharedPreferences("runtime",0).edit().putBoolean("loggedIn",loggedIn).apply();
            account=a==null?"Войти с ChatGPT":a.optString("email","ChatGPT")+" · "+a.optString("planType","");changed();
        });
    }
    void refreshThreads() { queryThreads(threadSearch,false); }
    void queryThreads(String search,boolean more) {
        threadSearch=search;JSONObject p=j("limit",50,"sortDirection","desc");
        try { if(!search.trim().isEmpty())p.put("searchTerm",search.trim());if(more&&!threadCursor.isEmpty())p.put("cursor",threadCursor); }
        catch(JSONException ignored){}
        request("thread/list",p,r->{
            if(!search.equals(threadSearch))return;
            JSONArray data=r.optJSONArray("data");if(data==null)data=new JSONArray();
            if(remoteMode){JSONArray own=new JSONArray();Set<String> ids=getSharedPreferences("settings",0).getStringSet("pcThreads",Collections.emptySet());for(int i=0;i<data.length();i++)if(ids.contains(data.optJSONObject(i).optString("id")))own.put(data.optJSONObject(i));data=own;}
            if(more){for(int i=0;i<data.length();i++)threads.put(data.opt(i));}else threads=data;
            threadCursor=r.isNull("nextCursor")?"":r.optString("nextCursor");changed();
        });
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
        if(!loginId.isEmpty()){
            String previous=loginId;loginId="";authUrl="";userCode="";
            request("account/login/cancel",j("loginId",previous),r->beginLogin(device));return;
        }
        beginLogin(device);
    }
    private void beginLogin(boolean device) {
        state="Подготавливаю вход…";changed();
        request("account/login/start",device?j("type","chatgptDeviceCode"):j("type","chatgpt","useHostedLoginSuccessPage",true,"appBrand","codex"),r->{
            loginId=r.optString("loginId");
            authUrl=r.optString("authUrl",r.optString("verificationUrl"));userCode=r.optString("userCode");state="Заверши вход в браузере";changed();
            getSharedPreferences("runtime",0).edit().putBoolean("authPending",true).putString("lastLoginError","").apply();
        });
    }
    void logout() { if(remoteMode){notice("Выход из аккаунта ПК выполняется на самом ПК.");return;} request("account/logout",j(),r->{loggedIn=false;account="Войти с ChatGPT";changed();}); }
    JSONObject threadOptions() {
        JSONObject p;
        if(remoteMode){
            String cwd;try{cwd=RemotePc.profile(this).getString("cwd");}catch(Exception e){throw new IllegalStateException("Не настроена рабочая папка ПК");}
            p=j("cwd",cwd,"approvalPolicy","on-request","sandbox","workspace-write",
                "developerInstructions","Ты работаешь на ПК владельца с Windows через личный мобильный клиент. Команды выполняются на ПК, а не на Android. Учитывай реальную ОС и PowerShell. Сохраняй пользовательские данные; для опасных изменений сначала получи явное разрешение. Не изменяй загрузчик или прошивку подключенного телефона без отдельной команды.");
        }else p=j("cwd",BASE+"/workspace","approvalPolicy","on-request","sandbox","danger-full-access","developerInstructions","Это личный мобильный Codex на Android. Для создания и редактирования картинок используй встроенный image_gen.imagegen, если он доступен в этой сессии; показывай фактический результат и сообщай о недоступности или лимите сервиса, не подменяй генерацию описанием. Ссылки на файлы оформляй как [название](/абсолютный/путь), пути с пробелами как [название](</абсолютный/путь с пробелами>); веб-ссылки оформляй с https://. Не выдавай локальный путь за веб-адрес.");
        try{p.put("config",j("features",j("image_generation",true)));}catch(JSONException ignored){}
        if(!model.isEmpty())try{p.put("model",model);}catch(JSONException ignored){}return p;
    }
    void newThread() { if(busy)return;if(!outbox.entries.isEmpty()){notice("Сначала отправь или удали сообщения из очереди.");return;}threadId="";currentTitle="Новый чат";messages.clear();getSharedPreferences("settings",0).edit().remove(lastThreadKey()).apply();changed(); }
    void sendText(String text) {
        submitText(text,"queue");
    }
    boolean submitText(String text,String mode){
        return submitText(text,mode,new JSONArray());
    }
    boolean submitText(String text,String mode,JSONArray images){
        text=text.trim();if(text.isEmpty()&&images.length()==0)return false;
        try{if(images.length()>6)throw new IOException("Можно прикрепить до 6 изображений");for(int i=0;i<images.length();i++)MediaFiles.image(this,images.getString(i));}catch(Exception e){notice(e.getMessage());return false;}
        if(!ready){notice("Дождись подключения движка; текст сохранён в поле ввода.");return false;}
        if(!loggedIn){notice("Сначала войди через аккаунт ChatGPT.");return false;}
        if(outbox.entries.size()>=50){notice("В очереди уже 50 сообщений.");return false;}
        if(!busy&&outbox.entries.isEmpty())outbox.paused=false;
        JSONObject selected=selectedModel();
        String useModel=model.isEmpty()&&selected!=null?selected.optString("model",selected.optString("id")):model;
        String useEffort=effort.isEmpty()&&selected!=null?selected.optString("defaultReasoningEffort"):effort;
        if(images.length()>0&&selected!=null){JSONArray modes=selected.optJSONArray("inputModalities");if(modes!=null&&!modes.toString().contains("\"image\"")){notice("Выбранная модель не поддерживает изображения.");return false;}}
        TurnOutbox.Entry added=outbox.add(text,threadId,"steer".equals(mode)?"steer":"queue",useModel,useEffort);added.images=images.toString();saveOutbox();changed();drainOutbox();return true;
    }
    private void drainOutbox(){
        if(!ready||!loggedIn||stopRequested)return;
        TurnOutbox.Entry e=outbox.next(busy,threadId,turnId);if(e==null)return;
        e.status="sending";saveOutbox();changed();
        if(busy){
            String expected=turnId;
            final JSONArray input;try{input=MediaFiles.input(this,e,remoteMode);}catch(Exception ex){outbox.failed(e,ex.getMessage());saveOutbox();changed();return;}
            request("turn/steer",j("threadId",threadId,"expectedTurnId",expected,"input",input),r->{
                accepted(e);drainOutbox();
            },error->{outbox.failed(e,"Уточнение не принято: "+error);saveOutbox();notice("Уточнение осталось в очереди. Его можно отправить повторно или удалить.");changed();});
            return;
        }
        startText(e);
    }
    private void accepted(TurnOutbox.Entry e){outbox.entries.remove(e);String id="local-"+e.id;add(id,"user",e.text);messages.get(id).images=MediaFiles.describeIds(this,e.images);messages.get(id).revision++;saveOutbox();changed();}
    private void startText(TurnOutbox.Entry e){
        busy=true;turnId="";stopRequested=false;state="Codex работает…";
        Consumer<String> fail=error->{busy=false;turnId="";stopRequested=false;outbox.failed(e,error);state="Ошибка запроса";saveOutbox();notice(error+"\nСообщение сохранено в очереди.");changed();};
        Consumer<String> start=id->{
            final JSONArray input;try{input=MediaFiles.input(this,e,remoteMode);}catch(Exception ex){fail.accept(ex.getMessage());return;}
            JSONObject p=j("threadId",id,"input",input);
            try{p.put("summary","detailed");}catch(JSONException ignored){}
            try{p.put("model",e.model.isEmpty()?JSONObject.NULL:e.model);p.put("effort",e.effort.isEmpty()?JSONObject.NULL:e.effort);}catch(JSONException ignored){}
            request("turn/start",p,r->{JSONObject t=r.optJSONObject("turn");if(t!=null)turnId=t.optString("id");accepted(e);if(stopRequested)interrupt();else drainOutbox();},fail);
        };
        if(threadId.isEmpty())request("thread/start",threadOptions(),r->{
            threadId=r.optJSONObject("thread").optString("id");outbox.bindThread(threadId);saveOutbox();rememberPcThread(threadId);currentTitle=e.text.length()>55?e.text.substring(0,55):e.text;
            getSharedPreferences("settings",0).edit().putString(lastThreadKey(),threadId).apply();start.accept(threadId);
        },fail);
        else start.accept(threadId);
    }
    JSONArray outboxState(){JSONArray a=new JSONArray();for(TurnOutbox.Entry e:outbox.entries)a.put(j("id",e.id,"text",e.text,"thread",e.thread,"mode",e.mode,"status",e.status,"error",e.error,"model",e.model,"effort",e.effort,"images",e.images));return a;}
    private void saveOutbox(){getSharedPreferences("outbox",0).edit().putString(remoteMode?"pc":"phone",outboxState().toString()).apply();}
    private void restoreOutbox(){try{JSONArray a=new JSONArray(getSharedPreferences("outbox",0).getString(remoteMode?"pc":"phone","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);TurnOutbox.Entry e=new TurnOutbox.Entry(o.getString("id"),o.getString("text"),o.optString("thread"),o.optString("mode","queue"),o.optString("model"),o.optString("effort"));e.images=o.optString("images","[]");e.status=o.optString("status","waiting");e.error=o.optString("error");outbox.entries.add(e);}outbox.recover();}catch(JSONException ignored){} }
    private void connectionLost(){modelsLoading=false;outbox.recover();saveOutbox();callbacks.clear();failures.clear();requestMethods.clear();}
    void removeQueued(String id){if(outbox.remove(id)){saveOutbox();changed();}}
    void steerQueued(String id){TurnOutbox.Entry e=outbox.get(id);if(e==null||e.status.equals("sending"))return;e.mode="steer";e.status="waiting";e.error="";saveOutbox();changed();drainOutbox();}
    void pauseQueue(boolean paused){outbox.paused=paused;if(!paused)for(TurnOutbox.Entry e:outbox.entries)if(e.status.equals("failed")){e.status="waiting";e.mode="queue";e.error="";}saveOutbox();changed();drainOutbox();}
    void rememberPcThread(String id){if(!remoteMode)return;Set<String> ids=new HashSet<>(getSharedPreferences("settings",0).getStringSet("pcThreads",Collections.emptySet()));ids.add(id);getSharedPreferences("settings",0).edit().putStringSet("pcThreads",ids).apply();}
    void resume(String id) {
        if(busy)return;
        if(!outbox.entries.isEmpty()&&!id.equals(outbox.entries.get(0).thread)){notice("Сначала отправь или удали сообщения из очереди.");return;}
        if(remoteMode&&!getSharedPreferences("settings",0).getStringSet("pcThreads",Collections.emptySet()).contains(id)){notice("Здесь открываются отдельные чаты Mobile на ПК.");return;}
        JSONObject p=threadOptions();try{p.put("threadId",id);}catch(JSONException ignored){}
        request("thread/resume",p,r->{
            threadId=id;messages.clear();JSONObject thread=r.optJSONObject("thread");
            if(thread!=null){currentTitle=thread.optString("name",thread.optString("preview","Чат"));if(currentTitle.equals("null")||currentTitle.isEmpty())currentTitle=thread.optString("preview","Чат");}
            getSharedPreferences("settings",0).edit().putString(lastThreadKey(),id).apply();loadTurns(id,null);
        });
    }
    void renameThread(String id,String name) {
        if(name.trim().isEmpty())return;
        request("thread/name/set",j("threadId",id,"name",name.trim()),r->{if(id.equals(threadId))currentTitle=name.trim();refreshThreads();});
    }
    void archiveThread(String id) {
        if(id.equals(threadId)&&(busy||!outbox.entries.isEmpty())){notice("Сначала заверши задачу и освободи очередь.");return;}
        request("thread/archive",j("threadId",id),r->{if(id.equals(threadId))newThread();refreshThreads();});
    }
    void deleteThread(String id) {
        if(id.equals(threadId)&&(busy||!outbox.entries.isEmpty())){notice("Сначала заверши задачу и освободи очередь.");return;}
        request("thread/delete",j("threadId",id),r->{if(id.equals(threadId))newThread();refreshThreads();});
    }
    void loadTurns(String id,String cursor) {
        JSONObject p=j("threadId",id,"itemsView","full","limit",50,"sortDirection","asc");if(cursor!=null)try{p.put("cursor",cursor);}catch(JSONException ignored){}
        request("thread/turns/list",p,r->{
            JSONArray turns=r.optJSONArray("data");
            if(turns!=null)for(int i=0;i<turns.length();i++) {
                JSONArray items=turns.optJSONObject(i).optJSONArray("items");
                if(items!=null)for(int k=0;k<items.length();k++)showItem(items.optJSONObject(k),true);
            }
            if(!r.isNull("nextCursor") && !r.optString("nextCursor").isEmpty())loadTurns(id,r.optString("nextCursor"));changed();
        });
    }
    void interrupt() {stopRequested=true;outbox.paused=true;saveOutbox();changed();if(!turnId.isEmpty())request("turn/interrupt",j("threadId",threadId,"turnId",turnId),r->{},error->{stopRequested=false;notice(error);}); }
    void answer(JSONObject req,JSONObject result) { approvals.remove(req);send(j("id",req.opt("id"),"result",result));changed(); }
    void diagnostics() {
        if(!ready || busy)return;
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
        stopping=true;++engineGeneration;
        if(remoteConnection!=null)remoteConnection.close();
        try{if(writer!=null)writer.close();}catch(IOException ignored){}
        if(process!=null)process.destroy();
        ready=false;busy=false;super.onDestroy();
    }
}
