package com.robloxium.runtime;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;
import java.util.List;

/** Roblox Players service. The LocalPlayer is a proxy representing Minecraft's actual player. */
public final class RobloxPlayers extends RobloxInstance {
    private final RobloxPlayer localPlayer=new RobloxPlayer("LocalPlayer");
    private final RobloxCharacter character=new RobloxCharacter("Player");
    private volatile Object minecraftLocalPlayer;
    private final RobloxPart root=new RobloxPart("HumanoidRootPart");
    RobloxPlayers(){
        super("Players","Players");
        addChild(localPlayer);
        localPlayer.character(character);
        root.size(new Vec3(2,5,2));root.anchored(true);root.canCollide(false);
        character.addChild(root);character.torso(root);
    }
    public RobloxPlayer localPlayer(){return localPlayer;}
    public RobloxPlayer robloxLocalPlayer(){return localPlayer;}
    public RobloxPlayer getLocalPlayer(){return localPlayer;} public RobloxPlayer LocalPlayer(){return localPlayer;} public RobloxPlayer GetLocalPlayer(){return localPlayer;}
    public List<RobloxPlayer> GetPlayers(){return List.of(localPlayer);}
    public int NumPlayers(){return 1;}
    public void setMinecraftLocalPlayer(Object player){minecraftLocalPlayer=player;localPlayer.hostPlayer(player);}
    public void setMinecraftLocalPlayer(Object player,String playerName){setMinecraftLocalPlayer(player);if(playerName!=null&&!playerName.isBlank())localPlayer.name(playerName);}
    public Object minecraftLocalPlayer(){return minecraftLocalPlayer;}
    public void updatePosition(Vec3 position){if(position!=null)root.cframe(CFrame.identity().withPosition(position));}
}
