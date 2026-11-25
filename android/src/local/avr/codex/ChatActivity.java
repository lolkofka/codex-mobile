package local.avr.codex.copy;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Personal, native mobile frontend for the official local Codex app server. */
public class ChatActivity extends Activity {
    static final int BG=0xff171717,PANEL=0xff303030,TEXT=0xfff1f1f1,MUTED=0xffa4a4a4;
    AgentService agent;
    LinearLayout root,content,welcome;
    ScrollView scroll;
    EditText input;
    TextView status;
    Button title,effort,account;
    ImageButton send,mic,voiceButton;
    final HashMap<String,TextView> cards=new HashMap<>();
    final Handler main=new Handler(Looper.getMainLooper());
    boolean queued,bound,foreground,authRequested,pendingVoice;
    String shownApproval="",openedAuth="",lastThread="";
    Dialog drawer,voiceDialog;
    AlertDialog approvalDialog;
    ListView chatList;
    ArrayAdapter<String> chatAdapter;
    final ArrayList<JSONObject> shownChats=new ArrayList<>();
    VoiceController voice;
    TextView voiceState,voiceCaption;
    Button mute;
    OrbView orb;
    final Runnable observer=()->{
        if(queued)return;queued=true;
        main.postDelayed(()->{queued=false;if(!isFinishing())render();},24);
    };
    final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){agent=((AgentService.LocalBinder)b).get();if(foreground&&!agent.listeners.contains(observer)){agent.listeners.add(observer);agent.visibleClients++;}render();}
        public void onServiceDisconnected(ComponentName n){agent=null;status.setText("Движок отключён");}
    };
    int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    Button button(String s,Runnable action){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(TEXT);b.setMinHeight(dp(42));b.setMinimumWidth(0);
        b.setPadding(dp(14),0,dp(14),0);b.setBackground(shape(PANEL,22));b.setOnClickListener(v->action.run());return b;
    }
    ImageButton icon(String kind,String label,Runnable action){
        ImageButton b=new ImageButton(this);b.setImageDrawable(new Glyph(kind,TEXT));b.setPadding(dp(11),dp(11),dp(11),dp(11));b.setContentDescription(label);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),shape(Color.TRANSPARENT,24),null));b.setOnClickListener(v->action.run());return b;
    }
    void space(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    void pad(View v,int n){v.setPadding(dp(n),dp(n),dp(n),dp(n));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        voice=new VoiceController(this,new VoiceController.Listener(){
            public void transcript(String words,boolean spoken){
                if(spoken&&agent!=null){if(voiceCaption!=null)voiceCaption.setText(words);agent.sendText(words);}
                else{String before=input.getText().toString();input.setText(before.isEmpty()?words:before+" "+words);input.setSelection(input.length());}
            }
            public void state(String value){if(voiceState!=null)voiceState.setText(value);else if(foreground)status.setText(value);}
            public void level(float value){if(orb!=null)orb.level=value;}
        });
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());Insets ime=i.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return i;});setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(4),dp(10),dp(2));
        header.addView(icon("menu","Открыть историю чатов",this::showDrawer),new LinearLayout.LayoutParams(dp(44),dp(44)));
        title=button("Codex  ⌄",this::chooseModel);title.setTextSize(19);title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);title.setBackgroundColor(Color.TRANSPARENT);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        header.addView(icon("new","Новый чат",()->{if(available()){agent.newThread();resetCards();render();}}),new LinearLayout.LayoutParams(dp(44),dp(44)));
        account=button("Войти",this::accountMenu);account.setTextSize(12);account.setSingleLine(true);account.setContentDescription("Аккаунт ChatGPT");header.addView(account,new LinearLayout.LayoutParams(dp(76),dp(36)));root.addView(header);
        status=text("Подключаюсь к телефону…",11,MUTED);status.setPadding(dp(22),0,dp(22),dp(5));root.addView(status,new LinearLayout.LayoutParams(-1,dp(28)));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(18),dp(20),dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composerWrap=new LinearLayout(this);composerWrap.setOrientation(LinearLayout.VERTICAL);composerWrap.setPadding(dp(12),dp(6),dp(12),dp(8));
        LinearLayout composer=new LinearLayout(this);composer.setOrientation(LinearLayout.VERTICAL);composer.setPadding(dp(8),dp(4),dp(8),dp(6));composer.setBackground(shape(PANEL,27));
        input=new EditText(this);input.setHint("Спросить Codex");input.setHintTextColor(MUTED);input.setTextColor(TEXT);input.setTextSize(16);input.setMaxLines(7);input.setMinLines(1);input.setPadding(dp(12),dp(12),dp(12),dp(10));input.setBackgroundColor(Color.TRANSPARENT);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(getPreferences(0).getString("draft",""));composer.addView(input,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        effort=button("Размышление  ⌄",this::chooseEffort);effort.setTextSize(12);effort.setPadding(dp(12),0,dp(12),0);controls.addView(effort,new LinearLayout.LayoutParams(-2,dp(40)));
        controls.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        mic=icon("mic","Продиктовать сообщение",()->requestVoice(false));controls.addView(mic,new LinearLayout.LayoutParams(dp(42),dp(42)));
        voiceButton=icon("wave","Начать голосовой диалог",()->requestVoice(true));voiceButton.setBackground(shape(TEXT,23));voiceButton.setImageDrawable(new Glyph("wave",BG));controls.addView(voiceButton,new LinearLayout.LayoutParams(dp(42),dp(42)));
        send=icon("send","Отправить сообщение",this::send);send.setBackground(shape(TEXT,23));send.setImageDrawable(new Glyph("send",BG));controls.addView(send,new LinearLayout.LayoutParams(dp(42),dp(42)));composer.addView(controls);composerWrap.addView(composer);
        TextView note=text("На этом телефоне · root · Docker",10,0xff8f8f8f);note.setGravity(Gravity.CENTER);note.setPadding(0,dp(6),0,0);composerWrap.addView(note);root.addView(composerWrap);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateSend();}public void afterTextChanged(Editable e){}});updateSend();
        Intent service=new Intent(this,AgentService.class);startForegroundService(service);bound=bindService(service,connection,BIND_AUTO_CREATE);
    }
    boolean available(){return agent!=null&&agent.ready;}
    void updateSend(){boolean has=input.length()>0,busy=agent!=null&&agent.chat.busy;send.setVisibility(has||busy?View.VISIBLE:View.GONE);voiceButton.setVisibility(!has&&!busy?View.VISIBLE:View.GONE);send.setImageDrawable(new Glyph(busy&&!has?"stop":"send",BG));send.setEnabled(available());}
    void resetCards(){cards.clear();content.removeAllViews();welcome=null;}
    void render(){
        if(agent==null)return;status.setText(agent.state);account.setText(agent.loggedIn?"●":"Войти");title.setText((agent.model.isEmpty()?"Codex":agent.model)+"  ⌄");effort.setText("Думать: "+effortName(agent.effort)+"  ⌄");updateSend();
        if(!lastThread.equals(agent.chat.id)){lastThread=agent.chat.id;resetCards();}
        if(agent.chat.messages.isEmpty()){
            if(welcome==null){resetCards();welcome=new LinearLayout(this);welcome.setOrientation(LinearLayout.VERTICAL);welcome.setGravity(Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-1);content.addView(welcome,lp);
                TextView hero=text("Чем могу помочь?",27,TEXT);hero.setTypeface(null,Typeface.BOLD);hero.setGravity(Gravity.CENTER);welcome.addView(hero);space(welcome,16);
                TextView desc=text("Приложения, файлы и контейнеры\nна твоём телефоне",14,MUTED);desc.setGravity(Gravity.CENTER);desc.setLineSpacing(dp(3),1);welcome.addView(desc);space(welcome,28);
                Button test=button("Проверить root и Docker",()->agent.diagnostics());welcome.addView(test);space(welcome,10);
                Button sample=button("Показать мои контейнеры",()->{input.setText("Покажи запущенные Docker-контейнеры на этом телефоне.");input.requestFocus();});welcome.addView(sample);
            }
        }else{
            if(welcome!=null){content.removeView(welcome);welcome=null;}
            if(cards.keySet().stream().anyMatch(k->!agent.chat.messages.containsKey(k)))resetCards();
            boolean bottom=scroll.getChildAt(0).getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(180);
            for(AgentService.Message m:agent.chat.messages.values()){
                TextView card=cards.get(m.id);
                if(card==null){
                    LinearLayout holder=new LinearLayout(this);holder.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(m.kind.equals("user")?-2:-1,-2);hp.gravity=m.kind.equals("user")?Gravity.END:Gravity.START;hp.bottomMargin=dp(24);content.addView(holder,hp);
                    card=text("",m.kind.equals("command")?12:m.kind.equals("reasoning")?13:16,m.kind.equals("reasoning")||m.kind.equals("notice")?MUTED:TEXT);card.setTextIsSelectable(true);card.setLineSpacing(dp(4),1);
                    if(m.kind.equals("user")){card.setMaxWidth(dp(305));pad(card,14);card.setBackground(shape(PANEL,22));}
                    if(m.kind.equals("command")){card.setTypeface(Typeface.MONOSPACE);card.setBackground(shape(0xff222222,14));pad(card,12);}
                    if(m.kind.equals("reasoning")){card.setMaxLines(2);card.setOnClickListener(v->{TextView t=(TextView)v;t.setMaxLines(t.getMaxLines()==2?Integer.MAX_VALUE:2);});}
                    holder.addView(card,new LinearLayout.LayoutParams(-1,-2));cards.put(m.id,card);
                    if(m.kind.equals("assistant")){
                        LinearLayout actions=new LinearLayout(this);
                        actions.addView(icon("copy","Копировать ответ",()->copy(m.text)),new LinearLayout.LayoutParams(dp(34),dp(34)));
                        actions.addView(icon("speaker","Озвучить ответ",()->voice.speak(m.text)),new LinearLayout.LayoutParams(dp(34),dp(34)));holder.addView(actions);
                    }
                }
                String shown=m.kind.equals("reasoning")?"Размышление  ⌄\n"+m.text:m.text;
                if(!shown.equals(card.getTag())){card.setText(m.kind.equals("assistant")?markdown(shown):shown);card.setTag(shown);}
            }
            if(bottom)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
        }
        updateDrawer();
        voice.sync(agent.chat.busy,agent.chat.completedTurns,agent.chat.lastAnswer,agent.currentApproval()!=null);
        if(mute!=null)mute.setText(voice.muted()?"Включить микрофон":"Микрофон включён");
        showPendingLogin();
        renderApproval();
    }
    void copy(String text){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Codex",text));}
    void send(){if(agent==null)return;if(!agent.loggedIn){accountMenu();return;}String body=input.getText().toString().trim();if(body.isEmpty()){if(agent.chat.busy)agent.interrupt();return;}if(agent.chat.busy){new AlertDialog.Builder(this).setTitle("Отправить сообщение").setItems(new String[]{"В очередь","Уточнить сейчас"},(d,i)->{if(agent.submitText(body,i==1?"steer":"queue"))input.setText("");}).show();return;}if(agent.submitText(body,"queue")){input.setText("");((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);}}
    String effortName(String value){switch(value){case "":return "авто";case "none":return "без размышлений";case "minimal":return "минимально";case "low":return "низкое";case "medium":return "среднее";case "high":return "высокое";case "xhigh":return "очень высокое";case "max":return "максимум";case "ultra":return "ультра";default:return value;}}
    void chooseEffort(){
        if(!available())return;JSONObject m=agent.selectedModel();JSONArray options=m==null?null:m.optJSONArray("supportedReasoningEfforts");
        ArrayList<String> names=new ArrayList<>(),values=new ArrayList<>();names.add("Автоматически");values.add("");
        if(options!=null)for(int i=0;i<options.length();i++){String value=options.optJSONObject(i).optString("reasoningEffort");values.add(value);names.add(effortName(value));}
        new AlertDialog.Builder(this).setTitle("Глубина размышлений").setSingleChoiceItems(names.toArray(new String[0]),values.indexOf(agent.effort),(d,i)->{agent.selectEffort(values.get(i));d.dismiss();}).setNegativeButton("Закрыть",null).show();
    }
    void chooseModel(){
        if(!available())return;ArrayList<String> names=new ArrayList<>(),values=new ArrayList<>();names.add("Автоматически");values.add("");
        for(int i=0;i<agent.models.length();i++){JSONObject m=agent.models.optJSONObject(i);if(m.optBoolean("hidden"))continue;names.add(m.optString("displayName",m.optString("model")));values.add(m.optString("model",m.optString("id")));}
        new AlertDialog.Builder(this).setTitle("Модель").setSingleChoiceItems(names.toArray(new String[0]),values.indexOf(agent.model),(d,i)->{agent.selectModel(values.get(i));d.dismiss();}).setNegativeButton("Закрыть",null).show();
    }
    void showDrawer(){
        if(!available())return;
        drawer=new Dialog(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(0xff202020);pad(panel,18);
        Button fresh=button("＋  Новый чат",()->{agent.newThread();drawer.dismiss();});panel.addView(fresh,new LinearLayout.LayoutParams(-1,dp(48)));space(panel,12);
        EditText search=new EditText(this);search.setHint("Поиск чатов");search.setHintTextColor(MUTED);search.setTextColor(TEXT);search.setTextSize(15);search.setSingleLine(true);search.setBackground(shape(PANEL,15));pad(search,12);panel.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));space(panel,16);
        chatList=new ListView(this);chatList.setDivider(null);chatList.setBackgroundColor(Color.TRANSPARENT);chatAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<>());chatList.setAdapter(chatAdapter);panel.addView(chatList,new LinearLayout.LayoutParams(-1,0,1));
        chatList.setOnItemClickListener((p,v,i,id)->{agent.resume(shownChats.get(i).optString("id"));drawer.dismiss();});chatList.setOnItemLongClickListener((p,v,i,id)->{chatActions(shownChats.get(i));return true;});
        Button more=button("Загрузить ещё",()->{if(!agent.threadCursor.isEmpty())agent.queryThreads(agent.threadSearch,true);});panel.addView(more,new LinearLayout.LayoutParams(-1,dp(46)));
        Button settings=button(agent.account,this::accountMenu);panel.addView(settings,new LinearLayout.LayoutParams(-1,dp(46)));
        search.addTextChangedListener(new TextWatcher(){Runnable pending;public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(pending!=null)main.removeCallbacks(pending);String q=s.toString();pending=()->{if(available())agent.queryThreads(q,false);};main.postDelayed(pending,250);}public void afterTextChanged(Editable e){}});
        drawer.setContentView(panel);Window w=drawer.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.START);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.5f);}
        drawer.show();w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.88),-1);agent.queryThreads("",false);updateDrawer();
    }
    void updateDrawer(){if(drawer==null||!drawer.isShowing()||chatAdapter==null||agent==null)return;shownChats.clear();chatAdapter.clear();JSONArray list=agent.chatList();for(int i=0;i<list.length();i++){JSONObject t=list.optJSONObject(i);shownChats.add(t);String n=t.optString("name");if(n.isEmpty()||n.equals("null"))n=t.optString("preview","Новый чат");chatAdapter.add((t.optBoolean("needsApproval")?"◉ ":t.optBoolean("busy")?"● ":"")+(n.isEmpty()?"Новый чат":n));}chatAdapter.notifyDataSetChanged();}
    void chatActions(JSONObject thread){
        String id=thread.optString("id");new AlertDialog.Builder(this).setItems(new String[]{"Переименовать","Архивировать","Удалить чат"},(d,i)->{
            if(i==0){EditText name=new EditText(this);name.setText(thread.optString("name",thread.optString("preview")));new AlertDialog.Builder(this).setTitle("Название чата").setView(name).setPositiveButton("Сохранить",(x,z)->agent.renameThread(id,name.getText().toString())).setNegativeButton("Отмена",null).show();}
            if(i==1)agent.archiveThread(id);
            if(i==2)new AlertDialog.Builder(this).setTitle("Удалить этот чат?").setMessage("Его локальная история будет удалена.").setPositiveButton("Удалить",(x,z)->agent.deleteThread(id)).setNegativeButton("Отмена",null).show();
        }).show();
    }
    void accountMenu(){
        if(agent==null)return;if(!agent.ready){new AlertDialog.Builder(this).setTitle("Движок Codex").setMessage(agent.state).setPositiveButton("Запустить",(d,w)->agent.startEngine()).setNegativeButton("Закрыть",null).show();return;}
        if(agent.loginFlow.working()){Toast.makeText(this,agent.state,Toast.LENGTH_SHORT).show();return;}
        if(!agent.loggedIn&&!agent.loginFlow.url.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Вход в ChatGPT").setItems(new String[]{"Продолжить вход","Начать новый вход через браузер","Начать новый вход по коду"},(d,i)->{
                authRequested=true;openedAuth="";if(i==0)render();else agent.restartLogin(i==2);
            }).setNegativeButton("Позже",null).show();return;
        }
        String[] choices=agent.loggedIn?new String[]{"Проверить root и Docker","Обновить аккаунт","Выйти из аккаунта","Остановить помощника"}:new String[]{"Войти через браузер","Войти по коду устройства","Проверить root и Docker","Остановить помощника"};
        new AlertDialog.Builder(this).setTitle(agent.account).setItems(choices,(d,i)->{if(agent.loggedIn){if(i==0)agent.diagnostics();if(i==1)agent.readAccount();if(i==2)agent.logout();if(i==3)stopAgent();}else{if(i<2){authRequested=true;openedAuth="";agent.login(i==1);}if(i==2)agent.diagnostics();if(i==3)stopAgent();}}).show();
    }
    void showPendingLogin(){
        if(!foreground||agent==null||!authRequested||!agent.loginFlow.waiting()||openedAuth.equals(agent.loginFlow.url))return;
        final String url=agent.loginFlow.url,code=agent.loginFlow.code;
        openedAuth=url;authRequested=false;
        if(!code.isEmpty()){
            copy(code);new AlertDialog.Builder(this).setTitle("Код входа: "+code).setMessage("Код скопирован. Введи его на сайте OpenAI.")
                .setPositiveButton("Открыть",(d,w)->{if(agent!=null&&agent.loginFlow.waiting()&&url.equals(agent.loginFlow.url)&&code.equals(agent.loginFlow.code))openAuth(url);else Toast.makeText(this,"Эта попытка входа завершена. Открой вход заново.",Toast.LENGTH_LONG).show();})
                .setNegativeButton("Позже",null).show();
        }else openAuth(url);
    }
    void stopAgent(){stopService(new Intent(this,AgentService.class));finish();}
    void openAuth(String value){Uri u=Uri.parse(value);String host=u.getHost();if(!"https".equals(u.getScheme())||!("auth.openai.com".equals(host)||"chatgpt.com".equals(host)||"login.openai.com".equals(host))){Toast.makeText(this,"Неожиданный адрес входа",Toast.LENGTH_LONG).show();return;}startActivity(new Intent(Intent.ACTION_VIEW,u));}
    void requestVoice(boolean continuous){
        if(continuous&&(!available()||!agent.loggedIn)){accountMenu();return;}pendingVoice=continuous;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},31);return;}startVoice(continuous);
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==31&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startVoice(pendingVoice);}
    void startVoice(boolean continuous){
        if(!continuous){voice.begin(false,agent==null?0:agent.chat.completedTurns);return;}
        voiceDialog=new Dialog(this,android.R.style.Theme_Material_NoActionBar);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setGravity(Gravity.CENTER_HORIZONTAL);panel.setBackgroundColor(BG);panel.setPadding(dp(24),dp(42),dp(24),dp(34));
        TextView label=text("Голосовой режим",18,TEXT);label.setGravity(Gravity.CENTER);panel.addView(label);space(panel,12);
        voiceCaption=text("Говори, что нужно сделать на телефоне",14,MUTED);voiceCaption.setGravity(Gravity.CENTER);voiceCaption.setMaxLines(5);panel.addView(voiceCaption,new LinearLayout.LayoutParams(-1,dp(100)));
        FrameLayout middle=new FrameLayout(this);orb=new OrbView();FrameLayout.LayoutParams op=new FrameLayout.LayoutParams(dp(230),dp(230),Gravity.CENTER);middle.addView(orb,op);panel.addView(middle,new LinearLayout.LayoutParams(-1,0,1));orb.setOnClickListener(v->{if(agent.chat.busy)agent.interrupt();voice.retry();});
        voiceState=text("Подключаюсь…",16,TEXT);voiceState.setGravity(Gravity.CENTER);voiceState.setMaxLines(4);panel.addView(voiceState,new LinearLayout.LayoutParams(-1,dp(90)));
        mute=button("Микрофон включён",()->{voice.toggleMute();mute.setText(voice.muted()?"Включить микрофон":"Микрофон включён");});panel.addView(mute,new LinearLayout.LayoutParams(-1,dp(48)));space(panel,12);
        Button close=button("Завершить",()->voiceDialog.dismiss());panel.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));
        voiceDialog.setContentView(panel);voiceDialog.setOnDismissListener(d->{voice.close();voiceState=null;voiceCaption=null;mute=null;orb=null;voiceDialog=null;});voiceDialog.show();voiceDialog.getWindow().setLayout(-1,-1);voice.begin(true,agent.chat.completedTurns);
    }
    void renderApproval(){
        JSONObject request=agent.currentApproval();
        String id=request==null?"":String.valueOf(request.opt("id"));
        if(!shownApproval.equals(id)&&approvalDialog!=null){approvalDialog.dismiss();approvalDialog=null;shownApproval="";}
        if(foreground&&request!=null)showApproval(request);
    }
    void showApproval(JSONObject request){
        String id=String.valueOf(request.opt("id"));if(id.equals(shownApproval))return;shownApproval=id;JSONObject p=request.optJSONObject("params");String method=request.optString("method");
        if(method.equals("item/tool/requestUserInput")){
            LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);pad(form,20);HashMap<String,EditText> fields=new HashMap<>();JSONArray qs=p.optJSONArray("questions");
            if(qs!=null)for(int i=0;i<qs.length();i++){JSONObject q=qs.optJSONObject(i);form.addView(text(q.optString("question"),16,TEXT));JSONArray opts=q.optJSONArray("options");if(opts!=null){StringBuilder hints=new StringBuilder();for(int k=0;k<opts.length();k++)hints.append("• ").append(opts.optJSONObject(k).optString("label")).append('\n');form.addView(text(hints.toString(),13,MUTED));}EditText answer=new EditText(this);answer.setHint("Ответ");form.addView(answer);fields.put(q.optString("id"),answer);}
            approvalDialog=new AlertDialog.Builder(this).setTitle("Вопрос Codex · "+agent.approvalTitle(request)).setView(form).setCancelable(false).setPositiveButton("Ответить",(d,w)->{JSONObject answers=new JSONObject();for(String key:fields.keySet())try{answers.put(key,AgentService.j("answers",new JSONArray().put(fields.get(key).getText().toString())));}catch(JSONException ignored){}agent.answer(request,AgentService.j("answers",answers));shownApproval="";}).setNegativeButton("Остановить",(d,w)->{agent.interruptApproval(request);}).show();return;
        }
        String detail=p.optString("command",p.optString("reason","Изменения файлов"));AgentService.Message item=agent.approvalMessage(request);if(item!=null)detail=item.text;TextView preview=text(detail,13,TEXT);preview.setTypeface(Typeface.MONOSPACE);pad(preview,20);preview.setTextIsSelectable(true);ScrollView box=new ScrollView(this);box.addView(preview);
        approvalDialog=new AlertDialog.Builder(this).setTitle("Разрешить действие? · "+agent.approvalTitle(request)).setView(box).setCancelable(false).setPositiveButton("Разрешить один раз",(d,w)->{agent.answer(request,AgentService.j("decision","accept"));shownApproval="";}).setNegativeButton("Отклонить",(d,w)->{agent.answer(request,AgentService.j("decision","decline"));shownApproval="";}).show();
    }
    static CharSequence markdown(String raw){
        SpannableStringBuilder out=new SpannableStringBuilder();boolean code=false;int codeStart=0;
        for(String line:raw.split("\n",-1)){
            if(line.trim().startsWith("```")){if(code){out.setSpan(new TypefaceSpan("monospace"),codeStart,out.length(),33);out.setSpan(new BackgroundColorSpan(0xff252525),codeStart,out.length(),33);}else codeStart=out.length();code=!code;continue;}
            if(code){out.append(line).append('\n');continue;}
            boolean heading=line.matches("^#{1,6} .*" );if(heading)line=line.replaceFirst("^#{1,6} ","");line=line.replaceFirst("^[-*] ","• ");int start=out.length();Matcher m=Pattern.compile("\\*\\*([^*]+)\\*\\*|`([^`]+)`").matcher(line);int pos=0;
            while(m.find()){out.append(line.substring(pos,m.start()));int a=out.length();out.append(m.group(1)!=null?m.group(1):m.group(2));out.setSpan(m.group(1)!=null?new StyleSpan(Typeface.BOLD):new TypefaceSpan("monospace"),a,out.length(),33);pos=m.end();}out.append(line.substring(pos));if(heading){out.setSpan(new StyleSpan(Typeface.BOLD),start,out.length(),33);out.setSpan(new RelativeSizeSpan(1.12f),start,out.length(),33);}out.append('\n');
        }if(code){out.setSpan(new TypefaceSpan("monospace"),codeStart,out.length(),33);}return out;
    }
    final class OrbView extends View{
        float level;Paint paint=new Paint(3);OrbView(){super(ChatActivity.this);setContentDescription("Прервать ответ и говорить");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f;float r=Math.min(x,y)*(.78f+.07f*(float)Math.sin(System.currentTimeMillis()/470.0)+.1f*level);paint.setShader(new RadialGradient(x-r*.4f,y-r*.5f,r*1.7f,new int[]{0xffffffff,0xffd8eaff,0xff6eacff,0xff2359b7},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,r,paint);if(isAttachedToWindow())postInvalidateOnAnimation();}
    }
    static final class Glyph extends Drawable{
        final String kind;final Paint p=new Paint(3);final int color;Glyph(String k,int c){kind=k;color=c;}
        @Override public void draw(Canvas c){Rect b=getBounds();c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);p.setColor(color);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);Path a=new Path();
            switch(kind){
                case "menu":c.drawLine(4,8,20,8,p);c.drawLine(4,16,15,16,p);break;
                case "new":c.drawRoundRect(4,4,20,20,4,4,p);c.drawLine(10,14,18,6,p);c.drawLine(9,15,10,12,p);break;
                case "mic":c.drawRoundRect(9,3,15,14,3,3,p);a.moveTo(6,11);a.cubicTo(6,21,18,21,18,11);c.drawPath(a,p);c.drawLine(12,18,12,22,p);break;
                case "wave":for(int i=0;i<5;i++){float h=new float[]{3,6,9,5,2}[i];c.drawLine(4+i*4,12-h,4+i*4,12+h,p);}break;
                case "send":c.drawLine(12,20,12,4,p);a.moveTo(5,11);a.lineTo(12,4);a.lineTo(19,11);c.drawPath(a,p);break;
                case "stop":p.setStyle(Paint.Style.FILL);c.drawRoundRect(6,6,18,18,2,2,p);break;
                case "copy":c.drawRoundRect(8,7,20,21,2,2,p);a.moveTo(15,3);a.lineTo(4,3);a.lineTo(4,16);c.drawPath(a,p);break;
                case "speaker":a.moveTo(4,9);a.lineTo(8,9);a.lineTo(13,5);a.lineTo(13,19);a.lineTo(8,15);a.lineTo(4,15);a.close();c.drawPath(a,p);c.drawArc(10,6,21,18,-65,130,false,p);break;
            }c.restore();}
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    @Override public void onStart(){super.onStart();foreground=true;if(agent!=null&&!agent.listeners.contains(observer)){agent.listeners.add(observer);agent.visibleClients++;}if(agent!=null)render();}
    @Override public void onPause(){getPreferences(0).edit().putString("draft",input.getText().toString()).apply();if(voiceDialog!=null)voiceDialog.dismiss();voice.close();super.onPause();}
    @Override public void onStop(){foreground=false;if(agent!=null){agent.listeners.remove(observer);agent.visibleClients=Math.max(0,agent.visibleClients-1);}super.onStop();}
    @Override public void onDestroy(){if(agent!=null)agent.listeners.remove(observer);if(bound)unbindService(connection);voice.destroy();super.onDestroy();}
}
