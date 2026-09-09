package com.robloxium.runtime;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

public final class RobloxScriptEngine {
    private static final ExecutorService EXECUTOR=Executors.newCachedThreadPool(new ScriptThreadFactory());
    private static final ConcurrentHashMap<RobloxGame,List<Future<?>>> RUNNING=new ConcurrentHashMap<>();
    private RobloxScriptEngine(){}
    public static void start(RobloxGame game){
        stop(game);
        List<Future<?>> futures=new java.util.concurrent.CopyOnWriteArrayList<>();
        RUNNING.put(game,futures);
        for(RobloxScript script:game.dataModel().descendants(RobloxScript.class))
            if(!script.disabled() && !script.source().isBlank())futures.add(EXECUTOR.submit(()->run(game,script)));
    }
    public static void stop(RobloxGame game){
        List<Future<?>> futures=RUNNING.remove(game);
        if(futures!=null)for(Future<?> future:futures)future.cancel(true);
    }
    private static void run(RobloxGame game,RobloxScript script){try{Globals globals=JsePlatform.standardGlobals();globals.set("wait",new OneArgFunction(){@Override public LuaValue call(LuaValue seconds){long millis=Math.max(0,Math.round(seconds.optdouble(0)*1000));try{Thread.sleep(millis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return LuaValue.NIL;}});globals.set("tick",new org.luaj.vm2.lib.ZeroArgFunction(){@Override public LuaValue call(){return LuaValue.valueOf(System.nanoTime()/1_000_000_000.0);}});globals.set("game",CoerceJavaToLua.coerce(game));globals.set("script",CoerceJavaToLua.coerce(script));globals.load(script.source(),script.name()).call();System.out.println("[Robloxium] script started: "+script.name());}catch(LuaError error){System.err.println("[Robloxium] script failed: "+script.name()+" | "+error.getMessage());}catch(Exception error){System.err.println("[Robloxium] script failed: "+script.name()+" | "+error.getMessage());}}
    private static final class ScriptThreadFactory implements ThreadFactory{private final AtomicInteger id=new AtomicInteger();public Thread newThread(Runnable runnable){Thread thread=new Thread(runnable,"robloxium-script-"+id.incrementAndGet());thread.setDaemon(true);return thread;}}
}
