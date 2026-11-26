package local.avr.codex.copy;

public final class BackgroundDisplayPolicyTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        String primary="  Display: mDisplayId=0 (organized)\n";
        String background="  Display: mDisplayId=27 (organized)\n";
        check(BackgroundDisplayPolicy.ownerHasPackage(primary+" mCurrentFocus=Window{abc u0 example.app/.Main}","example.app"),"visible primary app cannot be moved");
        check(BackgroundDisplayPolicy.ownerHasPackage(primary+" mCurrentFocus=null\n mFocusedApp=ActivityRecord{abc u0 example.app/.Main t1}","example.app"),"keyguard hiding the primary window does not allow stealing the app");
        check(BackgroundDisplayPolicy.ownerHasPackage(primary+" mCurrentFocus=Window{abc u0 com.android.systemui/.Keyguard}\n mFocusedApp=ActivityRecord{abc u0 example.app/.Main t1}","example.app"),"owner app is protected behind keyguard");
        check(!BackgroundDisplayPolicy.ownerHasPackage(background+" mCurrentFocus=Window{abc u0 example.app/.Main}\n"+primary+" mFocusedApp=ActivityRecord{abc u0 example.owner/.Main t1}","example.app"),"app already on a background display is allowed");
        check(!BackgroundDisplayPolicy.ownerHasPackage(primary+" mFocusedApp=ActivityRecord{abc u0 example.application/.Main t1}","example.app"),"package matching is exact");
        check(!BackgroundDisplayPolicy.ownerHasPackage(primary+" mCurrentFocus=null\n mFocusedApp=null","example.app"),"empty primary display does not block another app");
        check(!BackgroundDisplayPolicy.ownerHasPackage(primary+" mFocusedApp=ActivityRecord{abc u0 example.owner/.Main t1}\n"+background+" mFocusedApp=ActivityRecord{abc u0 example.app/.Main t2}","example.app"),"display parsing stops protecting background focus");
        System.out.println("PASS: "+checks+" primary display isolation checks");
    }
}
