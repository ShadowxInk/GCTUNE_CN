package com.poc.gctune;
import java.io.*;
import java.util.*;
public final class FontInstaller {
    public static void validate(File file) throws Exception {
        FontCore.validateFont(file);
    }
    public static String command(String path, String md5) {
        if(path==null)path=""; if(md5==null)md5="";
        if(!md5.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Font MD5 missing");
        String token=UUID.randomUUID().toString();
        return "( EXPECT="+FontCore.quote(md5)+"; S=; for p in "+FontCore.quote(path)+" /storage/emulated/0/Download/gctune/font.ttf /sdcard/Download/gctune/font.ttf /storage/emulated/0/Android/data/com.poc.gctune/files/font.ttf; do [ -r \"$p\" ] && [ \"$(md5sum \"$p\" | cut -d' ' -f1)\" = \"$EXPECT\" ] && S=\"$p\" && break; done; "
        +"if [ -z \"$S\" ]; then echo SRC_UNREADABLE_OR_STALE; exit 1; fi; echo SRC_OK; "
        +"LIST=$(find /data/vfonts -maxdepth 1 -type f -iname '*.ttf' 2>/dev/null); "
        +"if [ -z \"$LIST\" ]; then echo NO_FONT_FOUND; exit 1; fi; "
        +"PKG=$(pm list packages 2>/dev/null | sed 's/^package://' | grep '^com\\.bbk\\.theme$' | head -n1); "
        +"case \"$PKG\" in *.*.*) am force-stop \"$PKG\";; esac; "
        +"echo \"$LIST\" | while IFS= read -r V; do "
        +"if [ ! -f \"$V\" ] || [ -L \"$V\" ]; then echo \"FAIL:MISSING_TTF:$V\"; continue; fi; "
        +"B=\"$V.gctune-"+token+".bak\"; OLD=$(md5sum \"$V\" | cut -d' ' -f1); "
        +"if [ -e \"$B\" ] || ! cp -p \"$V\" \"$B\" || [ \"$(md5sum \"$B\" | cut -d' ' -f1)\" != \"$OLD\" ]; then echo \"FAIL:BACKUP:$V\"; continue; fi; "
        +"if cat \"$S\" > \"$V\" && [ \"$(md5sum \"$V\" | cut -d' ' -f1)\" = \"$EXPECT\" ]; then echo \"OK:$V\"; echo \"BACKUP:$B\"; "
        +"else cat \"$B\" > \"$V\"; if [ \"$(md5sum \"$V\" | cut -d' ' -f1)\" = \"$OLD\" ]; then echo \"FAIL:ROLLED_BACK:$V\"; else echo \"FAIL:RESTORE_REQUIRED:$V\"; fi; fi; done; "
        +"echo FOUND=$(echo \"$LIST\" | wc -l); "
        +"case \"$PKG\" in *.*.*) ACT=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"$PKG\" 2>/dev/null | tail -n1); echo ACT=$ACT; O=$(am start -n \"$ACT\" 2>&1 | tail -n3); echo \"$O\"; echo \"$O\" | grep -q Starting && echo APP_OPENED || { M=$(monkey -p \"$PKG\" -c android.intent.category.LAUNCHER 1 2>&1 | tail -n2); echo \"$M\"; echo \"$M\" | grep -q 'Events injected' && echo APP_OPENED || echo APP_FAILED; };; *) echo NO_PKG;; esac; )";
    }
}
