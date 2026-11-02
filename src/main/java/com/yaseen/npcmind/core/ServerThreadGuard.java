package com.yaseen.npcmind.core;

import com.yaseen.npcmind.api.Internal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Central logical-server-thread guard for public entity/world helpers. */
@Internal
public final class ServerThreadGuard {
    private ServerThreadGuard() {}

    /** Returns true only for a live server context on its owning thread. */
    public static boolean isOnServerThread(Entity entity) {
        return entity != null && isOnServerThread(entity.level());
    }

    /** Returns true only for a server level whose server confirms the current thread. */
    public static boolean isOnServerThread(Level level) {
        if (level == null || !(level instanceof ServerLevel serverLevel)) return false;
        MinecraftServer server = serverLevel.getServer();
        return allows(level.isClientSide(), true, server != null, server != null && server.isSameThread());
    }

    static boolean allows(boolean clientSide, boolean serverLevel, boolean serverAvailable, boolean sameThread) {
        return !clientSide && serverLevel && serverAvailable && sameThread;
    }
}
