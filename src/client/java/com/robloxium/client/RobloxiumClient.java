package com.robloxium.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.robloxium.Robloxium;
import com.robloxium.place.RbxlLoader;
import com.robloxium.runtime.RobloxGame;
import com.robloxium.math.RobloxCoordinateSpace;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;

public final class RobloxiumClient implements ClientModInitializer {
    public static final RobloxRuntimeHost HOST=new RobloxRuntimeHost();

    @Override public void onInitializeClient(){
        RobloxClientRuntime.install();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(ClientCommands.literal("robloxium")
            .then(ClientCommands.literal("load").then(ClientCommands.argument("file",StringArgumentType.word()).executes(c->load(c.getSource(),StringArgumentType.getString(c,"file")))))
            .then(ClientCommands.literal("unload").executes(c->unload(c.getSource())))
            .then(ClientCommands.literal("info").executes(c->{var g=HOST.game();c.getSource().sendFeedback(Component.literal("Robloxium 3 | "+(g.running()?"running":"stopped")+" | Parts: "+g.workspace().parts().size()+" | Tick: "+g.tick()));return 1;}))
            .then(ClientCommands.literal("roblox2010").then(ClientCommands.literal("start").executes(c->{var r=Roblox2010Sidecar.start();c.getSource().sendFeedback(r.component());return r.success()?1:0;})).then(ClientCommands.literal("stop").executes(c->{var r=Roblox2010Sidecar.stop();c.getSource().sendFeedback(r.component());return r.success()?1:0;})).then(ClientCommands.literal("status").executes(c->{c.getSource().sendFeedback(Component.literal(Roblox2010Sidecar.status()));return 1;})))));
        System.out.println("[Robloxium] "+Robloxium.BUILD_ID+" | Minecraft 26.2 host / 2010 guest");
        if(Roblox2010Sidecar.autoStartEnabled())System.out.println("[Robloxium] 2010 sidecar: "+Roblox2010Sidecar.start().message());
    }

    private static int load(FabricClientCommandSource source,String file){
        try{
            Path p=findPlace(file);
            RobloxGame g=new RobloxGame();
            try(var in=Files.newInputStream(p)){RbxlLoader.loadXml(in,g);}
            Minecraft mc=Minecraft.getInstance();
            if(mc.player==null||mc.level==null)throw new IllegalStateException("a Minecraft world must be open before loading a Roblox place");

            // Keep the Roblox place in the current Minecraft world. The Roblox
            // SpawnLocation is anchored to the player's current position, so
            // loading a place never changes dimensions, world data, or the
            // player's Minecraft world.
            Vec3 minecraftPlayer=mc.player.position();
            com.robloxium.math.Vec3 robloxSpawn=g.spawnCFrame().position();
            Vec3 spawn=new Vec3(
                RobloxCoordinateSpace.toMinecraft(robloxSpawn.x()),
                RobloxCoordinateSpace.toMinecraft(robloxSpawn.y()),
                RobloxCoordinateSpace.toMinecraft(robloxSpawn.z()));
            RobloxPartRenderer.setSceneOrigin(minecraftPlayer.subtract(spawn));

            // Roblox scripts see the real Minecraft player as Players.LocalPlayer.
            // This avoids maintaining a second fake avatar for script interaction.
            g.playersService().setMinecraftLocalPlayer(mc.player,mc.player.getName().getString());

            g.start();
            HOST.replace(g);
            RobloxPartRenderer.rebuildShadowCache(g);
            source.sendFeedback(Component.literal("Robloxium: loaded "+file+" into the current world | Parts: "+g.workspace().parts().size()+" | Spawn: "+g.spawnCFrame().position()));
            return 1;
        }catch(Exception e){source.sendError(Component.literal("Robloxium: load failed: "+e.getMessage()));e.printStackTrace();return 0;}
    }

    private static int unload(FabricClientCommandSource source){
        try{
            HOST.stop();
            RobloxPartRenderer.clearSceneOrigin();
            source.sendFeedback(Component.literal("Robloxium: unloaded from the current world."));
            return 1;
        }catch(Exception e){source.sendError(Component.literal("Robloxium: unload failed: "+e.getMessage()));return 0;}
    }

    private static Path findPlace(String file){Path requested=Path.of(file);if(requested.isAbsolute())throw new IllegalArgumentException("absolute place paths are not allowed");for(Path base:new Path[]{Path.of("config","robloxium","places"),Path.of("..","config","robloxium","places")}){Path dir=base.toAbsolutePath().normalize();Path candidate=dir.resolve(requested).normalize();if(candidate.startsWith(dir)&&Files.isRegularFile(candidate))return candidate;}throw new IllegalArgumentException("place not found: "+file);}
}
