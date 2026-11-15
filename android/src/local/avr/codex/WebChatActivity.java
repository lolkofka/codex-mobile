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
    private JSONArray lastModels,lastThreads;
    private int lastThreadCount=-1;
    private final HashMap<String,String> sent=new HashMap<>();
    private JSONArray attachments=new JSONArray();
    private final java.util.concurrent.ExecutorService mediaIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int imports;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{attachments=new JSONArray(getPreferences(0).getString("attachments","[]"));}catch(JSONException ignored){}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("askedNotifications",false)){getPreferences(0).edit().putBoolean("askedNotifications",true).apply();requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},42);}
        root.removeAllViews();web=new WebView(this){
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){android.view.inputmethod.InputConnection base=super.onCreateInputConnection(info);if(base==null)return null;info.contentMimeTypes=new String[]{"image/*"};return new android.view.inputmethod.InputConnectionWrapper(base,false){@Override public boolean commitContent(android.view.inputmethod.InputContentInfo content,int flags,Bundle opts){if(!content.getDescription().hasMimeType("image/*"))return super.commitContent(content,flags,opts);try{if((flags&android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)!=0)content.requestPermission();main.post(()->importUris(Collections.singletonList(content.getContentUri()),content::releasePermission));return true;}catch(Exception e){return false;}}};}
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
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleShare(intent);}
    private void attachmentsToWeb(){getPreferences(0).edit().putString("attachments",attachments.toString()).apply();callJs("setAttachments",AgentService.j("images",attachments,"loading",imports>0));}
    private void importUris(List<Uri> uris,Runnable release){
        if(uris.isEmpty())return;imports++;attachmentsToWeb();
        mediaIo.execute(()->{ArrayList<JSONObject> added=new ArrayList<>();String error="";try{for(Uri uri:uris){if(added.size()>=6)break;added.add(MediaFiles.importUri(this,uri));}}catch(Exception e){error="Не удалось добавить фото: "+e.getMessage();}finally{if(release!=null)try{release.run();}catch(Exception ignored){}}
            String failure=error;main.post(()->{imports--;for(JSONObject image:added)if(attachments.length()<6)attachments.put(image);attachmentsToWeb();if(!failure.isEmpty())Toast.makeText(this,failure,Toast.LENGTH_LONG).show();});});
    }
    private List<Uri> clipUris(ClipData clip){ArrayList<Uri> list=new ArrayList<>();if(clip!=null)for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null&&"content".equals(uri.getScheme()))list.add(uri);}return list;}
    private void pickImages(){try{Intent i=Build.VERSION.SDK_INT>=33?new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*").putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX,6):new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,41);}catch(ActivityNotFoundException e){startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),41);}}
    private void pasteClipboard(){android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);ClipData clip=clipboard.getPrimaryClip();List<Uri> uris=clipUris(clip);if(!uris.isEmpty())importUris(uris,null);else if(clip!=null&&clip.getItemCount()>0)callJs("pasteText",AgentService.j("text",String.valueOf(clip.getItemAt(0).coerceToText(this))));else Toast.makeText(this,"В буфере нет изображения или текста",Toast.LENGTH_SHORT).show();}
    private void handleShare(Intent intent){if(intent==null)return;String action=intent.getAction();if(Intent.ACTION_SEND.equals(action)||Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> uris=new ArrayList<>();if(Intent.ACTION_SEND.equals(action)){Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null)uris.add(u);}else{ArrayList<Uri> a=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(a!=null)uris.addAll(a);}if(uris.isEmpty())uris.addAll(clipUris(intent.getClipData()));importUris(uris,null);intent.setAction(Intent.ACTION_MAIN);} }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41&&result==RESULT_OK&&data!=null){List<Uri> uris=clipUris(data.getClipData());if(uris.isEmpty()&&data.getData()!=null)uris.add(data.getData());importUris(uris,null);}}
    private void callJs(String function,JSONObject payload){if(uiReady&&web!=null)web.evaluateJavascript("window."+function+"("+payload.toString()+");",null);}
    private void draftToWeb(){if(input!=null)callJs("setNativeDraft",AgentService.j("text",input.getText().toString()));}
    @Override void updateSend(){if(!receivingDraft)draftToWeb();}
    @Override void render(){
        if(!uiReady||agent==null)return;
        String requested=getIntent().getStringExtra("notificationThread");
        if(requested!=null&&agent.ready&&!agent.busy&&agent.outbox.entries.isEmpty()){getIntent().removeExtra("notificationThread");if(!requested.isEmpty()&&!requested.equals(agent.threadId))agent.resume(requested);}
        String viewId=(agent.remoteMode?"pc:":"phone:")+agent.threadId;
        boolean replace=!renderedThread.equals(viewId);
        if(replace){sent.clear();renderedThread=viewId;renderedOrder="";}
        JSONArray updates=new JSONArray(),order=new JSONArray();
        HashSet<String> present=new HashSet<>();
        for(AgentService.Message message:agent.messages.values()){
            order.put(message.id);present.add(message.id);
            String signature=message.revision+"/"+message.kind+"/"+message.complete+"/"+message.phase+"/"+message.status;
            if(!signature.equals(sent.get(message.id))){
                updates.put(AgentService.j("id",message.id,"kind",message.kind,"text",message.text,"phase",message.phase,
                    "complete",message.complete,"status",message.status,"title",message.title,"durationMs",message.durationMs,"images",message.images));sent.put(message.id,signature);
            }
        }
        sent.keySet().retainAll(present);
        JSONObject packet=AgentService.j("replace",replace,"updates",updates,"ready",agent.ready,"busy",agent.busy,
            "loggedIn",agent.loggedIn,"loginPending",!agent.authUrl.isEmpty(),"account",agent.account,"status",agent.state,"model",agent.model,"effort",agent.effort,
            "hasMoreChats",!agent.threadCursor.isEmpty(),"title",agent.currentTitle,
            "outbox",agent.outboxState(),"queuePaused",agent.outbox.paused,"modelsLoading",agent.modelsLoading,"modelsError",agent.modelsError,
            "target",agent.remoteMode?"pc":"phone","pcConfigured",RemotePc.configured(this));
        try{
            if(lastModels!=agent.models){packet.put("models",agent.models);lastModels=agent.models;}
            if(lastThreads!=agent.threads||lastThreadCount!=agent.threads.length()){
                packet.put("threads",agent.threads);lastThreads=agent.threads;lastThreadCount=agent.threads.length();
            }
        }catch(JSONException ignored){}
        String currentOrder=order.toString();
        if(!currentOrder.equals(renderedOrder)){try{packet.put("order",order);}catch(JSONException ignored){}renderedOrder=currentOrder;}
        callJs("applyState",packet);
        voice.sync(agent.busy,agent.completedTurns,agent.lastAnswer,!agent.approvals.isEmpty());
        if(mute!=null)mute.setText(voice.muted()?"Включить микрофон":"Микрофон включён");
        if(authRequested&&!agent.authUrl.isEmpty()&&!openedAuth.equals(agent.authUrl)){
            openedAuth=agent.authUrl;authRequested=false;
            if(!agent.userCode.isEmpty()){
                copy(agent.userCode);new android.app.AlertDialog.Builder(this).setTitle("Код входа: "+agent.userCode)
                    .setMessage("Код скопирован. Введи его на сайте OpenAI.").setPositiveButton("Открыть",(d,w)->openAuth(agent.authUrl)).setNegativeButton("Позже",null).show();
            }else openAuth(agent.authUrl);
        }
        if(foreground&&!agent.approvals.isEmpty())showApproval(agent.approvals.get(0));
        if(agent.approvals.isEmpty()&&approvalDialog!=null){approvalDialog.dismiss();approvalDialog=null;shownApproval="";}
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
        File f=MediaFiles.shareCopy(this,path);Uri uri=MediaFiles.shareUri(this,f);Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,MediaFiles.mime(f.getName())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri("Файл",uri));main.post(()->{try{startActivity(Intent.createChooser(view,"Открыть файл"));}catch(Exception e){Toast.makeText(this,"Нет приложения для этого типа файла",Toast.LENGTH_LONG).show();}});
    }catch(Exception e){main.post(()->Toast.makeText(this,"Не удалось открыть файл: "+e.getMessage(),Toast.LENGTH_LONG).show());}});}
    private final class PhoneBridge {
        @JavascriptInterface public void post(String source){
            if(source==null||source.length()>200000)return;
            try{JSONObject payload=new JSONObject(source);main.post(()->action(payload));}catch(JSONException ignored){}
        }
    }
    private void action(JSONObject payload){
        String name=payload.optString("action"),value=payload.optString("value");
        if(name.equals("draft")){receivingDraft=true;input.setText(value);receivingDraft=false;return;}
        if(name.equals("pickImages")){pickImages();return;}
        if(name.equals("pasteClipboard")){pasteClipboard();return;}
        if(name.equals("removeAttachment")){JSONArray kept=new JSONArray();for(int i=0;i<attachments.length();i++)if(!attachments.optJSONObject(i).optString("id").equals(value))kept.put(attachments.opt(i));attachments=kept;attachmentsToWeb();return;}
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
            if(name.equals("send"))callJs("submissionResult",AgentService.j("accepted",false,"value",value));
            if(agent!=null&&name.equals("removeQueued"))agent.removeQueued(value);
            if(agent!=null&&name.equals("pauseQueue"))agent.pauseQueue(true);
            return;
        }
        switch(name){
            case "send":
                JSONArray ids=new JSONArray();for(int n=0;n<attachments.length();n++)ids.put(attachments.optJSONObject(n).optString("id"));
                boolean accepted=imports==0&&agent.submitText(value,payload.optString("mode","queue"),ids);
                if(accepted){attachments=new JSONArray();attachmentsToWeb();}
                if(accepted&&input.getText().toString().trim().equals(value.trim())){receivingDraft=true;input.setText("");receivingDraft=false;}
                callJs("submissionResult",AgentService.j("accepted",accepted,"value",value));break;
            case "removeQueued":agent.removeQueued(value);break;
            case "steerQueued":agent.steerQueued(value);break;
            case "pauseQueue":agent.pauseQueue(true);break;
            case "resumeQueue":agent.pauseQueue(false);break;
            case "refreshModels":agent.refreshModels();break;
            case "stopTurn":agent.interrupt();break;
            case "newChat":if(!agent.busy)agent.newThread();break;
            case "openChat":if(!agent.busy)agent.resume(value);break;
            case "searchChats":agent.queryThreads(value,false);break;
            case "moreChats":if(!agent.threadCursor.isEmpty())agent.queryThreads(agent.threadSearch,true);break;
            case "renameChat":agent.renameThread(payload.optString("id"),value);break;
            case "deleteChat":agent.deleteThread(value);break;
            case "archiveChat":agent.archiveThread(value);break;
            case "model":agent.selectModel(value);break;
            case "effort":agent.selectEffort(value);break;
            case "copyMessage":if(agent.messages.containsKey(value))copy(agent.messages.get(value).text);break;
            case "speakMessage":if(agent.messages.containsKey(value))voice.speak(agent.messages.get(value).text);break;
            case "diagnostics":agent.diagnostics();break;
            case "loginBrowser":authRequested=true;openedAuth="";agent.login(false);break;
            case "loginDevice":authRequested=true;openedAuth="";agent.login(true);break;
            case "continueLogin":authRequested=true;openedAuth="";render();break;
            case "logout":agent.logout();break;
            case "stopEngine":stopAgent();break;
        }
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
