package com.robloxium.runtime;
public final class RobloxDecal extends RobloxInstance {
    private int face;
    private String texture="";
    private double transparency, shininess, specular;
    public RobloxDecal(String name){super("Decal",name);}
    public int face(){return face;} public void face(int v){face=v;}
    public String texture(){return texture;} public void texture(String v){texture=v==null?"":v;}
    public double transparency(){return transparency;} public void transparency(double v){transparency=Math.max(0,Math.min(1,v));}
    public double shininess(){return shininess;} public void shininess(double v){shininess=v;}
    public double specular(){return specular;} public void specular(double v){specular=v;}
}
