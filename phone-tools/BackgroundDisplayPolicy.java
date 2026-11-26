package local.avr.codex.copy;

/** The owner keeps the primary app even when keyguard hides its focused window. */
public final class BackgroundDisplayPolicy {
    public static boolean ownerHasPackage(String windowDisplays,String pkg){
        boolean primary=false;
        for(String line:windowDisplays.split("\n")){
            if(line.trim().startsWith("Display: mDisplayId="))primary=line.contains("mDisplayId=0 ");
            if(primary&&(line.contains("mCurrentFocus=")||line.contains("mFocusedApp="))&&line.contains(" "+pkg+"/"))return true;
        }
        return false;
    }
}
