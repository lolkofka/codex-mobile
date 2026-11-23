package local.avr.codex;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.util.*;

/** Bundled UI only: no remote page can access the native phone bridge. */
public class WebChatActivity extends ChatActivity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private WebView web;
    private boolean uiReady,receivingDraft;
    private String renderedThread="",renderedOrder="";
    private JSONArray lastModels;
    private String lastChats="";
    private final HashMap<String,String> sent=new HashMap<>();
    private final HashMap<String,Composer> composers=new HashMap<>();
    private final List<Uri> pendingShare=new ArrayList<>();
    private static final int PICK_IMAGES=41, PICK_FILES=43, MAX_ATTACHMENTS=6;
    private final java.util.concurrent.ExecutorService mediaIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private Composer composer;
    private String pickerKey="", composerOwner="";
    private boolean legacyComposerLoaded;

    private static final class Composer {
        String key, text="";
        JSONArray attachments=new JSONArray();
        int imports, reservedSlots;
        Composer(String key){this.key=key;}
    }

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null)pickerKey=saved.getString("pickerKey","");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("askedNotifications",false)){getPreferences(0).edit().putBoolean("askedNotifications",true).apply();requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},42);}
        root.removeAllViews();web=new WebView(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){android.view.inputmethod.InputConnection base=super.onCreateInputConnection(info);if(base==null)return null;info.contentMimeTypes=new String[]{"image/*"};return new android.view.inputmethod.InputConnectionWrapper(base,false){@Override public boolean commitContent(android.view.inputmethod.InputContentInfo content,int flags,Bundle opts){if(!content.getDescription().hasMimeType("image/*"))return super.commitContent(content,flags,opts);try{if((flags&android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)!=0)content.requestPermission();main.post(()->importUris(composer,Collections.singletonList(content.getContentUri()),false,content::releasePermission));return true;}catch(Exception e){return false;}}};}
        };web.setBackgroundColor(0xff212121);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setTextZoom(100);
        web.addJavascriptInterface(new PhoneBridge(),"Phone");
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri u=request.getUrl();String path=u.getPath();
                if(ORIGIN.equals(u.getScheme()+"://"+u.getHost())&&path!=null&&path.startsWith("/media/")){
                    try{File f=MediaFiles.image(WebChatActivity.this,path.substring(7));return new WebResourceResponse(MediaFiles.mime(f.getName()),null,new FileInputStream(f));}catch(Exception ignored){}
                }
                if(ORIGIN.equals(u.getScheme()+"://"+u.getHost())&&Arrays.asList("/ui/index.html","/ui/chat.css","/ui/chat.js").contains(path)){
                    try{return new WebResourceResponse(path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":"text/html","UTF-8",getAssets().open(path.substring(1)));}
                    catch(IOException ignored){}
                }
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(r.isForMainFrame()&&r.hasGesture())openExternal(r.getUrl().toString());return true;
            }
            @Override public void onPageFinished(WebView v,String url){if(url.equals(ORIGIN+"/ui/index.html")){uiReady=true;render();draftToWeb();attachmentsToWeb();}}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)getSharedPreferences("runtime",0).edit().putString("lastUiError",message.message().substring(0,Math.min(220,message.message().length()))).apply();
                return true;
            }
        });
        root.addView(web,new android.widget.LinearLayout.LayoutParams(-1,-1));web.loadUrl(ORIGIN+"/ui/index.html");
        handleShare(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShare(intent);render();}
    private Composer loadComposer(String key){
        Composer draft=composers.get(key);
        if(draft!=null)return draft;
        draft=new Composer(key);
        String saved=getPreferences(0).getString("composer:"+key,"");
        if(!saved.isEmpty()){
            try{
                JSONObject data=new JSONObject(saved);
                draft.text=data.optString("text");
                JSONArray stored=data.optJSONArray("attachments");if(stored!=null)draft.attachments=stored;
            }catch(JSONException ignored){}
        }
        composers.put(key,draft);
        return draft;
    }
    private void persist(Composer draft){
        getPreferences(0).edit().putString("composer:"+draft.key,AgentService.j("text",draft.text,"attachments",draft.attachments).toString()).apply();
    }
    private void selectComposer(AgentService.ChatSession chat){
        String target=agent.remoteMode?"pc:":"phone:";
        String key=target+chat.listId();
        if(composer!=null&&key.equals(composer.key)&&composerOwner.equals(chat.key))return;
        String previousKey=target+chat.draftId;
        if(!chat.draftId.isEmpty()&&!key.equals(previousKey)&&
            (composers.containsKey(previousKey)||getPreferences(0).contains("composer:"+previousKey))){
            Composer previous=loadComposer(previousKey);composers.remove(previousKey);
            getPreferences(0).edit().remove("composer:"+previousKey).apply();
            previous.key=key;composers.put(key,previous);persist(previous);
            if(pickerKey.equals(previousKey))pickerKey=key;
        }
        composer=loadComposer(key);composerOwner=chat.key;
        if(!legacyComposerLoaded&&agent.ready){
            legacyComposerLoaded=true;
            if(!getPreferences(0).contains("composer:"+key)){
                composer.text=getPreferences(0).getString("draft","");
                try{composer.attachments=new JSONArray(getPreferences(0).getString("attachments","[]"));}catch(JSONException ignored){}
                persist(composer);
            }
            getPreferences(0).edit().remove("draft").remove("attachments").apply();
        }
        receivingDraft=true;input.setText(composer.text);receivingDraft=false;
        draftToWeb();attachmentsToWeb();
        if(!pendingShare.isEmpty()){
            importUris(composer,new ArrayList<>(pendingShare),false,null);
            pendingShare.clear();
        }
    }
    private void attachmentsToWeb(){
        if(composer==null)return;
        callJs("setAttachments",AgentService.j("attachments",composer.attachments,"loading",composer.imports>0));
    }
    private void importUris(Composer owner,List<Uri> uris,boolean files,Runnable release){
        if(owner==null||uris.isEmpty()){if(release!=null)release.run();return;}
        int space=Math.min(uris.size(),MAX_ATTACHMENTS-owner.attachments.length()-owner.reservedSlots);
        if(space<=0){
            Toast.makeText(this,"Можно добавить до 6 вложений",Toast.LENGTH_SHORT).show();
            if(release!=null)release.run();return;
        }
        owner.imports++;owner.reservedSlots+=space;attachmentsToWeb();
        mediaIo.execute(()->{
            ArrayList<JSONObject> added=new ArrayList<>();
            String failure="";
            try{
                for(Uri uri:uris){
                    if(added.size()>=space){failure="Можно добавить до 6 вложений";break;}
                    try{added.add(files?AttachmentFiles.importUri(this,uri):MediaFiles.importUri(this,uri));}
                    catch(Exception e){failure="Не удалось добавить вложение: "+e.getMessage();}
                }
            }finally{if(release!=null)try{release.run();}catch(Exception ignored){}}
            String error=failure;
            main.post(()->{
                owner.imports--;owner.reservedSlots-=space;
                for(JSONObject attachment:added)owner.attachments.put(attachment);
                persist(owner);
                if(composer==owner)attachmentsToWeb();
                if(!error.isEmpty())Toast.makeText(this,error,Toast.LENGTH_LONG).show();
            });
        });
    }
    private List<Uri> clipUris(ClipData clip){
        ArrayList<Uri> list=new ArrayList<>();
        if(clip!=null)for(int i=0;i<clip.getItemCount();i++){
            Uri uri=clip.getItemAt(i).getUri();
            if(uri!=null&&"content".equals(uri.getScheme()))list.add(uri);
        }
        return list;
    }
    private void pickImages(){
        if(composer==null)return;
        pickerKey=composer.key;
        try{
            Intent intent=Build.VERSION.SDK_INT>=33
                ?new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*").putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX,6)
                :documentPicker("image/*");
            startActivityForResult(intent,PICK_IMAGES);
        }catch(ActivityNotFoundException e){startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),PICK_IMAGES);}
    }
    private Intent documentPicker(String mime){
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
    }
    private void pickFiles(){
        if(composer==null)return;
        pickerKey=composer.key;
        try{startActivityForResult(documentPicker("*/*"),PICK_FILES);}
        catch(ActivityNotFoundException e){Toast.makeText(this,"Системный выбор файлов недоступен",Toast.LENGTH_SHORT).show();}
    }
    private void pasteClipboard(){
        android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
        ClipData clip=clipboard.getPrimaryClip();List<Uri> uris=clipUris(clip);
        if(!uris.isEmpty())importUris(composer,uris,false,null);
        else if(clip!=null&&clip.getItemCount()>0)callJs("pasteText",AgentService.j("text",String.valueOf(clip.getItemAt(0).coerceToText(this))));
        else Toast.makeText(this,"В буфере нет изображения или текста",Toast.LENGTH_SHORT).show();
    }
    private void handleShare(Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return;
        ArrayList<Uri> uris=new ArrayList<>();
        if(Intent.ACTION_SEND.equals(action)){Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}
        else{ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(list!=null)uris.addAll(list);}
        if(uris.isEmpty())uris.addAll(clipUris(intent.getClipData()));
        if(composer==null)pendingShare.addAll(uris);else importUris(composer,uris,false,null);
        intent.setAction(Intent.ACTION_MAIN);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putString("pickerKey",pickerKey);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_IMAGES&&request!=PICK_FILES)return;
        String key=pickerKey;pickerKey="";
        if(result!=RESULT_OK||data==null||key.isEmpty())return;
        List<Uri> uris=clipUris(data.getClipData());
        if(uris.isEmpty()&&data.getData()!=null)uris.add(data.getData());
        importUris(loadComposer(key),uris,request==PICK_FILES,null);
    }
    private void callJs(String function,JSONObject payload){if(uiReady&&web!=null)web.evaluateJavascript("window."+function+"("+payload.toString()+");",null);}
    private void draftToWeb(){if(input!=null)callJs("setNativeDraft",AgentService.j("text",input.getText().toString()));}
    @Override void updateSend(){
        if(receivingDraft)return;
        if(composer!=null){composer.text=input.getText().toString();persist(composer);}
        draftToWeb();
    }
    @Override public void onPause(){
        super.onPause();
        if(legacyComposerLoaded)getPreferences(0).edit().remove("draft").apply();
    }
    @Override void render(){
        if(!uiReady||agent==null)return;
        String requested=getIntent().getStringExtra("notificationThread");
        if(requested!=null&&agent.ready){getIntent().removeExtra("notificationThread");if(!requested.isEmpty()&&!requested.equals(agent.chat.listId()))agent.resume(requested);}
        if(agent.ready)selectComposer(agent.chat);
        AgentService.ChatSession chat=agent.chat;
        String viewId=chat.key;
        boolean replace=!renderedThread.equals(viewId);
        if(replace){sent.clear();renderedThread=viewId;renderedOrder="";}
        JSONArray updates=new JSONArray(),order=new JSONArray();
        HashSet<String> present=new HashSet<>();
        for(AgentService.Message message:chat.messages.values()){
            order.put(message.id);present.add(message.id);
            String signature=message.revision+"/"+message.kind+"/"+message.complete+"/"+message.phase+"/"+message.status;
            if(!signature.equals(sent.get(message.id))){
                updates.put(AgentService.j("id",message.id,"kind",message.kind,"text",message.text,"phase",message.phase,
                    "complete",message.complete,"status",message.status,"title",message.title,"durationMs",message.durationMs,"images",message.images,"files",message.files));sent.put(message.id,signature);
            }
        }
        sent.keySet().retainAll(present);
        JSONObject packet=AgentService.j("replace",replace,"updates",updates,"ready",agent.ready,"busy",chat.busy,"chatKey",chat.key,"chatId",chat.listId(),"chatLoading",chat.loading||chat.removing||(!chat.id.isEmpty()&&!chat.loaded),"busyCount",agent.busyCount(),
            "loggedIn",agent.loggedIn,"loginPending",agent.loginFlow.waiting(),"loginWorking",agent.loginFlow.working(),"loginError",agent.loginError,"account",agent.account,"status",agent.state,"model",agent.model,"effort",agent.effort,
            "hasMoreChats",!agent.threadCursor.isEmpty(),"title",chat.title,
            "outbox",agent.outboxState(),"queuePaused",chat.outbox.paused,"modelsLoading",agent.modelsLoading,"modelsError",agent.modelsError,
            "target",agent.remoteMode?"pc":"phone","pcConfigured",RemotePc.configured(this));
        try{
            if(lastModels!=agent.models){packet.put("models",agent.models);lastModels=agent.models;}
            JSONArray chats=agent.chatList();
            String signature=chats.toString();
            if(!signature.equals(lastChats)){packet.put("threads",chats);lastChats=signature;}
        }catch(JSONException ignored){}
        String currentOrder=order.toString();
        if(!currentOrder.equals(renderedOrder)){try{packet.put("order",order);}catch(JSONException ignored){}renderedOrder=currentOrder;}
        callJs("applyState",packet);
        voice.sync(chat.busy,chat.completedTurns,chat.lastAnswer,agent.currentApproval()!=null);
        if(mute!=null)mute.setText(voice.muted()?"Включить микрофон":"Микрофон включён");
        showPendingLogin();
        renderApproval();
    }
    private void openExternal(String value){
        Uri uri=Uri.parse(value);
        if("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())){try{if(uri.getHost()==null)throw new IllegalArgumentException();startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception e){Toast.makeText(this,"Не удалось открыть браузер",Toast.LENGTH_LONG).show();}return;}
        if(agent!=null&&agent.remoteMode){Toast.makeText(this,"Это путь на ПК. Открой файл на ПК или попроси передать его на телефон.",Toast.LENGTH_LONG).show();return;}
        try{String path=MediaFiles.localPath(value);openLocalFile(path);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void openLocalFile(String path){mediaIo.execute(()->{try{
        Process p=new ProcessBuilder("su","-c","if [ -d "+MediaFiles.quote(path)+" ]; then printf 'DIRECTORY\\n'; ls -1Ap -- "+MediaFiles.quote(path)+" | head -n 300; fi").start();String list;try(InputStream in=p.getInputStream()){list=new String(MediaFiles.read(in,100000),java.nio.charset.StandardCharsets.UTF_8);}p.waitFor();
        if(list.startsWith("DIRECTORY\n")){String[] children=list.substring(10).split("\n");main.post(()->new android.app.AlertDialog.Builder(this).setTitle(path).setItems(children,(d,n)->openLocalFile(path.replaceAll("/+$","")+"/"+children[n])).setNegativeButton("Закрыть",null).show());return;}
        showSharedFile(MediaFiles.shareCopy(this,path));
    }catch(Exception e){main.post(()->Toast.makeText(this,"Не удалось открыть файл: "+e.getMessage(),Toast.LENGTH_LONG).show());}});}
    private final class PhoneBridge {
        @JavascriptInterface public void post(String source){
            if(source==null||source.length()>200000)return;
            try{JSONObject payload=new JSONObject(source);main.post(()->action(payload));}catch(JSONException ignored){}
        }
    }
    private void action(JSONObject payload){
        String name=payload.optString("action"),value=payload.optString("value");
        if(name.equals("draft")){
            if(!ownsComposer(payload))return;
            receivingDraft=true;input.setText(value);receivingDraft=false;
            composer.text=value;persist(composer);return;
        }
        if(name.equals("pickImages")){pickImages();return;}
        if(name.equals("pickFiles")){pickFiles();return;}
        if(name.equals("pasteClipboard")){pasteClipboard();return;}
        if(name.equals("removeAttachment")){
            if(!ownsComposer(payload))return;
            JSONArray kept=new JSONArray();
            for(int i=0;i<composer.attachments.length();i++)if(!composer.attachments.optJSONObject(i).optString("id").equals(value))kept.put(composer.attachments.opt(i));
            composer.attachments=kept;persist(composer);attachmentsToWeb();return;
        }
        if(name.equals("openAttachment")){
            mediaIo.execute(()->{
                try{showSharedFile(AttachmentFiles.shareCopy(this,value));}
                catch(IOException e){main.post(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}
            });return;
        }
        if(name.equals("openImage")){try{openLocalFile(MediaFiles.image(this,value).getAbsolutePath());}catch(Exception ignored){}return;}
        if(name.equals("notificationSettings")){startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));return;}
        if(name.equals("mic")){requestVoice(false);return;}
        if(name.equals("voice")){requestVoice(true);return;}
        if(name.equals("openLink")){openExternal(value);return;}
        if(name.equals("copyText")){copy(value);return;}
        if(name.equals("connectPhone")){if(agent!=null)agent.switchTarget(false);return;}
        if(name.equals("connectPc")){if(agent!=null)agent.switchTarget(true);return;}
        if(name.equals("pcSettings")){showPcSettings();return;}
        if(name.equals("metrics")){
            getSharedPreferences("runtime",0).edit().putInt("uiLiveTextPaints",payload.optInt("liveTextPaints"))
                .putInt("uiReasoningPaints",payload.optInt("reasoningPaints")).putInt("uiToolPaints",payload.optInt("toolPaints"))
                .putLong("uiFirstLivePaintAt",payload.optLong("firstLivePaintAt")).putLong("uiLastTurnFinishedAt",payload.optLong("lastTurnFinishedAt")).apply();return;
        }
        if(!available()){
            if(name.equals("send"))submissionResult(payload,false);
            if(agent!=null&&name.equals("removeQueued"))agent.removeQueued(value);
            if(agent!=null&&name.equals("pauseQueue"))agent.pauseQueue(true);
            return;
        }
        switch(name){
            case "send":
                if(!ownsComposer(payload)||composer.imports>0){submissionResult(payload,false);break;}
                JSONArray images=new JSONArray(), files=new JSONArray();
                for(int i=0;i<composer.attachments.length();i++){
                    JSONObject attachment=composer.attachments.optJSONObject(i);
                    (attachment.has("url")?images:files).put(attachment.optString("id"));
                }
                boolean accepted=agent.submitText(value,payload.optString("mode","queue"),images,files);
                if(accepted){
                    composer.attachments=new JSONArray();
                    if(composer.text.trim().equals(value.trim())){
                        composer.text="";receivingDraft=true;input.setText("");receivingDraft=false;
                    }
                    persist(composer);attachmentsToWeb();
                }
                submissionResult(payload,accepted);break;
            case "removeQueued":agent.removeQueued(value);break;
            case "steerQueued":agent.steerQueued(value);break;
            case "pauseQueue":agent.pauseQueue(true);break;
            case "resumeQueue":agent.pauseQueue(false);break;
            case "refreshModels":agent.refreshModels();break;
            case "stopTurn":agent.interrupt();break;
            case "newChat":agent.newThread();render();break;
            case "openChat":agent.resume(value);render();break;
            case "pinChat":agent.togglePin(value);break;
            case "searchChats":agent.queryThreads(value,false);break;
            case "moreChats":if(!agent.threadCursor.isEmpty())agent.queryThreads(agent.threadSearch,true);break;
            case "renameChat":agent.renameThread(payload.optString("id"),value);break;
            case "deleteChat":agent.deleteThread(value);break;
            case "archiveChat":agent.archiveThread(value);break;
            case "model":agent.selectModel(value);break;
            case "effort":agent.selectEffort(value);break;
            case "copyMessage":if(agent.chat.messages.containsKey(value))copy(agent.chat.messages.get(value).text);break;
            case "speakMessage":if(agent.chat.messages.containsKey(value))voice.speak(agent.chat.messages.get(value).text);break;
            case "diagnostics":agent.diagnostics();break;
            case "loginBrowser":authRequested=true;openedAuth="";agent.login(false);break;
            case "loginDevice":authRequested=true;openedAuth="";agent.login(true);break;
            case "restartLoginBrowser":authRequested=true;openedAuth="";agent.restartLogin(false);break;
            case "restartLoginDevice":authRequested=true;openedAuth="";agent.restartLogin(true);break;
            case "continueLogin":authRequested=true;openedAuth="";render();break;
            case "checkAccount":agent.readAccount(true);Toast.makeText(this,"Проверяю сохранённый вход…",Toast.LENGTH_SHORT).show();break;
            case "logout":agent.logout();break;
            case "stopEngine":stopAgent();break;
        }
    }
    private boolean ownsComposer(JSONObject payload){
        return composer!=null&&agent!=null&&composerOwner.equals(agent.chat.key)&&composerOwner.equals(payload.optString("chatKey"));
    }
    private void submissionResult(JSONObject payload,boolean accepted){
        callJs("submissionResult",AgentService.j("accepted",accepted,"value",payload.optString("value"),"chatKey",payload.optString("chatKey")));
    }
    private void showSharedFile(File file)throws IOException{
        Uri uri=MediaFiles.shareUri(this,file);
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,MediaFiles.mime(file.getName())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Файл",uri));
        main.post(()->{
            try{startActivity(Intent.createChooser(view,"Открыть файл"));}
            catch(Exception e){Toast.makeText(this,"Нет приложения для этого типа файла",Toast.LENGTH_LONG).show();}
        });
    }
    private void showPcSettings(){
        try{
            JSONObject profile=RemotePc.profile(this);
            android.widget.LinearLayout layout=new android.widget.LinearLayout(this);layout.setOrientation(1);layout.setPadding(36,16,36,16);
            TextView note=new TextView(this);note.setText("SSH с проверкой ключа компьютера. В другой сети нужен адрес ПК в личной VPN. Это отдельные чаты Mobile на ПК.");layout.addView(note);
            EditText host=new EditText(this);host.setSingleLine(true);host.setHint("Адрес ПК");host.setText(profile.getString("host"));layout.addView(host);
            EditText port=new EditText(this);port.setSingleLine(true);port.setInputType(2);port.setHint("Порт SSH");port.setText(String.valueOf(profile.getInt("port")));layout.addView(port);
            new android.app.AlertDialog.Builder(this).setTitle("Подключение ПК").setView(layout).setNegativeButton("Отмена",null)
                .setNeutralButton("Публичный ключ",(d,w)->{try{RemotePc.prepareIdentity(this);copy(new String(java.nio.file.Files.readAllBytes(new File(RemotePc.directory(this),"identity.pub").toPath()),java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"Публичный ключ скопирован",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Не удалось подготовить ключ",Toast.LENGTH_SHORT).show();}})
                .setPositiveButton("Сохранить",(d,w)->{
                    try{
                        String address=host.getText().toString().trim();int n=Integer.parseInt(port.getText().toString());
                        if(!address.matches("[A-Za-z0-9.:-]{1,253}")||n<1||n>65535)throw new IllegalArgumentException();
                        profile.put("host",address);profile.put("port",n);
                        java.nio.file.Files.write(new File(RemotePc.directory(this),"profile.json").toPath(),profile.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        Toast.makeText(this,"Адрес сохранён. Подключись к ПК в меню устройств.",Toast.LENGTH_LONG).show();render();
                    }catch(Exception e){Toast.makeText(this,"Проверь адрес и порт",Toast.LENGTH_SHORT).show();}
                }).show();
        }catch(Exception e){new android.app.AlertDialog.Builder(this).setTitle("ПК ещё не настроен").setMessage("Нужно один раз установить публичный ключ телефона на ПК и передать телефону проверенный ключ компьютера. Пароль аккаунта для этого не нужен.").setPositiveButton("Понятно",null).show();}
    }
    @Override public void onBackPressed(){if(uiReady)web.evaluateJavascript("window.handleBack()",result->{if(!"true".equals(result))super.onBackPressed();});else super.onBackPressed();}
    @Override public void onDestroy(){uiReady=false;mediaIo.shutdown();if(web!=null){web.removeJavascriptInterface("Phone");web.destroy();}super.onDestroy();}
}
