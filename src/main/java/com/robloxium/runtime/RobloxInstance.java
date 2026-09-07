package com.robloxium.runtime;

import java.util.*;

public class RobloxInstance {
    private final String className;
    private String name;
    private RobloxInstance parent;
    private String referent;
    private final List<RobloxInstance> children = new ArrayList<>();
    private final Map<String,String> raw = new LinkedHashMap<>();
    private final Map<String,String> rawTypes = new LinkedHashMap<>();

    public RobloxInstance(String className, String name) { this.className=className; this.name=name==null?className:name; }
    public String className(){return className;}
    public String name(){return name;}
    public String getName(){return name;}
    public void name(String v){name=v==null?className:v;}
    public RobloxInstance parent(){return parent;}
    public RobloxInstance getParent(){return parent;}
    public String referent(){return referent;}
    public void referent(String v){referent=v;}
    public List<RobloxInstance> children(){return Collections.unmodifiableList(children);}
    public List<RobloxInstance> getChildren(){return children();}
    public RobloxInstance FindFirstChild(String wanted){if(wanted==null)return null;for(RobloxInstance c:children)if(wanted.equals(c.name()))return c;return null;}
    public RobloxInstance findFirstChild(String wanted){return FindFirstChild(wanted);}
    public void addChild(RobloxInstance child){ if(child==null||child==this)return; if(child.parent!=null)child.parent.children.remove(child); child.parent=this; children.add(child); }
    public void removeChild(RobloxInstance child){ if(children.remove(child))child.parent=null; }
    public void rawProperty(String k,String v){raw.put(k,v);}
    public void rawPropertyType(String k,String v){rawTypes.put(k,v);}
    public String rawPropertyType(String k){return rawTypes.get(k);}
    public Map<String,String> rawPropertyTypes(){return Collections.unmodifiableMap(rawTypes);}
    public String rawProperty(String k){return raw.get(k);}
    public Map<String,String> rawProperties(){return Collections.unmodifiableMap(raw);}
    public <T extends RobloxInstance> List<T> descendants(Class<T> type){
        List<T> out=new ArrayList<>();
        for(RobloxInstance c:children){ if(type.isInstance(c))out.add(type.cast(c)); out.addAll(c.descendants(type)); }
        return out;
    }
}
