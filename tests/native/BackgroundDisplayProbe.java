package local.avr.codex.copy;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Disposable app with no keep-screen-on or keyguard overrides. No owner data or network access. */
public final class BackgroundDisplayProbe extends Activity {
    final Handler handler=new Handler(Looper.getMainLooper());
    TextView counter;int ticks,taps;boolean resumed,focused;
    void record(){
        try{JSONObject report=new JSONObject().put("display",getDisplay().getDisplayId()).put("ticks",ticks).put("taps",taps).put("resumed",resumed).put("focused",focused).put("updated",System.currentTimeMillis());
            Files.write(new File(getFilesDir(),"display-test.json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){throw new RuntimeException(e);}
    }
    final Runnable tick=new Runnable(){public void run(){ticks++;counter.setText("Background frames: "+ticks+"\nTaps: "+taps);record();handler.postDelayed(this,500);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(24,40,24,24);content.setBackgroundColor(Color.rgb(21,26,24));
        counter=new TextView(this);counter.setTextColor(Color.WHITE);counter.setTextSize(24);content.addView(counter,new LinearLayout.LayoutParams(-1,160));
        Button button=new Button(this);button.setText("Test tap");button.setOnClickListener(v->{taps++;record();});content.addView(button,new LinearLayout.LayoutParams(-1,140));
        setContentView(content);handler.post(tick);
    }
    @Override public void onResume(){super.onResume();resumed=true;record();}
    @Override public void onPause(){resumed=false;record();super.onPause();}
    @Override public void onWindowFocusChanged(boolean value){super.onWindowFocusChanged(value);focused=value;record();}
    @Override public void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
}
