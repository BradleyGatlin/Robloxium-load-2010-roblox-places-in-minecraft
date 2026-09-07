package com.robloxium.client;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
final class RobloxClientRuntime {
    private RobloxClientRuntime(){}
    static void install(){
        RobloxPartRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc->{
            if(RobloxiumClient.HOST.game().running()){ RobloxiumClient.HOST.game().step(); RobloxPlayerCollision.resolve(mc,RobloxiumClient.HOST.game()); }
        });
    }
}
