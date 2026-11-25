package local.avr.codex.copy;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.text.format.Formatter;
import android.widget.Toast;
import java.io.*;

/** Share file bytes through a private content URI, never the root filesystem path. */
final class FileActions {
    static Intent intent(Context context,File file,boolean send)throws IOException{
        Uri uri=MediaFiles.shareUri(context,file);
        Intent target=new Intent(send?Intent.ACTION_SEND:Intent.ACTION_VIEW);
        String mime=MediaFiles.mime(file.getName());
        if(send)target.setType(mime).putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TITLE,file.getName());
        else target.setDataAndType(uri,mime);
        target.setClipData(ClipData.newRawUri(file.getName(),uri));
        return target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static Intent chooser(Context context,File file,boolean send)throws IOException{
        Intent target=intent(context,file,send);
        Intent chooser=Intent.createChooser(target,send?"Отправить файл":"Открыть файл");
        chooser.setClipData(target.getClipData());
        return chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static void launch(Activity activity,File file,boolean send){
        try{activity.startActivity(chooser(activity,file,send));}
        catch(ActivityNotFoundException e){Toast.makeText(activity,"Нет приложения для этого действия",Toast.LENGTH_LONG).show();}
        catch(IOException e){Toast.makeText(activity,"Не удалось подготовить файл",Toast.LENGTH_LONG).show();}
    }
    static AlertDialog show(Activity activity,File file){
        if(activity.isFinishing()||activity.isDestroyed())return null;
        android.widget.LinearLayout body=NativeUi.column(activity);body.setPadding(NativeUi.dp(activity,20),NativeUi.dp(activity,10),NativeUi.dp(activity,20),NativeUi.dp(activity,20));
        android.view.View handle=new android.view.View(activity);handle.setBackground(NativeUi.surface(activity,0xff617067,3));android.widget.LinearLayout.LayoutParams hp=new android.widget.LinearLayout.LayoutParams(NativeUi.dp(activity,36),NativeUi.dp(activity,4));hp.gravity=android.view.Gravity.CENTER_HORIZONTAL;hp.setMargins(0,0,0,NativeUi.dp(activity,13));body.addView(handle,hp);
        android.widget.LinearLayout header=new android.widget.LinearLayout(activity);header.setGravity(android.view.Gravity.CENTER_VERTICAL);android.widget.TextView title=NativeUi.text(activity,"Файл",21,NativeUi.TEXT);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));header.addView(title,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        android.widget.Button close=new android.widget.Button(activity);close.setText("×");close.setTextSize(23);close.setTextColor(NativeUi.MUTED);close.setContentDescription("Закрыть");close.setPadding(0,0,0,0);close.setMinWidth(0);close.setMinHeight(0);close.setBackground(NativeUi.surface(activity,NativeUi.CARD,22));header.addView(close,new android.widget.LinearLayout.LayoutParams(NativeUi.dp(activity,40),NativeUi.dp(activity,40)));body.addView(header);
        android.widget.LinearLayout info=new android.widget.LinearLayout(activity);info.setGravity(android.view.Gravity.CENTER_VERTICAL);info.setPadding(NativeUi.dp(activity,16),NativeUi.dp(activity,18),NativeUi.dp(activity,16),NativeUi.dp(activity,18));info.setBackground(NativeUi.surface(activity,NativeUi.INPUT,18));
        android.widget.ImageView glyph=NativeUi.icon(activity,R.drawable.ic_action_file,NativeUi.ACCENT);android.widget.LinearLayout.LayoutParams gp=new android.widget.LinearLayout.LayoutParams(NativeUi.dp(activity,34),NativeUi.dp(activity,38));gp.setMargins(0,0,NativeUi.dp(activity,15),0);info.addView(glyph,gp);
        android.widget.LinearLayout copy=NativeUi.column(activity);android.widget.TextView name=NativeUi.text(activity,file.getName(),17,NativeUi.TEXT);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));name.setMaxLines(3);name.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(name);
        int dot=file.getName().lastIndexOf('.');String type=dot<0?"Документ":file.getName().substring(dot+1).toUpperCase(java.util.Locale.ROOT);android.widget.TextView meta=NativeUi.text(activity,type+" · "+Formatter.formatShortFileSize(activity,file.length()),12,NativeUi.MUTED);meta.setPadding(0,NativeUi.dp(activity,7),0,0);copy.addView(meta);info.addView(copy,new android.widget.LinearLayout.LayoutParams(0,-2,1));android.widget.LinearLayout.LayoutParams ip=new android.widget.LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,NativeUi.dp(activity,16),0,NativeUi.dp(activity,12));body.addView(info,ip);
        final AlertDialog dialog=new AlertDialog.Builder(activity).setView(body).create();
        android.view.View send=NativeUi.action(activity,R.drawable.ic_action_send,"Отправить…","Telegram и другие приложения",true,()->{dialog.dismiss();launch(activity,file,true);});send.setTag("file-send");body.addView(send,new android.widget.LinearLayout.LayoutParams(-1,-2));
        android.view.View open=NativeUi.action(activity,R.drawable.ic_action_open,"Открыть","Выбрать приложение для просмотра",false,()->{dialog.dismiss();launch(activity,file,false);});open.setTag("file-open");android.widget.LinearLayout.LayoutParams op=new android.widget.LinearLayout.LayoutParams(-1,-2);op.setMargins(0,NativeUi.dp(activity,9),0,0);body.addView(open,op);
        android.widget.Button cancel=new android.widget.Button(activity);cancel.setText("Отмена");cancel.setTag("file-cancel");NativeUi.secondary(cancel);cancel.setTextColor(NativeUi.MUTED);cancel.setOnClickListener(v->dialog.dismiss());body.addView(cancel);close.setOnClickListener(v->dialog.dismiss());
        dialog.show();NativeUi.style(dialog,true);return dialog;
    }
}
