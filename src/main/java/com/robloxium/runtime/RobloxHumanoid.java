package com.robloxium.runtime;

import com.robloxium.math.Vec3;

public final class RobloxHumanoid extends RobloxInstance {
    public enum State { NONE,RUNNING,JUMPING,FREEFALL,LANDED,DEAD,CLIMBING,SEATED,GETTING_UP,FALLING_DOWN,PLATFORM_STANDING }
    private double walkSpeed=16, jumpPower=50, health=100, maxHealth=100;
    private boolean autoRotate=true, platformStand, sit, jump;
    private State state=State.NONE;
    private Vec3 moveDirection=Vec3.ZERO;
    public RobloxHumanoid(String name){super("Humanoid",name);}
    public double walkSpeed(){return walkSpeed;} public void walkSpeed(double v){walkSpeed=Math.max(0,v);}
    public double jumpPower(){return jumpPower;} public void jumpPower(double v){jumpPower=Math.max(0,v);}
    public double health(){return health;}
    public double getHealth(){return health;} public void health(double v){health=Math.max(0,Math.min(maxHealth,v));}
    public void setHealth(double v){health(v);}
    public void takeDamage(double amount){health(health-Math.max(0,amount));}
    public void TakeDamage(double amount){takeDamage(amount);}
    public double maxHealth(){return maxHealth;} public void maxHealth(double v){maxHealth=Math.max(0,v);health(health);}
    public boolean autoRotate(){return autoRotate;} public void autoRotate(boolean v){autoRotate=v;}
    public boolean platformStand(){return platformStand;} public void platformStand(boolean v){platformStand=v;}
    public boolean sit(){return sit;} public void sit(boolean v){sit=v;}
    public boolean jump(){return jump;} public void jump(boolean v){jump=v;}
    public State state(){return state;} public void state(State v){state=v==null?State.NONE:v;}
    public Vec3 moveDirection(){return moveDirection;}
    public void move(double x,double z){double n=Math.sqrt(x*x+z*z);if(n>1){x/=n;z/=n;}moveDirection=new Vec3(x,0,z);}
}
