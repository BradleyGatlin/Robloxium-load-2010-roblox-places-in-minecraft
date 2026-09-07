package com.robloxium.runtime;
import com.robloxium.math.Vec3;
public final class RobloxSpecialMesh extends RobloxInstance {
    private int meshType; private String meshId="", textureId=""; private Vec3 scale=new Vec3(1,1,1), offset=Vec3.ZERO, vertexColor=new Vec3(1,1,1);
    public RobloxSpecialMesh(String name){super("SpecialMesh",name);}
    public int meshType(){return meshType;} public void meshType(int v){meshType=v;}
    public String meshId(){return meshId;} public void meshId(String v){meshId=v==null?"":v;}
    public String textureId(){return textureId;} public void textureId(String v){textureId=v==null?"":v;}
    public Vec3 scale(){return scale;} public void scale(Vec3 v){scale=v==null?new Vec3(1,1,1):v;}
    public Vec3 offset(){return offset;} public void offset(Vec3 v){offset=v==null?Vec3.ZERO:v;}
    public Vec3 vertexColor(){return vertexColor;} public void vertexColor(Vec3 v){vertexColor=v==null?new Vec3(1,1,1):v;}
}
