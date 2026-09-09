package com.robloxium.runtime;

import com.robloxium.math.Vec3;

public final class RobloxHumanoid extends RobloxInstance {
    public enum State{NONE,RUNNING,JUMPING,FREEFALL,LANDED,DEAD,CLIMBING,SEATED,GETTING_UP,FALLING_DOWN,PLATFORM_STANDING}
    private double walkSpeed=16,jumpPower=50,health=100,maxHealth=100;
    private boolean autoRotate=true,platformStand,sit,jump; private State state=State.NONE; private Vec3 moveDirection=Vec3.ZERO;
    private final RobloxSignal died=new RobloxSignal(),running=new RobloxSignal(),jumping=new RobloxSignal(),gettingUp=new RobloxSignal(),freeFalling=new RobloxSignal(),fallingDown=new RobloxSignal(),healthChanged=new RobloxSignal();
    public RobloxHumanoid(String name){super("Humanoid",name);}
    public double walkSpeed(){return walkSpeed;} public void walkSpeed(double v){walkSpeed=Math.max(0,v);changed("WalkSpeed");}
    public double jumpPower(){return jumpPower;} public void jumpPower(double v){jumpPower=Math.max(0,v);changed("JumpPower");}
    public double health(){return health;} public double getHealth(){return health;}
    public void health(double v){double old=health;health=Math.max(0,Math.min(maxHealth,v));if(health!=old){healthChanged.fire(health);changed("Health");if(health<=0&&old>0)died.fire();}}
    public void setHealth(double v){health(v);} public void takeDamage(double amount){health(health-Math.max(0,amount));} public void TakeDamage(double amount){takeDamage(amount);}
    public double maxHealth(){return maxHealth;} public void maxHealth(double v){maxHealth=Math.max(0,v);health(health);changed("MaxHealth");}
    public boolean autoRotate(){return autoRotate;} public void autoRotate(boolean v){autoRotate=v;changed("AutoRotate");}
    public boolean platformStand(){return platformStand;} public void platformStand(boolean v){platformStand=v;changed("PlatformStand");}
    public boolean sit(){return sit;} public void sit(boolean v){sit=v;changed("Sit");}
    public boolean jump(){return jump;} public void jump(boolean v){boolean old=jump;jump=v;if(v&&!old)jumping.fire(true);changed("Jump");}
    public State state(){return state;} public void state(State v){state=v==null?State.NONE:v;}
    public Vec3 moveDirection(){return moveDirection;}
    public void move(double x,double z){double n=Math.sqrt(x*x+z*z);if(n>1){x/=n;z/=n;}moveDirection=new Vec3(x,0,z);if(n>0)running.fire(n*walkSpeed);}
    private void changed(String property){getChanged().fire(property);}
    public double getWalkSpeed(){return walkSpeed;} public void setWalkSpeed(double v){walkSpeed(v);}
    public double getJumpPower(){return jumpPower;} public void setJumpPower(double v){jumpPower(v);}
    public double getMaxHealth(){return maxHealth;} public void setMaxHealth(double v){maxHealth(v);}
    public boolean getAutoRotate(){return autoRotate;} public void setAutoRotate(boolean v){autoRotate(v);}
    public boolean getPlatformStand(){return platformStand;} public void setPlatformStand(boolean v){platformStand(v);}
    public boolean getSit(){return sit;} public void setSit(boolean v){sit(v);}
    public boolean getJump(){return jump;} public void setJump(boolean v){jump(v);}
    public RobloxSignal getDied(){return died;} public RobloxSignal getRunning(){return running;} public RobloxSignal getJumping(){return jumping;} public RobloxSignal getGettingUp(){return gettingUp;} public RobloxSignal getFreeFalling(){return freeFalling;} public RobloxSignal getFallingDown(){return fallingDown;} public RobloxSignal getHealthChanged(){return healthChanged;}
    public RobloxSignal Died(){return died;} public RobloxSignal Running(){return running;} public RobloxSignal Jumping(){return jumping;} public RobloxSignal GettingUp(){return gettingUp;} public RobloxSignal FreeFalling(){return freeFalling;} public RobloxSignal FallingDown(){return fallingDown;} public RobloxSignal HealthChanged(){return healthChanged;}
}
