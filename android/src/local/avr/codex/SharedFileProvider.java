package local.avr.codex.copy;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Read-only, non-exported provider with per-URI Android grants. */
public final class SharedFileProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException{
        try{List<String> p=uri.getPathSegments();if(p.size()!=2||!p.get(0).matches("[a-f0-9-]{36}"))throw new IOException();File base=new File(getContext().getCacheDir(),"shared").getCanonicalFile();File f=new File(new File(base,p.get(0)),p.get(1)).getCanonicalFile();if(!f.getPath().startsWith(base.getPath()+"/"+p.get(0)+"/")||!f.isFile())throw new IOException();return f;}catch(IOException e){throw new FileNotFoundException();}
    }
    public String getType(Uri u){return MediaFiles.mime(u.getLastPathSegment());}
    public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(resolve(u),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri u,String[] columns,String where,String[] args,String sort){try{File f=resolve(u);String[] names=columns==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:columns;MatrixCursor c=new MatrixCursor(names);Object[] row=new Object[names.length];for(int i=0;i<names.length;i++)row[i]=names[i].equals(OpenableColumns.DISPLAY_NAME)?f.getName():names[i].equals(OpenableColumns.SIZE)?f.length():null;c.addRow(row);return c;}catch(Exception e){return null;}}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String w,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String w,String[] a){throw new UnsupportedOperationException();}
}
