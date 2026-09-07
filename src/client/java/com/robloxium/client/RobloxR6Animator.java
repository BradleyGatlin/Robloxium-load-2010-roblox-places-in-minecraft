package com.robloxium.client;
import com.robloxium.math.CFrame;
import com.robloxium.runtime.*;
/** Small faithful implementation of the 2010 R6 animation style. */
final class RobloxR6Animator {
    private double time;
    void reset(){time=0;}
    void step(RobloxCharacter c,double dt){
        time+=dt;RobloxHumanoid h=c.humanoid();
        RobloxMotor6D rs=c.joint("Right Shoulder"),ls=c.joint("Left Shoulder"),rh=c.joint("Right Hip"),lh=c.joint("Left Hip");
        if(rs==null||ls==null||rh==null||lh==null)return;
        if(h.state()==RobloxHumanoid.State.JUMPING||h.state()==RobloxHumanoid.State.FREEFALL){set(rs,Math.PI,.5,dt);set(ls,-Math.PI,.5,dt);set(rh,0,.5,dt);set(lh,0,.5,dt);return;}
        if(h.sit()||h.state()==RobloxHumanoid.State.SEATED){set(rs,Math.PI/2,.15,dt);set(ls,-Math.PI/2,.15,dt);set(rh,Math.PI/2,.15,dt);set(lh,-Math.PI/2,.15,dt);return;}
        double amp=h.state()==RobloxHumanoid.State.RUNNING||h.state()==RobloxHumanoid.State.CLIMBING?1:0;
        double freq=h.state()==RobloxHumanoid.State.RUNNING||h.state()==RobloxHumanoid.State.CLIMBING?9:1;
        double fudge=h.state()==RobloxHumanoid.State.CLIMBING?Math.PI:0;
        double d=amp*Math.sin(time*freq);
        set(rs,d+fudge,.5,dt);set(ls,-d-fudge,.5,dt);set(rh,-d,.5,dt);set(lh,d,.5,dt);
    }
    private static void set(RobloxMotor6D j,double target,double rate,double dt){j.desiredAngle(target);j.maxVelocity(rate);double step=rate*Math.max(1,dt*20);double d=target-j.currentAngle();if(Math.abs(d)>step)d=Math.copySign(step,d);j.currentAngle(j.currentAngle()+d);j.transform(CFrame.angles(j.currentAngle(),0,0));}
}
