package com.robloxium.runtime;

/** Generic 2010 DataModel service. Unknown/legacy services remain addressable instead of being discarded. */
public final class RobloxService extends RobloxInstance {
    public RobloxService(String className, String name) { super(className, name == null || name.isBlank() ? className : name); }
}
