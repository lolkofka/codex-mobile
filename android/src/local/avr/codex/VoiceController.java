package local.avr.codex;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import java.util.*;

/** Android speech input/output; model text and tools remain in Codex. */
final class VoiceController implements RecognitionListener {
    interface Listener {
        void transcript(String text,boolean send);
        void state(String text);
        void level(float level);
    }
    private final Activity activity;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private AudioFocusRequest focus;
    private boolean conversation, listening, speaking, waiting, muted, ttsReady, cancelled;
    private int seenTurns, misses;
    private String pendingSpeech="", finalUtterance="";

    VoiceController(Activity a,Listener l){activity=a;listener=l;}
    boolean active(){return conversation;}
    boolean muted(){return muted;}
    boolean available(){return SpeechRecognizer.isRecognitionAvailable(activity);}
    void begin(boolean continuous,int currentTurns){
        close();conversation=continuous;seenTurns=currentTurns;muted=false;misses=0;
        if(!available()){listener.state("В системе не найдено распознавание речи");return;}
        AudioManager am=activity.getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS)close();}).build();
        am.requestAudioFocus(focus);listen();
    }
    private void listen(){
        if(listening||speaking||waiting||muted||activity.isFinishing())return;
        if(recognizer==null){recognizer=SpeechRecognizer.createSpeechRecognizer(activity);recognizer.setRecognitionListener(this);}
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        cancelled=false;listening=true;listener.state("Слушаю…");
        try{recognizer.startListening(i);}catch(Exception e){listening=false;listener.state("Не удалось включить микрофон: "+e.getMessage());}
    }
    void sync(boolean busy,int completed,String answer,boolean approval){
        if(!conversation){seenTurns=completed;return;}
        if(approval){cancelRecognition();listener.state("Нужно твоё подтверждение");return;}
        if(completed>seenTurns){
            seenTurns=completed;waiting=false;
            if(!answer.trim().isEmpty())speak(answer);else{listener.state("Ответ не получен. Нажми на сферу, чтобы продолжить.");muted=true;}
        }else if(busy){waiting=true;listener.state("Codex отвечает…");}
    }
    void toggleMute(){
        muted=!muted;
        if(muted){cancelRecognition();listener.state("Микрофон выключен");}
        else if(!waiting&&!speaking)listen();
    }
    void retry(){
        if(tts!=null)tts.stop();speaking=false;waiting=false;muted=false;misses=0;
        cancelRecognition();main.postDelayed(this::listen,200);
    }
    void speak(String raw){
        String text=raw.replaceAll("(?s)```.*?```"," Код показан в чате. ")
            .replaceAll("\\[([^]]+)\\]\\([^)]+\\)","$1").replaceAll("[*#`_]","").trim();
        if(text.isEmpty()){if(conversation)listen();return;}
        pendingSpeech=text;
        if(tts==null){
            listener.state("Готовлю озвучку…");
            tts=new TextToSpeech(activity,status->{
                ttsReady=status==TextToSpeech.SUCCESS;
                if(!ttsReady){listener.state("Не удалось запустить озвучку Android");return;}
                int language=tts.setLanguage(new Locale("ru","RU"));
                if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){
                    listener.state("Для озвучки нужно установить русский голос в настройках Android");return;
                }
                tts.setSpeechRate(1.03f);
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    public void onStart(String id){}
                    public void onDone(String id){main.post(()->{if(id.equals(finalUtterance)){speaking=false;listener.level(0);if(conversation&&!muted)main.postDelayed(()->{if(conversation&&!muted)listen();},450);else listener.state("Озвучка завершена");}});}
                    public void onError(String id){main.post(()->{speaking=false;listener.state("Ошибка озвучки. Нажми на сферу для продолжения.");});}
                });
                flushSpeech();
            });
        }else if(ttsReady)flushSpeech();
    }
    private void flushSpeech(){
        if(pendingSpeech.isEmpty()||!ttsReady)return;
        String text=pendingSpeech;pendingSpeech="";speaking=true;listener.state("Говорю…");
        cancelRecognition();
        int chunkSize=Math.min(3400,TextToSpeech.getMaxSpeechInputLength()-1);
        String sequence=UUID.randomUUID().toString();int count=(text.length()+chunkSize-1)/chunkSize;
        finalUtterance=sequence+"-"+(count-1);
        for(int index=0;index<count;index++){
            int start=index*chunkSize,end=Math.min(text.length(),start+chunkSize);
            tts.speak(text.substring(start,end),index==0?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,new Bundle(),sequence+"-"+index);
        }
    }
    void close(){
        conversation=false;listening=false;speaking=false;waiting=false;pendingSpeech="";
        cancelled=true;
        if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
        if(tts!=null)tts.stop();
        if(focus!=null){activity.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}
    }
    void destroy(){close();if(tts!=null){tts.shutdown();tts=null;}}
    private void cancelRecognition(){cancelled=true;if(recognizer!=null)recognizer.cancel();listening=false;}
    @Override public void onReadyForSpeech(Bundle params){listener.state("Слушаю…");}
    @Override public void onBeginningOfSpeech(){misses=0;}
    @Override public void onRmsChanged(float rms){listener.level(Math.max(0,Math.min(1,(rms+2)/12)));}
    @Override public void onBufferReceived(byte[] buffer){}
    @Override public void onEndOfSpeech(){listening=false;listener.state("Распознаю…");}
    @Override public void onResults(Bundle result){
        listening=false;ArrayList<String> list=result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(list!=null&&!list.isEmpty()){
            waiting=conversation;listener.transcript(list.get(0),conversation);
            listener.state(conversation?"Codex отвечает…":"Текст добавлен в поле ввода");
            if(!conversation&&focus!=null){activity.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);focus=null;}
        }else onError(SpeechRecognizer.ERROR_NO_MATCH);
    }
    @Override public void onPartialResults(Bundle partial){
        ArrayList<String> list=partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(conversation&&list!=null&&!list.isEmpty())listener.state(list.get(0));
    }
    @Override public void onError(int code){
        if(cancelled)return;
        listening=false;
        if(conversation&&!muted&&(code==SpeechRecognizer.ERROR_NO_MATCH||code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT)&&++misses<=3){
            main.postDelayed(()->{if(conversation&&!muted&&!waiting)listen();},650);return;
        }
        String reason=code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS?"Разреши доступ к микрофону":
            code==SpeechRecognizer.ERROR_NETWORK||code==SpeechRecognizer.ERROR_NETWORK_TIMEOUT?"Проверь интернет для распознавания речи":
            code==SpeechRecognizer.ERROR_RECOGNIZER_BUSY?"Распознавание занято. Попробуй ещё раз":"Речь не распознана. Попробуй ещё раз";
        listener.state(reason);if(conversation)muted=true;
    }
    @Override public void onEvent(int type,Bundle params){}
}
