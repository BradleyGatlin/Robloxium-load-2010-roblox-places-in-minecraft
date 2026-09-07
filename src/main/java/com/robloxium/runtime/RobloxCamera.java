package com.robloxium.runtime;
import com.robloxium.math.CFrame;
public final class RobloxCamera extends RobloxInstance {
    private CFrame cframe=CFrame.identity(), focus=CFrame.identity();
    private int cameraType;
    private String cameraSubjectRef="";
    public RobloxCamera(String name){super("Camera",name);}
    public CFrame cframe(){return cframe;} public void cframe(CFrame v){cframe=v==null?CFrame.identity():v;}
    public CFrame focus(){return focus;} public void focus(CFrame v){focus=v==null?CFrame.identity():v;}
    public int cameraType(){return cameraType;} public void cameraType(int v){cameraType=v;}
    public String cameraSubjectRef(){return cameraSubjectRef;} public void cameraSubjectRef(String v){cameraSubjectRef=v==null?"":v;}
}
