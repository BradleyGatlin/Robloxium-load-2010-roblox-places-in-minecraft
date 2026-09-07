package com.robloxium.runtime;

import java.util.List;

public final class RobloxWorkspace extends RobloxInstance {
    public RobloxWorkspace(){super("Workspace","Workspace");}
    public List<RobloxPart> parts(){return descendants(RobloxPart.class);}
}
