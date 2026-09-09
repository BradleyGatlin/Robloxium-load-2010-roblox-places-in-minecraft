package com.robloxium.runtime;

public final class RobloxRunService extends RobloxService {
    private final RobloxSignal stepped=new RobloxSignal(),heartbeat=new RobloxSignal(),renderStepped=new RobloxSignal();
    public RobloxRunService(){super("RunService","RunService");}
    public RobloxSignal getStepped(){return stepped;} public RobloxSignal getHeartbeat(){return heartbeat;} public RobloxSignal getRenderStepped(){return renderStepped;}
    public RobloxSignal Stepped(){return stepped;} public RobloxSignal Heartbeat(){return heartbeat;} public RobloxSignal RenderStepped(){return renderStepped;}
    void fire(double dt){stepped.fire(System.nanoTime()/1_000_000_000.0,dt);heartbeat.fire(dt);renderStepped.fire(dt);}
}
