package com.robloxium.runtime;
public final class RobloxAppearance {
    public static final double MIN_SCALE=0.75;
    public static final double MAX_SCALE=1.5;
    private int head=24,torso=23,arms=24,legs=119;
    private double scale=1.0;
    public int head(){return head;} public void head(int v){head=v;}
    public int torso(){return torso;} public void torso(int v){torso=v;}
    public int arms(){return arms;} public void arms(int v){arms=v;}
    public int legs(){return legs;} public void legs(int v){legs=v;}
    public double scale(){return scale;}
    public void scale(double v){scale=Math.max(MIN_SCALE,Math.min(MAX_SCALE,v));}
    public void reset(){head=24;torso=23;arms=24;legs=119;scale=1.0;}
}
