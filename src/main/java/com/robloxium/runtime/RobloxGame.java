package com.robloxium.runtime;

import java.util.Collections;
import java.util.List;
import com.robloxium.math.CFrame;


/** The guest DataModel. Minecraft's Player is never used as the Roblox character. */
public final class RobloxGame {
    private final RobloxInstance dataModel=new RobloxInstance("DataModel","Game");
    private final RobloxWorkspace workspace=new RobloxWorkspace();
    private final RobloxLighting lighting=new RobloxLighting();
    private final RobloxPlayers playersService=new RobloxPlayers();
    private final java.util.Map<String,RobloxInstance> services=new java.util.LinkedHashMap<>();
    private long tick;
    private boolean running;
    private final RobloxPhysics physics;
    private CFrame spawnCFrame=CFrame.identity();
    public RobloxGame(){dataModel.addChild(workspace); addService(playersService); physics=new RobloxPhysics(this);}
    public RobloxInstance dataModel(){return dataModel;}
    public RobloxWorkspace workspace(){return workspace;}
    public RobloxLighting lighting(){return lighting;}
    public RobloxPlayers playersService(){return playersService;}
    public RobloxPlayers getPlayers(){return playersService;}
    public void addService(RobloxInstance service){if(service==null)return; services.put(service.name(),service); if(service.parent()==null)dataModel.addChild(service);}
    public RobloxInstance service(String name){if(name==null)return null; if("Players".equalsIgnoreCase(name))return playersService; RobloxInstance s=services.get(name); if(s!=null)return s; for(var e:services.entrySet())if(e.getKey().equalsIgnoreCase(name))return e.getValue(); return dataModel.FindFirstChild(name);}
    public RobloxPlayers Players(){return playersService;}
    public RobloxInstance GetService(String name){return service(name);}
    public RobloxWorkspace getWorkspace(){return workspace;}
    public RobloxWorkspace Workspace(){return workspace;}
    public CFrame spawnCFrame(){return spawnCFrame;}
    public void spawnCFrame(CFrame value){spawnCFrame=value==null?CFrame.identity():value;}
    /** Roblox Player instances from a place may still exist in the DataModel,
     * but Robloxium no longer creates or controls a local Roblox avatar. */
    public List<RobloxPlayer> players(){return dataModel.descendants(RobloxPlayer.class);}
    /** Character models remain available for NPCs/scripts. */
    public List<RobloxCharacter> characters(){return dataModel.descendants(RobloxCharacter.class);}
    public long tick(){return tick;}
    public boolean running(){return running;}
    public synchronized void start(){if(running)return;running=true;RobloxScriptEngine.start(this);}
    public synchronized void stop(){if(!running)return;running=false;RobloxScriptEngine.stop(this);}
    public void step(){if(running){tick++;}}
    /** Legacy fallback for callers without a host player. */
    public void firePlayerTouched(RobloxPart part){if(part!=null&&part.canCollide())part.fireTouched(playersService.localPlayer());}
    /** Fire Touched with the actual host player object when available. */
    public void firePlayerTouched(RobloxPart part,Object hostPlayer){if(part!=null&&part.canCollide())part.fireTouched(hostPlayer!=null?hostPlayer:playersService.localPlayer());}
}
