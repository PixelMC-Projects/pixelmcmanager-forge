package com.pixelmc.pixelmcmanager.event;

import com.pixelmc.pixelmcmanager.bridge.BridgeAgent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Loader events delegate to the same lifecycle and telemetry operations as the other ports. */
public final class NeoForgeEvents {
    private final WelcomeEventHandler events;
    private final BridgeAgent bridge;
    public NeoForgeEvents(WelcomeEventHandler events, BridgeAgent bridge) { this.events=events; this.bridge=bridge; }
    @SubscribeEvent public void started(ServerStartedEvent e) { events.onServerStarted(e.getServer()); bridge.started(e.getServer()); }
    @SubscribeEvent public void stopping(ServerStoppingEvent e) { events.onServerStopping(e.getServer()); bridge.stopping(e.getServer()); }
    @SubscribeEvent public void stopped(ServerStoppedEvent e) { bridge.stopped(e.getServer()); }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void login(PlayerEvent.PlayerLoggedInEvent e) { if(e.getEntity() instanceof ServerPlayer p) events.onPlayerLoggedIn(p); }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent e) { if(e.getEntity() instanceof ServerPlayer p) events.onPlayerLoggedOut(p); }
    @SubscribeEvent public void chunkLoaded(ChunkEvent.Load e) { if(e.getLevel() instanceof ServerLevel l && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c) bridge.chunkLoaded(l,c); }
    @SubscribeEvent public void chunkUnloaded(ChunkEvent.Unload e) { if(e.getLevel() instanceof ServerLevel l && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c) bridge.chunkUnloaded(l,c); }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void begin(ServerTickEvent.Pre e) { bridge.beginTick(e.getServer()); }
    @SubscribeEvent public void tick(ServerTickEvent.Post e) { events.onServerTick(e.getServer()); }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void end(ServerTickEvent.Post e) { bridge.endTick(e.getServer()); }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public void beginLevel(LevelTickEvent.Pre e) { if(e.getLevel() instanceof ServerLevel l) bridge.beginLevel(l); }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void endLevel(LevelTickEvent.Post e) { if(e.getLevel() instanceof ServerLevel l) bridge.endLevel(l); }
}
