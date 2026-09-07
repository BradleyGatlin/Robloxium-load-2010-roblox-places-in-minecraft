package com.robloxium.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;

public final class RobloxCharacter extends RobloxInstance {
    public static final double TORSO_HEIGHT_FROM_FEET_STUDS=3.0;
    public static final double BODY_HEIGHT_STUDS=5.0;
    public static final double BODY_WIDTH_STUDS=4.0;
    public static final double BODY_DEPTH_STUDS=1.0;
    private final RobloxHumanoid humanoid=new RobloxHumanoid("Humanoid");
    private final RobloxAppearance appearance=new RobloxAppearance();
    private final Map<String,RobloxMotor6D> joints=new LinkedHashMap<>();
    private RobloxPart torso;
    private Vec3 velocity=Vec3.ZERO;
    private boolean grounded;
    private double appliedScale=1.0;
    public RobloxCharacter(String name){super("Model",name);addChild(humanoid);}
    public RobloxHumanoid humanoid(){return humanoid;}
    public RobloxHumanoid getHumanoid(){return humanoid;}
    public RobloxAppearance appearance(){return appearance;}
    public RobloxPart torso(){return torso;}
    public void torso(RobloxPart p){torso=p;}
    public Vec3 velocity(){return velocity;} public void velocity(Vec3 v){velocity=v;}
    public boolean grounded(){return grounded;} public void grounded(boolean v){grounded=v;}
    public void joint(RobloxMotor6D j){joints.put(j.name(),j);addChild(j);}
    public RobloxMotor6D joint(String n){return joints.get(n);}
    public Collection<RobloxMotor6D> joints(){return Collections.unmodifiableCollection(joints.values());}
    public List<RobloxPart> bodyParts(){return descendants(RobloxPart.class);}
    public double scale(){return appearance.scale();}
    public double bodyHeightStuds(){return BODY_HEIGHT_STUDS*scale();}
    public double bodyWidthStuds(){return BODY_WIDTH_STUDS*scale();}
    public double bodyDepthStuds(){return BODY_DEPTH_STUDS*scale();}
    public double torsoHeightFromFeetStuds(){return TORSO_HEIGHT_FROM_FEET_STUDS*scale();}
    public double hipHeightStuds(){return TORSO_HEIGHT_FROM_FEET_STUDS*scale();}
    public double colliderWidthStuds(){return torso==null?bodyWidthStuds():torso.size().x();}
    public double colliderDepthStuds(){return torso==null?bodyDepthStuds():torso.size().z();}
    public double colliderHeightStuds(){return bodyHeightStuds();}
    public Bounds collisionBounds(){
        if(torso==null)return new Bounds(-bodyWidthStuds()/2,0,-bodyDepthStuds()/2,bodyWidthStuds()/2,bodyHeightStuds(),bodyDepthStuds()/2);
        CFrame inverse=torso.cframe().inverse();double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
        for(RobloxPart part:bodyParts()){Vec3 center=inverse.transformPoint(part.cframe().position());double hx=part.size().x()/2,hy=part.size().y()/2,hz=part.size().z()/2;double[][] r=inverse.multiply(part.cframe()).rotation();double ex=Math.abs(r[0][0])*hx+Math.abs(r[0][1])*hy+Math.abs(r[0][2])*hz;double ey=Math.abs(r[1][0])*hx+Math.abs(r[1][1])*hy+Math.abs(r[1][2])*hz;double ez=Math.abs(r[2][0])*hx+Math.abs(r[2][1])*hy+Math.abs(r[2][2])*hz;minX=Math.min(minX,center.x()-ex);minY=Math.min(minY,center.y()-ey);minZ=Math.min(minZ,center.z()-ez);maxX=Math.max(maxX,center.x()+ex);maxY=Math.max(maxY,center.y()+ey);maxZ=Math.max(maxZ,center.z()+ez);}
        return new Bounds(minX,minY,minZ,maxX,maxY,maxZ);
    }
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){public double width(){return maxX-minX;}public double height(){return maxY-minY;}public double depth(){return maxZ-minZ;}}
    public void applyAppearance(){
        double ratio=appearance.scale()/appliedScale;
        if(Math.abs(ratio-1.0)>0.000001){
            for(RobloxPart p:bodyParts())p.size(p.size().mul(ratio));
            for(RobloxMotor6D j:joints.values()){
                j.c0(j.c0().withPosition(j.c0().position().mul(ratio)));
                j.c1(j.c1().withPosition(j.c1().position().mul(ratio)));
            }
            appliedScale=appearance.scale();
        }
        solveJoints();
    }
    public void solveJoints(){
        solve("Neck");solve("Left Shoulder");solve("Right Shoulder");solve("Left Hip");solve("Right Hip");
    }
    private void solve(String name){RobloxMotor6D j=joints.get(name);if(j==null||j.part0()==null||j.part1()==null)return;j.part1().cframe(j.part0().cframe().multiply(j.c0()).multiply(j.transform()).multiply(j.c1().inverse()));}
}
