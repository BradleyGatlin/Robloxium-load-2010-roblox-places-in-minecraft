package com.robloxium.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.robloxium.math.RobloxCoordinateSpace;
import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;
import com.robloxium.runtime.*;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.regex.*;

public final class RobloxPartRenderer {
    private static final int LIGHT=0x00F000F0;
    private static final double MATERIAL_TILE_STUDS=6.0;

    private static double materialTileStuds(RobloxPart p){
        return switch(p.material()){
            case 816 -> 4.0;
            default -> MATERIAL_TILE_STUDS;
        };
    }

    private static final double SURFACE_TILE_STUDS=3.0;
    private static final Map<Identifier,RenderType> REPEAT_TEXTURE_TYPES=new HashMap<>();
    private static final String SURFACE_STUDS="textures/2010/materials/surface_studs.png";
    private static final String SURFACE_INLET="textures/2010/materials/surface_inlet.png";
    private static final String SURFACE_UNIVERSAL="textures/2010/materials/surface_universal.png";

    private static final double DETAIL_DISTANCE_STUDS=200.0;

    private static final SkyEnvironment SKY_ENVIRONMENT=SkyEnvironment.load();

    private static final String WHITE="textures/2010/materials/blank.png";
    private static final String FACE="textures/2010/face.png";
    private static final String[] SKY={"textures/2010/sky/rt.png","textures/2010/sky/lf.png","textures/2010/sky/up.png","textures/2010/sky/dn.png","textures/2010/sky/ft.png","textures/2010/sky/bk.png"};
    private static final Map<String,String> MATERIAL_TEXTURES=Map.ofEntries(
        Map.entry("256","textures/2010/materials/blank.png"),
        Map.entry("272","textures/2010/materials/blank.png"),
        Map.entry("288","textures/2010/materials/blank.png"),
        Map.entry("512","textures/2010/materials/wood.png"),
        Map.entry("528","textures/2010/materials/wood.png"),
        Map.entry("800","textures/2010/materials/slate.png"),
        Map.entry("816","textures/2010/materials/concrete.png"),
        Map.entry("1040","textures/2010/materials/rust_combined.png"),
        Map.entry("1056","textures/2010/materials/diamondplate.png"),
        Map.entry("1072","textures/2010/materials/aluminum.png"),
        Map.entry("1280","textures/2010/materials/grass.png"),
        Map.entry("1536","textures/2010/materials/ice.png")
    );
    private static final Map<String,Identifier> TEX=new HashMap<>();
    private static final Map<String,OnlineMesh> MESH_CACHE=new ConcurrentHashMap<>();
    private static final Set<String> MESH_PENDING=ConcurrentHashMap.newKeySet();
    private static final Set<String> MESH_FAILED=ConcurrentHashMap.newKeySet();
    private static final ExecutorService MESH_DOWNLOADS=Executors.newFixedThreadPool(3,r->{
        Thread t=new Thread(r,"robloxium-mesh");
        t.setDaemon(true);
        return t;
    });
    private static final HeadMesh HEAD_MESH=loadHeadMesh();
    private static RobloxLighting CURRENT_LIGHTING=new RobloxLighting();
    private static Vec3 CURRENT_SUN=new Vec3(-.35,.82,-.45).normalized();
    private static net.minecraft.world.phys.Vec3 CURRENT_CAMERA=net.minecraft.world.phys.Vec3.ZERO;
    private static Vec3 CURRENT_ROBLOX_CAMERA=Vec3.ZERO;

    private static net.minecraft.world.phys.Vec3 SCENE_ORIGIN=net.minecraft.world.phys.Vec3.ZERO;

    private static final List<ShadowTriangle> SHADOW_CACHE=new ArrayList<>();
    private static final Map<RobloxPart,List<ShadowTriangle>> SHADOWS_BY_RECEIVER=new IdentityHashMap<>();
    private static List<RobloxPart> CURRENT_SHADOW_PARTS=List.of();

    private static boolean shadowCacheValid;
    private static long shadowCacheSignature;
    private static boolean registered;

    private static final String VULKAN_BACKEND_NAME="vulkan";
    private static Boolean IRIS_PRESENT;
    private static boolean irisPresent(){
        if(IRIS_PRESENT!=null)return IRIS_PRESENT;
        boolean present=false;
        try{
            Class<?> loader=Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst=loader.getMethod("getInstance").invoke(null);
            present=(Boolean)loader.getMethod("isModLoaded",String.class).invoke(inst,"iris");
        }catch(Throwable ignored){}
        IRIS_PRESENT=present;
        return present;
    }
    private static boolean irisShadersActive(){
        if(!irisPresent())return false;
        try{
            Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst=api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(inst));
        }catch(Throwable ignored){
            return true;
        }
    }
    private static void requireVulkanBackend(){

        if(irisPresent())return;
        String backend;
        try{
            backend=RenderSystem.getDevice().getDeviceInfo().backendName();
        }catch(Throwable t){
            throw new IllegalStateException("\n\n"+
                "============================================================\n"+
                "                 ROBLOXIUM VULKAN ERROR                 \n"+
                "============================================================\n"+
                "Robloxium's 2010 renderer requires Minecraft 26.2's\n"+
                "VULKAN graphics backend. The GPU device was not ready.\n"+
                "\n"+
                "Enable: Video Settings -> Graphics API -> Prefer Vulkan\n"+
                "then restart Minecraft.\n"+
                "\n"+
                "Renderer initialization failed before any Roblox geometry\n"+
                "was submitted. This is intentional: Robloxium will NOT\n"+
                "silently run its renderer on OpenGL.\n"+
                "============================================================\n",t);
        }
        if(backend==null || !backend.toLowerCase(Locale.ROOT).contains(VULKAN_BACKEND_NAME)){
            throw new IllegalStateException("\n\n"+
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"+
                "!!                ROBLOXIUM VULKAN ERROR                !!\n"+
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"+
                "!! This Robloxium build is VULKAN ONLY.                  !!\n"+
                "!!                                                        !!\n"+
                "!! Active Minecraft graphics backend: "+String.valueOf(backend)+"\n"+
                "!!                                                        !!\n"+
                "!! OpenGL is deliberately NOT supported by the Roblox    !!\n"+
                "!! 2010 renderer. No Roblox geometry was rendered.       !!\n"+
                "!!                                                        !!\n"+
                "!! Go to Video Settings -> Graphics API -> Prefer Vulkan !!\n"+
                "!! and restart Minecraft.                                 !!\n"+
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n");
        }
    }
    private RobloxPartRenderer(){}
    public static void register(){
        if(registered)return;

        registered=true;
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx->{
            RobloxGame g=RobloxiumClient.HOST.game();
            if(!g.running())return;
            requireVulkanBackend();
            CURRENT_LIGHTING=g.lighting();
            CURRENT_SUN=sunDirection(CURRENT_LIGHTING);
            CURRENT_CAMERA=ctx.levelState().cameraRenderState.pos;
            CURRENT_ROBLOX_CAMERA=new Vec3(
                RobloxCoordinateSpace.toRoblox(CURRENT_CAMERA.x()-SCENE_ORIGIN.x()),
                RobloxCoordinateSpace.toRoblox(CURRENT_CAMERA.y()-SCENE_ORIGIN.y()),
                RobloxCoordinateSpace.toRoblox(CURRENT_CAMERA.z()-SCENE_ORIGIN.z()));
            PoseStack ps=ctx.poseStack();
            var cam=CURRENT_CAMERA;
            SubmitNodeCollector c=ctx.submitNodeCollector();
            ps.pushPose();
            ps.translate(-cam.x(),-cam.y(),-cam.z());

            long signature=shadowSignature(g);
            if(!shadowCacheValid || signature!=shadowCacheSignature) rebuildShadowCache(g);
            drawParts(c,ps,g.workspace().parts(),cam,false);
            ps.popPose();
        });
    }
    static double sceneOriginX(){return SCENE_ORIGIN.x();}
    static double sceneOriginY(){return SCENE_ORIGIN.y();}
    static double sceneOriginZ(){return SCENE_ORIGIN.z();}

    public static void setSceneOrigin(net.minecraft.world.phys.Vec3 origin){
        SCENE_ORIGIN=origin==null?net.minecraft.world.phys.Vec3.ZERO:origin;
        invalidateShadowCache();
    }

    public static void clearSceneOrigin(){
        SCENE_ORIGIN=net.minecraft.world.phys.Vec3.ZERO;
        invalidateShadowCache();
    }

    public static void invalidateShadowCache(){
        SHADOW_CACHE.clear();
        SHADOWS_BY_RECEIVER.clear();
        CURRENT_SHADOW_PARTS=List.of();
        shadowCacheValid=false;
        shadowCacheSignature=0L;
    }

    private static long shadowSignature(RobloxGame game){
        if(game==null)return 0L;
        long h=1469598103934665603L;
        h=mix(h,Double.doubleToLongBits(game.lighting().timeOfDay()));
        h=mix(h,Double.doubleToLongBits(game.lighting().geographicLatitude()));
        h=mix(h,game.lighting().shadowColor());
        for(RobloxPart p:game.workspace().parts()){
            if(!shadowRelevantPart(p))continue;
            h=mix(h,System.identityHashCode(p));
            h=mix(h,p.anchored()?1:0);
            Vec3 vel=p.velocity();
            if(!p.anchored()){
                h=mix(h,Double.doubleToLongBits(vel.x()));
                h=mix(h,Double.doubleToLongBits(vel.y()));
                h=mix(h,Double.doubleToLongBits(vel.z()));
            }
            Vec3 q=p.cframe().position();
            h=mix(h,Double.doubleToLongBits(q.x())); h=mix(h,Double.doubleToLongBits(q.y())); h=mix(h,Double.doubleToLongBits(q.z()));
            h=mix(h,Double.doubleToLongBits(p.size().x())); h=mix(h,Double.doubleToLongBits(p.size().y())); h=mix(h,Double.doubleToLongBits(p.size().z()));
            h=mix(h,p.canCollide()?1:0); h=mix(h,Double.doubleToLongBits(p.transparency()));
        }
        return h;
    }
    private static long mix(long h,long v){h^=v;return h*1099511628211L;}

    public static void rebuildShadowCache(RobloxGame game){
        SHADOW_CACHE.clear();
        SHADOWS_BY_RECEIVER.clear();
        CURRENT_SHADOW_PARTS=game==null?List.of():List.copyOf(game.workspace().parts());
        if(game==null||!game.running()||game.workspace().parts().isEmpty()){
            shadowCacheValid=true;
            return;
        }
        CURRENT_LIGHTING=game.lighting();
        if(CURRENT_LIGHTING.sunFactor()<=0.001){
            shadowCacheValid=true;
            return;
        }
        CURRENT_SUN=sunDirection(CURRENT_LIGHTING);
        Vec3 light=CURRENT_SUN.normalized();
        if(light.lengthSquared()<1e-8){
            shadowCacheValid=true;
            return;
        }
        buildShadowCache(game.workspace().parts(),light);
        shadowCacheSignature=shadowSignature(game);
        shadowCacheValid=true;
    }

    private static Vec3 shadowFocusRoblox(){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null){
            net.minecraft.world.phys.Vec3 p=mc.player.position();
            return new Vec3(
                RobloxCoordinateSpace.toRoblox(p.x()-SCENE_ORIGIN.x()),
                RobloxCoordinateSpace.toRoblox(p.y()-SCENE_ORIGIN.y()),
                RobloxCoordinateSpace.toRoblox(p.z()-SCENE_ORIGIN.z()));
        }
        return CURRENT_ROBLOX_CAMERA==null?Vec3.ZERO:CURRENT_ROBLOX_CAMERA;
    }

    private static void drawSkybox(SubmitNodeCollector c,PoseStack ps,net.minecraft.world.phys.Vec3 cam){

        double r=8.0;
        for(int i=0;i<SKY.length;i++){
            Identifier texture=tex(SKY[i]);
            int face=i;
            c.submitCustomGeometry(ps,skyTextureType(texture),(pose,b)->skyFace(pose,b,face,r,cam));
        }
    }
    private static void skyFace(PoseStack.Pose pose,VertexConsumer b,int face,double r,net.minecraft.world.phys.Vec3 cam){Vec3 o=new Vec3(cam.x(),cam.y(),cam.z()),a,bb,c,d;switch(face){case 0-> {a=o.add(new Vec3(r,-r,-r));bb=o.add(new Vec3(r,-r,r));c=o.add(new Vec3(r,r,r));d=o.add(new Vec3(r,r,-r));}case 1->{a=o.add(new Vec3(-r,-r,r));bb=o.add(new Vec3(-r,-r,-r));c=o.add(new Vec3(-r,r,-r));d=o.add(new Vec3(-r,r,r));}case 2->{a=o.add(new Vec3(-r,r,r));bb=o.add(new Vec3(r,r,r));c=o.add(new Vec3(r,r,-r));d=o.add(new Vec3(-r,r,-r));}case 3->{a=o.add(new Vec3(-r,-r,-r));bb=o.add(new Vec3(r,-r,-r));c=o.add(new Vec3(r,-r,r));d=o.add(new Vec3(-r,-r,r));}case 4->{a=o.add(new Vec3(-r,-r,r));bb=o.add(new Vec3(r,-r,r));c=o.add(new Vec3(r,r,r));d=o.add(new Vec3(-r,r,r));}default->{a=o.add(new Vec3(r,-r,-r));bb=o.add(new Vec3(-r,-r,-r));c=o.add(new Vec3(-r,r,-r));d=o.add(new Vec3(r,r,-r));}}
        Vec3 normal=switch(face){case 0->new Vec3(-1,0,0);case 1->new Vec3(1,0,0);case 2->new Vec3(0,-1,0);case 3->new Vec3(0,1,0);case 4->new Vec3(0,0,-1);default->new Vec3(0,0,1);};skyVertex(pose,b,d,0,0,normal);skyVertex(pose,b,c,1,0,normal);skyVertex(pose,b,bb,1,1,normal);skyVertex(pose,b,bb,1,1,normal);skyVertex(pose,b,a,0,1,normal);skyVertex(pose,b,d,0,0,normal);}
    private static void skyVertex(PoseStack.Pose pose,VertexConsumer b,Vec3 v,float u,float vv,Vec3 normal){b.addVertex(pose,(float)v.x(),(float)v.y(),(float)v.z()).setColor(255,255,255,255).setUv(u,vv).setOverlay(0).setLight(LIGHT).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());}

    private static Vec3 sunDirection(RobloxLighting lighting){

        double t=lighting.timeOfDay()%24.0;
        double latitude=Math.toRadians(lighting.geographicLatitude()-23.5);
        double longitude=Math.toRadians((t-6.0)*15.0);
        double x=Math.cos(latitude)*Math.cos(longitude);
        double y=Math.cos(latitude)*Math.sin(longitude);
        double z=Math.sin(latitude);
        return new Vec3(x,y,z).normalized();
    }
    private static Identifier tex(String s){
        if(s==null||s.isBlank())return tex(WHITE);
        String raw=s.trim();

        if(raw.regionMatches(true,0,"rbxasset://",0,11)){
            String legacy=raw.substring(11).replace('\\','/');
            while(legacy.startsWith("/"))legacy=legacy.substring(1);
            String lowerLegacy=legacy.toLowerCase(Locale.ROOT);
            if(lowerLegacy.startsWith("textures/")){
                String leaf=lowerLegacy.substring("textures/".length());
                if(leaf.equals("face.png"))return tex(FACE);
                String bundled="textures/2010/"+leaf;
                if(RobloxPartRenderer.class.getResource("/assets/robloxium/"+bundled)!=null)return tex(bundled);
            }

            return tex(WHITE);
        }

        Identifier online=RobloxOnlineAssets.texture(raw);
        if(online!=null)return online;
        if(RobloxOnlineAssets.resolveUrl(raw)!=null)return tex(WHITE);

        String n=raw.replace('\\','/');
        while(n.startsWith("/"))n=n.substring(1);
        n=n.toLowerCase(Locale.ROOT);
        return TEX.computeIfAbsent(n,k->Identifier.fromNamespaceAndPath("robloxium",k));
    }
    private static void drawParts(SubmitNodeCollector c,PoseStack ps,List<RobloxPart> parts,net.minecraft.world.phys.Vec3 cam,boolean character){
        Map<Identifier,List<RobloxPart>> solid=new HashMap<>(),translucent=new HashMap<>();
        for(RobloxPart p:parts){
            if(p.transparency()>=1)continue;
            (p.transparency()>0.001?translucent:solid).computeIfAbsent(textureFor(p),k->new ArrayList<>()).add(p);
        }
        for(var entry:solid.entrySet())c.submitCustomGeometry(ps,repeatTextureType(entry.getKey()),(pose,b)->{for(RobloxPart p:entry.getValue())drawPart(pose,b,p);});
        for(var entry:translucent.entrySet()){
            entry.getValue().sort((a,b)->Double.compare(distanceSquared(b,cam),distanceSquared(a,cam)));
            c.submitCustomGeometry(ps,translucentTextureType(entry.getKey()),(pose,b)->{for(RobloxPart p:entry.getValue())drawPart(pose,b,p);});
        }

        drawDecals(c,ps,parts,cam);
        submitSurfaceType(c,ps,parts,3,SURFACE_STUDS);
        submitSurfaceType(c,ps,parts,4,SURFACE_INLET);
        submitSurfaceType(c,ps,parts,5,SURFACE_UNIVERSAL);
    }
    private static double distanceSquared(RobloxPart p,net.minecraft.world.phys.Vec3 cam){Vec3 q=worldPosition(p.cframe().position());double dx=q.x()-cam.x(),dy=q.y()-cam.y(),dz=q.z()-cam.z();return dx*dx+dy*dy+dz*dz;}

    private record ShadowTriangle(Vec3 a,Vec3 b,Vec3 c,Vec3 normal,Vec3 center,double radius,
                                   RobloxPart caster,RobloxPart receiver){}

    private static void buildShadowCache(List<RobloxPart> parts,Vec3 light){

        List<RobloxPart> receivers=new ArrayList<>();
        for(RobloxPart receiver:parts){
            if(receivesShadow(receiver))receivers.add(receiver);
        }

        for(RobloxPart caster:parts){
            if(!castsShadow(caster))continue;
            List<Vec3> casterVertices=boxWorldVertices(caster);
            if(casterVertices.isEmpty())continue;

            for(RobloxPart receiver:receivers){
                if(receiver==caster)continue;
                projectShadowIntoCache(caster,receiver,casterVertices,light);
            }
        }
    }

    private static void projectShadowIntoCache(RobloxPart caster,RobloxPart receiver,
                                                List<Vec3> casterVertices,Vec3 light){

        Vec3 center=receiver.cframe().position();
        Vec3[] axes={receiver.cframe().right().normalized(),receiver.cframe().up().normalized(),receiver.cframe().back().normalized()};
        double hx=Math.abs(receiver.size().x()*receiver.meshScale().x())*.5;
        double hy=Math.abs(receiver.size().y()*receiver.meshScale().y())*.5;
        double hz=Math.abs(receiver.size().z()*receiver.meshScale().z())*.5;
        ReceiverFace[] faces={
            new ReceiverFace(axes[0],center.add(axes[0].mul(hx)),axes[1],axes[2],hy,hz),
            new ReceiverFace(axes[0].mul(-1),center.sub(axes[0].mul(hx)),axes[1],axes[2],hy,hz),
            new ReceiverFace(axes[1],center.add(axes[1].mul(hy)),axes[0],axes[2],hx,hz),
            new ReceiverFace(axes[1].mul(-1),center.sub(axes[1].mul(hy)),axes[0],axes[2],hx,hz),
            new ReceiverFace(axes[2],center.add(axes[2].mul(hz)),axes[0],axes[1],hx,hy),
            new ReceiverFace(axes[2].mul(-1),center.sub(axes[2].mul(hz)),axes[0],axes[1],hx,hy)
        };

        for(ReceiverFace face:faces){
            if(face.normal().dot(light)<=0.001)continue;
            List<FacePoint> projected=new ArrayList<>(casterVertices.size());
            for(Vec3 vertex:casterVertices){
                Vec3 ray=light.mul(-1.0);
                double denom=ray.dot(face.normal());
                if(Math.abs(denom)<1e-9)continue;
                double t=face.point().sub(vertex).dot(face.normal())/denom;
                if(t<=0.0)continue;
                Vec3 hit=vertex.add(ray.mul(t));
                Vec3 rel=hit.sub(face.point());
                projected.add(new FacePoint(rel.dot(face.u()),rel.dot(face.v()),hit));
            }
            if(projected.size()<3)continue;

            List<FacePoint> hull=convexHullFace(projected);
            if(hull.size()<3)continue;
            List<FacePoint> clipped=clipFacePolygon(hull,face.halfU(),face.halfV());
            if(clipped.size()<3)continue;

            Vec3 offset=face.normal().mul(0.01);
            FacePoint root=clipped.get(0);
            for(int i=1;i<clipped.size()-1;i++){
                Vec3 a=facePoint3(face,root).add(offset);
                Vec3 b=facePoint3(face,clipped.get(i)).add(offset);
                Vec3 c=facePoint3(face,clipped.get(i+1)).add(offset);

                Vec3 triCenter=a.add(b).add(c).mul(1.0/3.0);
                double radius=Math.max(triCenter.sub(a).length(),Math.max(triCenter.sub(b).length(),triCenter.sub(c).length()));

                if(shadowTriangleFullyOccluded(caster,a,b,c,face.normal()))continue;

                ShadowTriangle tri=new ShadowTriangle(a,b,c,face.normal(),triCenter,radius,caster,receiver);
                SHADOW_CACHE.add(tri);
                SHADOWS_BY_RECEIVER.computeIfAbsent(receiver,k->new ArrayList<>()).add(tri);
            }
        }
    }

    private static double partRadius(RobloxPart p){
        Vec3 s=p.size(),m=p.meshScale();
        double x=Math.abs(s.x()*m.x())*.5, y=Math.abs(s.y()*m.y())*.5, z=Math.abs(s.z()*m.z())*.5;
        return Math.sqrt(x*x+y*y+z*z);
    }

    private static Vec3 facePoint3(ReceiverFace face,FacePoint p){
        return face.point().add(face.u().mul(p.u())).add(face.v().mul(p.v()));
    }

    private static List<FacePoint> clipFacePolygon(List<FacePoint> input,double halfU,double halfV){
        List<FacePoint> out=input;
        out=clipEdge(out,0,halfU);
        out=clipEdge(out,1,-halfU);
        out=clipEdge(out,2,halfV);
        out=clipEdge(out,3,-halfV);
        return out;
    }

    private static List<FacePoint> clipEdge(List<FacePoint> input,int edge,double limit){
        if(input.isEmpty())return input;
        List<FacePoint> out=new ArrayList<>();
        FacePoint prev=input.get(input.size()-1);
        boolean prevInside=insideEdge(prev,edge,limit);
        for(FacePoint cur:input){
            boolean curInside=insideEdge(cur,edge,limit);
            if(curInside!=prevInside){
                double px=prev.u(),py=prev.v(),cx=cur.u(),cy=cur.v();
                double d;
                if(edge<2){
                    d=cx-px;
                    if(Math.abs(d)>1e-12){
                        double t=(limit-px)/d;
                        double y=py+(cy-py)*t;
                        out.add(new FacePoint(limit,y,null));
                    }
                }else{
                    d=cy-py;
                    if(Math.abs(d)>1e-12){
                        double t=(limit-py)/d;
                        double x=px+(cx-px)*t;
                        out.add(new FacePoint(x,limit,null));
                    }
                }
            }
            if(curInside)out.add(cur);
            prev=cur;
            prevInside=curInside;
        }
        return out;
    }

    private static boolean insideEdge(FacePoint p,int edge,double limit){
        return edge==0?p.u()<=limit:edge==1?p.u()>=limit:edge==2?p.v()<=limit:p.v()>=limit;
    }

    private static boolean shadowRelevantPart(RobloxPart p){
        return p!=null && p.transparency()<0.999;
    }

    private static boolean castsShadow(RobloxPart p){

        return shadowRelevantPart(p);
    }

    private static boolean receivesShadow(RobloxPart p){

        return shadowRelevantPart(p);
    }

    private static List<Vec3> boxWorldVertices(RobloxPart p){
        Vec3 s=p.size(),scale=p.meshScale();
        double hx=Math.abs(s.x()*scale.x())*.5;
        double hy=Math.abs(s.y()*scale.y())*.5;
        double hz=Math.abs(s.z()*scale.z())*.5;
        Vec3[] local={
            new Vec3(-hx,-hy,-hz),new Vec3(hx,-hy,-hz),
            new Vec3(hx,-hy,hz),new Vec3(-hx,-hy,hz),
            new Vec3(-hx,hy,-hz),new Vec3(hx,hy,-hz),
            new Vec3(hx,hy,hz),new Vec3(-hx,hy,hz)
        };
        List<Vec3> out=new ArrayList<>(8);
        for(Vec3 v:local)out.add(p.cframe().transformPoint(v));
        return out;
    }

    private record ReceiverFace(Vec3 normal,Vec3 point,Vec3 u,Vec3 v,double halfU,double halfV){}
    private record FacePoint(double u,double v,Vec3 point){}

    private static List<FacePoint> convexHullFace(List<FacePoint> points){
        List<FacePoint> p=new ArrayList<>(points);
        p.sort(Comparator.comparingDouble(FacePoint::u).thenComparingDouble(FacePoint::v));
        List<FacePoint> lower=new ArrayList<>(),upper=new ArrayList<>();
        for(FacePoint q:p){
            while(lower.size()>=2&&cross2(lower.get(lower.size()-1),lower.get(lower.size()-2),q)<=0)
                lower.remove(lower.size()-1);
            lower.add(q);
        }
        for(int i=p.size()-1;i>=0;i--){
            FacePoint q=p.get(i);
            while(upper.size()>=2&&cross2(upper.get(upper.size()-1),upper.get(upper.size()-2),q)<=0)
                upper.remove(upper.size()-1);
            upper.add(q);
        }
        if(lower.size()>1)lower.remove(lower.size()-1);
        if(upper.size()>1)upper.remove(upper.size()-1);
        lower.addAll(upper);
        return lower;
    }

    private static double cross2(FacePoint a,FacePoint b,FacePoint c){
        return (a.u()-b.u())*(c.v()-b.v())-(a.v()-b.v())*(c.u()-b.u());
    }

    private static void shadowTriangle(PoseStack.Pose pose,VertexConsumer b,Vec3 a,Vec3 bb,Vec3 c,Vec3 normal,int color){
        Vec3 n=normal.normalized();
        vertexUnlit(pose,b,worldPosition(a),color,0,0,n);
        vertexUnlit(pose,b,worldPosition(bb),color,1,0,n);
        vertexUnlit(pose,b,worldPosition(c),color,0,1,n);
        vertexUnlit(pose,b,worldPosition(c),color,0,1,n);
        vertexUnlit(pose,b,worldPosition(bb),color,1,0,n);
        vertexUnlit(pose,b,worldPosition(a),color,0,0,n);
    }

    private static void mergeIntersectingShadowTriangles(){
        if(SHADOWS_BY_RECEIVER.isEmpty())return;
        SHADOW_CACHE.clear();
        for(List<ShadowTriangle> list:SHADOWS_BY_RECEIVER.values()){
            boolean changed=true;
            while(changed && list.size()>1){
                changed=false;
                outer:
                for(int i=0;i<list.size();i++){
                    ShadowTriangle a=list.get(i);
                    for(int j=i+1;j<list.size();j++){
                        ShadowTriangle b=list.get(j);
                        if(!coplanar(a,b))continue;
                        if(!shadowTrianglesOverlap(a,b))continue;
                        ShadowTriangle merged=mergeShadowPair(a,b);
                        if(merged==null)continue;
                        list.set(i,merged);
                        list.remove(j);
                        changed=true;
                        break outer;
                    }
                }
            }
            SHADOW_CACHE.addAll(list);
        }
    }

    private static boolean coplanar(ShadowTriangle a,ShadowTriangle b){
        Vec3 n=a.normal().normalized();
        if(Math.abs(n.dot(b.normal().normalized()))<0.995)return false;
        return Math.abs(b.a().sub(a.a()).dot(n))<0.08 &&
               Math.abs(b.b().sub(a.a()).dot(n))<0.08 &&
               Math.abs(b.c().sub(a.a()).dot(n))<0.08;
    }

    private static boolean shadowTrianglesOverlap(ShadowTriangle a,ShadowTriangle b){
        Vec3 n=a.normal().normalized();
        Vec3 u=Math.abs(n.x())<0.8?new Vec3(1,0,0):new Vec3(0,1,0);
        u=u.sub(n.mul(u.dot(n))).normalized();
        Vec3 v=n.cross(u).normalized();
        double[][] pa=projectTri(a,u,v), pb=projectTri(b,u,v);

        double[][][] tris={pa,pb};
        for(double[][] tri:tris){
            for(int e=0;e<3;e++){
                double ex=tri[(e+1)%3][0]-tri[e][0], ey=tri[(e+1)%3][1]-tri[e][1];
                double ax=-ey, ay=ex;
                double amin=Double.POSITIVE_INFINITY,amax=Double.NEGATIVE_INFINITY;
                double bmin=Double.POSITIVE_INFINITY,bmax=Double.NEGATIVE_INFINITY;
                for(double[] q:pa){double d=q[0]*ax+q[1]*ay;amin=Math.min(amin,d);amax=Math.max(amax,d);}
                for(double[] q:pb){double d=q[0]*ax+q[1]*ay;bmin=Math.min(bmin,d);bmax=Math.max(bmax,d);}
                if(amax<bmin-1e-7||bmax<amin-1e-7)return false;
            }
        }
        return true;
    }

    private static double[][] projectTri(ShadowTriangle t,Vec3 u,Vec3 v){
        return new double[][]{
            {t.a().dot(u),t.a().dot(v)},
            {t.b().dot(u),t.b().dot(v)},
            {t.c().dot(u),t.c().dot(v)}
        };
    }

    private static ShadowTriangle mergeShadowPair(ShadowTriangle a,ShadowTriangle b){
        Vec3 n=a.normal().normalized();
        Vec3 u=Math.abs(n.x())<0.8?new Vec3(1,0,0):new Vec3(0,1,0);
        u=u.sub(n.mul(u.dot(n))).normalized();
        Vec3 v=n.cross(u).normalized();
        List<double[]> pts=new ArrayList<>(6);
        double[][] aa=projectTri(a,u,v), bb=projectTri(b,u,v);
        Collections.addAll(pts,aa);Collections.addAll(pts,bb);
        List<double[]> hull=hull2d(pts);
        if(hull.size()<3)return null;
        double sourceArea=triArea2d(aa)+triArea2d(bb);
        double hullArea=polyArea2d(hull);
        if(sourceArea<=1e-8 || hullArea>sourceArea*1.18)return null;

        double interLower=Math.max(0.0,sourceArea-hullArea);
        if(interLower<1e-7)return null;
        Vec3 origin=a.a();
        Vec3 p0=origin.add(u.mul(hull.get(0)[0]-origin.dot(u))).add(v.mul(hull.get(0)[1]-origin.dot(v)));
        Vec3 p1=origin.add(u.mul(hull.get(1)[0]-origin.dot(u))).add(v.mul(hull.get(1)[1]-origin.dot(v)));
        Vec3 p2=origin.add(u.mul(hull.get(2)[0]-origin.dot(u))).add(v.mul(hull.get(2)[1]-origin.dot(v)));

        if(hull.size()!=3)return null;
        Vec3 center=p0.add(p1).add(p2).mul(1.0/3.0);
        double radius=Math.max(center.sub(p0).length(),Math.max(center.sub(p1).length(),center.sub(p2).length()));
        return new ShadowTriangle(p0,p1,p2,n,center,radius,a.caster(),a.receiver());
    }

    private static double triArea2d(double[][] t){
        return Math.abs((t[1][0]-t[0][0])*(t[2][1]-t[0][1])-(t[1][1]-t[0][1])*(t[2][0]-t[0][0]))*.5;
    }

    private static double polyArea2d(List<double[]> p){
        double a=0;for(int i=0;i<p.size();i++){double[] x=p.get(i),y=p.get((i+1)%p.size());a+=x[0]*y[1]-x[1]*y[0];}return Math.abs(a)*.5;
    }

    private static List<double[]> hull2d(List<double[]> pts){
        pts=new ArrayList<>(pts);
        pts.sort((a,b)->a[0]==b[0]?Double.compare(a[1],b[1]):Double.compare(a[0],b[0]));
        List<double[]> h=new ArrayList<>();
        for(double[] p:pts){while(h.size()>=2&&cross2d(h.get(h.size()-2),h.get(h.size()-1),p)<=1e-9)h.remove(h.size()-1);h.add(p);}
        int lower=h.size();
        for(int i=pts.size()-2;i>=0;i--){double[] p=pts.get(i);while(h.size()>lower&&cross2d(h.get(h.size()-2),h.get(h.size()-1),p)<=1e-9)h.remove(h.size()-1);h.add(p);}
        if(h.size()>1)h.remove(h.size()-1);
        return h;
    }

    private static double cross2d(double[] a,double[] b,double[] c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}

    private static long gridKey(int x,int y,int z){

        return ((long)(x&0x1FFFFF)<<42)|((long)(y&0x1FFFFF)<<21)|(long)(z&0x1FFFFF);
    }

    private static void drawCachedShadows(SubmitNodeCollector c,PoseStack ps){

    }
    private static void drawCharacter(SubmitNodeCollector c,PoseStack ps,RobloxCharacter ch){if(ch==null)return;Map<Identifier,List<RobloxPart>> groups=new HashMap<>();for(RobloxPart p:ch.bodyParts())if(p.transparency()<1)groups.computeIfAbsent(textureFor(p),k->new ArrayList<>()).add(p);for(var entry:groups.entrySet()){boolean translucent=entry.getValue().stream().anyMatch(p->p.transparency()>0.001);c.submitCustomGeometry(ps,translucent?translucentTextureType(entry.getKey()):repeatTextureType(entry.getKey()),(pose,b)->{for(RobloxPart p:entry.getValue())drawPart(pose,b,p);});}drawDecals(c,ps,ch.bodyParts(),CURRENT_CAMERA);}
    private static Identifier textureFor(RobloxPart p){
        String id=p.textureId()==null?"":p.textureId().trim();
        if(!id.isBlank()){
            if(RobloxOnlineAssets.resolveUrl(id)!=null)return tex(id);
            String normalized=id.replace('\\','/');
            String lower=normalized.toLowerCase(Locale.ROOT);
            String asset=localAssetTexture(lower);
            if(asset!=null)return tex(asset);
            int hash=lower.lastIndexOf('/');
            String leaf=hash>=0?lower.substring(hash+1):lower;
            if(lower.contains("face")||leaf.equals("face.png"))return tex(FACE);
            if(lower.contains("wood"))return tex("textures/2010/materials/wood.png");
            if(lower.contains("grass"))return tex("textures/2010/materials/grass.png");
            if(lower.contains("slate"))return tex("textures/2010/materials/slate.png");
            if(lower.contains("concrete"))return tex("textures/2010/materials/concrete.png");
            if(lower.contains("diamond"))return tex("textures/2010/materials/diamondplate.png");
            if(lower.contains("ice"))return tex("textures/2010/materials/ice.png");
            if(lower.contains("aluminum")||lower.contains("foil"))return tex("textures/2010/materials/aluminum.png");
            if(lower.contains("rust")||lower.contains("corroded"))return tex("textures/2010/materials/rust_combined.png");
            if(lower.contains("dirt"))return tex("textures/2010/materials/dirt.png");
            if(lower.contains("blank"))return tex(WHITE);
            if(lower.startsWith("textures/2010/"))return tex(normalized);
        }
        return tex(MATERIAL_TEXTURES.getOrDefault(Integer.toString(p.material()),WHITE));
    }
    private static String localAssetTexture(String id){
        String digits=id.replaceAll("[^0-9]","");
        if(digits.isBlank())return null;
        String file=digits+".png";
        Path config=Path.of("config","robloxium","assets",file);
        if(Files.isRegularFile(config))return "assets/"+file;
        String bundled="textures/assets/"+file;
        if(RobloxPartRenderer.class.getResource("/robloxium/"+bundled)!=null)return bundled;
        return null;
    }

    private static void drawPart(PoseStack.Pose pose,VertexConsumer b,RobloxPart p){
        Vec3 size=p.size(),mesh=p.meshScale();
        Vec3 visual=new Vec3(size.x()*mesh.x(),size.y()*mesh.y(),size.z()*mesh.z());
        double hx=visual.x()/2,hy=visual.y()/2,hz=visual.z()/2;
        int col=variedColor(p);

        if(!p.meshId().isBlank()){
            OnlineMesh onlineMesh=resolveMesh(p.meshId());
            if(onlineMesh!=null){
                boolean fitToPart="MeshPart".equalsIgnoreCase(p.className());
                drawOnlineMesh(pose,b,p,onlineMesh,col,fitToPart);
                return;
            }
        }
        int meshType=p.meshType();

        if(meshType==0 && "Head".equalsIgnoreCase(p.name())
                && detailDistanceSquared(p)<=DETAIL_DISTANCE_STUDS*DETAIL_DISTANCE_STUDS){
            drawHead(pose,b,p,col);
            return;
        }
        if(meshType==1){drawTorso(pose,b,p,hx,hy,hz,col);return;}
        if(meshType==2){drawWedge(pose,b,p,hx,hy,hz,col);return;}
        if(meshType==3){drawSphere(pose,b,p,col);return;}
        if(meshType==4){drawCylinder(pose,b,p,hx,hy,hz,col);return;}
        if(meshType==11){drawCornerWedge(pose,b,p,hx,hy,hz,col);return;}

        if("WedgePart".equalsIgnoreCase(p.className())){
            drawWedge(pose,b,p,hx,hy,hz,col);
            return;
        }
        if("CornerWedgePart".equalsIgnoreCase(p.className())){
            drawCornerWedge(pose,b,p,hx,hy,hz,col);
            return;
        }
        switch(p.shape()){
            case 0 -> { drawSphere(pose,b,p,col); return; }
            case 2 -> { drawCylinder(pose,b,p,hx,hy,hz,col); return; }
            case 3 -> { drawWedge(pose,b,p,hx,hy,hz,col); return; }
            case 4 -> { drawCornerWedge(pose,b,p,hx,hy,hz,col); return; }
            case 1 -> {  }
            default -> {  }
        }
        drawCube(pose,b,p,hx,hy,hz,col);
    }
    private static double detailDistanceSquared(RobloxPart p){
        double dx=p.cframe().position().x()-CURRENT_ROBLOX_CAMERA.x();
        double dy=p.cframe().position().y()-CURRENT_ROBLOX_CAMERA.y();
        double dz=p.cframe().position().z()-CURRENT_ROBLOX_CAMERA.z();
        return dx*dx+dy*dy+dz*dz;
    }

    private static void drawDecals(SubmitNodeCollector c,PoseStack ps,List<RobloxPart> parts,net.minecraft.world.phys.Vec3 cam){
        for(RobloxPart p:parts){
            if(p.transparency()>=1)continue;
            for(RobloxInstance child:p.children()){
                if(!(child instanceof RobloxDecal d)||d.transparency()>=1||d.texture().isBlank())continue;
                Identifier texture=tex(d.texture());
                c.submitCustomGeometry(ps,translucentTextureType(texture),(pose,b)->drawDecal(pose,b,p,d));
            }
        }
    }

    private static void drawDecal(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,RobloxDecal d){
        Vec3 size=p.size(),m=p.meshScale();
        double hx=Math.abs(size.x()*m.x())*.5,hy=Math.abs(size.y()*m.y())*.5,hz=Math.abs(size.z()*m.z())*.5;
        final double off=.003;
        Vec3 a,bb,c,e,n;
        switch(d.face()){
            case 0 -> {n=new Vec3(1,0,0);a=new Vec3(hx+off,-hy,-hz);bb=new Vec3(hx+off,-hy,hz);c=new Vec3(hx+off,hy,hz);e=new Vec3(hx+off,hy,-hz);}
            case 1 -> {n=new Vec3(0,1,0);a=new Vec3(-hx,hy+off,hz);bb=new Vec3(hx,hy+off,hz);c=new Vec3(hx,hy+off,-hz);e=new Vec3(-hx,hy+off,-hz);}
            case 2 -> {n=new Vec3(0,0,1);a=new Vec3(hx,-hy,hz+off);bb=new Vec3(-hx,-hy,hz+off);c=new Vec3(-hx,hy,hz+off);e=new Vec3(hx,hy,hz+off);}
            case 3 -> {n=new Vec3(-1,0,0);a=new Vec3(-hx-off,-hy,hz);bb=new Vec3(-hx-off,-hy,-hz);c=new Vec3(-hx-off,hy,-hz);e=new Vec3(-hx-off,hy,hz);}
            case 4 -> {n=new Vec3(0,-1,0);a=new Vec3(-hx,-hy-off,-hz);bb=new Vec3(hx,-hy-off,-hz);c=new Vec3(hx,-hy-off,hz);e=new Vec3(-hx,-hy-off,hz);}
            default -> {n=new Vec3(0,0,-1);a=new Vec3(-hx,-hy,-hz-off);bb=new Vec3(hx,-hy,-hz-off);c=new Vec3(hx,hy,-hz-off);e=new Vec3(-hx,hy,-hz-off);}
        }
        Vec3 wa=world(p,a),wb=world(p,bb),wc=world(p,c),wd=world(p,e),wn=worldNormal(p,n);
        int alpha=(int)Math.round((1-d.transparency())*255);
        int color=(alpha<<24)|0xFFFFFF;

        vertexSurface(pose,b,wa,color,0,0,wn,p);
        vertexSurface(pose,b,wb,color,1,0,wn,p);
        vertexSurface(pose,b,wc,color,1,1,wn,p);
        vertexSurface(pose,b,wc,color,1,1,wn,p);
        vertexSurface(pose,b,wd,color,0,1,wn,p);
        vertexSurface(pose,b,wa,color,0,0,wn,p);
    }

    private static void drawOnlineMesh(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,OnlineMesh mesh,int color){
        drawOnlineMesh(pose,b,p,mesh,color,false);
    }
    private static void drawOnlineMesh(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,OnlineMesh mesh,int color,boolean fitToPart){
        Vec3 scale=p.meshScale(),off=p.meshOffset();
        double sx=scale.x(),sy=scale.y(),sz=scale.z();
        double cx=0,cy=0,cz=0;
        if(fitToPart){
            Vec3 part=p.size(),half=mesh.half(),center=meshCenter(mesh);
            sx=(part.x()*scale.x())/Math.max(half.x()*2.0,1e-9);
            sy=(part.y()*scale.y())/Math.max(half.y()*2.0,1e-9);
            sz=(part.z()*scale.z())/Math.max(half.z()*2.0,1e-9);
            cx=center.x();cy=center.y();cz=center.z();
        }
        for(int i=0;i<mesh.positions().length;i+=3){
            Vec3 a=mesh.positions()[i],bb=mesh.positions()[i+1],cc=mesh.positions()[i+2];
            a=new Vec3((a.x()-cx)*sx+off.x(),(a.y()-cy)*sy+off.y(),(a.z()-cz)*sz+off.z());
            bb=new Vec3((bb.x()-cx)*sx+off.x(),(bb.y()-cy)*sy+off.y(),(bb.z()-cz)*sz+off.z());
            cc=new Vec3((cc.x()-cx)*sx+off.x(),(cc.y()-cy)*sy+off.y(),(cc.z()-cz)*sz+off.z());
            Vec3 na=mesh.normals()[i],nb=mesh.normals()[i+1],nc=mesh.normals()[i+2];
            float[] ua=mesh.uvs()[i],ub=mesh.uvs()[i+1],uc=mesh.uvs()[i+2];
            triangleSmooth(pose,b,p,a,bb,cc,na,nb,nc,color,new float[]{ua[0],ua[1],ub[0],ub[1],uc[0],uc[1]});
        }
    }

    private static final Map<OnlineMesh,Vec3> MESH_CENTERS=new IdentityHashMap<>();
    private static Vec3 meshCenter(OnlineMesh mesh){
        return MESH_CENTERS.computeIfAbsent(mesh,m->{
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
            double maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;
            for(Vec3 x:m.positions()){
                minX=Math.min(minX,x.x());minY=Math.min(minY,x.y());minZ=Math.min(minZ,x.z());
                maxX=Math.max(maxX,x.x());maxY=Math.max(maxY,x.y());maxZ=Math.max(maxZ,x.z());
            }
            return new Vec3((minX+maxX)*.5,(minY+maxY)*.5,(minZ+maxZ)*.5);
        });
    }

    private static OnlineMesh resolveMesh(String id){
        if(id==null||id.isBlank())return null;
        String key=id.trim();
        OnlineMesh cached=MESH_CACHE.get(key);
        if(cached!=null)return cached;
        String assetId=meshAssetId(key);
        if(assetId!=null){
            cached=MESH_CACHE.get(assetId);
            if(cached!=null){
                MESH_CACHE.put(key,cached);
                return cached;
            }
        }
        OnlineMesh mesh=null;
        try{
            try{mesh=RobloxOnlineAssets.mesh(id);}catch(Throwable ignored){}
            if(mesh==null)mesh=loadMeshBytesFromAssets(id);
            if(mesh==null)mesh=loadLocalMesh(id);
        }catch(Throwable ignored){
            mesh=null;
        }
        if(mesh!=null){
            MESH_CACHE.put(key,mesh);
            if(assetId!=null)MESH_CACHE.put(assetId,mesh);
            return mesh;
        }
        requestOnlineMesh(key,assetId);
        return MESH_CACHE.get(key);
    }

    private static String meshAssetId(String raw){
        if(raw==null)return null;
        Matcher query=Pattern.compile("(?:^|[?&/])id=(\\d+)",Pattern.CASE_INSENSITIVE).matcher(raw);
        if(query.find())return query.group(1);
        Matcher rbx=Pattern.compile("rbxassetid://(\\d+)",Pattern.CASE_INSENSITIVE).matcher(raw);
        if(rbx.find())return rbx.group(1);
        String trimmed=raw.trim();
        if(trimmed.matches("\\d{3,12}"))return trimmed;
        return null;
    }

    private static void requestOnlineMesh(String key,String assetId){
        if(assetId==null||MESH_FAILED.contains(assetId)||MESH_FAILED.contains(key))return;
        if(!MESH_PENDING.add(assetId))return;
        MESH_DOWNLOADS.execute(()->{
            try{
                byte[] bytes=downloadRobloxAsset(assetId);
                if(bytes==null||bytes.length<8){
                    MESH_FAILED.add(assetId);
                    return;
                }
                bytes=maybeGunzip(bytes);
                OnlineMesh mesh=parseMeshAsset(bytes);
                if(mesh==null){
                    MESH_FAILED.add(assetId);
                    return;
                }
                cacheDownloadedMesh(assetId,bytes);
                MESH_CACHE.put(assetId,mesh);
                MESH_CACHE.put(key,mesh);
            }catch(Throwable t){
                MESH_FAILED.add(assetId);
            }finally{
                MESH_PENDING.remove(assetId);
            }
        });
    }

    private static void cacheDownloadedMesh(String assetId,byte[] bytes){
        try{
            Path dir=Path.of("config","robloxium","assets");
            Files.createDirectories(dir);
            Files.write(dir.resolve(assetId+".mesh"),bytes);
        }catch(Exception ignored){}
    }

    private static byte[] downloadRobloxAsset(String assetId){
        String[] meta={
            "https://assetdelivery.roblox.com/v2/assetId/"+assetId,
            "https://assetdelivery.roproxy.com/v2/assetId/"+assetId
        };
        for(String url:meta){
            byte[] body=httpGet(url);
            if(body==null||body.length==0)continue;
            String text=new String(body,0,Math.min(body.length,8192),StandardCharsets.UTF_8);
            String location=jsonString(text,"location");
            if(location!=null&&!location.isBlank()){
                byte[] file=httpGet(location);
                if(file!=null&&file.length>=8)return file;
            }
            if(looksLikeMesh(body))return body;
        }
        String[] direct={
            "https://assetdelivery.roblox.com/v1/asset/?id="+assetId,
            "https://assetdelivery.roproxy.com/v1/asset/?id="+assetId,
            "https://www.roblox.com/asset/?id="+assetId
        };
        for(String url:direct){
            byte[] body=httpGet(url);
            if(body==null||body.length<8)continue;
            if(looksLikeMesh(body))return body;
            String text=new String(body,0,Math.min(body.length,4096),StandardCharsets.UTF_8);
            String location=jsonString(text,"location");
            if(location==null){
                Matcher m=Pattern.compile("https?://[^\\s\"'<>]+").matcher(text);
                if(m.find())location=m.group();
            }
            if(location!=null){
                byte[] file=httpGet(location);
                if(file!=null&&looksLikeMesh(file))return file;
            }
        }
        return null;
    }

    private static boolean looksLikeMesh(byte[] data){
        if(data==null||data.length<8)return false;
        if(data[0]==0x1f&&data[1]==(byte)0x8b)return true;
        String head=new String(data,0,Math.min(data.length,16),StandardCharsets.US_ASCII);
        return head.startsWith("version ")||head.contains("\nv ")||head.startsWith("#")||head.startsWith("v ");
    }

    private static String jsonString(String json,String key){
        Matcher m=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if(!m.find())return null;
        return m.group(1).replace("\\/","/").replace("\\n","").replace("\\\"","\"");
    }

    private static byte[] maybeGunzip(byte[] data){
        if(data==null||data.length<2||data[0]!=0x1f||data[1]!=(byte)0x8b)return data;
        try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(data))){
            return in.readAllBytes();
        }catch(Exception e){
            return data;
        }
    }

    private static byte[] httpGet(String url){
        if(url==null||url.isBlank())return null;
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent","Roblox/WinInet");
            conn.setRequestProperty("Accept","*/*");
            int code=conn.getResponseCode();
            if(code>=300&&code<400){
                String next=conn.getHeaderField("Location");
                if(next!=null&&!next.isBlank())return httpGet(next);
            }
            if(code<200||code>=300)return null;
            try(InputStream in=conn.getInputStream()){
                byte[] raw=in.readAllBytes();
                if(raw.length>8*1024*1024)return null;
                return raw;
            }
        }catch(Throwable t){
            return null;
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }

    private static OnlineMesh loadMeshBytesFromAssets(String id){
        String[] methods={"meshBytes","assetBytes","bytes","download","downloadBytes"};
        for(String name:methods){
            try{
                var method=RobloxOnlineAssets.class.getMethod(name,String.class);
                Object raw=method.invoke(null,id);
                if(raw instanceof byte[] bytes){
                    OnlineMesh mesh=parseMeshAsset(bytes);
                    if(mesh!=null)return mesh;
                }else if(raw instanceof String text){
                    OnlineMesh mesh=parseOnlineObj(text);
                    if(mesh!=null)return mesh;
                }
            }catch(Throwable ignored){}
        }
        return null;
    }

    private static OnlineMesh loadLocalMesh(String id){
        List<String> names=localMeshFileNames(id);
        for(String name:names){
            for(Path path:localMeshPaths(name)){
                try{
                    if(!Files.isRegularFile(path))continue;
                    OnlineMesh mesh=parseMeshAsset(Files.readAllBytes(path));
                    if(mesh!=null)return mesh;
                }catch(Exception ignored){}
            }
            String[] resources={
                "/assets/robloxium/meshes/"+name,
                "/robloxium/meshes/"+name,
                "/robloxium/client2010/content/fonts/"+name,
                "/robloxium/client2010/content/meshes/"+name
            };
            for(String res:resources){
                try(InputStream in=RobloxPartRenderer.class.getResourceAsStream(res)){
                    if(in==null)continue;
                    OnlineMesh mesh=parseMeshAsset(in.readAllBytes());
                    if(mesh!=null)return mesh;
                }catch(Exception ignored){}
            }
        }
        return null;
    }

    private static List<String> localMeshFileNames(String id){
        LinkedHashSet<String> names=new LinkedHashSet<>();
        String raw=id==null?"":id.trim();
        Matcher query=Pattern.compile("(?:^|[?&])id=(\\d+)",Pattern.CASE_INSENSITIVE).matcher(raw);
        while(query.find())addMeshFileName(names,query.group(1));
        Matcher rbx=Pattern.compile("rbxassetid://(\\d+)",Pattern.CASE_INSENSITIVE).matcher(raw);
        while(rbx.find())addMeshFileName(names,rbx.group(1));
        String slash=raw.replace('\\','/');
        int q=slash.indexOf('?');
        if(q>=0)slash=slash.substring(0,q);
        int leafAt=slash.lastIndexOf('/');
        String leaf=leafAt>=0?slash.substring(leafAt+1):slash;
        addMeshFileName(names,leaf);
        String digits=raw.replaceAll("[^0-9]","");
        if(digits.length()>=3 && digits.length()<=12)addMeshFileName(names,digits);
        return new ArrayList<>(names);
    }

    private static void addMeshFileName(Set<String> names,String token){
        if(token==null)return;
        String clean=token.trim().toLowerCase(Locale.ROOT).replace('\\','/');
        int slash=clean.lastIndexOf('/');
        if(slash>=0)clean=clean.substring(slash+1);
        clean=clean.replaceAll("[^a-z0-9._-]","");
        if(clean.isBlank()||clean.equals(".")||clean.equals(".."))return;
        names.add(clean);
        if(!clean.contains(".")){
            names.add(clean+".mesh");
            names.add(clean+".obj");
        }
    }

    private static List<Path> localMeshPaths(String name){
        List<Path> out=new ArrayList<>(2);
        try{out.add(Path.of("config","robloxium","assets",name));}catch(Exception ignored){}
        try{out.add(Path.of("config","robloxium","meshes",name));}catch(Exception ignored){}
        return out;
    }

    public static OnlineMesh parseMeshAsset(byte[] data){
        if(data==null||data.length<8)return null;
        int n=Math.min(data.length,32);
        String head=new String(data,0,n,StandardCharsets.US_ASCII);
        if(head.startsWith("version "))return parseRobloxMesh(data);
        String text=new String(data,StandardCharsets.UTF_8);
        String trimmed=text.stripLeading();
        if(trimmed.startsWith("version "))return parseRobloxMesh(data);
        return parseOnlineObj(text);
    }

    static OnlineMesh parseRobloxMesh(byte[] data){
        try{
            int lineEnd=0;
            while(lineEnd<data.length && data[lineEnd]!='\n')lineEnd++;
            if(lineEnd>=data.length)return null;
            String header=new String(data,0,lineEnd,StandardCharsets.US_ASCII).trim();
            if(header.endsWith("\r"))header=header.substring(0,header.length()-1);
            if(!header.regionMatches(true,0,"version ",0,8))return null;
            String ver=header.substring(8).trim();
            int body=lineEnd+1;
            if(ver.startsWith("1."))return parseRobloxMeshV1(new String(data,StandardCharsets.US_ASCII),ver);
            return parseRobloxMeshBinary(data,body,ver);
        }catch(Throwable ignored){return null;}
    }

    private static OnlineMesh parseRobloxMeshV1(String text,String ver){
        String[] lines=text.split("\\R",3);
        if(lines.length<3)return null;
        int faces=Integer.parseInt(lines[1].trim());
        Matcher m=Pattern.compile("\\[\\s*([^\\]]+)\\s*\\]").matcher(lines[2]);
        List<double[]> vecs=new ArrayList<>();
        while(m.find()){
            String[] p=m.group(1).split(",");
            if(p.length<3)continue;
            vecs.add(new double[]{Double.parseDouble(p[0].trim()),Double.parseDouble(p[1].trim()),Double.parseDouble(p[2].trim())});
        }
        if(vecs.size()<faces*9)return null;
        double scale="1.00".equals(ver)?0.5:1.0;
        List<Vec3> outV=new ArrayList<>(faces*3),outN=new ArrayList<>(faces*3);
        List<float[]> outUv=new ArrayList<>(faces*3);
        for(int i=0;i<faces;i++){
            int base=i*9;
            for(int k=0;k<3;k++){
                double[] pos=vecs.get(base+k*3),nrm=vecs.get(base+k*3+1),uv=vecs.get(base+k*3+2);
                outV.add(new Vec3(pos[0]*scale,pos[1]*scale,pos[2]*scale));
                outN.add(new Vec3(nrm[0],nrm[1],nrm[2]).normalized());
                outUv.add(new float[]{(float)uv[0],(float)uv[1]});
            }
        }
        return finishMesh(outV,outN,outUv);
    }

    private static OnlineMesh parseRobloxMeshBinary(byte[] data,int body,String ver){
        ByteBuffer buf=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(body<0||body>=data.length-4)return null;
        buf.position(body);
        int headerSize=Short.toUnsignedInt(buf.getShort());
        if(headerSize<12||body+headerSize>data.length)return null;
        int vertexSize,faceSize=12,lodCount=0,vertexCount,faceCount;
        if(ver.startsWith("2.")){
            vertexSize=Byte.toUnsignedInt(buf.get());
            faceSize=Byte.toUnsignedInt(buf.get());
            vertexCount=buf.getInt();
            faceCount=buf.getInt();
        }else if(ver.startsWith("3.")){
            vertexSize=Byte.toUnsignedInt(buf.get());
            faceSize=Byte.toUnsignedInt(buf.get());
            buf.getShort();
            lodCount=Short.toUnsignedInt(buf.getShort());
            vertexCount=buf.getInt();
            faceCount=buf.getInt();
        }else{

            buf.getShort();
            vertexCount=buf.getInt();
            faceCount=buf.getInt();
            lodCount=Short.toUnsignedInt(buf.getShort());
            int boneCount=Short.toUnsignedInt(buf.getShort());
            int nameTable=buf.getInt();
            buf.getShort();
            buf.get(); buf.get();
            if(ver.startsWith("5.")){buf.getInt();buf.getInt();}
            vertexSize=40;

            buf.position(body+headerSize);
            if(vertexCount<=0||faceCount<=0||vertexCount>2_000_000||faceCount>2_000_000)return null;
            List<Vec3> verts=new ArrayList<>(vertexCount),norms=new ArrayList<>(vertexCount);
            List<float[]> uvs=new ArrayList<>(vertexCount);
            for(int i=0;i<vertexCount;i++){
                int start=buf.position();
                if(start+Math.max(vertexSize,36)>data.length)return null;
                float px=buf.getFloat(),py=buf.getFloat(),pz=buf.getFloat();
                float nx=buf.getFloat(),ny=buf.getFloat(),nz=buf.getFloat();
                float u=buf.getFloat(),v=buf.getFloat();
                verts.add(new Vec3(px,py,pz));
                norms.add(new Vec3(nx,ny,nz).normalized());
                uvs.add(new float[]{u,1f-v});
                buf.position(start+vertexSize);
            }
            if(boneCount>0)buf.position(buf.position()+vertexCount*8);
            return assembleIndexedMesh(buf,data,verts,norms,uvs,faceCount,faceSize,lodCount);
        }
        buf.position(body+headerSize);
        if(vertexSize<36||faceSize<12||vertexCount<=0||faceCount<=0)return null;
        if(vertexCount>2_000_000||faceCount>2_000_000)return null;
        List<Vec3> verts=new ArrayList<>(vertexCount),norms=new ArrayList<>(vertexCount);
        List<float[]> uvs=new ArrayList<>(vertexCount);
        for(int i=0;i<vertexCount;i++){
            int start=buf.position();
            if(start+vertexSize>data.length)return null;
            float px=buf.getFloat(),py=buf.getFloat(),pz=buf.getFloat();
            float nx=buf.getFloat(),ny=buf.getFloat(),nz=buf.getFloat();
            float u=buf.getFloat(),v=buf.getFloat();
            verts.add(new Vec3(px,py,pz));
            norms.add(new Vec3(nx,ny,nz).normalized());
            uvs.add(new float[]{u,1f-v});
            buf.position(start+vertexSize);
        }
        return assembleIndexedMesh(buf,data,verts,norms,uvs,faceCount,faceSize,lodCount);
    }

    private static OnlineMesh assembleIndexedMesh(ByteBuffer buf,byte[] data,
            List<Vec3> verts,List<Vec3> norms,List<float[]> uvs,
            int faceCount,int faceSize,int lodCount){
        int[] lods=null;
        int usedFaces=faceCount;

        int faceStart=buf.position();
        if(lodCount>=2){
            int afterFaces=faceStart+faceCount*faceSize;
            if(afterFaces+lodCount*4<=data.length){
                ByteBuffer lodBuf=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                lodBuf.position(afterFaces);
                lods=new int[lodCount];
                for(int i=0;i<lodCount;i++)lods[i]=lodBuf.getInt();
                if(lods[0]==0 && lods[1]>0 && lods[1]<=faceCount)usedFaces=lods[1];
            }
        }
        List<Vec3> outV=new ArrayList<>(usedFaces*3),outN=new ArrayList<>(usedFaces*3);
        List<float[]> outUv=new ArrayList<>(usedFaces*3);
        buf.position(faceStart);
        for(int i=0;i<usedFaces;i++){
            int start=buf.position();
            if(start+12>data.length)return null;
            int ia=buf.getInt(),ib=buf.getInt(),ic=buf.getInt();
            if(ia<0||ib<0||ic<0||ia>=verts.size()||ib>=verts.size()||ic>=verts.size()){
                buf.position(start+faceSize);
                continue;
            }
            outV.add(verts.get(ia));outV.add(verts.get(ib));outV.add(verts.get(ic));
            outN.add(norms.get(ia));outN.add(norms.get(ib));outN.add(norms.get(ic));
            outUv.add(uvs.get(ia));outUv.add(uvs.get(ib));outUv.add(uvs.get(ic));
            buf.position(start+faceSize);
        }
        return finishMesh(outV,outN,outUv);
    }

    private static OnlineMesh finishMesh(List<Vec3> outV,List<Vec3> outN,List<float[]> outUv){
        if(outV.size()<3)return null;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
        double maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;
        for(Vec3 x:outV){
            minX=Math.min(minX,x.x());minY=Math.min(minY,x.y());minZ=Math.min(minZ,x.z());
            maxX=Math.max(maxX,x.x());maxY=Math.max(maxY,x.y());maxZ=Math.max(maxZ,x.z());
        }
        Vec3 half=new Vec3(Math.max((maxX-minX)*.5,1e-6),Math.max((maxY-minY)*.5,1e-6),Math.max((maxZ-minZ)*.5,1e-6));
        return new OnlineMesh(outV.toArray(Vec3[]::new),outN.toArray(Vec3[]::new),outUv.toArray(float[][]::new),half);
    }

    static OnlineMesh parseOnlineObj(String text){
        if(text==null||text.isBlank())return null;
        String trimmed=text.stripLeading();
        if(trimmed.startsWith("version ")){
            return parseMeshAsset(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        try{
            List<Vec3> v=new ArrayList<>(),n=new ArrayList<>();
            List<float[]> uv=new ArrayList<>(),outUv=new ArrayList<>();
            List<Vec3> outV=new ArrayList<>(),outN=new ArrayList<>();
            for(String line:text.split("\\R")){
                line=line.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
                String[] q=line.split("\\s+");
                switch(q[0]){
                    case "v" -> {if(q.length>=4)v.add(new Vec3(Double.parseDouble(q[1]),Double.parseDouble(q[2]),Double.parseDouble(q[3])));}
                    case "vt" -> {if(q.length>=3)uv.add(new float[]{Float.parseFloat(q[1]),Float.parseFloat(q[2])});}
                    case "vn" -> {if(q.length>=4)n.add(new Vec3(Double.parseDouble(q[1]),Double.parseDouble(q[2]),Double.parseDouble(q[3])).normalized());}
                    case "f" -> {
                        if(q.length<4)continue;
                        FaceVertex first=parseObjFace(q[1],v.size(),uv.size(),n.size());
                        for(int j=2;j<q.length-1;j++){
                            FaceVertex second=parseObjFace(q[j],v.size(),uv.size(),n.size()),third=parseObjFace(q[j+1],v.size(),uv.size(),n.size());
                            addObjVertex(first,v,uv,n,outV,outUv,outN);addObjVertex(second,v,uv,n,outV,outUv,outN);addObjVertex(third,v,uv,n,outV,outUv,outN);
                        }
                    }
                }
            }
            return finishMesh(outV,outN,outUv);
        }catch(Throwable ignored){return null;}
    }

    private static void drawHead(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,int col){
        if(HEAD_MESH==null){
            Vec3 size=p.size(),scale=p.meshScale();
            drawSphere(pose,b,p,col);
            return;
        }
        double headScale = 1.1;
        double sx = 0.5 * headScale * (p.size().x() * p.meshScale().x())/ Math.max(HEAD_MESH.half.x() * 2.0, 1e-9);
        double sy = headScale * (p.size().y() * p.meshScale().y())/ Math.max(HEAD_MESH.half.y() * 2.0, 1e-9);
        double sz = headScale * (p.size().z() * p.meshScale().z())/ Math.max(HEAD_MESH.half.z() * 2.0, 1e-9);
        Vec3 off=p.meshOffset();
        for(int i=0;i<HEAD_MESH.positions.length;i+=3){
            Vec3 a=HEAD_MESH.positions[i], bb=HEAD_MESH.positions[i+1], c=HEAD_MESH.positions[i+2];
            a=new Vec3(a.x()*sx+off.x(),a.y()*sy+off.y(),a.z()*sz+off.z());
            bb=new Vec3(bb.x()*sx+off.x(),bb.y()*sy+off.y(),bb.z()*sz+off.z());
            c=new Vec3(c.x()*sx+off.x(),c.y()*sy+off.y(),c.z()*sz+off.z());
            Vec3 na=HEAD_MESH.normals[i], nb=HEAD_MESH.normals[i+1], nc=HEAD_MESH.normals[i+2];
            float[] ua=HEAD_MESH.uvs[i], ub=HEAD_MESH.uvs[i+1], uc=HEAD_MESH.uvs[i+2];
            triangleSmooth(pose,b,p,a,bb,c,na,nb,nc,col,new float[]{ua[0],ua[1],ub[0],ub[1],uc[0],uc[1]});
        }
    }

    private static void drawSphere(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,int color){
        Vec3 size=p.size(), scale=p.meshScale();
        double hx=Math.abs(size.x()*scale.x())*.5;
        double hy=Math.abs(size.y()*scale.y())*.5;
        double hz=Math.abs(size.z()*scale.z())*.5;

        final int latitudes=12;
        final int longitudes=24;
        for(int lat=0;lat<latitudes;lat++){
            double t0=-Math.PI*.5+Math.PI*lat/latitudes;
            double t1=-Math.PI*.5+Math.PI*(lat+1)/latitudes;
            for(int lon=0;lon<longitudes;lon++){
                double p0=2*Math.PI*lon/longitudes;
                double p1=2*Math.PI*(lon+1)/longitudes;

                Vec3 n00=sphereNormal(t0,p0), n10=sphereNormal(t1,p0);
                Vec3 n11=sphereNormal(t1,p1), n01=sphereNormal(t0,p1);
                Vec3 off=p.meshOffset();
                Vec3 v00=new Vec3(hx*n00.x()+off.x(),hy*n00.y()+off.y(),hz*n00.z()+off.z());
                Vec3 v10=new Vec3(hx*n10.x()+off.x(),hy*n10.y()+off.y(),hz*n10.z()+off.z());
                Vec3 v11=new Vec3(hx*n11.x()+off.x(),hy*n11.y()+off.y(),hz*n11.z()+off.z());
                Vec3 v01=new Vec3(hx*n01.x()+off.x(),hy*n01.y()+off.y(),hz*n01.z()+off.z());

                float u0=(float)lon/longitudes, u1=(float)(lon+1)/longitudes;
                float v0=(float)lat/latitudes, v1=(float)(lat+1)/latitudes;

                triangleSmooth(pose,b,p,v00,v10,v11,n00,n10,n11,color,
                        new float[]{u0,v0,u0,v1,u1,v1});
                triangleSmooth(pose,b,p,v00,v11,v01,n00,n11,n01,color,
                        new float[]{u0,v0,u1,v1,u1,v0});
            }
        }
    }

    private static Vec3 sphereNormal(double latitude,double longitude){
        double c=Math.cos(latitude);
        return new Vec3(c*Math.cos(longitude),Math.sin(latitude),c*Math.sin(longitude));
    }

    private static void drawCylinder(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int color){
        Vec3 off=p.meshOffset();
        final int slices=20;
        Vec3 right=new Vec3(1,0,0),left=new Vec3(-1,0,0);
        Vec3 capPos=new Vec3(hx+off.x(),off.y(),off.z());
        Vec3 capNeg=new Vec3(-hx+off.x(),off.y(),off.z());
        for(int i=0;i<slices;i++){
            double a0=2*Math.PI*i/slices,a1=2*Math.PI*(i+1)/slices;
            double c0=Math.cos(a0),s0=Math.sin(a0),c1=Math.cos(a1),s1=Math.sin(a1);
            Vec3 n0=new Vec3(0,c0,s0),n1=new Vec3(0,c1,s1);
            Vec3 p0n=new Vec3(-hx+off.x(),hy*c0+off.y(),hz*s0+off.z());
            Vec3 p0p=new Vec3( hx+off.x(),hy*c0+off.y(),hz*s0+off.z());
            Vec3 p1n=new Vec3(-hx+off.x(),hy*c1+off.y(),hz*s1+off.z());
            Vec3 p1p=new Vec3( hx+off.x(),hy*c1+off.y(),hz*s1+off.z());
            float u0=(float)i/slices,u1=(float)(i+1)/slices;
            triangleSmooth(pose,b,p,p0n,p1n,p1p,n0,n1,n1,color,new float[]{u0,0,u1,0,u1,1});
            triangleSmooth(pose,b,p,p0n,p1p,p0p,n0,n1,n0,color,new float[]{u0,0,u1,1,u0,1});
            triangle(pose,b,p,capPos,p0p,p1p,right,color,new float[]{0.5f,0.5f,(float)(0.5+0.5*c0),(float)(0.5+0.5*s0),(float)(0.5+0.5*c1),(float)(0.5+0.5*s1)});
            triangle(pose,b,p,capNeg,p1n,p0n,left,color,new float[]{0.5f,0.5f,(float)(0.5+0.5*c1),(float)(0.5+0.5*s1),(float)(0.5+0.5*c0),(float)(0.5+0.5*s0)});
        }
    }

    private static void drawTorso(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int color){
        Vec3 off=p.meshOffset();
        double top=0.5;
        Vec3 a=new Vec3(-hx+off.x(),-hy+off.y(),-hz+off.z());
        Vec3 bb=new Vec3( hx+off.x(),-hy+off.y(),-hz+off.z());
        Vec3 c=new Vec3( hx+off.x(),-hy+off.y(), hz+off.z());
        Vec3 d=new Vec3(-hx+off.x(),-hy+off.y(), hz+off.z());
        Vec3 e=new Vec3(-hx*top+off.x(), hy+off.y(),-hz+off.z());
        Vec3 f=new Vec3( hx*top+off.x(), hy+off.y(),-hz+off.z());
        Vec3 g=new Vec3( hx*top+off.x(), hy+off.y(), hz+off.z());
        Vec3 h=new Vec3(-hx*top+off.x(), hy+off.y(), hz+off.z());
        face(pose,b,p,a,bb,c,d,0,-1,0,color);
        face(pose,b,p,e,f,g,h,0,1,0,color);
        face(pose,b,p,a,bb,f,e,0,0,-1,color);
        face(pose,b,p,d,c,g,h,0,0,1,color);
        triangleFace(pose,b,p,a,e,h,new Vec3(-1,hx-hx*top,0),color);
        triangleFace(pose,b,p,a,h,d,new Vec3(-1,hx-hx*top,0),color);
        triangleFace(pose,b,p,bb,c,g,new Vec3(1,hx-hx*top,0),color);
        triangleFace(pose,b,p,bb,g,f,new Vec3(1,hx-hx*top,0),color);
    }

    private static void triangleSmooth(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 na,Vec3 nb,Vec3 nc,int color,float[] uvs){
        Vec3 wa=world(p,a), wb=world(p,bb), wc=world(p,c);
        Vec3 wna=worldNormal(p,na), wnb=worldNormal(p,nb), wnc=worldNormal(p,nc);
        int reflectedA=reflect(color,p.reflectance(),wa,wna);
        int reflectedB=reflect(color,p.reflectance(),wb,wnb);
        int reflectedC=reflect(color,p.reflectance(),wc,wnc);
        vertex(pose,b,wa,reflectedA,uvs[0],uvs[1],wna,p);
        vertex(pose,b,wb,reflectedB,uvs[2],uvs[3],wnb,p);
        vertex(pose,b,wc,reflectedC,uvs[4],uvs[5],wnc,p);
        vertex(pose,b,wc,reflectedC,uvs[4],uvs[5],wnc,p);
        vertex(pose,b,wb,reflectedB,uvs[2],uvs[3],wnb,p);
        vertex(pose,b,wa,reflectedA,uvs[0],uvs[1],wna,p);
    }
    private static Vec3 ellipsoid(double hx,double hy,double hz,double latitude,double longitude){double cos=Math.cos(latitude);return new Vec3(hx*cos*Math.cos(longitude),hy*Math.sin(latitude),hz*cos*Math.sin(longitude));}
    private static void triangle(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 normal,int color,float[] uvs){Vec3 n=worldNormal(p,normal.normalized());Vec3 wa=world(p,a),wb=world(p,bb),wc=world(p,c);int ra=reflect(color,p.reflectance(),wa,n),rb=reflect(color,p.reflectance(),wb,n),rc=reflect(color,p.reflectance(),wc,n);vertex(pose,b,wa,ra,uvs[0],uvs[1],n,p);vertex(pose,b,wb,rb,uvs[2],uvs[3],n,p);vertex(pose,b,wc,rc,uvs[4],uvs[5],n,p);}
    private static Vec3 worldNormal(RobloxPart p,Vec3 n){return p.cframe().transformVector(n).normalized();}

    private static HeadMesh loadHeadMesh(){
        try(InputStream raw=RobloxPartRenderer.class.getResourceAsStream(
                "/robloxium/client2010/content/fonts/head.obj")){
            if(raw==null)return null;

            List<Vec3> vertices=new ArrayList<>();
            List<float[]> texcoords=new ArrayList<>();
            List<Vec3> normals=new ArrayList<>();
            List<Vec3> outPositions=new ArrayList<>();
            List<Vec3> outNormals=new ArrayList<>();
            List<float[]> outUvs=new ArrayList<>();

            try(BufferedReader reader=new BufferedReader(new InputStreamReader(raw,java.nio.charset.StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null){
                    line=line.trim();
                    if(line.isEmpty()||line.startsWith("#"))continue;
                    String[] parts=line.split("\\s+");
                    if(parts.length==0)continue;

                    switch(parts[0]){
                        case "v" -> {
                            if(parts.length>=4)
                                vertices.add(new Vec3(Double.parseDouble(parts[1]),Double.parseDouble(parts[2]),Double.parseDouble(parts[3])));
                        }
                        case "vt" -> {
                            if(parts.length>=3)
                                texcoords.add(new float[]{Float.parseFloat(parts[1]),Float.parseFloat(parts[2])});
                        }
                        case "vn" -> {
                            if(parts.length>=4)
                                normals.add(new Vec3(Double.parseDouble(parts[1]),Double.parseDouble(parts[2]),Double.parseDouble(parts[3])).normalized());
                        }
                        case "f" -> {
                            if(parts.length<4)continue;

                            FaceVertex first=parseObjFace(parts[1],vertices.size(),texcoords.size(),normals.size());
                            for(int j=2;j<parts.length-1;j++){
                                FaceVertex second=parseObjFace(parts[j],vertices.size(),texcoords.size(),normals.size());
                                FaceVertex third=parseObjFace(parts[j+1],vertices.size(),texcoords.size(),normals.size());
                                addObjVertex(first,vertices,texcoords,normals,outPositions,outUvs,outNormals);
                                addObjVertex(second,vertices,texcoords,normals,outPositions,outUvs,outNormals);
                                addObjVertex(third,vertices,texcoords,normals,outPositions,outUvs,outNormals);
                            }
                        }
                        default -> { }
                    }
                }
            }

            if(outPositions.size()<3)return null;

            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
            double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for(Vec3 v:outPositions){
                minX=Math.min(minX,v.x()); minY=Math.min(minY,v.y()); minZ=Math.min(minZ,v.z());
                maxX=Math.max(maxX,v.x()); maxY=Math.max(maxY,v.y()); maxZ=Math.max(maxZ,v.z());
            }
            Vec3 center=new Vec3((minX+maxX)*0.5,(minY+maxY)*0.5,(minZ+maxZ)*0.5);
            Vec3 half=new Vec3(Math.max((maxX-minX)*0.5,1e-6),Math.max((maxY-minY)*0.5,1e-6),Math.max((maxZ-minZ)*0.5,1e-6));

            Vec3[] centered=new Vec3[outPositions.size()];
            for(int i=0;i<outPositions.size();i++) centered[i]=outPositions.get(i).sub(center);
            return new HeadMesh(
                    centered,
                    outNormals.toArray(Vec3[]::new),
                    outUvs.toArray(float[][]::new),
                    center,half);
        }catch(Exception ignored){
            return null;
        }
    }

    private static FaceVertex parseObjFace(String token,int vertexCount,int uvCount,int normalCount){
        String[] fields=token.split("/",-1);
        return new FaceVertex(
                objIndex(fields.length>0?fields[0]:"",vertexCount),
                objIndex(fields.length>1?fields[1]:"",uvCount),
                objIndex(fields.length>2?fields[2]:"",normalCount));
    }

    private static int objIndex(String value,int count){
        if(value==null||value.isEmpty())return -1;
        int i=Integer.parseInt(value);
        return i>0?i-1:count+i;
    }

    private static void addObjVertex(FaceVertex fv,List<Vec3> vertices,List<float[]> texcoords,List<Vec3> normals,
                                     List<Vec3> outPositions,List<float[]> outUvs,List<Vec3> outNormals){
        if(fv.v()<0||fv.v()>=vertices.size())return;
        outPositions.add(vertices.get(fv.v()));
        outUvs.add(fv.t()>=0&&fv.t()<texcoords.size()?texcoords.get(fv.t()):new float[]{0,0});
        outNormals.add(fv.n()>=0&&fv.n()<normals.size()?normals.get(fv.n()):new Vec3(0,1,0));
    }

    record OnlineMesh(Vec3[] positions,Vec3[] normals,float[][] uvs,Vec3 half){}

    private record FaceVertex(int v,int t,int n){}

    private record HeadMesh(Vec3[] positions,Vec3[] normals,float[][] uvs,Vec3 center,Vec3 half){}
    private static void drawCube(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int col){face(pose,b,p,new Vec3(-hx,-hy,hz),new Vec3(hx,-hy,hz),new Vec3(hx,hy,hz),new Vec3(-hx,hy,hz),0,0,1,col);face(pose,b,p,new Vec3(hx,-hy,-hz),new Vec3(-hx,-hy,-hz),new Vec3(-hx,hy,-hz),new Vec3(hx,hy,-hz),0,0,-1,col);face(pose,b,p,new Vec3(-hx,-hy,-hz),new Vec3(-hx,-hy,hz),new Vec3(-hx,hy,hz),new Vec3(-hx,hy,-hz),-1,0,0,col);face(pose,b,p,new Vec3(hx,-hy,hz),new Vec3(hx,-hy,-hz),new Vec3(hx,hy,-hz),new Vec3(hx,hy,hz),1,0,0,col);face(pose,b,p,new Vec3(-hx,hy,hz),new Vec3(hx,hy,hz),new Vec3(hx,hy,-hz),new Vec3(-hx,hy,-hz),0,1,0,col);face(pose,b,p,new Vec3(-hx,-hy,-hz),new Vec3(hx,-hy,-hz),new Vec3(hx,-hy,hz),new Vec3(-hx,-hy,hz),0,-1,0,col);}

    private static void drawBeveledBox(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,
                                       double hx,double hy,double hz,int col){
        drawCube(pose,b,p,hx,hy,hz,col);
    }
    private static void quad(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 d,Vec3 normal,int color){
        Vec3 n=normal.normalized();
        if(bb.sub(a).cross(c.sub(a)).dot(n)<0){Vec3 t=bb;bb=d;d=t;}
        face(pose,b,p,a,bb,c,d,(float)n.x(),(float)n.y(),(float)n.z(),color);
    }
    private static void edge(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 d,Vec3 normal,int color){quad(pose,b,p,a,bb,c,d,normal,color);}
    private static void triangleFace(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 normal,int color){
        Vec3 n=normal.normalized();
        if(bb.sub(a).cross(c.sub(a)).dot(n)<0){Vec3 t=bb;bb=c;c=t;}
        triangle(pose,b,p,a,bb,c,n,color,new float[]{0,0,1,0,0,1});
    }
    private static void drawWedge(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int col){

        Vec3 a=new Vec3(-hx,-hy,-hz);
        Vec3 bb=new Vec3(hx,-hy,-hz);
        Vec3 c=new Vec3(hx,-hy,hz);
        Vec3 d=new Vec3(-hx,-hy,hz);
        Vec3 e=new Vec3(-hx,hy,hz);
        Vec3 f=new Vec3(hx,hy,hz);

        face(pose,b,p,a,bb,c,d,0,-1,0,col);
        face(pose,b,p,a,bb,f,e,0,0,-1,col);
        triangleFace(pose,b,p,a,e,d,new Vec3(-1,0,0),col);
        triangleFace(pose,b,p,bb,c,f,new Vec3(1,0,0),col);

        triangleFace(pose,b,p,e,f,c,new Vec3(0,hz,hy),col);
        triangleFace(pose,b,p,e,c,d,new Vec3(0,hz,hy),col);
    }
    private static void drawCornerWedge(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int col){

        Vec3 a=new Vec3(-hx,-hy,-hz),bb=new Vec3(hx,-hy,-hz),c=new Vec3(hx,-hy,hz),d=new Vec3(-hx,-hy,hz);
        Vec3 peak=new Vec3(hx,hy,-hz);
        face(pose,b,p,a,bb,c,d,0,-1,0,col);
        triangleFace(pose,b,p,a,bb,peak,new Vec3(0,0,-1),col);
        triangleFace(pose,b,p,bb,c,peak,new Vec3(1,1,0),col);
        triangleFace(pose,b,p,c,d,peak,new Vec3(0,1,1),col);
        triangleFace(pose,b,p,d,a,peak,new Vec3(-1,1,0),col);
    }
    private static void face(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 d,float nx,float ny,float nz,int color){
        Vec3 expected=new Vec3(nx,ny,nz).normalized();
        Vec3 geometric=bb.sub(a).cross(c.sub(a)).normalized();
        if(geometric.dot(expected)<0){Vec3 swap=bb;bb=d;d=swap;}
        double[][]r=p.cframe().rotation();
        Vec3 n=new Vec3(
            r[0][0]*nx+r[0][1]*ny+r[0][2]*nz,
            r[1][0]*nx+r[1][1]*ny+r[1][2]*nz,
            r[2][0]*nx+r[2][1]*ny+r[2][2]*nz);
        Vec3 edgeU=bb.sub(a),edgeV=d.sub(a);
        double tileStuds=materialTileStuds(p);
        float uTex=(float)(edgeU.length()/tileStuds);
        float vTex=(float)(edgeV.length()/tileStuds);

        emitFaceQuad(pose,b,p,a,bb,c,d,n,color,0f,uTex,0f,vTex);
        stampFaceShadows(pose,b,p,a,bb,d,n,color,tileStuds);
    }

    private static Vec3 lerp(Vec3 a,Vec3 b,double t){
        return a.add(b.sub(a).mul(t));
    }

    private static void emitFaceQuad(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,
                                      Vec3 a,Vec3 bb,Vec3 c,Vec3 d,Vec3 n,int color,
                                      float u0,float u1,float v0,float v1){
        Vec3 wn=n.normalized();
        Vec3 wa=world(p,a), wb=world(p,bb), wc=world(p,c), wd=world(p,d);
        int ra=reflect(color,p.reflectance(),wa,wn), rb=reflect(color,p.reflectance(),wb,wn),
            rc=reflect(color,p.reflectance(),wc,wn), rd=reflect(color,p.reflectance(),wd,wn);

        vertexWithShadow(pose,b,wa,ra,u0,v1,n,p,1.0);
        vertexWithShadow(pose,b,wb,rb,u1,v1,n,p,1.0);
        vertexWithShadow(pose,b,wc,rc,u1,v0,n,p,1.0);
        vertexWithShadow(pose,b,wc,rc,u1,v0,n,p,1.0);
        vertexWithShadow(pose,b,wd,rd,u0,v0,n,p,1.0);
        vertexWithShadow(pose,b,wa,ra,u0,v1,n,p,1.0);
    }

    private static void stampFaceShadows(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,
                                         Vec3 localA,Vec3 localB,Vec3 localD,Vec3 faceNormal,int color,double tileStuds){
        if(p.transparency()>=0.999)return;
        List<ShadowTriangle> tris=SHADOWS_BY_RECEIVER.get(p);
        if(tris==null||tris.isEmpty())return;
        Vec3 n=faceNormal.normalized();
        Vec3 origin=p.cframe().transformPoint(localA);
        Vec3 axisU=p.cframe().transformVector(localB.sub(localA));
        Vec3 axisV=p.cframe().transformVector(localD.sub(localA));
        double uLen=axisU.length(), vLen=axisV.length();
        if(uLen<1e-8||vLen<1e-8)return;
        Vec3 uDir=axisU.mul(1.0/uLen), vDir=axisV.mul(1.0/vLen);
        double shadow=shadowLightMultiplier();
        for(int i=0,count=tris.size();i<count;i++){
            ShadowTriangle t=tris.get(i);
            if(t.normal().normalized().dot(n)<0.95)continue;
            if(Math.abs(t.center().sub(origin).dot(n))>0.12)continue;
            emitShadowStamp(pose,b,p,t.a(),t.b(),t.c(),n,origin,uDir,vDir,tileStuds,color,shadow);
        }
    }

    private static void emitShadowStamp(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,
                                        Vec3 a,Vec3 bb,Vec3 c,Vec3 n,Vec3 origin,Vec3 uDir,Vec3 vDir,
                                        double tileStuds,int color,double shadow){
        Vec3 wa=worldPosition(a),wb=worldPosition(bb),wc=worldPosition(c);
        int ra=reflect(color,p.reflectance(),wa,n),rb=reflect(color,p.reflectance(),wb,n),rc=reflect(color,p.reflectance(),wc,n);
        float[] ua=faceUv(a,origin,uDir,vDir,tileStuds);
        float[] ub=faceUv(bb,origin,uDir,vDir,tileStuds);
        float[] uc=faceUv(c,origin,uDir,vDir,tileStuds);
        vertexWithShadow(pose,b,wa,ra,ua[0],ua[1],n,p,shadow);
        vertexWithShadow(pose,b,wb,rb,ub[0],ub[1],n,p,shadow);
        vertexWithShadow(pose,b,wc,rc,uc[0],uc[1],n,p,shadow);
        vertexWithShadow(pose,b,wc,rc,uc[0],uc[1],n,p,shadow);
        vertexWithShadow(pose,b,wb,rb,ub[0],ub[1],n,p,shadow);
        vertexWithShadow(pose,b,wa,ra,ua[0],ua[1],n,p,shadow);
    }

    private static float[] faceUv(Vec3 robloxPoint,Vec3 origin,Vec3 uDir,Vec3 vDir,double tileStuds){
        Vec3 rel=robloxPoint.sub(origin);
        return new float[]{(float)(rel.dot(uDir)/tileStuds),(float)(1.0-rel.dot(vDir)/tileStuds)};
    }
    private static Vec3 world(RobloxPart p,Vec3 local){return worldPosition(p.cframe().transformPoint(local));}
    private static Vec3 worldPosition(Vec3 roblox){return new Vec3(
        RobloxCoordinateSpace.toMinecraft(roblox.x())+SCENE_ORIGIN.x(),
        RobloxCoordinateSpace.toMinecraft(roblox.y())+SCENE_ORIGIN.y(),
        RobloxCoordinateSpace.toMinecraft(roblox.z())+SCENE_ORIGIN.z());}
    private static void vertexUnlit(PoseStack.Pose pose,VertexConsumer b,Vec3 v,int c,float u,float vv,Vec3 n){Vec3 normal=n.normalized();b.addVertex(pose,(float)v.x(),(float)v.y(),(float)v.z()).setColor((c>>16)&255,(c>>8)&255,c&255,(c>>>24)&255).setUv(u,vv).setOverlay(0).setLight(LIGHT).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());}
    private static void vertex(PoseStack.Pose pose,VertexConsumer b,Vec3 v,int c,float u,float vv,Vec3 n,RobloxPart part){
        vertexWithShadow(pose,b,v,c,u,vv,n,part,Double.NaN);
    }
    private static void vertexWithShadow(PoseStack.Pose pose,VertexConsumer b,Vec3 v,int c,float u,float vv,Vec3 n,RobloxPart part,double forcedShadow){
        Vec3 normal=n.normalized();
        double ndl=Math.max(0,normal.dot(CURRENT_SUN));
        double shadow=Double.isNaN(forcedShadow)?shadowMask(part,v,normal):forcedShadow;

        double diffuse=CURRENT_LIGHTING.ambientFactor()+CURRENT_LIGHTING.sunFactor()*ndl*shadow;
        double specular=0.0;
        Vec3 view=new Vec3(CURRENT_CAMERA.x()-v.x(),CURRENT_CAMERA.y()-v.y(),CURRENT_CAMERA.z()-v.z()).normalized();
        Vec3 half=CURRENT_SUN.add(view).normalized();
        if(ndl>0&&shadow>0.001&&half.lengthSquared()>1e-9){
            double exponent=materialSpecularExponent(part);
            specular=materialSpecularStrength(part)*Math.pow(Math.max(0,normal.dot(half)),exponent);
            specular*=0.25+0.75*Math.max(0.0,Math.min(1.0,CURRENT_LIGHTING.environmentSpecularScale()));
            specular*=shadow;
        }
        int shaded=shadeSpecular(c,diffuse,specular);
        shaded=applyEnvironmentLighting(shaded,part,normal,ndl,v);
        b.addVertex(pose,(float)v.x(),(float)v.y(),(float)v.z()).setColor((shaded>>16)&255,(shaded>>8)&255,shaded&255,(shaded>>>24)&255).setUv(u,vv).setOverlay(0).setLight(LIGHT).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());
    }
    private static Vec3 minecraftToRoblox(Vec3 mc){
        return new Vec3(
            RobloxCoordinateSpace.toRoblox(mc.x()-SCENE_ORIGIN.x()),
            RobloxCoordinateSpace.toRoblox(mc.y()-SCENE_ORIGIN.y()),
            RobloxCoordinateSpace.toRoblox(mc.z()-SCENE_ORIGIN.z()));
    }
    private static double shadowMask(RobloxPart receiver,Vec3 minecraftPoint,Vec3 worldNormal){
        if(receiver==null||CURRENT_LIGHTING==null||CURRENT_LIGHTING.sunFactor()<=0.001)return 1.0;
        List<ShadowTriangle> tris=SHADOWS_BY_RECEIVER.get(receiver);
        if(tris==null||tris.isEmpty())return 1.0;
        Vec3 p=minecraftToRoblox(minecraftPoint);
        for(int i=0,n=tris.size();i<n;i++){
            ShadowTriangle t=tris.get(i);
            double r=t.radius()+0.15;
            if(p.sub(t.center()).lengthSquared()>r*r)continue;
            if(pointInShadowTriangle(p,t))return shadowLightMultiplier();
        }
        return 1.0;
    }

    private static double shadowLightMultiplier(){
        int c=CURRENT_LIGHTING==null?0xFF333333:CURRENT_LIGHTING.shadowColor();
        double r=((c>>16)&255)/255.0, g=((c>>8)&255)/255.0, b=(c&255)/255.0;

        return Math.max(0.0,Math.min(1.0,0.2126*r+0.7152*g+0.0722*b));
    }

    private static boolean shadowRayOccluded(RobloxPart caster,Vec3 receiverPoint,Vec3 receiverNormal){
        Vec3 origin=receiverPoint.add(receiverNormal.mul(0.01));
        Vec3 light=CURRENT_SUN.normalized();
        if(light.lengthSquared()<1e-9)return false;

        double casterDistance=rayBoxHitDistance(origin,light,caster);
        if(casterDistance<0.02)return false;
        for(RobloxPart blocker:CURRENT_SHADOW_PARTS){
            if(blocker==caster || blocker.transparency()>=0.999)continue;
            double t=rayBoxHitDistance(origin,light,blocker);

            if(t>=0.01 && t<casterDistance-0.02)return true;
        }
        return false;
    }

    private static boolean shadowTriangleFullyOccluded(RobloxPart caster,Vec3 a,Vec3 b,Vec3 c,Vec3 normal){
        Vec3 center=a.add(b).add(c).mul(1.0/3.0);
        return shadowRayOccluded(caster,a,normal)
            && shadowRayOccluded(caster,b,normal)
            && shadowRayOccluded(caster,c,normal)
            && shadowRayOccluded(caster,center,normal);
    }

    private static double rayBoxHitDistance(Vec3 origin,Vec3 dir,RobloxPart part){

        CFrame cf=part.cframe();
        Vec3 localOrigin=cf.inverse().transformPoint(origin);
        Vec3 localDir=cf.inverse().transformVector(dir);
        Vec3 size=part.size(),scale=part.meshScale();
        double hx=Math.abs(size.x()*scale.x())*.5;
        double hy=Math.abs(size.y()*scale.y())*.5;
        double hz=Math.abs(size.z()*scale.z())*.5;
        double tMin=0.0,tMax=Double.POSITIVE_INFINITY;
        double[] o={localOrigin.x(),localOrigin.y(),localOrigin.z()};
        double[] d={localDir.x(),localDir.y(),localDir.z()};
        double[] h={hx,hy,hz};
        for(int i=0;i<3;i++){
            if(Math.abs(d[i])<1e-9){if(o[i] < -h[i] || o[i] > h[i])return -1;continue;}
            double a=(-h[i]-o[i])/d[i], b=(h[i]-o[i])/d[i];
            if(a>b){double q=a;a=b;b=q;}
            tMin=Math.max(tMin,a);tMax=Math.min(tMax,b);
            if(tMin>tMax)return -1;
        }
        return tMax>=0 ? Math.max(0,tMin) : -1;
    }

    private static boolean pointInShadowTriangle(Vec3 p,ShadowTriangle t){
        Vec3 n=t.normal().normalized();

        double plane=Math.abs(p.sub(t.a()).dot(n));
        if(plane>0.08)return false;
        Vec3 ab=t.b().sub(t.a()), ap=p.sub(t.a());
        Vec3 bc=t.c().sub(t.b()), bp=p.sub(t.b());
        Vec3 ca=t.a().sub(t.c()), cp=p.sub(t.c());
        return ab.cross(ap).dot(n)>=-0.05 &&
               bc.cross(bp).dot(n)>=-0.05 &&
               ca.cross(cp).dot(n)>=-0.05;
    }

    private static int applyEnvironmentLighting(int color,RobloxPart part,Vec3 normal,double ndl,Vec3 position){
        RobloxLighting l=CURRENT_LIGHTING;
        if(l==null)return color;
        double brightness=Math.max(0,Math.min(10,l.brightness()));

        int shift=ndl>=0.5?l.colorShiftTop():l.colorShiftBottom();
        double shiftAmount=Math.min(1.0,brightness*0.35);
        double sr=((shift>>16)&255)/255.0, sg=((shift>>8)&255)/255.0, sb=(shift&255)/255.0;
        double r=((color>>16)&255)/255.0, g=((color>>8)&255)/255.0, b=(color&255)/255.0;
        if((((shift>>16)&255)|((shift>>8)&255)|(shift&255))!=0){
            r=r*(1-shiftAmount)+r*sr*shiftAmount;
            g=g*(1-shiftAmount)+g*sg*shiftAmount;
            b=b*(1-shiftAmount)+b*sb*shiftAmount;
        }

        double envScale=l.environmentDiffuseScale();
        if(envScale>0.0001 && SKY_ENVIRONMENT!=null){
            int sky=SKY_ENVIRONMENT.sample(normal.normalized());
            double er=((sky>>16)&255)/255.0, eg=((sky>>8)&255)/255.0, eb=(sky&255)/255.0;
            double k=Math.min(1.0,envScale*0.65);
            r=r*(1-k)+er*k; g=g*(1-k)+eg*k; b=b*(1-k)+eb*k;
        }
        r=Math.max(0,Math.min(1,r));g=Math.max(0,Math.min(1,g));b=Math.max(0,Math.min(1,b));
        return (color&0xFF000000)|((int)Math.round(r*255)<<16)|((int)Math.round(g*255)<<8)|(int)Math.round(b*255);
    }

    private static double materialSpecularStrength(RobloxPart p){
        return switch(p.material()){
            case 1040,1056,1072->0.9;
            case 1536->0.35;
            case 816,800,1280->0.25;
            default->0.75;
        };
    }
    private static double materialSpecularExponent(RobloxPart p){
        return switch(p.material()){
            case 1040,1056,1072->64.0;
            case 1536->48.0;
            default->81.0;
        };
    }
    private static int shade(int color,double intensity){int r=Math.min(255,(int)Math.round(((color>>16)&255)*intensity));int g=Math.min(255,(int)Math.round(((color>>8)&255)*intensity));int b=Math.min(255,(int)Math.round((color&255)*intensity));return (color&0xFF000000)|(r<<16)|(g<<8)|b;}
    private static int shadeSpecular(int color,double intensity,double specular){
        int r=Math.min(255,(int)Math.round(((color>>16)&255)*intensity+255*specular));
        int g=Math.min(255,(int)Math.round(((color>>8)&255)*intensity+255*specular));
        int b=Math.min(255,(int)Math.round((color&255)*intensity+255*specular));
        return (color&0xFF000000)|(r<<16)|(g<<8)|b;
    }
    private static int reflect(int color,double amount,Vec3 position,Vec3 normal){
        amount=Math.max(0,Math.min(1,amount));
        if(amount<=0.0001||SKY_ENVIRONMENT==null)return color;
        Vec3 n=normal.normalized();
        Vec3 toCamera=new Vec3(CURRENT_CAMERA.x()-position.x(),CURRENT_CAMERA.y()-position.y(),CURRENT_CAMERA.z()-position.z()).normalized();
        if(toCamera.lengthSquared()<1e-9)return color;

        Vec3 environment=n.mul(2.0*n.dot(toCamera)).sub(toCamera).normalized();
        int sky=SKY_ENVIRONMENT.sample(environment);

        double facing=Math.max(0.0,n.dot(toCamera));
        double edge=0.25+0.75*Math.pow(1.0-facing,2.0);
        double strength=Math.min(1.0,amount*(0.72+0.28*edge));
        int r=(int)Math.round(((color>>16)&255)*(1-strength)+((sky>>16)&255)*strength);
        int g=(int)Math.round(((color>>8)&255)*(1-strength)+((sky>>8)&255)*strength);
        int b=(int)Math.round((color&255)*(1-strength)+(sky&255)*strength);
        return (color&0xFF000000)|(Math.max(0,Math.min(255,r))<<16)|(Math.max(0,Math.min(255,g))<<8)|Math.max(0,Math.min(255,b));
    }
    private static int reflect(int color,double amount,RobloxPart p,Vec3 position,Vec3 normal){
        return reflect(color,amount,position,normal);
    }
    private static int variedColor(RobloxPart p){
        int base=p.colorWithAlpha();
        long h=0xcbf29ce484222325L;
        String key=(p.referent()==null?p.name():p.referent())+"|"+p.material()+"|"+Math.round(p.cframe().position().x()*10)+","+Math.round(p.cframe().position().y()*10)+","+Math.round(p.cframe().position().z()*10);
        for(int i=0;i<key.length();i++){h^=key.charAt(i);h*=0x100000001b3L;}
        double variation=((h>>>11)&1023)/1023.0*0.06-0.03;
        int r=(int)Math.round(((base>>16)&255)*(1+variation));
        int g=(int)Math.round(((base>>8)&255)*(1+variation));
        int b=(int)Math.round((base&255)*(1+variation));
        return (base&0xFF000000)|(Math.max(0,Math.min(255,r))<<16)|(Math.max(0,Math.min(255,g))<<8)|Math.max(0,Math.min(255,b));
    }
    private static void submitSurfaceType(SubmitNodeCollector c,PoseStack ps,List<RobloxPart> parts,int type,String texture){
        boolean any=false;
        for(RobloxPart p:parts){
            if(p.transparency()>=1)continue;
            if(hasSurfaceType(p,type)){any=true;break;}
        }
        if(!any)return;
        Identifier id=tex(texture);
        c.submitCustomGeometry(ps,surfaceTextureType(id),(pose,b)->{
            for(RobloxPart p:parts){
                if(p.transparency()<1)drawSurfaceType(pose,b,p,type);
            }
        });
    }
    private static boolean hasSurfaceType(RobloxPart p,int type){
        return p.topSurface()==type||p.bottomSurface()==type||p.frontSurface()==type||
               p.backSurface()==type||p.leftSurface()==type||p.rightSurface()==type;
    }
    private static void drawSurfaceType(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,int type){
        int c=0xFFFFFFFF;
        double hx=p.size().x()/2,hy=p.size().y()/2,hz=p.size().z()/2;
        if(p.topSurface()==type)surfaceQuad(pose,b,p,new Vec3(-hx,hy,hz),new Vec3(hx,hy,hz),new Vec3(hx,hy,-hz),new Vec3(-hx,hy,-hz),0,1,0,c);
        if(p.bottomSurface()==type)surfaceQuad(pose,b,p,new Vec3(-hx,-hy,-hz),new Vec3(hx,-hy,-hz),new Vec3(hx,-hy,hz),new Vec3(-hx,-hy,hz),0,-1,0,c);
        if(p.frontSurface()==type)surfaceQuad(pose,b,p,new Vec3(-hx,-hy,hz),new Vec3(hx,-hy,hz),new Vec3(hx,hy,hz),new Vec3(-hx,hy,hz),0,0,1,c);
        if(p.backSurface()==type)surfaceQuad(pose,b,p,new Vec3(hx,-hy,-hz),new Vec3(-hx,-hy,-hz),new Vec3(-hx,hy,-hz),new Vec3(hx,hy,-hz),0,0,-1,c);
        if(p.leftSurface()==type)surfaceQuad(pose,b,p,new Vec3(-hx,-hy,-hz),new Vec3(-hx,-hy,hz),new Vec3(-hx,hy,hz),new Vec3(-hx,hy,-hz),-1,0,0,c);
        if(p.rightSurface()==type)surfaceQuad(pose,b,p,new Vec3(hx,-hy,hz),new Vec3(hx,-hy,-hz),new Vec3(hx,hy,-hz),new Vec3(hx,hy,hz),1,0,0,c);
    }
    private static void surfaceQuad(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,Vec3 a,Vec3 bb,Vec3 c,Vec3 d,float nx,float ny,float nz,int color){
        Vec3 expected=new Vec3(nx,ny,nz).normalized();
        Vec3 geometric=bb.sub(a).cross(c.sub(a)).normalized();
        if(geometric.dot(expected)<0){Vec3 swap=bb;bb=d;d=swap;}
        double[][]r=p.cframe().rotation();
        Vec3 n=new Vec3(r[0][0]*nx+r[0][1]*ny+r[0][2]*nz,r[1][0]*nx+r[1][1]*ny+r[1][2]*nz,r[2][0]*nx+r[2][1]*ny+r[2][2]*nz);
        Vec3 edgeU=bb.sub(a),edgeV=d.sub(a);
        float uTex=(float)(edgeU.length()/SURFACE_TILE_STUDS);
        float vTex=(float)(edgeV.length()/SURFACE_TILE_STUDS);
        Vec3 wn=n.normalized();

        final double SURFACE_OFFSET_STUDS=0.0005;
        Vec3 offset=expected.mul(SURFACE_OFFSET_STUDS);
        Vec3 wa=world(p,a.add(offset)), wb=world(p,bb.add(offset)),
             wc=world(p,c.add(offset)), wd=world(p,d.add(offset));
        int ra=reflect(color,p.reflectance(),wa,wn), rb=reflect(color,p.reflectance(),wb,wn), rc=reflect(color,p.reflectance(),wc,wn), rd=reflect(color,p.reflectance(),wd,wn);
        vertexSurface(pose,b,wa,ra,0,vTex,n,p);
        vertexSurface(pose,b,wb,rb,uTex,vTex,n,p);
        vertexSurface(pose,b,wc,rc,uTex,0,n,p);
        vertexSurface(pose,b,wc,rc,uTex,0,n,p);
        vertexSurface(pose,b,wd,rd,0,0,n,p);
        vertexSurface(pose,b,wa,ra,0,vTex,n,p);
    }

    private static final class SkyEnvironment {
        private final BufferedImage[] faces;
        private SkyEnvironment(BufferedImage[] faces){this.faces=faces;}
        static SkyEnvironment load(){
            try{
                BufferedImage[] f=new BufferedImage[6];

                for(int i=0;i<SKY.length;i++){
                    String path="/assets/robloxium/"+SKY[i];
                    try(InputStream in=RobloxPartRenderer.class.getResourceAsStream(path)){
                        if(in==null)return null;
                        f[i]=ImageIO.read(in);
                    }
                }
                return new SkyEnvironment(f);
            }catch(Exception e){return null;}
        }
        int sample(Vec3 direction){
            double x=direction.x(),y=direction.y(),z=direction.z();
            double ax=Math.abs(x),ay=Math.abs(y),az=Math.abs(z);
            int face;
            double u,v;
            if(ax>=ay&&ax>=az){
                if(x>0){face=0;u=(-z/ax+1)*0.5;v=(-y/ax+1)*0.5;}
                else {face=1;u=(z/ax+1)*0.5;v=(-y/ax+1)*0.5;}
            }else if(ay>=ax&&ay>=az){
                if(y>0){face=2;u=(x/ay+1)*0.5;v=(z/ay+1)*0.5;}
                else {face=3;u=(x/ay+1)*0.5;v=(-z/ay+1)*0.5;}
            }else{
                if(z>0){face=4;u=(x/az+1)*0.5;v=(-y/az+1)*0.5;}
                else {face=5;u=(-x/az+1)*0.5;v=(-y/az+1)*0.5;}
            }
            return bilinear(faces[face],u,v);
        }
        private static int bilinear(BufferedImage img,double u,double v){
            if(img==null)return 0x96B4DC;
            u=Math.max(0,Math.min(1,u));v=Math.max(0,Math.min(1,v));
            double px=u*(img.getWidth()-1), py=v*(img.getHeight()-1);
            int x0=(int)Math.floor(px),y0=(int)Math.floor(py),x1=Math.min(img.getWidth()-1,x0+1),y1=Math.min(img.getHeight()-1,y0+1);
            double fx=px-x0,fy=py-y0;
            int c00=img.getRGB(x0,y0),c10=img.getRGB(x1,y0),c01=img.getRGB(x0,y1),c11=img.getRGB(x1,y1);
            int r=lerp(lerp((c00>>16)&255,(c10>>16)&255,fx),lerp((c01>>16)&255,(c11>>16)&255,fx),fy);
            int g=lerp(lerp((c00>>8)&255,(c10>>8)&255,fx),lerp((c01>>8)&255,(c11>>8)&255,fx),fy);
            int b=lerp(lerp(c00&255,c10&255,fx),lerp(c01&255,c11&255,fx),fy);
            return (r<<16)|(g<<8)|b;
        }
        private static int lerp(int a,int b,double t){return (int)Math.round(a+(b-a)*t);}
    }

    private static RenderPipeline.Builder robloxPipeline(String path){
        return RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("robloxium", path))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
    }

    private static final RenderPipeline ROBLOX_OPAQUE_PIPELINE = robloxPipeline("pipeline/roblox_2010_opaque")
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();

    private static final RenderPipeline ROBLOX_TRANSLUCENT_PIPELINE = robloxPipeline("pipeline/roblox_2010_translucent")
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();

    private static final RenderPipeline ROBLOX_SURFACE_PIPELINE = robloxPipeline("pipeline/roblox_2010_surface")
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, -1f, -10f))
        .build();

    private static final RenderPipeline ROBLOX_SKY_PIPELINE = robloxPipeline("pipeline/roblox_2010_sky")
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, 0f, 0f))
        .build();

    private static final Map<Identifier,RenderType> OPAQUE_TEXTURE_TYPES=new HashMap<>();
    private static final Map<Identifier,RenderType> TRANSLUCENT_TEXTURE_TYPES=new HashMap<>();
    private static final Map<Identifier,RenderType> SURFACE_TEXTURE_TYPES=new HashMap<>();
    private static final Map<Identifier,RenderType> SKY_TEXTURE_TYPES=new HashMap<>();

    private enum PipelineKind { OPAQUE, TRANSLUCENT, SURFACE, SKY }

    private static RenderType textureType(Identifier texture,PipelineKind kind){
        requireVulkanBackend();
        Map<Identifier,RenderType> cache=switch(kind){
            case OPAQUE -> OPAQUE_TEXTURE_TYPES;
            case TRANSLUCENT -> TRANSLUCENT_TEXTURE_TYPES;
            case SURFACE -> SURFACE_TEXTURE_TYPES;
            case SKY -> SKY_TEXTURE_TYPES;
        };
        RenderPipeline pipeline=switch(kind){
            case OPAQUE -> ROBLOX_OPAQUE_PIPELINE;
            case TRANSLUCENT -> ROBLOX_TRANSLUCENT_PIPELINE;
            case SURFACE -> ROBLOX_SURFACE_PIPELINE;
            case SKY -> ROBLOX_SKY_PIPELINE;
        };
        String suffix=kind.name().toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(texture,id->RenderType.create(
            "robloxium:vulkan_2010_"+suffix+"_"+id.getPath().replace('/','_'),
            RenderSetup.builder(pipeline)
                .withTexture("Sampler0",id,()->RenderSystem.getSamplerCache().getSampler(
                    AddressMode.REPEAT,AddressMode.REPEAT,FilterMode.NEAREST,FilterMode.NEAREST,true))
                .createRenderSetup()));
    }

    private static RenderType repeatTextureType(Identifier texture){return textureType(texture,PipelineKind.OPAQUE);}
    private static RenderType translucentTextureType(Identifier texture){return textureType(texture,PipelineKind.TRANSLUCENT);}
    private static RenderType surfaceTextureType(Identifier texture){return textureType(texture,PipelineKind.SURFACE);}
    private static RenderType skyTextureType(Identifier texture){return textureType(texture,PipelineKind.SKY);}

    private static void vertexSurface(PoseStack.Pose pose,VertexConsumer b,Vec3 v,int c,float u,float vv,Vec3 n,RobloxPart p){
        Vec3 normal=n.normalized();
        double ndl=Math.max(0,normal.dot(CURRENT_SUN));
        double shadow=shadowMask(p,v,normal);
        double diffuse=CURRENT_LIGHTING.ambientFactor()+CURRENT_LIGHTING.sunFactor()*ndl*shadow;
        Vec3 view=new Vec3(CURRENT_CAMERA.x()-v.x(),CURRENT_CAMERA.y()-v.y(),CURRENT_CAMERA.z()-v.z()).normalized();
        Vec3 half=CURRENT_SUN.add(view).normalized();
        double specular=0.0;
        if(ndl>0&&half.lengthSquared()>1e-9){
            specular=materialSpecularStrength(p)*Math.pow(Math.max(0,normal.dot(half)),materialSpecularExponent(p));
            specular*=0.25+0.75*Math.max(0.0,Math.min(1.0,CURRENT_LIGHTING.environmentSpecularScale()));
        }
        int shaded=shadeSpecular(c,diffuse,specular);
        shaded=applyEnvironmentLighting(shaded,p,normal,ndl,v);
        b.addVertex(pose,(float)v.x(),(float)v.y(),(float)v.z()).setColor((shaded>>16)&255,(shaded>>8)&255,shaded&255,(shaded>>>24)&255).setUv(u,vv).setOverlay(0).setLight(LIGHT).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());
    }
}
