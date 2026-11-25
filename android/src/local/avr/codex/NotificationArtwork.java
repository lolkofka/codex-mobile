package local.avr.codex.copy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** The existing app logo as the notification's visible picture. */
final class NotificationArtwork {
    private static Bitmap logo;
    static synchronized Bitmap logo(Context context){
        if(logo==null){
            int size=192;
            logo=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            Drawable drawable=context.getDrawable(R.drawable.icon).mutate();
            drawable.setBounds(0,0,size,size);
            drawable.draw(new Canvas(logo));
        }
        return logo;
    }
}
