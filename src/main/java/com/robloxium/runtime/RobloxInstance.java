package com.robloxium.runtime;

import java.util.*;

/** Base object exposed to the embedded 2010 Roblox-compatible Lua runtime. */
public class RobloxInstance {
    private final String className;
    private String name;
    private RobloxInstance parent;
    private String referent;
    private final List<RobloxInstance> children=new ArrayList<>();
    private final Map<String,String> raw=new LinkedHashMap<>();
    private final Map<String,String> rawTypes=new LinkedHashMap<>();
    private final RobloxSignal childAdded=new RobloxSignal();
    private final RobloxSignal childRemoved=new RobloxSignal();
    private final RobloxSignal changed=new RobloxSignal();

    public RobloxInstance(String className,String name){this.className=className;this.name=name==null?className:name;}
    public String className(){return className;} public String getClassName(){return className;}
    public String name(){return name;} public String getName(){return name;}
    public void name(String v){name=v==null?className:v;changed.fire("Name");}
    public void setName(String v){name(v);}
    public RobloxInstance parent(){return parent;} public RobloxInstance getParent(){return parent;}
    public void parent(RobloxInstance p){if(p==this)return;if(p==null){if(parent!=null)parent.removeChild(this);return;}p.addChild(this);}
    public void setParent(RobloxInstance p){parent(p);}
    public String referent(){return referent;} public void referent(String v){referent=v;}
    public List<RobloxInstance> children(){return Collections.unmodifiableList(children);}
    public List<RobloxInstance> getChildren(){return children();}
    public RobloxSignal getChildAdded(){return childAdded;} public RobloxSignal getChildRemoved(){return childRemoved;} public RobloxSignal getChanged(){return changed;}
    public RobloxSignal ChildAdded(){return childAdded;} public RobloxSignal ChildRemoved(){return childRemoved;} public RobloxSignal Changed(){return changed;}

    public RobloxInstance FindFirstChild(String wanted){
        if(wanted==null)return null;
        for(RobloxInstance c:children)if(wanted.equals(c.name()))return c;
        return null;
    }
    public RobloxInstance findFirstChild(String wanted){return FindFirstChild(wanted);}
    public RobloxInstance GetChildren(int index){return index>=0&&index<children.size()?children.get(index):null;}

    /** Lets Lua's Java userdata property lookup resolve old Roblox child syntax. */
    public RobloxInstance get(String childName){return FindFirstChild(childName);}
    public RobloxInstance Get(String childName){return FindFirstChild(childName);}

    public void addChild(RobloxInstance child){
        if(child==null||child==this)return;
        if(child.parent!=null)child.parent.children.remove(child);
        child.parent=this;
        if(!children.contains(child))children.add(child);
        childAdded.fire(child);
    }
    public void removeChild(RobloxInstance child){
        if(child!=null&&children.remove(child)){child.parent=null;childRemoved.fire(child);}
    }
    public void Remove(){remove();}
    public void remove(){if(parent!=null)parent.removeChild(this);}
    public void Destroy(){remove();}
    public void rawProperty(String k,String v){raw.put(k,v);}
    public void rawPropertyType(String k,String v){rawTypes.put(k,v);}
    public String rawPropertyType(String k){return rawTypes.get(k);}
    public Map<String,String> rawPropertyTypes(){return Collections.unmodifiableMap(rawTypes);}
    public String rawProperty(String k){return raw.get(k);}
    public Map<String,String> rawProperties(){return Collections.unmodifiableMap(raw);}
    public <T extends RobloxInstance> List<T> descendants(Class<T> type){
        List<T> out=new ArrayList<>();
        for(RobloxInstance c:children){if(type.isInstance(c))out.add(type.cast(c));out.addAll(c.descendants(type));}
        return out;
    }
    public List<RobloxInstance> GetDescendants(){return descendants(RobloxInstance.class);}

    /** Marker used by script-created Models without importing renderer code. */
    public interface RobloxModelLike{}
}
