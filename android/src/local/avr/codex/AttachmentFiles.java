package local.avr.codex.copy;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores user-selected documents in app-private storage and resolves only imported IDs. */
final class AttachmentFiles {
    static final int LIMIT=20*1024*1024;
    private static final Pattern ID=Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final String HEADER="Прикреплённые файлы:";
    private static final Pattern PATH=Pattern.compile("^- (?:/[^\\n]*/attachments/|[^\\n]+/\\.codex-mobile-uploads/)("+ID.pattern()+")/([^/\\n]+)$");

    private static File directory(Context context){return new File(context.getFilesDir(),"attachments");}

    static JSONObject importUri(Context context,Uri uri)throws IOException{
        if(!"content".equals(uri.getScheme()))throw new IOException("Файл недоступен");
        String name="file";
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
        }
        File parent=new File(directory(context),UUID.randomUUID().toString());
        if(!parent.mkdirs())throw new IOException("Не удалось создать папку вложения");
        File file=new File(parent,safeName(name));
        boolean saved=false;
        try{
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                copy(in,out);
            }
            saved=true;return describe(file);
        }finally{if(!saved){file.delete();parent.delete();}}
    }

    private static String safeName(String value){
        String name=value.replaceAll("[^\\p{L}\\p{N}._ -]","_").trim().replaceFirst("[. ]+$","");
        if(name.isEmpty())return "file";
        if(name.length()>100){
            int dot=name.lastIndexOf('.');
            String extension=dot>0&&name.length()-dot<=12?name.substring(dot):"";
            name=name.substring(0,100-extension.length()).replaceFirst("[. ]+$","")+extension;
        }
        if(name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))name="_"+name;
        return name;
    }

    static File file(Context context,String id)throws IOException{
        if(!ID.matcher(id).matches())throw new IOException("Некорректное вложение");
        File parent=new File(directory(context),id);
        File[] contents=parent.listFiles();
        if(contents==null||contents.length!=1||!contents[0].isFile()||
            !contents[0].getCanonicalFile().equals(new File(directory(context).getCanonicalFile(),id+"/"+contents[0].getName())))
            throw new IOException("Файл вложения не найден");
        return contents[0];
    }

    private static JSONObject describe(File file){
        return AgentService.j("id",file.getParentFile().getName(),"name",file.getName(),"size",file.length());
    }

    static JSONArray describeIds(Context context,String ids){
        JSONArray result=new JSONArray();
        try{
            JSONArray array=new JSONArray(ids);
            for(int i=0;i<array.length();i++)try{result.put(describe(file(context,array.getString(i))));}catch(IOException ignored){}
        }catch(org.json.JSONException ignored){}
        return result;
    }

    static List<File> files(Context context,String ids)throws Exception{
        ArrayList<File> result=new ArrayList<>();
        JSONArray array=new JSONArray(ids);
        for(int i=0;i<array.length();i++)result.add(file(context,array.getString(i)));
        return result;
    }

    static JSONArray appendInput(TurnOutbox.Entry entry,JSONArray input,List<File> files,String remoteDirectory)throws org.json.JSONException{
        if(files.isEmpty())return input;
        StringBuilder text=new StringBuilder(entry.text);
        if(text.length()>0)text.append("\n\n");
        text.append(HEADER);
        for(File file:files){
            String path=remoteDirectory==null?file.getAbsolutePath():remoteDirectory+"/"+file.getParentFile().getName()+"/"+file.getName();
            text.append("\n- ").append(path);
        }
        JSONArray result=new JSONArray().put(AgentService.j("type","text","text",text.toString()));
        for(int i=entry.text.isEmpty()?0:1;i<input.length();i++)result.put(input.get(i));
        return result;
    }

    static void restoreMessage(Context context,AgentService.Message message){
        int start=message.text.lastIndexOf("\n\n"+HEADER+"\n");
        int firstPath=start>=0?start+2+HEADER.length()+1:HEADER.length()+1;
        if(start<0&&!message.text.startsWith(HEADER+"\n"))return;
        JSONArray restored=new JSONArray();
        for(String line:message.text.substring(firstPath).split("\n")){
            Matcher match=PATH.matcher(line);
            if(!match.matches())return;
            try{
                File file=file(context,match.group(1));
                if(!file.getName().equals(match.group(2)))return;
                restored.put(describe(file));
            }catch(IOException e){return;}
        }
        message.files=restored;message.text=start<0?"":message.text.substring(0,start);
    }

    static String encode(File file)throws IOException{
        try(InputStream in=new FileInputStream(file)){return Base64.encodeToString(MediaFiles.read(in,LIMIT),Base64.NO_WRAP);}
    }

    static File shareCopy(Context context,String id)throws IOException{
        File source=file(context,id);
        File dir=new File(context.getCacheDir(),"shared/"+UUID.randomUUID());
        if(!dir.mkdirs())throw new IOException("Не удалось создать копию файла");
        File copy=new File(dir,source.getName());
        boolean saved=false;
        try{
            Files.copy(source.toPath(),copy.toPath());
            saved=true;return copy;
        }finally{if(!saved){copy.delete();dir.delete();}}
    }

    private static void copy(InputStream in,OutputStream out)throws IOException{
        if(in==null)throw new IOException("Файл недоступен");
        byte[] buffer=new byte[16384];int size=0,n;
        while((n=in.read(buffer))!=-1){
            size+=n;
            if(size>LIMIT)throw new IOException("Размер файла превышает 20 МБ");
            out.write(buffer,0,n);
        }
    }
}
