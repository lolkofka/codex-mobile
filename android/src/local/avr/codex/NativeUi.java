package local.avr.codex.copy;

import android.app.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Native sheets and private credential forms use the same surfaces as the chat UI. */
final class NativeUi {
    static final int BG=0xff19201c,CARD=0xff242d27,INPUT=0xff151a18,TEXT=0xffedf2ef,MUTED=0xffa4afa8,ACCENT=0xff9be5cc,LINE=0xff39483e;
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(Context c,int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout form(Context c){LinearLayout l=column(c);l.setPadding(dp(c,20),dp(c,2),dp(c,20),dp(c,12));return l;}
    static ScrollView scroll(Context c){return new ScrollView(c){@Override protected void onMeasure(int width,int height){int cap=Math.round(getResources().getDisplayMetrics().heightPixels*.61f);if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)cap=Math.min(cap,MeasureSpec.getSize(height));super.onMeasure(width,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));}};}
    static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(c,2),1);return t;}
    static TextView label(LinearLayout parent,String value){Context c=parent.getContext();TextView t=text(c,value,12,MUTED);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,15),0,dp(c,7));parent.addView(t,p);return t;}
    static void section(LinearLayout parent,String value){Context c=parent.getContext();TextView t=text(c,value,13,ACCENT);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,23),0,dp(c,2));parent.addView(t,p);}
    static void input(EditText input){Context c=input.getContext();input.setTextColor(TEXT);input.setHintTextColor(0xff789182);input.setTextSize(15);input.setPadding(dp(c,14),dp(c,13),dp(c,14),dp(c,13));input.setMinHeight(dp(c,51));input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);StateListDrawable states=new StateListDrawable();GradientDrawable focus=surface(c,INPUT,13);focus.setStroke(dp(c,1),ACCENT);GradientDrawable normal=surface(c,INPUT,13);normal.setStroke(dp(c,1),LINE);states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},normal);input.setBackground(states);}
    static EditText field(LinearLayout parent,String label,String value,boolean secret){label(parent,label);EditText input=new EditText(parent.getContext());input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));input(input);input.setText(value);parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;}
    static TextView note(LinearLayout parent,String value){Context c=parent.getContext();TextView t=text(c,value,12,MUTED);t.setPadding(dp(c,13),dp(c,12),dp(c,13),dp(c,12));t.setBackground(surface(c,CARD,13));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,16),0,dp(c,4));parent.addView(t,p);return t;}
    static void secondary(Button button){Context c=button.getContext();button.setAllCaps(false);button.setTextSize(13);button.setTextColor(ACCENT);button.setMinHeight(dp(c,48));button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x259be5cc),surface(c,CARD,12),null));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,9),0,0);button.setLayoutParams(p);}
    static ImageView icon(Context c,int drawable,int tint){ImageView image=new ImageView(c);image.setImageResource(drawable);image.setImageTintList(ColorStateList.valueOf(tint));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;}
    static View action(Context c,int drawable,String title,String description,boolean primary,Runnable action){
        LinearLayout row=new LinearLayout(c);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(c,15),dp(c,15),dp(c,14),dp(c,15));row.setMinimumHeight(dp(c,78));row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x259be5cc),surface(c,primary?0xff294235:CARD,17),null));row.setFocusable(true);row.setClickable(true);row.setContentDescription(title+". "+description);row.setOnClickListener(v->action.run());
        ImageView glyph=icon(c,drawable,primary?ACCENT:0xffc3d3c8);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,25),dp(c,25));ip.setMargins(0,0,dp(c,15),0);row.addView(glyph,ip);
        LinearLayout copy=column(c);TextView name=text(c,title,16,primary?0xffc5f5df:TEXT);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));copy.addView(name);TextView detail=text(c,description,12,MUTED);LinearLayout.LayoutParams detailLayout=new LinearLayout.LayoutParams(-1,-2);detailLayout.setMargins(0,dp(c,4),0,0);copy.addView(detail,detailLayout);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=icon(c,R.drawable.ic_action_arrow,0xff80988a);row.addView(arrow,new LinearLayout.LayoutParams(dp(c,17),dp(c,17)));return row;
    }
    static void style(AlertDialog dialog,boolean bottom){
        Window window=dialog.getWindow();if(window==null)return;Context c=dialog.getContext();window.setBackgroundDrawable(surface(c,BG,bottom?28:24));window.setDimAmount(.6f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);int screen=c.getResources().getDisplayMetrics().widthPixels;window.setLayout(Math.min(screen-(bottom?0:dp(c,24)),dp(c,560)),WindowManager.LayoutParams.WRAP_CONTENT);if(bottom)window.setGravity(Gravity.BOTTOM);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_NEGATIVE}){Button b=dialog.getButton(which);if(b==null)continue;b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(c,48));b.setTextColor(which==AlertDialog.BUTTON_POSITIVE?ACCENT:MUTED);}
    }
}
