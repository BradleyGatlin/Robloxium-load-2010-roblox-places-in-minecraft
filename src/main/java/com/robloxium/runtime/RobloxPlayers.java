package com.robloxium.runtime;
import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;
/** Roblox Players service backed by the real Minecraft player. */
public final class RobloxPlayers extends RobloxInstance {
    private final RobloxPlayer localPlayer=new RobloxPlayer("LocalPlayer");
    private final RobloxCharacter character=new RobloxCharacter("Player");
    /** Actual host player exposed to LocalPlayer when running inside Minecraft. */
    private volatile Object minecraftLocalPlayer;
    private final RobloxPart root=new RobloxPart("HumanoidRootPart");
    RobloxPlayers(){
        super("Players","Players");
        addChild(localPlayer);
        localPlayer.character(character);
        root.size(new Vec3(2,5,2));
        root.anchored(true); root.canCollide(false);
        character.addChild(root); character.torso(root);
    }
    public RobloxPlayer localPlayer(){return localPlayer;}
    /** Internal Roblox compatibility object. */
    public RobloxPlayer robloxLocalPlayer(){return localPlayer;}
    /** Lua-facing LocalPlayer. The client binds this to Minecraft's actual LocalPlayer. */
    public Object getLocalPlayer(){return minecraftLocalPlayer!=null?minecraftLocalPlayer:localPlayer;}
    public Object LocalPlayer(){return getLocalPlayer();}
    public void setMinecraftLocalPlayer(Object player){minecraftLocalPlayer=player;}
    public void updatePosition(Vec3 position){root.cframe(CFrame.identity().withPosition(position));}
}
