package local.avr.codex.copy;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Secret inputs are native views, never serialized into the chat WebView. */
final class ServerEditor {
    static final int PICK_KEY=74;
    final WebChatActivity activity;
    EditText keyInput;
    AlertDialog dialog;
    ServerEditor(WebChatActivity activity){this.activity=activity;}
    EditText field(LinearLayout layout,String label,String value,boolean password){
        return NativeUi.field(layout,label,value,password);
    }
    void show(String id){
        if(activity.agent==null||activity.agent.workerServers==null)return;
        WorkerServers servers=activity.agent.workerServers;JSONObject current=new JSONObject();
        try{if(!id.isEmpty())current=ServerStore.metadata(servers.store.find(id));}catch(Exception e){Toast.makeText(activity,WorkerServers.safeError(e),Toast.LENGTH_LONG).show();return;}
        final JSONObject previous=current;ScrollView scroll=NativeUi.scroll(activity);LinearLayout layout=NativeUi.form(activity);scroll.addView(layout);
        NativeUi.section(layout,"Подключение");
        EditText name=field(layout,"Название для помощника",current.optString("name"),false),host=field(layout,"IP или домен",current.optString("host"),false),port=field(layout,"Порт SSH",String.valueOf(current.optInt("port",22)),false),user=field(layout,"Логин SSH",current.optString("user","root"),false);
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        NativeUi.section(layout,"Авторизация");
        CheckBox savedKey=new CheckBox(activity);savedKey.setText("Использовать сохранённый SSH-ключ");savedKey.setChecked(current.optBoolean("hasKey"));savedKey.setVisibility(current.optBoolean("hasKey")?View.VISIBLE:View.GONE);layout.addView(savedKey);
        keyInput=field(layout,"Закрытый SSH-ключ (вставить или выбрать файл)","",true);keyInput.setSingleLine(false);keyInput.setMinLines(2);keyInput.setMaxLines(4);
        final EditText key=keyInput;Button choose=new Button(activity);choose.setText("Выбрать файл ключа");NativeUi.secondary(choose);choose.setOnClickListener(v->{keyInput=key;activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_KEY);});layout.addView(choose);
        EditText phrase=field(layout,"Пароль ключа (если ключ зашифрован)","",true);
        if(current.optBoolean("hasPassphrase"))phrase.setHint("Сохранён · оставь пустым, чтобы сохранить");
        TextView passwordLabel=NativeUi.label(layout,"Пароль");
        EditText password=new EditText(activity);NativeUi.input(password);password.setSingleLine(true);password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);if(current.optBoolean("hasPassword"))password.setHint("Сохранён · оставь пустым, чтобы сохранить");layout.addView(password);
        CheckBox keepPassword=new CheckBox(activity);keepPassword.setText("Использовать сохранённый пароль");keepPassword.setChecked(current.optBoolean("hasPassword"));keepPassword.setVisibility(current.optBoolean("hasPassword")?View.VISIBLE:View.GONE);layout.addView(keepPassword);
        Runnable label=()->passwordLabel.setText(savedKey.isChecked()||key.length()>0?"Пароль root для su (не пароль SSH)":"Пароль SSH");label.run();savedKey.setOnCheckedChangeListener((b,checked)->label.run());
        key.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){label.run();}public void afterTextChanged(Editable e){}});
        TextView hint=NativeUi.note(layout,"С ключом пароль используется только для перехода в root по явной команде. Без ключа — для входа по SSH. Пароль ключа вводится отдельно. Пустое поле сохраняет прежний пароль. Ключ сервера запоминается при первом успешном входе.");
        if(current.has("hostKeyFingerprint")){TextView fingerprint=new TextView(activity);fingerprint.setTextSize(11);fingerprint.setText("Ключ сервера: "+current.optString("hostKeyFingerprint"));layout.addView(fingerprint);}
        dialog=new AlertDialog.Builder(activity).setTitle(id.isEmpty()?"Новый рабочий сервер":"Рабочий сервер").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{JSONObject fields=new JSONObject().put("id",id).put("name",name.getText().toString()).put("host",host.getText().toString()).put("port",Integer.parseInt(port.getText().toString())).put("user",user.getText().toString());
                boolean newKey=key.length()>0;
                if(newKey){fields.put("privateKey",key.getText().toString().trim()+"\n");fields.put("passphrase",phrase.getText().toString());}
                else if(!savedKey.isChecked()){fields.put("privateKey","");fields.put("passphrase","");}
                else if(phrase.length()>0)fields.put("passphrase",phrase.getText().toString());
                if(password.length()>0)fields.put("password",password.getText().toString());
                else if(!keepPassword.isChecked()||(newKey||savedKey.isChecked())!=previous.optBoolean("hasKey"))fields.put("password","");
                servers.store.save(fields);key.setText("");phrase.setText("");password.setText("");dialog.dismiss();activity.agent.changed();Toast.makeText(activity,"Сервер сохранён",Toast.LENGTH_SHORT).show();
            }catch(Exception e){Toast.makeText(activity,e instanceof NumberFormatException?"Проверь порт SSH":WorkerServers.safeError(e),Toast.LENGTH_LONG).show();}
        });});dialog.show();NativeUi.style(dialog,false);
    }
    boolean result(int request,int result,Intent data){
        if(request!=PICK_KEY)return false;if(result!=Activity.RESULT_OK||data==null||data.getData()==null||keyInput==null)return true;
        try(InputStream in=activity.getContentResolver().openInputStream(data.getData())){keyInput.setText(new String(MediaFiles.read(in,65536),StandardCharsets.UTF_8));}
        catch(Exception e){Toast.makeText(activity,"Не удалось прочитать файл ключа (до 64 КБ)",Toast.LENGTH_LONG).show();}return true;
    }
    void remove(String id){new AlertDialog.Builder(activity).setTitle("Удалить рабочий сервер?").setMessage("Будет удалён только сохранённый профиль на телефоне.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{try{activity.agent.workerServers.store.remove(id);activity.agent.changed();}catch(Exception e){Toast.makeText(activity,WorkerServers.safeError(e),Toast.LENGTH_LONG).show();}}).show();}
    void test(String id){
        WorkerServers servers=activity.agent.workerServers;Toast.makeText(activity,"Проверяю SSH…",Toast.LENGTH_SHORT).show();
        servers.requests.execute(()->{String message;try{JSONObject result=servers.ssh.execute(id,"printf 'SSH_OK\\n'; id -un; uname -s",false,20);message=result.optInt("exitCode")==0?"Подключение работает\n"+result.optString("stdout"):"SSH подключён, но проверочная команда завершилась с ошибкой";}catch(Exception e){message=WorkerServers.safeError(e);}final String text=message;activity.runOnUiThread(()->{if(activity.isFinishing())return;new AlertDialog.Builder(activity).setTitle("Проверка рабочего сервера").setMessage(text).setPositiveButton("Понятно",null).show();activity.agent.changed();});});
    }
}
