package com.yaseen.npcmind;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.yaseen.npcmind.api.Internal;

/**
 * Library identity holder: the {@code npcmind} registry namespace and the shared logger.
 *
 * <p><b>Since v1.2.0 this project is a developer library, NOT a Forge mod.</b> The class no
 * longer carries a Forge mod entrypoint annotation — the host mod owns the mod identity and
 * activates the library through {@link NPCMindLibrary}. The {@code npcmind} namespace is
 * retained for entity/menu registry names so existing saves and integrations keep working.</p>
 */
@Internal
public final class NPCMind {
    /** Registry namespace for the library's entity and menu registrations. */
    public static final String MOD_ID = "npcmind";
    /** Shared logger for all library subsystems. */
    public static final Logger LOG = LogUtils.getLogger();

    private NPCMind() {}
}
