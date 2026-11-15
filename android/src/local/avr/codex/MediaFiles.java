package local.avr.codex;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Imported media stays private; only explicitly opened copies are shared. */
final class MediaFiles {
    static File directory(Context c){File f=new File(c.getFilesDir(),"media");f.mkdirs();return f;}
    static File image(Context c,String id)throws IOException{
        if(!id.matches("[a-f0-9-]{36}\\.(png|jpg)"))throw new IOException("Некорректное вложение");
        File f=new File(directory(c),id);if(!f.isFile())throw new IOException("Файл вложения не найден");return f;
    }
    static JSONObject describe(File f){return AgentService.j("id",f.getName(),"name",f.getName(),"url","/media/"+f.getName());}
    static JSONObject importUri(Context c,Uri uri)throws Exception{
        if(!"content".equals(uri.getScheme()))throw new IOException("Ожидается изображение из галереи или буфера");
        byte[] bytes;try(InputStream in=c.getContentResolver().openInputStream(uri)){bytes=read(in,25*1024*1024);}
        ImageDecoder.Source source=ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes));
        Bitmap b=ImageDecoder.decodeBitmap(source,(decoder,info,s)->{android.util.Size size=info.getSize();double scale=Math.min(1,2048.0/Math.max(size.getWidth(),size.getHeight()));decoder.setTargetSize(Math.max(1,(int)(size.getWidth()*scale)),Math.max(1,(int)(size.getHeight()*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
        File f=new File(directory(c),UUID.randomUUID()+(b.hasAlpha()?".png":".jpg"));
        try(OutputStream out=new FileOutputStream(f)){if(!b.compress(b.hasAlpha()?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,90,out))throw new IOException("Не удалось сохранить изображение");}finally{b.recycle();}
        return describe(f);
    }
    static byte[] read(InputStream in,int limit)throws IOException{if(in==null)throw new IOException("Файл недоступен");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Файл слишком большой");out.write(b,0,n);}return out.toByteArray();}
    static String quote(String path){return "'"+path.replace("'","'\\''")+"'";}
    static String localPath(String value)throws IOException{
        if(value.startsWith("sandbox:"))value=value.substring(8);
        if(value.startsWith("file:")){Uri u=Uri.parse(value);if(u.getHost()!=null&&!u.getHost().isEmpty()&&!u.getHost().equals("localhost"))throw new IOException("Удалённый file URI не поддерживается");value=u.getPath();}
        value=value.replaceFirst(":\\d+(?::\\d+)?$","");
        if(!value.startsWith("/")||value.indexOf('\0')>=0||value.indexOf('\n')>=0)throw new IOException("Нужен абсолютный путь на телефоне");
        return value;
    }
    static String mime(String path){String ext=MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT);String m=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);return m==null?"application/octet-stream":m;}
    static byte[] rootRead(String path,int limit)throws Exception{
        Process p=new ProcessBuilder("su","-c","test -f "+quote(path)+" && cat -- "+quote(path)).start();
        java.util.concurrent.ExecutorService reader=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<byte[]> result=reader.submit(()->read(p.getInputStream(),limit));
        try{byte[] data=result.get(20,java.util.concurrent.TimeUnit.SECONDS);if(!p.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("Не удалось прочитать файл");return data;}finally{p.destroy();result.cancel(true);reader.shutdownNow();}
    }
    static File shareCopy(Context c,String value)throws Exception{
        String path=localPath(value);String name=new File(path).getName().replaceAll("[^\\p{L}\\p{N}._ -]","_");if(name.isEmpty())name="file";
        File dir=new File(c.getCacheDir(),"shared/"+UUID.randomUUID());dir.mkdirs();File f=new File(dir,name);
        Files.write(f.toPath(),rootRead(path,50*1024*1024));return f;
    }
    static Uri shareUri(Context c,File f)throws IOException{
        File base=new File(c.getCacheDir(),"shared").getCanonicalFile(),actual=f.getCanonicalFile();
        if(!actual.getPath().startsWith(base.getPath()+"/"))throw new IOException("Файл вне каталога обмена");
        return new Uri.Builder().scheme("content").authority(c.getPackageName()+".files").appendPath(actual.getParentFile().getName()).appendPath(actual.getName()).build();
    }
    static JSONArray input(Context c,TurnOutbox.Entry e,boolean remote)throws Exception{
        JSONArray input=new JSONArray();if(!e.text.isEmpty())input.put(AgentService.j("type","text","text",e.text));
        JSONArray images=new JSONArray(e.images);for(int i=0;i<images.length();i++){
            File f=image(c,images.getString(i));
            if(remote)input.put(AgentService.j("type","image","url","data:"+mime(f.getName())+";base64,"+android.util.Base64.encodeToString(Files.readAllBytes(f.toPath()),android.util.Base64.NO_WRAP)));
            else input.put(AgentService.j("type","localImage","path",f.getAbsolutePath()));
        }return input;
    }
    static JSONArray describeIds(Context c,String ids){JSONArray a=new JSONArray();try{JSONArray list=new JSONArray(ids);for(int i=0;i<list.length();i++)a.put(describe(image(c,list.getString(i))));}catch(Exception ignored){}return a;}
}
