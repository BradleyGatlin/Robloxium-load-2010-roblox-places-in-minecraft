package com.robloxium.math;

public record Vec3(double x, double y, double z) {
    public double getX(){return x;} public double getY(){return y;} public double getZ(){return z;}
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public Vec3 add(Vec3 v) { return new Vec3(x + v.x, y + v.y, z + v.z); }
    public Vec3 sub(Vec3 v) { return new Vec3(x - v.x, y - v.y, z - v.z); }
    public Vec3 mul(double s) { return new Vec3(x * s, y * s, z * s); }
    public Vec3 cross(Vec3 v) { return new Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x); }
    public double dot(Vec3 v) { return x*v.x+y*v.y+z*v.z; }
    public double lengthSquared() { return x*x + y*y + z*z; }
    public double length() { return Math.sqrt(lengthSquared()); }
    public Vec3 normalized() { double n=length(); return n < 1e-12 ? ZERO : mul(1.0/n); }
}
