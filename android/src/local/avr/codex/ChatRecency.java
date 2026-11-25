package local.avr.codex.copy;

import java.util.*;

/** Local message activity supplements engine timestamps; pins retain their insertion order. */
final class ChatRecency {
    private final Map<String,Long> usedAt=new LinkedHashMap<>();
    private long lastTime;

    void clear(){usedAt.clear();lastTime=0;}
    void restore(String id,long time){
        if(id.isEmpty()||time<=0)return;
        usedAt.put(id,time);lastTime=Math.max(lastTime,time);
    }
    void touch(String id,long now){
        if(id.isEmpty())return;
        restore(id,Math.max(now,lastTime+1));
    }
    long time(String id){return usedAt.getOrDefault(id,0L);}
    Map<String,Long> snapshot(){return new LinkedHashMap<>(usedAt);}
    void remove(String id){usedAt.remove(id);}
    void bind(String draft,String id){
        long time=Math.max(time(draft),time(id));
        usedAt.remove(draft);restore(id,time);
    }
    static long serverTime(long seconds){
        return seconds>0&&seconds<=Long.MAX_VALUE/1000?seconds*1000:0;
    }
    List<String> order(Map<String,Long> updatedAt,Set<String> pins){
        List<String> ids=new ArrayList<>(updatedAt.keySet());
        ids.sort((a,b)->{
            boolean ap=pins.contains(a),bp=pins.contains(b);
            if(ap||bp)return ap==bp?0:ap?-1:1;
            return Long.compare(Math.max(time(b),updatedAt.get(b)),Math.max(time(a),updatedAt.get(a)));
        });
        return ids;
    }
}
