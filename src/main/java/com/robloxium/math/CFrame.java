package com.robloxium.math;

import java.util.Arrays;

/**
 * Roblox's rigid transform: translation in studs plus a 3x3 rotation.
 * There is intentionally no scale hidden inside this class.
 */
public final class CFrame {
    private final Vec3 position;
    private final double[][] r;

    public CFrame(Vec3 position) { this(position, identityMatrix()); }
    public CFrame(Vec3 position, double[][] rotation) {
        if (rotation == null || rotation.length != 3 ||
            rotation[0].length != 3 || rotation[1].length != 3 || rotation[2].length != 3)
            throw new IllegalArgumentException("CFrame rotation must be 3x3");
        this.position = position;
        this.r = copy(rotation);
    }

    public static CFrame identity() { return new CFrame(Vec3.ZERO); }
    public static CFrame yaw(double radians) { return angles(0, radians, 0); }
    public static CFrame angles(double x, double y, double z) {
        double cx=Math.cos(x), sx=Math.sin(x), cy=Math.cos(y), sy=Math.sin(y), cz=Math.cos(z), sz=Math.sin(z);
        double[][] rx={{1,0,0},{0,cx,-sx},{0,sx,cx}};
        double[][] ry={{cy,0,sy},{0,1,0},{-sy,0,cy}};
        double[][] rz={{cz,-sz,0},{sz,cz,0},{0,0,1}};
        return new CFrame(Vec3.ZERO, mul(mul(rz, ry), rx));
    }

    public Vec3 position() { return position; }
    public Vec3 getPosition() { return position; }
    public Vec3 getP() { return position; }
    public Vec3 getLookVector() { return back().mul(-1); }
    public Vec3 getRightVector() { return right(); }
    public Vec3 getUpVector() { return up(); }
    public double[][] rotation() { return copy(r); }
    public CFrame withPosition(Vec3 p) { return new CFrame(p, r); }

    public Vec3 right() { return transformVector(new Vec3(1,0,0)); }
    public Vec3 up() { return transformVector(new Vec3(0,1,0)); }
    public Vec3 back() { return transformVector(new Vec3(0,0,1)); }

    public Vec3 transformPoint(Vec3 local) { return position.add(transformVector(local)); }
    public Vec3 transformVector(Vec3 local) {
        return new Vec3(
            r[0][0]*local.x()+r[0][1]*local.y()+r[0][2]*local.z(),
            r[1][0]*local.x()+r[1][1]*local.y()+r[1][2]*local.z(),
            r[2][0]*local.x()+r[2][1]*local.y()+r[2][2]*local.z());
    }

    public CFrame multiply(CFrame other) {
        return new CFrame(position.add(transformVector(other.position)), mul(r, other.r));
    }

    public CFrame inverse() {
        double[][] t=transpose(r);
        return new CFrame(new Vec3(-dot(t[0],position),-dot(t[1],position),-dot(t[2],position)), t);
    }

    private static double dot(double[] a, Vec3 v) { return a[0]*v.x()+a[1]*v.y()+a[2]*v.z(); }
    private static double[][] identityMatrix() { return new double[][]{{1,0,0},{0,1,0},{0,0,1}}; }
    private static double[][] transpose(double[][] a) { return new double[][]{{a[0][0],a[1][0],a[2][0]},{a[0][1],a[1][1],a[2][1]},{a[0][2],a[1][2],a[2][2]}}; }
    private static double[][] mul(double[][] a,double[][] b){ double[][]o=new double[3][3]; for(int i=0;i<3;i++)for(int j=0;j<3;j++)o[i][j]=a[i][0]*b[0][j]+a[i][1]*b[1][j]+a[i][2]*b[2][j]; return o; }
    private static double[][] copy(double[][] a){ return new double[][]{{a[0][0],a[0][1],a[0][2]},{a[1][0],a[1][1],a[1][2]},{a[2][0],a[2][1],a[2][2]}}; }
}
