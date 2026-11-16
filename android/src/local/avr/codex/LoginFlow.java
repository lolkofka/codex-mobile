package local.avr.codex;

/** In-memory login ceremony. Tokens remain owned and persisted by app-server. */
final class LoginFlow {
    enum Phase { IDLE, STARTING, WAITING, CANCELLING }
    private Phase phase=Phase.IDLE;
    private long revision;
    String id="",url="",code="";

    boolean pending(){return phase!=Phase.IDLE;}
    boolean waiting(){return phase==Phase.WAITING;}
    boolean working(){return phase==Phase.STARTING||phase==Phase.CANCELLING;}
    boolean starting(long ticket){return revision==ticket&&phase==Phase.STARTING;}
    boolean cancelling(long ticket){return revision==ticket&&phase==Phase.CANCELLING;}
    long begin(){if(pending())return 0;phase=Phase.STARTING;return ++revision;}
    boolean accept(long ticket,String id,String url,String code){
        if(!starting(ticket))return false;
        if(id.isEmpty()||url.isEmpty())throw new IllegalArgumentException("Incomplete login response");
        this.id=id;this.url=url;this.code=code;phase=Phase.WAITING;return true;
    }
    boolean complete(String completedId){
        // Late failures from an explicitly cancelled login must not erase a new one.
        if(!waiting()||id.isEmpty()||!id.equals(completedId))return false;
        reset();return true;
    }
    long cancel(){if(!waiting())return 0;phase=Phase.CANCELLING;return ++revision;}
    void cancelFailed(long ticket){if(cancelling(ticket)){phase=Phase.WAITING;++revision;}}
    void reset(){++revision;phase=Phase.IDLE;id="";url="";code="";}

    static String safeError(String value){
        if(value==null)return "";
        String safe=value.replaceAll("https?://\\S+","<url>")
            .replaceAll("(?i)(?:Bearer\\s+)?eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","<token>")
            .replaceAll("(?i)((?:access_token|refresh_token|id_token|authorization)\\s*[=:]\\s*)[^\\s,;]+","$1<redacted>");
        return safe.length()>1200?safe.substring(0,1200):safe;
    }
}
