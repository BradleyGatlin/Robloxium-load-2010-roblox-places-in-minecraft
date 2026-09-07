package com.robloxium.runtime;

/** Serialized legacy Sound state. Actual playback is host-side, but no place data is lost. */
public final class RobloxSound extends RobloxInstance {
    private String soundId = "";
    private double volume = 0.5;
    private double pitch = 1.0;
    private boolean playing, looping;
    public RobloxSound(String name) { super("Sound", name); }
    public String soundId(){return soundId;} public void soundId(String v){soundId=v==null?"":v;}
    public double volume(){return volume;} public void volume(double v){volume=Math.max(0, v);}
    public double pitch(){return pitch;} public void pitch(double v){pitch=v;}
    public boolean playing(){return playing;} public void playing(boolean v){playing=v;}
    public boolean looping(){return looping;} public void looping(boolean v){looping=v;}
}
