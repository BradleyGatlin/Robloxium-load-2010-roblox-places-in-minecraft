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
import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.regex.*;

/**
 * Rewritten geometry renderer.
 *
 * IMPORTANT: Part geometry is generated in Roblox studs, transformed by the
 * Roblox CFrame, and only then converted to Minecraft blocks. No local axis,
 * size, CFrame or rotation is scaled independently.
 */
public final class RobloxPartRenderer {
    private static final int LIGHT=0x00F000F0;
    private static final double MATERIAL_TILE_STUDS=6.0;
    // The 2010 client uses the same physical material tiling convention, but
    // the legacy Grass/Concrete source maps carry a denser authored pattern.
    // Compensate their source-map density so their visible pattern matches the
    // established 6-stud scale used by the other legacy material maps.
    private static double materialTileStuds(RobloxPart p){
        return switch(p.material()){
            case 816 -> 4.0; // Concrete: 1.5x UV density
            default -> MATERIAL_TILE_STUDS;
        };
    }
    // Legacy Texture objects and the built-in 2010 material UVs are tiled in
    // stud space. Minecraft samplers may clamp the UVs, so wrap them ourselves
    // instead of relying on the backend sampler state.
    private static final double SURFACE_TILE_STUDS=3.0;
    private static final Map<Identifier,RenderType> REPEAT_TEXTURE_TYPES=new HashMap<>();
    private static final String SURFACE_STUDS="textures/2010/materials/surface_studs.png";
    private static final String SURFACE_INLET="textures/2010/materials/surface_inlet.png";
    private static final String SURFACE_UNIVERSAL="textures/2010/materials/surface_universal.png";
    // Far parts use the cheap closed cube path. 100 Roblox studs is close enough
    // that the silhouette/detail difference is not useful at normal viewing
    // distance, while avoiding expensive curved/mesh detail for distant parts.
    private static final double DETAIL_DISTANCE_STUDS=100.0;
    // Environment map used by Roblox Reflectance. The six bundled 2010 skybox
    // images are sampled as a real cubemap instead of tinting parts toward a
    // hard-coded blue. This makes metal/foil/reflective parts respond to the
    // actual place sky and to the camera/view direction.
    private static final SkyEnvironment SKY_ENVIRONMENT=SkyEnvironment.load();
    private static final double SHADOW_BROADPHASE_EPSILON=0.01;
    // Shadows farther than this from the camera are not submitted. Their geometry
    // remains cached, but skipping the GPU vertices keeps large places cheap.
    private static final double SHADOW_RENDER_DISTANCE_STUDS=128.0;
    // Port of the Lua stencil-shadow script's 128-stud Prepare gate. Only
    // geometry close enough to the real Minecraft player is shadow-processed.
    // This is the important performance boundary: the old implementation tried
    // to prepare the entire place, even when only a tiny area was visible.
    private static final double SHADOW_PREPARE_DISTANCE_STUDS=128.0;
    // Do not project a shadow indefinitely through a huge place. This also keeps
    // the one-time bake from considering receivers that are visually irrelevant.
    private static final double SHADOW_MAX_DISTANCE_STUDS=128.0;
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
    private static final HeadMesh HEAD_MESH=loadHeadMesh();
    private static RobloxLighting CURRENT_LIGHTING=new RobloxLighting();
    private static Vec3 CURRENT_SUN=new Vec3(-.35,.82,-.45).normalized();
    private static net.minecraft.world.phys.Vec3 CURRENT_CAMERA=net.minecraft.world.phys.Vec3.ZERO;
    private static Vec3 CURRENT_ROBLOX_CAMERA=Vec3.ZERO;
    // Roblox coordinates are anchored into the already-open Minecraft world.
    // This is deliberately a render-space offset; no Minecraft dimension or
    // world-generation data is created or modified.
    private static net.minecraft.world.phys.Vec3 SCENE_ORIGIN=net.minecraft.world.phys.Vec3.ZERO;
    // Temporary shadow cache: shadows are expensive to calculate, so build the
    // shadow mesh once when a place is loaded and only rewrite the cached
    // triangles during rendering. This intentionally does not update when the
    // sun/parts move; it is a performance-first temporary solution.
    private static final List<ShadowTriangle> SHADOW_CACHE=new ArrayList<>();
    private static final Map<RobloxPart,List<ShadowTriangle>> SHADOWS_BY_RECEIVER=new IdentityHashMap<>();
    private static List<RobloxPart> CURRENT_SHADOW_PARTS=List.of();
    // Shadow direction is ALWAYS derived from the loaded Roblox Lighting
    // service. Minecraft's dimension sun/sky is never consulted.
    private static boolean shadowCacheValid;
    private static long shadowCacheSignature;
    private static boolean registered;
    /**
     * Robloxium 2010 rendering is intentionally Vulkan-only. Minecraft 26.2
     * exposes the active GPU backend through Blaze3D, so we can enforce the
     * contract without touching either OpenGL or Vulkan implementation classes.
     */
    private static final String VULKAN_BACKEND_NAME="vulkan";
    private static void requireVulkanBackend(){
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
        // GPU initialization happens after the Fabric client entrypoint.
        // Backend enforcement therefore belongs in the render callback, not here.
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
            // The Roblox place sky is intentionally disabled for screenshot/debug builds.
            // Keep the sky textures loaded for CPU environment lighting/specular, but do not
            // submit the six sky faces to the Minecraft world renderer.
            // Build the receiver-local shadow mask BEFORE emitting the Roblox
            // geometry. The previous version did this after drawParts(), so the
            // first rendered frame could never use the freshly-built cache.
            // TEMPORARY: custom Roblox stencil-shadow system disabled for performance.
            // Keep all normal Roblox lighting/specular/reflectance, but skip shadow
            // cache construction and per-face shadow tests until the renderer is optimized.
            // Do NOT draw projected black polygons over the scene. That was only
            // a visual stand-in for a stencil buffer and is exactly what caused
            // the giant overlapping triangles in the screenshot. The actual
            // shadow result is now applied while each receiver face is shaded,
            // so multiple casters union instead of alpha-stacking.
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

    /** Build the expensive shadow geometry once for the current Roblox place. */
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
        // Roblox's 2010 client renders its six sky faces as the environment
        // rather than blending them with the host world's sky. The vanilla
        // sky is cancelled by RobloxSkyRendererMixin; this cube therefore owns
        // every pixel that is not occupied by Minecraft/Roblox geometry.
        // Keep the 2010-style environment compact: one Minecraft chunk (16x16).
        // The sky depth test below makes it a background instead of an overlay.
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
    /**
     * Direction TO the sun, calculated entirely from Roblox Lighting.TimeOfDay
     * and GeographicLatitude. It is intentionally independent of Minecraft's
     * celestial angle, dimension time, weather, or skylight.
     */
    private static Vec3 sunDirection(RobloxLighting lighting){
        // This matches the legacy Roblox GetSunDirection convention rather than
        // using Minecraft's celestial angle.  In particular, Roblox's
        // GeographicLatitude already incorporates the 23.5-degree axial tilt
        // into this direction.  Example: 14:00 / 41.73 degrees produces
        // approximately (-0.4749, 0.8225, 0.3129), the documented legacy result.
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

        // Roblox content URIs are not Minecraft resource identifiers.
        // Resolve the legacy built-in rbxasset://textures/... namespace first,
        // before anything can reach Identifier.fromNamespaceAndPath().
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
            // Unknown built-in Roblox content must never crash the renderer.
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
    /**
     * Hard, sun-space projected shadows.
     *
     * This deliberately does NOT use Minecraft's block shadow system. Roblox
     * parts live in their own scene, so the shadow caster/receiver test is
     * performed in Roblox studs first and converted to Minecraft blocks only
     * when vertices are emitted.
     *
     * The algorithm is the same geometric idea used by a shadow-volume
     * renderer: rays leave the caster in the direction opposite the sun and
     * the resulting silhouette is clipped against every receiver face that
     * faces the sun.  This fixes the old "always hit the first top AABB" bug:
     * rotated parts, walls and stacked parts can now receive the correct hard
     * shadow.
     */
    private record ShadowTriangle(Vec3 a,Vec3 b,Vec3 c,Vec3 normal,Vec3 center,double radius,
                                   RobloxPart caster,RobloxPart receiver){}

    private static void buildShadowCache(List<RobloxPart> parts,Vec3 light){
        // Spatially bin receivers before the one-time bake. The old version
        // walked every receiver for every caster (O(N^2)); large Roblox places
        // could therefore spend seconds doing shadow work before the first
        // frame. A 64-stud grid limits each caster to only the cells its
        // projected shadow can actually reach.
        final double cell=64.0;
        Map<Long,List<RobloxPart>> grid=new HashMap<>();
        Vec3 shadowFocus=shadowFocusRoblox();
        for(RobloxPart receiver:parts){
            if(!receivesShadow(receiver))continue;
            Vec3 c=receiver.cframe().position();
            if(c.sub(shadowFocus).lengthSquared() > SHADOW_PREPARE_DISTANCE_STUDS*SHADOW_PREPARE_DISTANCE_STUDS) continue;
            double r=partRadius(receiver);
            int minX=(int)Math.floor((c.x()-r)/cell), maxX=(int)Math.floor((c.x()+r)/cell);
            int minY=(int)Math.floor((c.y()-r)/cell), maxY=(int)Math.floor((c.y()+r)/cell);
            int minZ=(int)Math.floor((c.z()-r)/cell), maxZ=(int)Math.floor((c.z()+r)/cell);
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++)
                grid.computeIfAbsent(gridKey(x,y,z),k->new ArrayList<>()).add(receiver);
        }

        Set<RobloxPart> candidates=Collections.newSetFromMap(new IdentityHashMap<>());
        for(RobloxPart caster:parts){
            if(!castsShadow(caster))continue;
            Vec3 casterCenter= caster.cframe().position();
            if(casterCenter.sub(shadowFocus).lengthSquared() > SHADOW_PREPARE_DISTANCE_STUDS*SHADOW_PREPARE_DISTANCE_STUDS) continue;
            List<Vec3> casterVertices=boxWorldVertices(caster);
            if(casterVertices.isEmpty())continue;

            Vec3 cc=casterCenter;
            double r=partRadius(caster);
            // The shadow can travel at most SHADOW_MAX_DISTANCE_STUDS along
            // -light. Build a conservative AABB around that swept sphere.
            Vec3 far=cc.sub(light.mul(SHADOW_MAX_DISTANCE_STUDS));
            double minX=Math.min(cc.x(),far.x())-r, maxX=Math.max(cc.x(),far.x())+r;
            double minY=Math.min(cc.y(),far.y())-r, maxY=Math.max(cc.y(),far.y())+r;
            double minZ=Math.min(cc.z(),far.z())-r, maxZ=Math.max(cc.z(),far.z())+r;
            int ix0=(int)Math.floor(minX/cell), ix1=(int)Math.floor(maxX/cell);
            int iy0=(int)Math.floor(minY/cell), iy1=(int)Math.floor(maxY/cell);
            int iz0=(int)Math.floor(minZ/cell), iz1=(int)Math.floor(maxZ/cell);
            candidates.clear();
            for(int x=ix0;x<=ix1;x++)for(int y=iy0;y<=iy1;y++)for(int z=iz0;z<=iz1;z++){
                List<RobloxPart> bucket=grid.get(gridKey(x,y,z));
                if(bucket!=null)candidates.addAll(bucket);
            }
            for(RobloxPart receiver:candidates){
                if(receiver==caster)continue;
                projectShadowIntoCache(caster,receiver,casterVertices,light);
            }
        }
    }

    private static void projectShadowIntoCache(RobloxPart caster,RobloxPart receiver,
                                                List<Vec3> casterVertices,Vec3 light){
        // Broad-phase first. A receiver can only be shadowed if it lies in the
        // direction opposite the light and its center is close enough to the
        // caster's projected footprint. This removes the old O(N^2) work for
        // unrelated parts while keeping side receivers possible.
        Vec3 casterCenter=caster.cframe().position();
        Vec3 receiverCenter=receiver.cframe().position();
        Vec3 delta=receiverCenter.sub(casterCenter);
        double along=delta.dot(light);
        if(along>=-SHADOW_BROADPHASE_EPSILON)return;
        if(-along>SHADOW_MAX_DISTANCE_STUDS)return;

        double casterRadius=partRadius(caster);
        double receiverRadius=partRadius(receiver);
        Vec3 lateral=delta.sub(light.mul(along));
        double maxLateral=casterRadius+receiverRadius+SHADOW_BROADPHASE_EPSILON;
        if(lateral.lengthSquared()>maxLateral*maxLateral)return;

        Vec3 center=receiverCenter;
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

        // Project the complete caster silhouette onto each receiver plane,
        // then clip that polygon to the receiver face rectangle. Unlike the
        // previous corner-hit approach, this produces one continuous flat
        // shadow that can cover floors, walls, ceilings and the sides of parts.
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

            // Reconstruct the exact 3D points from the face's local 2D
            // coordinates. The tiny normal offset prevents z-fighting while
            // keeping the shadow perfectly coplanar with the receiver.
            Vec3 offset=face.normal().mul(0.002);
            FacePoint root=clipped.get(0);
            for(int i=1;i<clipped.size()-1;i++){
                Vec3 a=facePoint3(face,root).add(offset);
                Vec3 b=facePoint3(face,clipped.get(i)).add(offset);
                Vec3 c=facePoint3(face,clipped.get(i+1)).add(offset);
                // Each triangle is its own flat shadow decal. No edge/face data
                // is shared with neighboring receiver faces, so a wall shadow
                // cannot accidentally become a connected 3D shadow surface.
                Vec3 triCenter=a.add(b).add(c).mul(1.0/3.0);
                double radius=Math.max(triCenter.sub(a).length(),Math.max(triCenter.sub(b).length(),triCenter.sub(c).length()));
                // A projected silhouette is only valid if the light ray from this
                // receiver sample can actually reach the caster.  Test the triangle
                // center once during the cache build so a wall/part between the
                // caster and receiver cuts the shadow instead of letting it pass
                // straight through the scene.
                if(shadowRayOccluded(caster,triCenter,face.normal()))continue;

                // Keep the caster attached to the triangle. This is important: stencil
                // shadows from separate Parts must remain separate shadow volumes.
                // We never union their geometry just because their projected areas touch.
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
        out=clipEdge(out,0,halfU);   // u <= +halfU
        out=clipEdge(out,1,-halfU);  // u >= -halfU
        out=clipEdge(out,2,halfV);   // v <= +halfV
        out=clipEdge(out,3,-halfV);  // v >= -halfV
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
        if(p==null || p.transparency()>=0.999)return false;
        if(p.anchored())return true;
        Vec3 v=p.velocity();
        Vec3 rv=p.rotVelocity();
        return v.lengthSquared()>0.01*0.01 || rv.lengthSquared()>0.01*0.01;
    }

    private static boolean castsShadow(RobloxPart p){
        // CanCollide has no bearing on whether a visible Roblox Part blocks the
        // sun. Anchored Parts always participate; an unanchored Part participates
        // while it is actually moving, matching the ActiveWaiting behavior of the
        // supplied 2010 stencil-shadow reference.
        return shadowRelevantPart(p);
    }

    private static boolean receivesShadow(RobloxPart p){
        return shadowRelevantPart(p);
    }

    /** Returns the eight corners in Roblox/world studs. */
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


    /**
     * Collapse overlapping coplanar shadow triangles into larger independent
     * triangles.  The shading path is binary (a point is either shadowed or
     * lit), so overlapping caster shadows must never stack and become darker.
     * Merging here also keeps the per-receiver point-in-triangle list small.
     *
     * We only merge when the two triangles overlap and their combined convex
     * hull is close to the area of the two source triangles.  That avoids
     * filling large concave gaps while still combining the common case of
     * intersecting shadow faces.
     */
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
        // Separating-axis test for two convex triangles in the receiver plane.
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
        // Keep merged geometry only when the triangles genuinely overlap in
        // area (edge-touching alone should not cause a merge).
        double interLower=Math.max(0.0,sourceArea-hullArea);
        if(interLower<1e-7)return null;
        Vec3 origin=a.a();
        Vec3 p0=origin.add(u.mul(hull.get(0)[0]-origin.dot(u))).add(v.mul(hull.get(0)[1]-origin.dot(v)));
        Vec3 p1=origin.add(u.mul(hull.get(1)[0]-origin.dot(u))).add(v.mul(hull.get(1)[1]-origin.dot(v)));
        Vec3 p2=origin.add(u.mul(hull.get(2)[0]-origin.dot(u))).add(v.mul(hull.get(2)[1]-origin.dot(v)));
        // The hull can have more than three points.  We intentionally keep the
        // largest-area triangle as the conservative merged representative only
        // when the hull is triangular; otherwise triangulate below by fan and
        // return null so the caller keeps the original exact union.
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
        // Three signed 21-bit coordinates packed into one long. Places are
        // nowhere near the range limit, and this avoids allocating key objects.
        return ((long)(x&0x1FFFFF)<<42)|((long)(y&0x1FFFFF)<<21)|(long)(z&0x1FFFFF);
    }

    // Kept as a named hook for older callers; projected black shadow geometry is
    // intentionally gone. Shadows are applied during receiver shading instead.
    private static void drawCachedShadows(SubmitNodeCollector c,PoseStack ps){
        // no-op
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
        if("Head".equalsIgnoreCase(p.name())&&p.meshType()==0&&detailDistanceSquared(p)<=DETAIL_DISTANCE_STUDS*DETAIL_DISTANCE_STUDS){drawHead(pose,b,p,col);return;}
        // Legacy SpecialMesh.MeshType.Sphere (2010): render the Part as a
        // smoothly shaded UV sphere using the Part's dimensions and mesh scale.
        // MeshType values are kept as the legacy numeric enum in the loader;
        // Sphere is 3.
        if(p.meshType()==3){drawSphere(pose,b,p,col);return;}
        if(!p.meshId().isBlank()){
            OnlineMesh onlineMesh=RobloxOnlineAssets.mesh(p.meshId());
            if(onlineMesh!=null){drawOnlineMesh(pose,b,p,onlineMesh,col);return;}
        }
        if("WedgePart".equalsIgnoreCase(p.className())){drawWedge(pose,b,p,hx,hy,hz,col);return;}
        if("CornerWedgePart".equalsIgnoreCase(p.className())){drawCornerWedge(pose,b,p,hx,hy,hz,col);return;}
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
        vertexSurface(pose,b,wa,color,0,1,wn,p);
        vertexSurface(pose,b,wb,color,1,1,wn,p);
        vertexSurface(pose,b,wc,color,1,0,wn,p);
        vertexSurface(pose,b,wc,color,1,0,wn,p);
        vertexSurface(pose,b,wd,color,0,0,wn,p);
        vertexSurface(pose,b,wa,color,0,1,wn,p);
    }

    private static void drawOnlineMesh(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,OnlineMesh mesh,int color){
        Vec3 part=p.size(),scale=p.meshScale(),half=mesh.half();
        double sx=(part.x()*scale.x())/Math.max(half.x()*2.0,1e-9), sy=(part.y()*scale.y())/Math.max(half.y()*2.0,1e-9), sz=(part.z()*scale.z())/Math.max(half.z()*2.0,1e-9);
        Vec3 off=p.meshOffset();
        for(int i=0;i<mesh.positions().length;i+=3){
            Vec3 a=mesh.positions()[i],bb=mesh.positions()[i+1],cc=mesh.positions()[i+2];
            a=new Vec3(a.x()*sx+off.x(),a.y()*sy+off.y(),a.z()*sz+off.z());
            bb=new Vec3(bb.x()*sx+off.x(),bb.y()*sy+off.y(),bb.z()*sz+off.z());
            cc=new Vec3(cc.x()*sx+off.x(),cc.y()*sy+off.y(),cc.z()*sz+off.z());
            Vec3 na=mesh.normals()[i],nb=mesh.normals()[i+1],nc=mesh.normals()[i+2];
            float[] ua=mesh.uvs()[i],ub=mesh.uvs()[i+1],uc=mesh.uvs()[i+2];
            triangleSmooth(pose,b,p,a,bb,cc,na,nb,nc,color,new float[]{ua[0],ua[1],ub[0],ub[1],uc[0],uc[1]});
        }
    }

    static OnlineMesh parseOnlineObj(String text){
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
            if(outV.size()<3)return null;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;
            for(Vec3 x:outV){minX=Math.min(minX,x.x());minY=Math.min(minY,x.y());minZ=Math.min(minZ,x.z());maxX=Math.max(maxX,x.x());maxY=Math.max(maxY,x.y());maxZ=Math.max(maxZ,x.z());}
            Vec3 center=new Vec3((minX+maxX)*.5,(minY+maxY)*.5,(minZ+maxZ)*.5),half=new Vec3(Math.max((maxX-minX)*.5,1e-6),Math.max((maxY-minY)*.5,1e-6),Math.max((maxZ-minZ)*.5,1e-6));
            Vec3[] positions=new Vec3[outV.size()];
            for(int i=0;i<outV.size();i++)positions[i]=outV.get(i).sub(center);
            return new OnlineMesh(positions,outN.toArray(Vec3[]::new),outUv.toArray(float[][]::new),half);
        }catch(Throwable ignored){return null;}
    }

    private static void drawHead(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,int col){
        if(HEAD_MESH==null)return;
        double headScale=0.5;
        double sx=headScale*(p.size().x()*p.meshScale().x())/Math.max(HEAD_MESH.half.x()*2.0,1e-9);
        double sy=headScale*(p.size().y()*p.meshScale().y())/Math.max(HEAD_MESH.half.y()*2.0,1e-9);
        double sz=headScale*(p.size().z()*p.meshScale().z())/Math.max(HEAD_MESH.half.z()*2.0,1e-9);
        for(int i=0;i<HEAD_MESH.positions.length;i+=3){
            Vec3 a=HEAD_MESH.positions[i].mul(sx), bb=HEAD_MESH.positions[i+1].mul(sx), c=HEAD_MESH.positions[i+2].mul(sx);
            a=new Vec3(a.x(),a.y()*sy,a.z()*sz);bb=new Vec3(bb.x(),bb.y()*sy,bb.z()*sz);c=new Vec3(c.x(),c.y()*sy,c.z()*sz);
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

        // Enough tessellation for the classic Roblox sphere without making
        // every SpecialMesh expensive. The poles are shared conceptually by
        // the UV grid, while vertices are emitted per triangle for smooth
        // normals and correct UVs.
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
                Vec3 v00=new Vec3(hx*n00.x(),hy*n00.y(),hz*n00.z());
                Vec3 v10=new Vec3(hx*n10.x(),hy*n10.y(),hz*n10.z());
                Vec3 v11=new Vec3(hx*n11.x(),hy*n11.y(),hz*n11.z());
                Vec3 v01=new Vec3(hx*n01.x(),hy*n01.y(),hz*n01.z());

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
    /**
     * Loads the bundled replacement head directly from the supplied OBJ.
     *
     * The old loader tried to reverse-engineer a legacy .mesh text layout and
     * then uniformly scaled the result. That was especially bad for the R6
     * head because the Roblox head occupies a rectangular 2x1x1 part while
     * the actual mesh is approximately spherical. The OBJ is now treated as
     * the source of truth: positions, UVs and normals are expanded per face,
     * centered and kept at the mesh's native scale.
     */
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
                            // OBJ faces may be polygons. Fan triangulate them so
                            // quads/ngons from Blender remain valid Minecraft geometry.
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
    /**
     * Builds a real chamfered box rather than shrinking a cube and trying to
     * patch the gaps around it.  The surface is split into the 6 original
     * planar faces, 12 planar edge strips and 8 corner triangles.
     *
     * Every generated polygon is wound from its requested outward normal, so
     * the bevel cannot randomly flip when a part is rotated.
     */
    /**
     * Robust bevel renderer. The important change is that the original closed
     * cube is ALWAYS emitted first. The bevel is an additional closed set of
     * strips inside that cube, so a bad winding or a corner calculation can
     * never create a literal hole in the part. The chamfer is deliberately
     * tiny, matching the restrained 2010-era block look.
     */
    // Kept as a compatibility method for old callers. It intentionally emits
    // a plain closed Roblox Part: meshes and Parts are never chamfered here.
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
        Vec3 a=new Vec3(-hx,-hy,-hz),bb=new Vec3(hx,-hy,-hz),c=new Vec3(hx,-hy,hz),d=new Vec3(-hx,-hy,hz);
        Vec3 e=new Vec3(-hx,hy,-hz),f=new Vec3(hx,hy,-hz);
        face(pose,b,p,a,bb,c,d,0,-1,0,col);
        face(pose,b,p,a,e,f,bb,0,0,-1,col);
        triangleFace(pose,b,p,a,d,e,new Vec3(-1,0,0),col);
        triangleFace(pose,b,p,bb,f,c,new Vec3(1,0,0),col);
        quad(pose,b,p,e,f,c,d,new Vec3(0,hz,hy),col);
    }
    private static void drawCornerWedge(PoseStack.Pose pose,VertexConsumer b,RobloxPart p,double hx,double hy,double hz,int col){
        // Closed corner-wedge volume. Keeping every face explicit avoids the
        // degenerate triangles the previous implementation emitted.
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

        // A projected stencil shadow has a hard boundary somewhere inside the
        // receiver face. A single quad only evaluates lighting at four corners,
        // which made a whole wall/floor miss the shadow whenever its corners were
        // outside the caster silhouette. Subdivide ONLY receivers that actually
        // have cached shadows. Four-by-four is enough for the old blocky 2010
        // look while avoiding the huge tessellation cost of subdividing every
        // Roblox face. Eight-by-eight keeps the hard boundary much closer to the
        // actual projected stencil silhouette without returning to black overlay
        // polygons.
        List<ShadowTriangle> shadowTris=SHADOWS_BY_RECEIVER.get(p);
        if(shadowTris==null || shadowTris.isEmpty() || p.transparency()>=0.999){
            emitFaceQuad(pose,b,p,a,bb,c,d,n,color,0f,uTex,0f,vTex);
            return;
        }
        final int cells=8;
        for(int iy=0;iy<cells;iy++){
            double v0=(double)iy/cells, v1=(double)(iy+1)/cells;
            Vec3 left0=lerp(a,d,v0), right0=lerp(bb,c,v0);
            Vec3 left1=lerp(a,d,v1), right1=lerp(bb,c,v1);
            for(int ix=0;ix<cells;ix++){
                double u0=(double)ix/cells, u1=(double)(ix+1)/cells;
                Vec3 p00=lerp(left0,right0,u0), p10=lerp(left0,right0,u1);
                Vec3 p11=lerp(left1,right1,u1), p01=lerp(left1,right1,u0);
                emitFaceQuad(pose,b,p,p00,p10,p11,p01,n,color,
                        (float)(u0*uTex),(float)(u1*uTex),
                        (float)((1.0-v0)*vTex),(float)((1.0-v1)*vTex));
            }
        }
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
        // Evaluate the shadow once at the center of the cell and keep all four
        // vertices on the same side of the hard stencil boundary. If the four
        // vertices were shaded independently, a boundary crossing a cell would
        // create a diagonal half-triangle, which is exactly the artifact we are
        // trying to avoid.
        Vec3 cellCenter=world(p,lerp(lerp(a,d,0.5),lerp(bb,c,0.5),0.5));
        double cellShadow=shadowMask(p,cellCenter,n);
        vertexWithShadow(pose,b,wa,ra,u0,v1,n,p,cellShadow);
        vertexWithShadow(pose,b,wb,rb,u1,v1,n,p,cellShadow);
        vertexWithShadow(pose,b,wc,rc,u1,v0,n,p,cellShadow);
        vertexWithShadow(pose,b,wc,rc,u1,v0,n,p,cellShadow);
        vertexWithShadow(pose,b,wd,rd,u0,v0,n,p,cellShadow);
        vertexWithShadow(pose,b,wa,ra,u0,v1,n,p,cellShadow);
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
        // Fake the old stencil result in the level's own shading path: ambient
        // light remains, while only the direct sun term is suppressed.
        double diffuse=CURRENT_LIGHTING.ambientFactor()+CURRENT_LIGHTING.sunFactor()*ndl*shadow;
        double specular=0.0;
        Vec3 view=new Vec3(CURRENT_CAMERA.x()-v.x(),CURRENT_CAMERA.y()-v.y(),CURRENT_CAMERA.z()-v.z()).normalized();
        Vec3 half=CURRENT_SUN.add(view).normalized();
        if(ndl>0&&half.lengthSquared()>1e-9){
            double exponent=materialSpecularExponent(part);
            specular=materialSpecularStrength(part)*Math.pow(Math.max(0,normal.dot(half)),exponent);
            specular*=0.25+0.75*Math.max(0.0,Math.min(1.0,CURRENT_LIGHTING.environmentSpecularScale()));
        }
        int shaded=shadeSpecular(c,diffuse,specular);
        shaded=applyEnvironmentLighting(shaded,part,normal,ndl,v);
        b.addVertex(pose,(float)v.x(),(float)v.y(),(float)v.z()).setColor((shaded>>16)&255,(shaded>>8)&255,shaded&255,(shaded>>>24)&255).setUv(u,vv).setOverlay(0).setLight(LIGHT).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());
    }
    private static double shadowMask(RobloxPart receiver,Vec3 minecraftPoint,Vec3 worldNormal){
        // TEMPORARILY DISABLED: custom Roblox shadows are too expensive right now.
        return 1.0;
    }

    private static double shadowLightMultiplier(){
        int c=CURRENT_LIGHTING==null?0xFF333333:CURRENT_LIGHTING.shadowColor();
        double r=((c>>16)&255)/255.0, g=((c>>8)&255)/255.0, b=(c&255)/255.0;
        // ShadowColor is the remaining light contribution in the legacy-style
        // CPU lighting path: black means a hard shadow, white means no darkening.
        return Math.max(0.0,Math.min(1.0,0.2126*r+0.7152*g+0.0722*b));
    }

    private static boolean shadowRayOccluded(RobloxPart caster,Vec3 receiverPoint,Vec3 receiverNormal){
        Vec3 origin=receiverPoint.add(receiverNormal.mul(0.01));
        Vec3 light=CURRENT_SUN.normalized();
        if(light.lengthSquared()<1e-9)return false;
        // Travel from the receiver toward the light. Any other opaque Part hit
        // before reaching the caster's projected ray blocks this shadow sample.
        double casterDistance=rayBoxHitDistance(origin,light,caster);
        if(casterDistance<0.02)return false;
        for(RobloxPart blocker:CURRENT_SHADOW_PARTS){
            if(blocker==caster || blocker.transparency()>=0.999)continue;
            double t=rayBoxHitDistance(origin,light,blocker);
            // Only geometry between the receiver and the caster can occlude
            // this caster's shadow. Parts behind the caster must not connect or
            // erase an otherwise valid shadow.
            if(t>=0.01 && t<casterDistance-0.02)return true;
        }
        return false;
    }

    private static double rayBoxHitDistance(Vec3 origin,Vec3 dir,RobloxPart part){
        // Transform the ray into the Part's local box space. Roblox Parts are
        // the authoritative collision/shadow volumes, so no Minecraft blocks
        // or Minecraft sun state participate here.
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
        // The triangle lies on the receiver plane. Allow a small tolerance for
        // floating point conversion and the existing 0.002 stud decal offset.
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
        // ColorShift_Top affects surfaces facing the Roblox sun; Bottom affects
        // surfaces facing away. These are Color3 lighting tints, not texture colors.
        int shift=ndl>=0.5?l.colorShiftTop():l.colorShiftBottom();
        double shiftAmount=Math.min(1.0,brightness*0.35);
        double sr=((shift>>16)&255)/255.0, sg=((shift>>8)&255)/255.0, sb=(shift&255)/255.0;
        double r=((color>>16)&255)/255.0, g=((color>>8)&255)/255.0, b=(color&255)/255.0;
        if((((shift>>16)&255)|((shift>>8)&255)|(shift&255))!=0){
            r=r*(1-shiftAmount)+r*sr*shiftAmount;
            g=g*(1-shiftAmount)+g*sg*shiftAmount;
            b=b*(1-shiftAmount)+b*sb*shiftAmount;
        }
        // EnvironmentDiffuseScale uses the actual loaded Roblox skybox as the
        // ambient environment. This is deliberately separate from Reflectance.
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
        // The outgoing environment ray is the reflection of the incoming
        // camera ray around the surface normal.
        Vec3 environment=n.mul(2.0*n.dot(toCamera)).sub(toCamera).normalized();
        int sky=SKY_ENVIRONMENT.sample(environment);
        // Roblox Reflectance is an environment contribution, not an opaque
        // replacement for the material. Preserve the brick/material color at
        // low values and blend toward the actual skybox at high values.
        // Reflectance in the 2010 renderer is an environment-map contribution.
        // Do not reduce it to a tiny specular highlight: at 1.0 the skybox is
        // supposed to be plainly visible on the surface. The Fresnel term keeps
        // low-angle surfaces from looking completely flat while preserving the
        // authored Reflectance value.
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
        // Surface types are decals sitting on the Part face. Offset them a tiny
        // amount toward the face normal as well as using the polygon-offset
        // render pipeline. This eliminates Vulkan/OpenGL z-fighting without
        // visibly separating the 2010 Roblox studs/inlet geometry.
        final double SURFACE_OFFSET_STUDS=0.0025;
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
    /** CPU-side cubemap sampler backed by the exact six skybox textures. */
    private static final class SkyEnvironment {
        private final BufferedImage[] faces;
        private SkyEnvironment(BufferedImage[] faces){this.faces=faces;}
        static SkyEnvironment load(){
            try{
                BufferedImage[] f=new BufferedImage[6];
                // Indices match SKY: +X, -X, +Y, -Y, +Z, -Z.
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

    /*
     * Roblox geometry gets its own world pipeline.  Do NOT use TEXT or
     * TEXT_POLYGON_OFFSET here: those pipelines require the text shader
     * descriptor layout (including Sampler2) and are not world-material
     * pipelines.  That was the source of the Vulkan Missing sampler Sampler2
     * crash.
     *
     * The Roblox renderer bakes Roblox 2010 lighting, shadowing, specular and
     * Reflectance into vertex colour. Therefore the world shader only needs
     * position + UV + colour + Sampler0. No Minecraft lightmap is involved.
     */
    /*
     * 26.2 provides a POSITION_TEX_COLOR + Sampler0 shader interface through
     * GUI_TEXTURED_SNIPPET. We reuse only that shader interface and override
     * the primitive topology to TRIANGLES for the Roblox level stream.
     */
    // 2010 Roblox geometry is submitted as explicit triangle lists.  Do not
    // inherit GUI_QUADS topology here: Vulkan will otherwise reinterpret the
    // vertex stream and create the triangular holes/diagonal faces seen in the
    // broken level renderer.  The shader interface remains POSITION_TEX_COLOR
    // + Sampler0, but the primitive topology is explicitly TRIANGLES.
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
