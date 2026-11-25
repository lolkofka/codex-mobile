package local.avr.codex.copy;

/** Runs on the host JDK: no Android runtime or real credentials required. */
public final class LoginFlowTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        LoginFlow f=new LoginFlow();
        long first=f.begin();check(first!=0&&f.working(),"request starts once");
        check(f.begin()==0,"double tap while starting cannot create a second login");
        check(f.accept(first,"first","https://auth.openai.com/codex/device","ABCD-1234"),"server response accepted");
        check(f.waiting()&&!f.working()&&f.begin()==0,"existing ceremony is reused");
        long cancel=f.cancel();check(cancel!=0&&f.working(),"explicit restart waits for cancellation");
        check(!f.complete("first"),"old completion cannot interrupt cancellation");
        f.cancelFailed(cancel);check(f.waiting()&&f.code.equals("ABCD-1234"),"cancel failure preserves current ceremony");
        cancel=f.cancel();f.reset();long second=f.begin();
        check(!f.cancelling(cancel),"late cancellation cannot affect a newer request");
        check(!f.accept(first,"first","https://auth.openai.com/old","OLD"),"late start response is rejected");
        f.accept(second,"second","https://auth.openai.com/codex/device","EFGH-5678");
        check(!f.complete("first")&&f.id.equals("second"),"cancelled login failure keeps new code alive");
        check(!f.complete("")&&f.waiting(),"unrelated null login ID cannot clear pending login");
        check(f.complete("second")&&!f.pending()&&f.url.isEmpty(),"matching completion clears ceremony");
        check(!f.complete("second"),"duplicate completion ignored");
        long interrupted=f.begin();f.reset();
        check(!f.accept(interrupted,"late","https://auth.openai.com/late","CODE"),"engine loss invalidates response");
        check(f.url.isEmpty()&&f.code.isEmpty()&&!f.pending(),"engine loss removes dead links and code");
        long invalid=f.begin();boolean rejected=false;
        try{f.accept(invalid,"","","CODE");}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"malformed response is not offered as a working login");f.reset();
        String safe=LoginFlow.safeError("Failed https://auth.openai.com/callback?code=secret refresh_token=secret Bearer eyJabc.def.ghi");
        check(!safe.contains("secret")&&!safe.contains("eyJabc"),"diagnostic errors redact URLs and tokens");
        System.out.println("PASS: "+checks+" login lifecycle checks");
    }
}
