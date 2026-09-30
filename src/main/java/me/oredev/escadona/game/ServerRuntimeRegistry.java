package me.oredev.escadona.game;

import net.minecraft.server.MinecraftServer;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ServerRuntimeRegistry {
    private final Map<MinecraftServer, EscadonaRuntime> runtimes = new IdentityHashMap<>();

    public synchronized EscadonaRuntime getOrCreate(MinecraftServer server) {
        return runtimes.computeIfAbsent(server, EscadonaRuntime::create);
    }

    public synchronized void remove(MinecraftServer server) {
        EscadonaRuntime runtime = runtimes.remove(server);
        if (runtime != null) runtime.close();
    }
}
