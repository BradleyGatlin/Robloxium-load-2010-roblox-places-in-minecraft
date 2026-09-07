package com.robloxium.runtime;

import com.robloxium.math.CFrame;

public final class RobloxMotor6D extends RobloxInstance {
    private RobloxPart part0,part1;
    private CFrame c0=CFrame.identity(),c1=CFrame.identity(),transform=CFrame.identity();
    private double desiredAngle,currentAngle,maxVelocity;
    public RobloxMotor6D(String name){super("Motor6D",name);}
    public RobloxPart part0(){return part0;} public void part0(RobloxPart v){part0=v;}
    public RobloxPart part1(){return part1;} public void part1(RobloxPart v){part1=v;}
    public CFrame c0(){return c0;} public void c0(CFrame v){c0=v;}
    public CFrame c1(){return c1;} public void c1(CFrame v){c1=v;}
    public CFrame transform(){return transform;} public void transform(CFrame v){transform=v;}
    public double desiredAngle(){return desiredAngle;} public void desiredAngle(double v){desiredAngle=v;}
    public double currentAngle(){return currentAngle;} public void currentAngle(double v){currentAngle=v;}
    public double maxVelocity(){return maxVelocity;} public void maxVelocity(double v){maxVelocity=Math.max(0,v);}
}
