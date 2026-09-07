package com.robloxium.runtime;

public final class RobloxScript extends RobloxInstance {
    private String source="";
    private boolean local;
    private boolean disabled;
    private String linkedSource="";
    public RobloxScript(String className,String name){super(className,name);}
    public String source(){return source;}
    public void source(String value){source=value==null?"":value;}
    public boolean local(){return local;}
    public boolean disabled(){return disabled;} public void disabled(boolean v){disabled=v;}
    public String linkedSource(){return linkedSource;} public void linkedSource(String v){linkedSource=v==null?"":v;}
    public void local(boolean value){local=value;}
}
