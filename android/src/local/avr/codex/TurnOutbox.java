package local.avr.codex.copy;

import java.util.*;

/** Main-thread delivery state, independent of the view and Android lifecycle. */
final class TurnOutbox {
    static final class Entry {
        final String id,text,model,effort;
        String thread,mode,status="waiting",error="";
        String images="[]", files="[]";
        Entry(String id,String text,String thread,String mode,String model,String effort){
            this.id=id;this.text=text;this.thread=thread;this.mode=mode;this.model=model;this.effort=effort;
        }
    }
    final ArrayList<Entry> entries=new ArrayList<>();
    boolean paused;
    Entry add(String text,String thread,String mode,String model,String effort){
        Entry e=new Entry(UUID.randomUUID().toString(),text,thread,mode,model,effort);entries.add(e);return e;
    }
    Entry get(String id){for(Entry e:entries)if(e.id.equals(id))return e;return null;}
    boolean hasSending(){for(Entry e:entries)if(e.status.equals("sending"))return true;return false;}
    Entry next(boolean busy,String thread,String turn){
        if(hasSending())return null;
        for(Entry e:entries){
            if(!e.status.equals("waiting")||!e.thread.equals(thread))continue;
            // Explicit steering remains usable while the normal queue is paused.
            if(busy){if(!turn.isEmpty()&&e.mode.equals("steer"))return e;}
            else if(!paused)return e;
        }
        return null;
    }
    void bindThread(String id){for(Entry e:entries)if(e.thread.isEmpty())e.thread=id;}
    void failed(Entry e,String error){e.status="failed";e.error=error;paused=true;}
    boolean remove(String id){Entry e=get(id);return e!=null&&!e.status.equals("sending")&&entries.remove(e);}
    void recover(){paused=true;for(Entry e:entries)if(e.status.equals("sending")){e.status="failed";e.error="Доставка не подтверждена. Проверь историю перед повторной отправкой.";}}
}
