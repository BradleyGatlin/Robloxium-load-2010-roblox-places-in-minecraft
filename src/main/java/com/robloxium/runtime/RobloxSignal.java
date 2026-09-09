package com.robloxium.runtime;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/** Small Roblox 2010 compatible signal/event implementation. */
public final class RobloxSignal {
    private final CopyOnWriteArrayList<LuaValue> listeners=new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Object[]> waits=new LinkedBlockingQueue<>();

    public Connection Connect(LuaValue fn){
        if(fn!=null&&fn.isfunction()) listeners.addIfAbsent(fn);
        return new Connection(listeners,fn);
    }
    public Connection connect(LuaValue fn){return Connect(fn);}

    /** Roblox events support event:wait() in old scripts. */
    public Varargs waitEvent(){
        try{
            Object[] args=waits.take();
            LuaValue[] values=new LuaValue[args.length];
            for(int i=0;i<args.length;i++) values[i]=CoerceJavaToLua.coerce(args[i]);
            return LuaValue.varargsOf(values);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return LuaValue.NONE;
        }
    }
    public Varargs Wait(){return waitEvent();}
    // Varargs avoids Object.wait() while still exposing the legacy Lua name :wait().
    public Varargs wait(Object... ignored){return waitEvent();}

    public void fire(Object... args){
        // Wake one waiter for each fired event, just like the old yielding event surface.
        waits.offer(args==null?new Object[0]:args.clone());
        for(LuaValue fn:listeners){
            try{
                LuaValue[] v=new LuaValue[args==null?0:args.length];
                for(int i=0;i<v.length;i++) v[i]=CoerceJavaToLua.coerce(args[i]);
                fn.invoke(LuaValue.varargsOf(v));
            }catch(Throwable t){
                System.err.println("[Robloxium] signal callback failed: "+t.getMessage());
            }
        }
    }

    public static final class Connection{
        private final CopyOnWriteArrayList<LuaValue> list; private final LuaValue fn;
        Connection(CopyOnWriteArrayList<LuaValue> l,LuaValue f){list=l;fn=f;}
        public void Disconnect(){if(fn!=null)list.remove(fn);}
        public void disconnect(){Disconnect();}
    }
}
