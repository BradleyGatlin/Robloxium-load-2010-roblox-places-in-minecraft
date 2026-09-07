package com.robloxium.runtime;

/** Generic ValueBase-compatible object used by old places and scripts. */
public final class RobloxValue extends RobloxInstance {
    private String value = "";
    public RobloxValue(String className, String name) { super(className, name); }
    public String value() { return value; }
    public void value(String v) { value = v == null ? "" : v; }
    public String Value() { return value; }
}
