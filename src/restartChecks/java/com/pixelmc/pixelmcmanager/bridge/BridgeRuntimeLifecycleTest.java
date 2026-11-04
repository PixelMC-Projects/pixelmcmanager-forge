package com.pixelmc.pixelmcmanager.bridge;

import com.google.gson.JsonObject;
import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Two runtime generations in one JVM, without starting Minecraft, HTTP, or a database. */
public final class BridgeRuntimeLifecycleTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static Field field(String name)throws Exception{Field value=BridgeAgent.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static boolean fieldStopping(BridgeAgent bridge){try{return field("stopping").getBoolean(bridge);}catch(Exception failure){throw new AssertionError(failure);}}
    public static void main(String[] args)throws Exception{
        BridgeAgent bridge=new BridgeAgent(null,null,null,null,null);
        try{
            bridge.prepareRuntime();
            String firstRun=(String)field("run").get(bridge);
            long firstGeneration=field("runtimeGeneration").getLong(bridge);
            ExecutorService firstIo=(ExecutorService)field("io").get(bridge);
            check(firstIo.submit(()->"first").get(2,TimeUnit.SECONDS).equals("first"),"First generation worker accepts work");
            UUID player=UUID.randomUUID();
            @SuppressWarnings("unchecked") Set<UUID> admitted=(Set<UUID>)field("admitted").get(bridge);admitted.add(player);
            @SuppressWarnings("unchecked") BlockingQueue<JsonObject> frames=(BlockingQueue<JsonObject>)field("frames").get(bridge);frames.add(new JsonObject());
            @SuppressWarnings("unchecked") Map<UUID,Long> sessions=(Map<UUID,Long>)field("sessions").get(bridge);sessions.put(player,1L);
            field("archive").set(bridge,UUID.randomUUID().toString());field("world").set(bridge,UUID.randomUUID().toString());
            field("seq").setLong(bridge,7);field("startedAt").setLong(bridge,1);
            try{bridge.prepareRuntime();throw new AssertionError("Active generation must not be reset");}catch(IllegalStateException expected){checks++;}
            CountDownLatch oldEntered=new CountDownLatch(1),neverReleased=new CountDownLatch(1);
            AtomicInteger crossedGeneration=new AtomicInteger();
            CompletableFuture<String> oldFuture=CompletableFuture.supplyAsync(()->{
                oldEntered.countDown();try{neverReleased.await(5,TimeUnit.SECONDS);return firstRun;}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CompletionException(stopped);}
            },firstIo).whenComplete((value,error)->{if(!bridge.runtimeCurrent(firstGeneration)&&!fieldStopping(bridge))crossedGeneration.incrementAndGet();});
            check(oldEntered.await(2,TimeUnit.SECONDS),"Old generation future starts before shutdown");
            field("stopping").setBoolean(bridge,true);bridge.stopWorkers();
            check(oldFuture.isCompletedExceptionally(),"Old worker future completes before reuse");
            check(firstIo.isTerminated(),"Owned worker terminates before another save starts");
            bridge.prepareRuntime();
            ExecutorService secondIo=(ExecutorService)field("io").get(bridge);
            check(firstIo!=secondIo&&!secondIo.isShutdown(),"Second generation gets fresh workers");
            check(secondIo.submit(()->"second").get(2,TimeUnit.SECONDS).equals("second"),"Same JVM second generation accepts work");
            check(!firstRun.equals(field("run").get(bridge)),"Run identity changes across saves");
            check(!field("stopping").getBoolean(bridge)&&!field("finalized").getBoolean(bridge),"Stopped/finalized flags do not poison the next save");
            check(admitted.isEmpty()&&sessions.isEmpty()&&frames.isEmpty(),"Admission, session and telemetry queues are isolated");
            check(field("archive").get(bridge).equals("")&&field("world").get(bridge).equals("")&&field("seq").getLong(bridge)==0,"Previous archive identity and sequence are cleared");
            check(field("playerHubScope").get(bridge)==null&&field("spool").get(bridge)==null,"Previous metadata and spool are not reused");
            ManagerBridgeApi.register("lifecycle-check","Lifecycle","1","safe");
            ManagerBridgeApi.registerStorage("lifecycle-check","{\"version\":1,\"fields\":[]}");
            ManagerBridgeApi.register("lifecycle-check","Lifecycle","1","safe");
            ManagerBridgeApi.registerStorage("lifecycle-check","{\"version\":1,\"fields\":[]}");
            check(ManagerBridgeApi.requiresPlatform("lifecycle-check"),"Identical re-registration retains validated storage");
            try{ManagerBridgeApi.register("lifecycle-check","Different identity","1","safe");throw new AssertionError("Conflicting registration accepted");}catch(IllegalStateException expected){checks++;}
            AtomicInteger callbacks=new AtomicInteger();ManagerBridgeApi.onCallback("lifecycle-check",(op,json)->callbacks.incrementAndGet());
            ManagerBridgeApi.callback("lifecycle-check","test","{}");ManagerBridgeApi.attach(null);ManagerBridgeApi.callback("lifecycle-check","test","{}");
            check(callbacks.get()==1,"Detach revokes old runtime callbacks");
            ManagerBridgeApi.register("lifecycle-check","Lifecycle","1","safe");
            ManagerBridgeApi.onCallback("lifecycle-check",(op,json)->callbacks.addAndGet(10));ManagerBridgeApi.callback("lifecycle-check","test","{}");
            check(callbacks.get()==11,"Fresh runtime callback registration works");
            Runnable oldCallback=()->{if(bridge.runtimeCurrent(firstGeneration))ManagerBridgeApi.callback("lifecycle-check","old","{}");};
            oldCallback.run();
            check(callbacks.get()==11&&crossedGeneration.get()==0,"Late old future/callback cannot execute in the fresh generation");
            System.out.println("Bridge same-JVM lifecycle checks passed: "+checks);
        }finally{bridge.stopWorkers();ManagerBridgeApi.attach(null);}
    }
}
