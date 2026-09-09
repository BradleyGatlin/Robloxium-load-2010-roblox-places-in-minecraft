package com.robloxium.runtime;

import java.util.List;
import com.robloxium.math.CFrame;

/** Guest DataModel. Lua sees a Roblox Player proxy backed by Minecraft's real player. */
public final class RobloxGame {
    private final RobloxInstance dataModel=new RobloxInstance("DataModel","Game");
    private final RobloxWorkspace workspace=new RobloxWorkspace();
    private final RobloxLighting lighting=new RobloxLighting();
    private final RobloxPlayers playersService=new RobloxPlayers();
    private final RobloxRunService runService=new RobloxRunService();
    private final java.util.Map<String,RobloxInstance> services=new java.util.LinkedHashMap<>();
    private long tick; private boolean running;
    private final RobloxPhysics physics; private CFrame spawnCFrame=CFrame.identity();
    public RobloxGame(){dataModel.addChild(workspace);addService(playersService);addService(runService);physics=new RobloxPhysics(this);}
    public RobloxInstance dataModel(){return dataModel;}
    public RobloxInstance getDataModel(){return dataModel;}
    public RobloxWorkspace workspace(){return workspace;} public RobloxWorkspace getWorkspace(){return workspace;} public RobloxWorkspace Workspace(){return workspace;}
    public RobloxLighting lighting(){return lighting;} public RobloxLighting getLighting(){return lighting;} public RobloxLighting Lighting(){return lighting;}
    public RobloxPlayers playersService(){return playersService;} public RobloxPlayers getPlayers(){return playersService;} public RobloxPlayers Players(){return playersService;}
    public RobloxRunService runService(){return runService;} public RobloxRunService getRunService(){return runService;}
    public void addService(RobloxInstance service){if(service==null)return;services.put(service.name(),service);if(service.parent()==null)dataModel.addChild(service);}
    public RobloxInstance service(String name){
        if(name==null)return null;
        if("Players".equalsIgnoreCase(name))return playersService;
        if("RunService".equalsIgnoreCase(name))return runService;
        RobloxInstance s=services.get(name);if(s!=null)return s;
        for(var e:services.entrySet())if(e.getKey().equalsIgnoreCase(name))return e.getValue();
        return dataModel.FindFirstChild(name);
    }
    public RobloxInstance Service(String name){return service(name);} public RobloxInstance GetService(String name){return service(name);}
    public CFrame spawnCFrame(){return spawnCFrame;} public void spawnCFrame(CFrame v){spawnCFrame=v==null?CFrame.identity():v;}
    public List<RobloxPlayer> players(){return playersService.GetPlayers();}
    public List<RobloxCharacter> characters(){return dataModel.descendants(RobloxCharacter.class);}
    public long tick(){return tick;} public boolean running(){return running;}
    public synchronized void start(){if(running)return;running=true;RobloxScriptEngine.start(this);}
    public synchronized void stop(){if(!running)return;running=false;RobloxScriptEngine.stop(this);}
    public void step(){if(running){tick++;runService.fire(1.0/20.0);physics.step(1.0/20.0);}}
    public void firePlayerTouched(RobloxPart part){if(part!=null&&part.canCollide())part.fireTouched(playersService.localPlayer());}
    public void firePlayerTouched(RobloxPart part,Object hostPlayer){if(part!=null&&part.canCollide())part.fireTouched(playersService.localPlayer());}
}
