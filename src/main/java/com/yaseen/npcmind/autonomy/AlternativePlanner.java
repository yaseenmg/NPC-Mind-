package com.yaseen.npcmind.autonomy;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.api.Internal;

/** Keeps a compact record of failed plans so the next plan is not identical. */
@Internal
public final class AlternativePlanner {
    public static void failed(CompanionEntity c,String plan,String reason){c.getWorldMemory().failure("PLAN="+plan+" reason="+reason);c.getDecisions().record("plan_failure",plan,reason);}
    public static String instruction(CompanionEntity c,String plan){return "Plan="+plan+". If it fails, inspect the exact TOOL_RESULT, record the cause, then choose a materially different real-world action instead of repeating blindly.";}
    private AlternativePlanner(){}
}
