package com.robloxium.runtime;
import java.util.concurrent.CopyOnWriteArrayList;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
public final class RobloxTouchEvent {
    private final CopyOnWriteArrayList<LuaValue> listeners=new CopyOnWriteArrayList<>();
    public RobloxTouchConnection connect(LuaValue fn){if(fn!=null&&!fn.isnil()&&fn.isfunction())listeners.addIfAbsent(fn);return new RobloxTouchConnection(listeners,fn);}
    public RobloxTouchConnection Connect(LuaValue fn){return connect(fn);}
    void fire(Object hit){for(LuaValue fn:listeners){try{fn.call(CoerceJavaToLua.coerce(hit));}catch(Throwable e){System.err.println("[Robloxium] Touched callback failed: "+e.getMessage());}}}
    public static final class RobloxTouchConnection{private final CopyOnWriteArrayList<LuaValue> list;private final LuaValue fn;RobloxTouchConnection(CopyOnWriteArrayList<LuaValue> l,LuaValue f){list=l;fn=f;}public void disconnect(){list.remove(fn);}public void Disconnect(){disconnect();}}
}
