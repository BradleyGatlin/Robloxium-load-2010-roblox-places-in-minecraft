package com.robloxium.runtime;

/** 2010 BrickColor compatibility object. */
public final class RobloxBrickColor {
    private final String name;
    private final int number;
    public RobloxBrickColor(String name){
        this.name=name==null?"Medium stone grey":name;
        this.number=RobloxPart.brickColorNumber(this.name);
    }
    public RobloxBrickColor(int number){this.name="BrickColor("+number+")";this.number=number;}
    public String getName(){return name;}
    public int getNumber(){return number;}
    public int getColor(){return number;}
    public int Number(){return number;}
    @Override public String toString(){return name;}
}
