package com.robloxium.runtime;

import com.robloxium.math.CFrame;

/** Script-facing Roblox Player proxy; it represents the Minecraft player, not a second avatar. */
public final class RobloxPlayer extends RobloxInstance {
    private RobloxCharacter character; private CFrame spawn=CFrame.identity(); private Object hostPlayer;
    public RobloxPlayer(String name){super("Player",name);}
    public RobloxCharacter character(){return character;} public RobloxCharacter getCharacter(){return character;}
    public void character(RobloxCharacter v){if(character!=null)removeChild(character);character=v;if(v!=null)addChild(v);}
    public void setCharacter(RobloxCharacter v){character(v);}
    public CFrame spawnCFrame(){return spawn;} public void spawnCFrame(CFrame v){spawn=v==null?CFrame.identity():v;}
    public Object hostPlayer(){return hostPlayer;} void hostPlayer(Object v){hostPlayer=v;}
    public RobloxCharacter Character(){return character;}
    public RobloxInstance getPlayerGui(){return FindFirstChild("PlayerGui");}
}
