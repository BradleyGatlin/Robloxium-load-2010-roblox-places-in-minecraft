package com.robloxium.runtime;
import com.robloxium.math.CFrame;
public final class RobloxPlayer extends RobloxInstance {
    private RobloxCharacter character;
    private CFrame spawn=CFrame.identity();
    public RobloxPlayer(String name){super("Player",name);}
    public RobloxCharacter character(){return character;}
    public RobloxCharacter getCharacter(){return character;}
    public void character(RobloxCharacter v){if(character!=null)removeChild(character);character=v;if(v!=null)addChild(v);}
    public CFrame spawnCFrame(){return spawn;} public void spawnCFrame(CFrame v){spawn=v;}
}
