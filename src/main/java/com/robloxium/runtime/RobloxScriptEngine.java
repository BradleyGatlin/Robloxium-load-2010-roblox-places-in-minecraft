package com.robloxium.runtime;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Embedded Lua runtime with a practical Roblox 2010 script surface. */
public final class RobloxScriptEngine {
    private static final ExecutorService EXECUTOR=Executors.newCachedThreadPool(new ScriptThreadFactory());
    private static final ConcurrentHashMap<RobloxGame,List<Future<?>>> RUNNING=new ConcurrentHashMap<>();
    private RobloxScriptEngine(){}

    public static void start(RobloxGame game){
        stop(game);
        List<Future<?>> futures=new CopyOnWriteArrayList<>(); RUNNING.put(game,futures);
        for(RobloxScript script:game.dataModel().descendants(RobloxScript.class)){
            if(script.disabled()||script.source().isBlank())continue;
            futures.add(EXECUTOR.submit(()->run(game,script)));
        }
    }
    public static void stop(RobloxGame game){
        List<Future<?>> f=RUNNING.remove(game);
        if(f!=null)for(Future<?> x:f)x.cancel(true);
    }

    private static void run(RobloxGame game,RobloxScript script){
        try{
            Globals globals=JsePlatform.standardGlobals();
            installGlobals(globals,game,script);
            globals.load(script.source(),script.name()).call();
            System.out.println("[Robloxium] script started: "+script.name());
        }catch(LuaError e){
            System.err.println("[Robloxium] script failed: "+script.name()+" | "+e.getMessage());
        }catch(Throwable e){
            System.err.println("[Robloxium] script failed: "+script.name()+" | "+e);
        }
    }

    private static void installGlobals(Globals g,RobloxGame game,RobloxScript script){
        g.set("game",CoerceJavaToLua.coerce(game));
        g.set("script",CoerceJavaToLua.coerce(script));
        g.set("workspace",CoerceJavaToLua.coerce(game.workspace()));

        g.set("wait",new VarArgFunction(){
            public Varargs invoke(Varargs a){
                double requested=Math.max(0,a.optdouble(1,0.03));
                long start=System.nanoTime(); sleep(requested);
                double elapsed=(System.nanoTime()-start)/1_000_000_000.0;
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(elapsed)});
            }
        });
        g.set("delay",new VarArgFunction(){
            public Varargs invoke(Varargs a){
                double seconds=Math.max(0,a.optdouble(1,0)); LuaValue fn=a.arg(2);
                if(fn.isfunction())EXECUTOR.submit(()->{sleep(seconds);if(!Thread.currentThread().isInterrupted())try{fn.call();}catch(Throwable e){System.err.println("[Robloxium] delayed callback failed: "+e.getMessage());}});
                return LuaValue.NONE;
            }
        });
        g.set("spawn",new OneArgFunction(){
            public LuaValue call(LuaValue fn){
                if(fn.isfunction())EXECUTOR.submit(()->{try{fn.call();}catch(Throwable e){System.err.println("[Robloxium] spawned callback failed: "+e.getMessage());}});
                return LuaValue.NIL;
            }
        });
        g.set("tick",new ZeroArgFunction(){public LuaValue call(){return LuaValue.valueOf(System.nanoTime()/1_000_000_000.0);}});
        g.set("Instance",instanceLibrary());
        g.set("Vector3",vector3Library());
        g.set("CFrame",cframeLibrary());
        g.set("Color3",color3Library());
        g.set("BrickColor",brickColorLibrary());
        g.set("Enum",enumLibrary());
    }

    private static LuaTable instanceLibrary(){
        LuaTable t=new LuaTable();
        t.set("new",new VarArgFunction(){
            public Varargs invoke(Varargs a){
                String cls=a.checkjstring(1); String name=cls;
                RobloxInstance obj=RobloxInstanceFactory.create(cls,name);
                if(a.narg()>=2&&!a.arg(2).isnil()){
                    Object parent=luaObject(a.arg(2)); if(parent instanceof RobloxInstance p)p.addChild(obj);
                }
                return CoerceJavaToLua.coerce(obj);
            }
        });
        return t;
    }

    private static Object luaObject(LuaValue value){return value.isuserdata()?value.touserdata():null;}

    private static LuaTable vector3Library(){
        LuaTable t=new LuaTable();
        t.set("new",new VarArgFunction(){public Varargs invoke(Varargs a){return CoerceJavaToLua.coerce(new Vec3(a.optdouble(1,0),a.optdouble(2,0),a.optdouble(3,0)));}});
        t.set("zero",CoerceJavaToLua.coerce(Vec3.ZERO)); return t;
    }
    private static LuaTable cframeLibrary(){
        LuaTable t=new LuaTable();
        t.set("new",new VarArgFunction(){public Varargs invoke(Varargs a){return CoerceJavaToLua.coerce(new CFrame(new Vec3(a.optdouble(1,0),a.optdouble(2,0),a.optdouble(3,0))));}});
        t.set("Angles",new VarArgFunction(){public Varargs invoke(Varargs a){return CoerceJavaToLua.coerce(CFrame.angles(a.optdouble(1,0),a.optdouble(2,0),a.optdouble(3,0)));}});
        t.set("fromEulerAnglesXYZ",t.get("Angles")); return t;
    }
    private static LuaTable color3Library(){
        LuaTable t=new LuaTable();
        t.set("new",new VarArgFunction(){public Varargs invoke(Varargs a){return CoerceJavaToLua.coerce(new RobloxColor3(a.optdouble(1,0),a.optdouble(2,0),a.optdouble(3,0)));}});
        t.set("fromRGB",new VarArgFunction(){public Varargs invoke(Varargs a){return CoerceJavaToLua.coerce(new RobloxColor3(a.optdouble(1,0)/255.0,a.optdouble(2,0)/255.0,a.optdouble(3,0)/255.0));}}); return t;
    }
    private static LuaTable brickColorLibrary(){
        LuaTable t=new LuaTable();
        t.set("new",new OneArgFunction(){public LuaValue call(LuaValue v){if(v.isnumber())return LuaValue.valueOf(v.toint());return CoerceJavaToLua.coerce(new RobloxBrickColor(v.tojstring()));}}); return t;
    }
    private static LuaTable enumLibrary(){
        LuaTable e=new LuaTable(),m=new LuaTable();
        m.set("Plastic",LuaValue.valueOf(256));m.set("Wood",LuaValue.valueOf(512));m.set("Concrete",LuaValue.valueOf(816));m.set("Grass",LuaValue.valueOf(1280));m.set("Ice",LuaValue.valueOf(1536));
        e.set("Material",m); return e;
    }
    private static void sleep(double seconds){try{long ms=Math.max(0,Math.round(seconds*1000));Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static final class ScriptThreadFactory implements ThreadFactory{private final AtomicInteger id=new AtomicInteger();public Thread newThread(Runnable r){Thread t=new Thread(r,"robloxium-script-"+id.incrementAndGet());t.setDaemon(true);return t;}}
}
