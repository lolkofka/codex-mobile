package local.avr.codex.copy;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Two disposable app UIDs; no messages are sent to Telegram or other real apps. */
public final class FileSharingProbe extends Activity {
    static final byte[] BYTES={37,80,68,70,0,-1};
    static final String NAME="Отчёт ноябрь.pdf",RECEIVER="local.avr.codex.sharereceiver";
    final JSONArray checks=new JSONArray();Intent captured;File fixture,other;Uri shared;
    @Override public void startActivity(Intent intent){captured=intent;}
    void check(boolean condition,String name)throws Exception{if(!condition)throw new AssertionError(name);checks.put(name);}
    File fixture(String name,byte[] bytes)throws Exception{File dir=new File(getCacheDir(),"shared/"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException();File file=new File(dir,name);Files.write(file.toPath(),bytes);return file;}
    interface Step{void run()throws Exception;}
    void later(Step step){new android.os.Handler(getMainLooper()).post(()->{try{step.run();}catch(Throwable e){report("FAIL",e);}});}
    void report(String status,Throwable error){try{JSONObject result=new JSONObject().put("status",status).put("checks",checks);if(error!=null)result.put("error",error.toString());Files.write(new File(getFilesDir(),"file-share-tests.json").toPath(),result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
    @Override public void onCreate(Bundle b){super.onCreate(b);try{
        fixture=fixture(NAME,BYTES);other=fixture("private.txt",new byte[]{42});
        AlertDialog dialog=FileActions.show(this,fixture);
        if(getIntent().getBooleanExtra("preview",false)){
            android.view.View decor=dialog.getWindow().getDecorView();decor.postDelayed(()->{try{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(getFilesDir(),"file-actions-preview.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();report("PREVIEW",null);}catch(Throwable e){report("FAIL",e);}},300);return;
        }
check(dialog!=null&&dialog.getWindow().getDecorView().findViewWithTag("file-send")!=null,"file click offers native Send action");dialog.getWindow().getDecorView().findViewWithTag("file-send").performClick();
        later(()->{
        check(Intent.ACTION_CHOOSER.equals(captured.getAction()),"Send always opens Android Sharesheet");Intent send=captured.getParcelableExtra(Intent.EXTRA_INTENT);
        check(Intent.ACTION_SEND.equals(send.getAction())&&"application/pdf".equals(send.getType()),"file is sent as a PDF stream");shared=send.getParcelableExtra(Intent.EXTRA_STREAM);
        check("content".equals(shared.getScheme())&&!send.hasExtra(Intent.EXTRA_TEXT),"shared item is file bytes, not a root path or text link");
        check((send.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&(send.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0,"target receives read-only URI grant");
        check(send.getClipData().getItemAt(0).getUri().equals(shared)&&captured.getClipData().getItemAt(0).getUri().equals(shared),"target and chooser both carry ClipData");
        check(send.getComponent()==null&&send.getPackage()==null,"owner can choose Telegram or any compatible app");
        FileActions.show(this,fixture).getWindow().getDecorView().findViewWithTag("file-open").performClick();later(()->{Intent view=captured.getParcelableExtra(Intent.EXTRA_INTENT);check(Intent.ACTION_VIEW.equals(view.getAction())&&view.getData().equals(shared),"Open remains available with a readable file URI");
        Intent previous=captured;FileActions.show(this,fixture).getWindow().getDecorView().findViewWithTag("file-cancel").performClick();later(()->{check(previous==captured,"Cancel launches no other app");
        Intent denied=new Intent().setClassName(RECEIVER,Receiver.class.getName()).putExtra("uri",shared.toString()).putExtra("mode","deny");super.startActivityForResult(denied,101);
        });});});
    }catch(Throwable e){report("FAIL",e);}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);try{
        check(result==RESULT_OK&&data!=null&&data.getBooleanExtra("ok",false),request==101?"another app cannot read an ungranted URI":"receiving app reads original bytes, filename and MIME with no write or sibling-file access");
        if(request==101){Intent send=FileActions.intent(this,fixture,true).setClassName(RECEIVER,Receiver.class.getName()).putExtra("mode","grant").putExtra("other",MediaFiles.shareUri(this,other).toString());super.startActivityForResult(send,102);}
        else{report("PASS",null);finish();}
    }catch(Throwable e){report("FAIL",e);}}
    public static final class Receiver extends Activity {
        @Override public void onCreate(Bundle b){super.onCreate(b);boolean ok=false;try{
            Intent intent=getIntent();boolean granted=intent.getStringExtra("mode").equals("grant");Uri uri=granted?intent.getParcelableExtra(Intent.EXTRA_STREAM):Uri.parse(intent.getStringExtra("uri"));
            if(!granted){try(InputStream in=getContentResolver().openInputStream(uri)){in.read();}catch(SecurityException expected){ok=true;}}
            else{
                try(InputStream in=getContentResolver().openInputStream(uri)){ok=Arrays.equals(BYTES,MediaFiles.read(in,100));}
                try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){ok&=c!=null&&c.moveToFirst()&&NAME.equals(c.getString(0))&&c.getLong(1)==BYTES.length;}
                ok&="application/pdf".equals(getContentResolver().getType(uri));
                boolean writable=false;try(android.os.ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"w")){writable=true;}catch(IOException|SecurityException expected){}ok&=!writable;
                boolean otherReadable=false;try(InputStream in=getContentResolver().openInputStream(Uri.parse(intent.getStringExtra("other")))){otherReadable=true;}catch(IOException|SecurityException expected){}ok&=!otherReadable;
            }
        }catch(Throwable ignored){}setResult(RESULT_OK,new Intent().putExtra("ok",ok));finish();}
    }
}
