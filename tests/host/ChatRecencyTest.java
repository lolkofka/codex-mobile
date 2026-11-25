package local.avr.codex.copy;

import java.util.*;

public final class ChatRecencyTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static void order(ChatRecency recent,Map<String,Long> rows,Set<String> pins,String... ids){
        check(recent.order(rows,pins).equals(Arrays.asList(ids)),"Unexpected chat order: "+recent.order(rows,pins));
    }
    public static void main(String[] args){
        ChatRecency recent=new ChatRecency();
        Map<String,Long> rows=new LinkedHashMap<>();
        rows.put("pin-first",1L);rows.put("pin-second",9000L);
        rows.put("old",1000L);rows.put("new",2000L);rows.put("same-time",2000L);
        Set<String> pins=new HashSet<>(Arrays.asList("pin-first","pin-second"));
        order(recent,rows,pins,"pin-first","pin-second","new","same-time","old");
        recent.touch("old",3000L);
        order(recent,rows,pins,"pin-first","pin-second","old","new","same-time");
        recent.touch("pin-second",4000L);
        order(recent,rows,pins,"pin-first","pin-second","old","new","same-time");
        recent.touch("new",4000L);
        order(recent,rows,pins,"pin-first","pin-second","new","old","same-time");
        recent.touch("old",3500L);
        order(recent,rows,pins,"pin-first","pin-second","old","new","same-time");
        rows.put("same-time",5000L);
        order(recent,rows,pins,"pin-first","pin-second","same-time","old","new");
        ChatRecency restarted=new ChatRecency();
        for(Map.Entry<String,Long> item:recent.snapshot().entrySet())restarted.restore(item.getKey(),item.getValue());
        check(restarted.order(rows,pins).equals(recent.order(rows,pins)),"restarting preserves local recency");
        restarted.touch("new",3000L);
        check(restarted.time("new")>recent.time("old"),"clock changes after restart still preserve use order");
        Map<String,Long> filtered=new LinkedHashMap<>();filtered.put("new",2000L);filtered.put("old",1000L);
        order(recent,filtered,pins,"old","new");
        check(!recent.order(filtered,pins).contains("pin-first"),"ordering does not reintroduce filtered or missing chats");
        recent.touch("draft-one",6000L);recent.bind("draft-one","created");
        check(recent.time("created")==6000L&&recent.time("draft-one")==0,"new chat retains its activity when bound to a real ID");
        rows.put("created",0L);
        order(recent,rows,pins,"pin-first","pin-second","created","same-time","old","new");
        recent.remove("created");check(recent.time("created")==0,"deleted or archived chat activity is removed");
        recent.clear();check(recent.snapshot().isEmpty(),"switching phone/PC clears previous target activity");
        order(recent,filtered,Collections.emptySet(),"new","old");
        recent.restore("",50L);recent.restore("invalid",-1L);
        check(recent.snapshot().isEmpty(),"malformed persisted activity is ignored");
        check(ChatRecency.serverTime(1700000000L)==1700000000000L,"server seconds are compared with local milliseconds");
        check(ChatRecency.serverTime(-1)==0&&ChatRecency.serverTime(Long.MAX_VALUE)==0,"invalid server timestamps cannot overflow ordering");
        System.out.println("PASS: "+checks+" chat ordering checks");
    }
}
