package com.robloxium.runtime;

import com.robloxium.math.CFrame;

/** Legacy rigid joint used by old Roblox places. */
public final class RobloxWeld extends RobloxInstance {
    private RobloxPart part0, part1;
    private CFrame c0 = CFrame.identity(), c1 = CFrame.identity();
    public RobloxWeld(String className, String name) { super(className, name); }
    public RobloxPart part0(){ return part0; }
    public void part0(RobloxPart v){ part0=v; }
    public RobloxPart part1(){ return part1; }
    public void part1(RobloxPart v){ part1=v; }
    public CFrame c0(){ return c0; }
    public void c0(CFrame v){ c0=v==null?CFrame.identity():v; }
    public CFrame c1(){ return c1; }
    public void c1(CFrame v){ c1=v==null?CFrame.identity():v; }
}
