package com.robloxium.place;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;
import com.robloxium.runtime.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.InputStream;
import java.util.*;

/**
 * 2010-era RBXLX compatibility loader. The important rule here is loss minimization:
 * every serialized Item is retained, every property is retained in its original name,
 * and known 2010 runtime classes get typed state in addition to the raw property bag.
 */
public final class RbxlLoader {
    private RbxlLoader() {}

    private static final Set<String> SERVICES = Set.of(
            "Players","RunService","ContentProvider","ContentFilter","KeyframeSequenceProvider",
            "GuiService","StarterPack","StarterGui","SoundService","PhysicsService","BadgeService",
            "Geometry","Debris","Timer","ScriptInformationProvider","Selection","ChangeHistoryService",
            "ReplicatedFirst","ServerScriptService","ServerStorage","NetworkServer","NetworkClient"
    );

    public static void loadXml(InputStream in, RobloxGame game) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false); f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setXIncludeAware(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document d=f.newDocumentBuilder().parse(in);
        Element root=d.getDocumentElement();
        if(!"roblox".equalsIgnoreCase(root.getTagName())) throw new IllegalArgumentException("Not an XML .rbxl/.rbxlx place");

        Map<String,RobloxInstance> refs=new HashMap<>();
        for(Element item:direct(root,"Item")) readTopLevel(item,game,refs);
        bindWelds(game,refs);
        bindReferences(game,refs);
        chooseSpawn(game);
    }

    private static void readTopLevel(Element item,RobloxGame game,Map<String,RobloxInstance> refs){
        String cls=attr(item,"class","Instance");
        if("Workspace".equalsIgnoreCase(cls)){readItem(item,game.workspace(),game,refs);return;}
        if("Lighting".equalsIgnoreCase(cls)){readLighting(item,game);return;}
        String name=propertyString(item,"Name"); if(name==null||name.isBlank())name=cls;
        if(SERVICES.contains(cls)||"Service".equalsIgnoreCase(cls)){
            RobloxInstance service=("Players".equalsIgnoreCase(cls)?game.playersService():create(cls,name));
            service.referent(attr(item,"referent",null));
            if(service.referent()!=null)refs.put(service.referent(),service);
            readProperties(item,service);
            game.addService(service);
            for(Element child:direct(item,"Item"))readItem(child,service,game,refs);
            return;
        }
        readItem(item,game.dataModel(),game,refs);
    }

    private static void readItem(Element item,RobloxInstance parent,RobloxGame game,Map<String,RobloxInstance> refs){
        String cls=attr(item,"class","Instance");
        String name=propertyString(item,"Name"); if(name==null||name.isBlank())name=cls;

        if("Workspace".equalsIgnoreCase(cls)){
            readProperties(item,game.workspace());
            putRef(item,game.workspace(),refs);
            for(Element child:direct(item,"Item"))readItem(child,game.workspace(),game,refs);
            return;
        }
        if("Lighting".equalsIgnoreCase(cls)){readLighting(item,game);return;}

        RobloxInstance obj=create(cls,name);
        obj.referent(attr(item,"referent",null));
        if(obj.referent()!=null)refs.put(obj.referent(),obj);
        readProperties(item,obj);
        applyTypedProperties(item,obj,game);
        parent.addChild(obj);
        for(Element child:direct(item,"Item"))readItem(child,obj,game,refs);
    }

    private static RobloxInstance create(String cls,String name){
        return switch(cls){
            case "Part","SpawnLocation","WedgePart","CornerWedgePart","TrussPart","Seat","VehicleSeat","Platform" -> new RobloxPart(cls,name);
            case "Humanoid" -> new RobloxHumanoid(name);
            case "Player" -> new RobloxPlayer(name);
            case "Script","LocalScript" -> new RobloxScript(cls,name);
            case "Weld","WeldConstraint","Motor","Glue","Snap","RotateP","RotateV","Motor6D" -> new RobloxWeld(cls,name);
            case "Sound" -> new RobloxSound(name);
            case "Camera" -> new RobloxCamera(name);
            case "Decal","Texture" -> new RobloxDecal(name);
            case "SpecialMesh","BlockMesh","CylinderMesh","FileMesh","BevelMesh" -> new RobloxSpecialMesh(name);
            case "IntValue","BoolValue","NumberValue","StringValue","Vector3Value","CFrameValue","Color3Value","BrickColorValue","ObjectValue" -> new RobloxValue(cls,name);
            case "ScreenGui","BillboardGui","Frame","TextLabel","TextButton","TextBox","ImageLabel","ImageButton","ScrollingFrame" -> new RobloxGuiObject(cls,name);
            default -> SERVICES.contains(cls) ? new RobloxService(cls,name) : new RobloxInstance(cls,name);
        };
    }

    private static void applyTypedProperties(Element item,RobloxInstance obj,RobloxGame game){
        Element props=directOne(item,"Properties"); if(props==null)return;
        for(Element p:elements(props)){
            String n=p.getAttribute("name");
            if(obj instanceof RobloxPart part) apply(part,n,p);
            else if(obj instanceof RobloxWeld weld) applyWeld(weld,n,p);
            else if(obj instanceof RobloxScript script) applyScript(script,n,p);
            else if(obj instanceof RobloxSound sound) applySound(sound,n,p);
            else if(obj instanceof RobloxCamera camera) applyCamera(camera,n,p);
            else if(obj instanceof RobloxDecal decal) applyDecal(decal,n,p);
            else if(obj instanceof RobloxSpecialMesh mesh) applyMesh(mesh,n,p);
            else if(obj instanceof RobloxValue value) value.value(canonical(p));
        }

        if(obj instanceof RobloxSpecialMesh mesh && item.getParentNode() instanceof Element parentItem){
            String parentClass=attr((Element)parentItem,"class","");
            // SpecialMesh is normally a child of a Part. Mirror its render state into the
            // owning Part so the old renderer does not need to walk child instances per frame.
            if(parentClass.endsWith("Part") || "Part".equalsIgnoreCase(parentClass)){
                RobloxPart part=findOwningPart(game,parentItem);
                if(part!=null){
                    part.meshType(mesh.meshType()); part.meshScale(mesh.scale()); part.meshOffset(mesh.offset());
                    part.meshVertexColor(mesh.vertexColor()); part.meshId(mesh.meshId()); part.textureId(mesh.textureId());
                }
            }
        }
    }

    private static RobloxPart findOwningPart(RobloxGame game,Element item){
        String ref=attr(item,"referent","");
        if(!ref.isBlank()){
            RobloxInstance found=findByReferent(game.dataModel(),ref);
            if(found instanceof RobloxPart p)return p;
        }
        return null;
    }
    private static RobloxInstance findByReferent(RobloxInstance root,String ref){
        if(ref.equals(root.referent()))return root;
        for(RobloxInstance c:root.children()){RobloxInstance x=findByReferent(c,ref);if(x!=null)return x;}
        return null;
    }

    private static void readLighting(Element item,RobloxGame game){
        Element props=directOne(item,"Properties"); if(props==null)return;
        for(Element p:elements(props)){
            String n=p.getAttribute("name");
            switch(n.toLowerCase(Locale.ROOT)){
                case "ambient" -> game.lighting().ambient(packedColor(p,game.lighting().ambient()));
                case "outdoorambient" -> game.lighting().outdoorAmbient(packedColor(p,game.lighting().outdoorAmbient()));
                case "colorshift_top","colorshifttop" -> game.lighting().colorShiftTop(packedColor(p,game.lighting().colorShiftTop()));
                case "colorshift_bottom","colorshiftbottom" -> game.lighting().colorShiftBottom(packedColor(p,game.lighting().colorShiftBottom()));
                case "shadowcolor" -> game.lighting().shadowColor(packedColor(p,game.lighting().shadowColor()));
                case "brightness" -> game.lighting().brightness(number(p,1));
                case "environmentdiffusescale" -> game.lighting().environmentDiffuseScale(number(p,0));
                case "environmentspecularscale" -> game.lighting().environmentSpecularScale(number(p,0));
                case "geographiclatitude" -> game.lighting().geographicLatitude(number(p,41));
                case "timeofday" -> game.lighting().timeOfDay(raw(p));
                case "clocktime" -> game.lighting().timeOfDay(number(p,14));
            }
        }
    }

    private static void readProperties(Element item,RobloxInstance obj){
        Element props=directOne(item,"Properties"); if(props==null)return;
        for(Element p:elements(props)){
            String n=p.getAttribute("name"); if(n==null||n.isBlank())continue;
            obj.rawProperty(n,canonical(p));
            obj.rawPropertyType(n,p.getTagName());
        }
    }

    private static void apply(RobloxPart p,String n,Element e){
        try{
            switch(n.toLowerCase(Locale.ROOT)){
                case "cframe","coordinateframe" -> p.cframe(cframe(e));
                case "position" -> p.cframe(p.cframe().withPosition(vec3(e)));
                case "size" -> p.size(vec3(e));
                case "brickcolor" -> p.brickColor(firstInt(e,194));
                case "material" -> p.material(firstInt(e,256));
                case "textureid" -> p.textureId(raw(e));
                case "reflectance" -> p.reflectance(number(e,0));
                case "anchored" -> p.anchored(bool(e));
                case "cancollide" -> p.canCollide(bool(e));
                case "transparency" -> p.transparency(number(e,0));
                case "velocity" -> p.velocity(vec3(e));
                case "rotvelocity" -> p.rotVelocity(vec3(e));
                case "friction" -> p.friction(number(e,p.friction()));
                case "elasticity" -> p.elasticity(number(e,p.elasticity()));
                case "locked" -> p.locked(bool(e));
                case "shape" -> p.shape(firstInt(e,0));
                case "topsurface" -> p.surfaces(firstInt(e,0),p.bottomSurface(),p.frontSurface(),p.backSurface(),p.leftSurface(),p.rightSurface());
                case "bottomsurface" -> p.surfaces(p.topSurface(),firstInt(e,0),p.frontSurface(),p.backSurface(),p.leftSurface(),p.rightSurface());
                case "frontsurface" -> p.surfaces(p.topSurface(),p.bottomSurface(),firstInt(e,0),p.backSurface(),p.leftSurface(),p.rightSurface());
                case "backsurface" -> p.surfaces(p.topSurface(),p.bottomSurface(),p.frontSurface(),firstInt(e,0),p.leftSurface(),p.rightSurface());
                case "leftsurface" -> p.surfaces(p.topSurface(),p.bottomSurface(),p.frontSurface(),p.backSurface(),firstInt(e,0),p.rightSurface());
                case "rightsurface" -> p.surfaces(p.topSurface(),p.bottomSurface(),p.frontSurface(),p.backSurface(),p.leftSurface(),firstInt(e,0));
            }
        }catch(Exception ignored){}
    }
    private static void applyWeld(RobloxWeld w,String n,Element e){
        if("c0".equalsIgnoreCase(n))w.c0(cframe(e)); else if("c1".equalsIgnoreCase(n))w.c1(cframe(e));
    }
    private static void applyScript(RobloxScript s,String n,Element e){
        if("source".equalsIgnoreCase(n))s.source(raw(e));
        else if("disabled".equalsIgnoreCase(n))s.disabled(bool(e));
        else if("linkedsource".equalsIgnoreCase(n))s.linkedSource(raw(e));
    }
    private static void applySound(RobloxSound s,String n,Element e){
        switch(n.toLowerCase(Locale.ROOT)){
            case "soundid" -> s.soundId(raw(e)); case "volume" -> s.volume(number(e,.5)); case "pitch" -> s.pitch(number(e,1));
            case "playing" -> s.playing(bool(e)); case "looped","looping" -> s.looping(bool(e));
        }
    }
    private static void applyCamera(RobloxCamera c,String n,Element e){
        switch(n.toLowerCase(Locale.ROOT)){case "coordinateframe","cframe"->c.cframe(cframe(e));case "focus"->c.focus(cframe(e));case "cameratype"->c.cameraType(firstInt(e,0));case "camerasubject"->c.cameraSubjectRef(raw(e));}
    }
    private static void applyDecal(RobloxDecal d,String n,Element e){
        switch(n.toLowerCase(Locale.ROOT)){case "face"->d.face(firstInt(e,0));case "texture"->d.texture(raw(e));case "transparency"->d.transparency(number(e,0));case "shiny"->d.shininess(number(e,0));case "specular"->d.specular(number(e,0));}
    }
    private static void applyMesh(RobloxSpecialMesh m,String n,Element e){
        switch(n.toLowerCase(Locale.ROOT)){case "meshtype"->m.meshType(firstInt(e,0));case "meshid"->m.meshId(raw(e));case "textureid"->m.textureId(raw(e));case "scale"->m.scale(vec3(e));case "offset"->m.offset(vec3(e));case "vertexcolor"->m.vertexColor(vec3(e));}
    }

    private static int packedColor(Element e,int fallback){
        try{
            Double r=named(e,"R"),g=named(e,"G"),b=named(e,"B");
            if(r!=null&&g!=null&&b!=null){
                int rr=(int)Math.round(Math.max(0,Math.min(1,r))*255),gg=(int)Math.round(Math.max(0,Math.min(1,g))*255),bb=(int)Math.round(Math.max(0,Math.min(1,b))*255);
                return 0xFF000000|(rr<<16)|(gg<<8)|bb;
            }
            String s=raw(e).trim();
            if(s.startsWith("0x")||s.startsWith("0X"))return (int)Long.parseLong(s.substring(2),16);
            try{return (int)Long.parseUnsignedLong(s);}catch(NumberFormatException unsigned){return (int)Long.parseLong(s);}
        }catch(Exception ignored){return fallback;}
    }
    private static void chooseSpawn(RobloxGame game){
        RobloxPart fallback=null,preferred=null;
        for(RobloxPart p:game.workspace().parts()) if("SpawnLocation".equalsIgnoreCase(p.className())){
            if(fallback==null)fallback=p; boolean en=boolString(p.rawProperty("Enabled"),true), neu=boolString(p.rawProperty("Neutral"),true);
            if(en&&neu){preferred=p;break;}
        }
        RobloxPart chosen=preferred!=null?preferred:fallback;
        if(chosen!=null){Vec3 pos=chosen.cframe().position();game.spawnCFrame(chosen.cframe().withPosition(new Vec3(pos.x(),pos.y()+3,pos.z())));} else game.spawnCFrame(CFrame.identity());
    }
    private static boolean boolString(String s,boolean d){if(s==null||s.isBlank())return d;return "true".equalsIgnoreCase(s)||"1".equals(s);}

    private static void bindWelds(RobloxGame game,Map<String,RobloxInstance> refs){
        for(RobloxWeld w:game.dataModel().descendants(RobloxWeld.class)){
            RobloxInstance a=refs.get(w.rawProperty("Part0")), b=refs.get(w.rawProperty("Part1"));
            if(a instanceof RobloxPart pa)w.part0(pa); if(b instanceof RobloxPart pb)w.part1(pb);
        }
    }
    private static void bindReferences(RobloxGame game,Map<String,RobloxInstance> refs){
        // Keep reference properties intact. Runtime objects that understand a reference
        // can resolve it later; this avoids the old loader's destructive nulling of legacy refs.
        for(RobloxInstance i:allInstances(game.dataModel())){
            String subject=i.rawProperty("CameraSubject");
            if(i instanceof RobloxCamera c && subject!=null)c.cameraSubjectRef(subject);
        }
    }
    private static List<RobloxInstance> allInstances(RobloxInstance root){List<RobloxInstance> out=new ArrayList<>();for(RobloxInstance c:root.children()){out.add(c);out.addAll(allInstances(c));}return out;}
    private static void putRef(Element item,RobloxInstance obj,Map<String,RobloxInstance> refs){String ref=attr(item,"referent","");if(!ref.isBlank())refs.put(ref,obj);}

    private static CFrame cframe(Element e){
        Double x=named(e,"X"),y=named(e,"Y"),z=named(e,"Z"); double[][] r=new double[3][3];boolean ok=x!=null&&y!=null&&z!=null;
        for(int i=0;i<3;i++)for(int j=0;j<3;j++){Double v=named(e,"R"+i+j);if(v==null)ok=false;else r[i][j]=v;}
        if(ok)return new CFrame(new Vec3(x,y,z),r);
        double[] n=leafNumbers(e);if(n.length>=12)return new CFrame(new Vec3(n[0],n[1],n[2]),new double[][]{{n[3],n[4],n[5]},{n[6],n[7],n[8]},{n[9],n[10],n[11]}});
        if(n.length>=3)return new CFrame(new Vec3(n[0],n[1],n[2]));return CFrame.identity();
    }
    private static Vec3 vec3(Element e){Double x=named(e,"X"),y=named(e,"Y"),z=named(e,"Z");if(x!=null&&y!=null&&z!=null)return new Vec3(x,y,z);double[]n=leafNumbers(e);return n.length>=3?new Vec3(n[0],n[1],n[2]):Vec3.ZERO;}
    private static String propertyString(Element item,String name){Element props=directOne(item,"Properties");if(props==null)return null;for(Element p:elements(props))if(name.equalsIgnoreCase(p.getAttribute("name")))return raw(p);return null;}
    private static Double named(Element e,String wanted){for(Element n:all(e))if(wanted.equalsIgnoreCase(n.getAttribute("name")))try{return Double.parseDouble(n.getTextContent().trim());}catch(Exception ignored){}return null;}
    private static double[] leafNumbers(Element e){List<Double>a=new ArrayList<>();for(Element n:all(e))if(elements(n).isEmpty())try{a.add(Double.parseDouble(n.getTextContent().trim()));}catch(Exception ignored){}double[]out=new double[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i);return out;}
    private static boolean bool(Element e){return boolString(raw(e),false);}
    private static double number(Element e,double d){try{return Double.parseDouble(raw(e));}catch(Exception x){return d;}}
    private static int firstInt(Element e,int d){try{String s=raw(e).replaceAll("[^0-9-]+"," ").trim();return Integer.parseInt(s.split("\\s+")[0]);}catch(Exception x){return d;}}
    private static String raw(Element e){if(e==null)return "";if(elements(e).isEmpty())return e.getTextContent().trim();return canonical(e);}
    private static String canonical(Element e){
        if(e==null)return ""; if(elements(e).isEmpty())return e.getTextContent().trim();
        List<String> vals=new ArrayList<>(); for(Element c:elements(e)){String n=c.getAttribute("name");String v=canonical(c);vals.add(n==null||n.isBlank()?v:n+"="+v);} return String.join(" ",vals);
    }
    private static String attr(Element e,String n,String d){String s=e.getAttribute(n);return s==null||s.isBlank()?d:s;}
    private static List<Element> direct(Element e,String tag){List<Element>o=new ArrayList<>();for(Element c:elements(e))if(tag.equalsIgnoreCase(c.getTagName()))o.add(c);return o;}
    private static Element directOne(Element e,String tag){for(Element c:elements(e))if(tag.equalsIgnoreCase(c.getTagName()))return c;return null;}
    private static List<Element> elements(Element e){List<Element>o=new ArrayList<>();NodeList n=e.getChildNodes();for(int i=0;i<n.getLength();i++)if(n.item(i) instanceof Element x)o.add(x);return o;}
    private static List<Element> all(Element e){List<Element>o=new ArrayList<>();for(Element c:elements(e)){o.add(c);o.addAll(all(c));}return o;}
}
