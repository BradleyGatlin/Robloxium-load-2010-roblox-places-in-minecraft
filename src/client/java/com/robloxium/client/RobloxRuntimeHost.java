package com.robloxium.client;
import com.robloxium.runtime.*;
public final class RobloxRuntimeHost {
    private RobloxGame game=new RobloxGame();
    private RobloxPhysics physics=new RobloxPhysics(game);
    public synchronized RobloxGame game(){return game;}
    public synchronized RobloxPhysics physics(){return physics;}
    public synchronized void replace(RobloxGame next){game.stop();game=next;physics=new RobloxPhysics(next);}
    public synchronized void stop(){game.stop();}
}
