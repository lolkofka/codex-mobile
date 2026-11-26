package local.avr.codex.copy;

import android.app.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Native, secure subscription input: URLs with tokens stay out of chat and JS state. */
final class TunnelEditor {
    final WebChatActivity activity;
    TunnelEditor(WebChatActivity activity){this.activity=activity;}
    void show(String id){
        if(activity.agent==null||activity.agent.tunnel==null)return;
        TunnelManager tunnel=activity.agent.tunnel;JSONObject sub=new JSONObject();
        try{if(!id.isEmpty())sub=tunnel.store.subscription(id);}catch(Exception e){Toast.makeText(activity,"Подписка не найдена",Toast.LENGTH_LONG).show();return;}
        ScrollView scroll=NativeUi.scroll(activity);LinearLayout layout=NativeUi.form(activity);scroll.addView(layout);
        EditText name=NativeUi.field(layout,"Название подписки",sub.optString("name"),false);
        NativeUi.label(layout,"Ссылка HTTPS или список серверов");
        EditText source=new EditText(activity);NativeUi.input(source);source.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);source.setMinLines(3);source.setMaxLines(6);source.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);source.setText(sub.optString("url"));source.setHint("https://… или vless://…");layout.addView(source);
        TextView hint=NativeUi.note(layout,"VLESS / REALITY, VMess, Trojan, Shadowsocks. Поддерживаются обычные и Base64-подписки, Xray JSON. Загрузка подписки использует сеть телефона. Первый импорт включит строгий Xray и выберет первый хост; затем можно выбрать другой. Ссылка и ключи хранятся зашифрованными.");
        TextView error=new TextView(activity);error.setTextColor(0xffff9e91);layout.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(id.isEmpty()?"Добавить подписку Xray":"Обновить подписку Xray").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Загрузить",null).create();
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String address=source.getText().toString().trim(),title=name.getText().toString().trim();if(title.isEmpty()){error.setText("Укажи название подписки");return;}if(address.isEmpty()){error.setText("Вставь ссылку или список серверов");return;}if(tunnel.working){error.setText("Подожди завершения текущей операции");return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);error.setText("Загружаю и проверяю подписку…");
            tunnel.importSubscription(id,title,address,address.startsWith("https://"),warning->activity.runOnUiThread(()->{source.setText("");dialog.dismiss();Toast.makeText(activity,warning.isEmpty()?"Подписка сохранена · Xray без обхода":"Подписка сохранена. "+warning,Toast.LENGTH_LONG).show();}),message->activity.runOnUiThread(()->{if(!dialog.isShowing())return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);error.setText(message);}));
        });});dialog.show();NativeUi.style(dialog,false);
    }
    void remove(String id){new AlertDialog.Builder(activity).setTitle("Удалить подписку?").setMessage("Если в ней выбран активный хост, запросы будут заблокированы до выбора другого сервера.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->activity.agent.tunnel.remove(id)).show();}
    void enable(boolean enabled){
        if(enabled){activity.agent.tunnel.enable(true);return;}
        new AlertDialog.Builder(activity).setTitle("Отключить строгий Xray?").setMessage("Запросы Codex снова будут использовать сеть телефона и Happ. Автоматического обхода при включённом Xray нет.").setNegativeButton("Отмена",(d,w)->activity.agent.changed()).setPositiveButton("Отключить Xray",(d,w)->activity.agent.tunnel.enable(false)).show();
    }
}
