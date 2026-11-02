package com.yaseen.npcmind.ai;

import java.text.Normalizer;
import com.yaseen.npcmind.api.Internal;

/** Keeps Arabic input readable and removes invisible directional controls that can confuse prompts/logs. */
@Internal
public final class InputNormalizer {
    public static String normalize(String input){
        if(input==null) return "";
        String s=Normalizer.normalize(input, Normalizer.Form.NFC);
        s=s.replace("\uFEFF","").replace("\u200E","").replace("\u200F","")
             .replace("\u202A","").replace("\u202B","").replace("\u202C","")
             .replace("\u202D","").replace("\u202E","").replace("\u2066","")
             .replace("\u2067","").replace("\u2068","").replace("\u2069","");
        return s.trim();
    }
    private InputNormalizer(){}
}
