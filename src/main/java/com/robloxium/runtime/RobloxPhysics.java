package com.robloxium.runtime;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Legacy Roblox 2010 part physics.
 *
 * Units are deliberately Roblox units:
 *   position/size = studs
 *   velocity      = studs/sec
 *   gravity       = studs/sec^2
 *
 * Anchored parts are immovable. Unanchored parts are integrated every
 * simulation tick and collide with other CanCollide parts. This is kept
 * separate from the Minecraft player controller so Roblox Parts remain
 * guest-side physics objects.
 */
public final class RobloxPhysics {
    /** Exact legacy Roblox gravity used by the 2010-era engine. */
    public static final double GRAVITY=196.2;
    private static final int SUBSTEPS=4;
    private static final double RESTITUTION=0.0;
    private static final double FRICTION=0.80;
    private static final double EPS=1.0e-5;
    /** Large welded assemblies are treated as static to prevent physics explosions. */
    private static final int MAX_WELDS_PER_PART=20;
    private final RobloxGame game;

    public RobloxPhysics(RobloxGame g){game=g;}

    public void step(double dt){
        if(dt<=0||game==null)return;
        double h=Math.min(dt,0.1)/SUBSTEPS;
        for(int sub=0;sub<SUBSTEPS;sub++){
            List<RobloxPart> parts=new ArrayList<>(game.workspace().parts());
            Set<PartPair> weldedPairs=weldedPairs();
            Set<RobloxPart> disabledAssemblies=physicsDisabledAssemblies(parts);

            // Integrate free parts. Roblox gravity affects every unanchored
            // BasePart, including CanCollide=false parts.
            for(RobloxPart p:parts){
                if(p.anchored()||disabledAssemblies.contains(p))continue;
                Vec3 v=p.velocity();
                v=new Vec3(v.x(),v.y()-GRAVITY*h,v.z());
                p.velocity(v);
                p.cframe(p.cframe().withPosition(p.cframe().position().add(v.mul(h))));
            }

            solveWelds(parts, disabledAssemblies);

            // Resolve part/part contacts. Legacy places overwhelmingly use
            // boxes, so the broad-phase and contact shape use the rotated
            // part's world-space AABB just like the 2010 broad collision path.
            for(int i=0;i<parts.size();i++){
                RobloxPart a=parts.get(i);
                if(!a.canCollide()||a.transparency()>=1)continue;
                for(int j=i+1;j<parts.size();j++){
                    RobloxPart b=parts.get(j);
                    if(!b.canCollide()||b.transparency()>=1)continue;
                    if(a.anchored()&&b.anchored())continue;
                    if(disabledAssemblies.contains(a)||disabledAssemblies.contains(b))continue;
                    if(weldedPairs.contains(new PartPair(a,b)))continue;
                    resolvePair(a,b);
                }
            }
        }
    }


    /** Keep legacy Weld/WeldConstraint assemblies rigid after integration.
     * This is intentionally transform-based: old places rely on joints to
     * keep loose parts together, and treating each part as an independent
     * rigid body creates visible explosions/jitter. */
    private void solveWelds(List<RobloxPart> parts, Set<RobloxPart> disabledAssemblies){
        for(RobloxWeld w:game.dataModel().descendants(RobloxWeld.class)){
            RobloxPart a=w.part0(), b=w.part1();
            if(a==null||b==null)continue;
            if(disabledAssemblies.contains(a)||disabledAssemblies.contains(b))continue;
            CFrame desiredB=a.cframe().multiply(w.c0()).multiply(w.c1().inverse());
            boolean am=!a.anchored(), bm=!b.anchored();
            if(!am&&!bm)continue;
            if(!am){
                b.cframe(desiredB); b.velocity(Vec3.ZERO); b.rotVelocity(Vec3.ZERO);
            }else if(!bm){
                CFrame desiredA=b.cframe().multiply(w.c1()).multiply(w.c0().inverse());
                a.cframe(desiredA); a.velocity(Vec3.ZERO); a.rotVelocity(Vec3.ZERO);
            }else{
                b.cframe(desiredB);
                b.velocity(a.velocity());
                b.rotVelocity(a.rotVelocity());
            }
        }
    }

    /**
     * Returns every part belonging to a welded assembly containing a part
     * touched by more than MAX_WELDS_PER_PART welds. Such assemblies are
     * deliberately left out of dynamic simulation: they are commonly large
     * models (vehicles, buildings, machinery) and solving hundreds of rigid
     * contacts every tick is both wasteful and unstable. The assembly remains
     * intact because we do not move or split any of its welded parts.
     */
    private Set<RobloxPart> physicsDisabledAssemblies(List<RobloxPart> parts){
        List<RobloxWeld> welds=game.dataModel().descendants(RobloxWeld.class);
        java.util.Map<RobloxPart,Integer> counts=new java.util.IdentityHashMap<>();
        java.util.Map<RobloxPart,List<RobloxPart>> graph=new java.util.IdentityHashMap<>();
        for(RobloxWeld w:welds){
            RobloxPart a=w.part0(),b=w.part1();
            if(a==null||b==null)continue;
            counts.put(a,counts.getOrDefault(a,0)+1);
            counts.put(b,counts.getOrDefault(b,0)+1);
            graph.computeIfAbsent(a,k->new ArrayList<>()).add(b);
            graph.computeIfAbsent(b,k->new ArrayList<>()).add(a);
        }
        Set<RobloxPart> disabled=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<RobloxPart> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(RobloxPart root:parts){
            if(seen.contains(root))continue;
            boolean welded=graph.containsKey(root);
            if(!welded){
                seen.add(root);
                if(counts.getOrDefault(root,0)>MAX_WELDS_PER_PART)disabled.add(root);
                continue;
            }
            ArrayList<RobloxPart> assembly=new ArrayList<>();
            ArrayList<RobloxPart> stack=new ArrayList<>();
            stack.add(root); seen.add(root);
            boolean tooLarge=false;
            while(!stack.isEmpty()){
                RobloxPart p=stack.remove(stack.size()-1);
                assembly.add(p);
                if(counts.getOrDefault(p,0)>MAX_WELDS_PER_PART)tooLarge=true;
                for(RobloxPart n:graph.getOrDefault(p,java.util.List.of())){
                    if(seen.add(n))stack.add(n);
                }
            }
            if(tooLarge)disabled.addAll(assembly);
        }
        return disabled;
    }

    private Set<PartPair> weldedPairs(){
        Set<PartPair> out=new HashSet<>();
        for(RobloxWeld w:game.dataModel().descendants(RobloxWeld.class)){
            if(w.part0()!=null&&w.part1()!=null)out.add(new PartPair(w.part0(),w.part1()));
        }
        return out;
    }

    private static void resolvePair(RobloxPart a,RobloxPart b){
        Bounds A=bounds(a),B=bounds(b);
        if(A.maxX<=B.minX+EPS||A.minX>=B.maxX-EPS||
           A.maxY<=B.minY+EPS||A.minY>=B.maxY-EPS||
           A.maxZ<=B.minZ+EPS||A.minZ>=B.maxZ-EPS)return;

        double px=Math.min(A.maxX-B.minX,B.maxX-A.minX);
        double py=Math.min(A.maxY-B.minY,B.maxY-A.minY);
        double pz=Math.min(A.maxZ-B.minZ,B.maxZ-A.minZ);
        Vec3 ac=a.cframe().position(),bc=b.cframe().position();

        if(py<=px&&py<=pz){
            double sign=ac.y()>=bc.y()?1:-1;
            separate(a,b,new Vec3(0,sign*py,0));
            verticalImpulse(a,b,sign);
        }else if(px<=pz){
            double sign=ac.x()>=bc.x()?1:-1;
            separate(a,b,new Vec3(sign*px,0,0));
            horizontalImpulse(a,b,new Vec3(1,0,0),sign);
        }else{
            double sign=ac.z()>=bc.z()?1:-1;
            separate(a,b,new Vec3(0,0,sign*pz));
            horizontalImpulse(a,b,new Vec3(0,0,1),sign);
        }
    }

    private static void separate(RobloxPart a,RobloxPart b,Vec3 correction){
        boolean am=!a.anchored(),bm=!b.anchored();
        if(am&&bm){
            a.cframe(a.cframe().withPosition(a.cframe().position().add(correction.mul(.5))));
            b.cframe(b.cframe().withPosition(b.cframe().position().sub(correction.mul(.5))));
        }else if(am){
            a.cframe(a.cframe().withPosition(a.cframe().position().add(correction)));
        }else if(bm){
            b.cframe(b.cframe().withPosition(b.cframe().position().sub(correction)));
        }
    }

    private static void verticalImpulse(RobloxPart a,RobloxPart b,double sign){
        double va=a.velocity().y(),vb=b.velocity().y();
        double rel=va-vb;
        if(rel*sign>=0)return;
        double impulse=-(1+RESTITUTION)*rel;
        if(!a.anchored()&&b.anchored())a.velocity(new Vec3(a.velocity().x(),va+impulse*sign,a.velocity().z()));
        else if(a.anchored()&&!b.anchored())b.velocity(new Vec3(b.velocity().x(),vb-impulse*sign,b.velocity().z()));
        else if(!a.anchored()&&!b.anchored()){
            a.velocity(new Vec3(a.velocity().x(),va+impulse*sign*.5,a.velocity().z()));
            b.velocity(new Vec3(b.velocity().x(),vb-impulse*sign*.5,b.velocity().z()));
        }
        // Old Roblox parts lose most horizontal motion while resting.
        if(!a.anchored())a.velocity(new Vec3(a.velocity().x()*FRICTION,a.velocity().y(),a.velocity().z()*FRICTION));
        if(!b.anchored())b.velocity(new Vec3(b.velocity().x()*FRICTION,b.velocity().y(),b.velocity().z()*FRICTION));
    }

    private static void horizontalImpulse(RobloxPart a,RobloxPart b,Vec3 axis,double sign){
        double va=dot(a.velocity(),axis),vb=dot(b.velocity(),axis),rel=va-vb;
        if(rel*sign>=0)return;
        double impulse=-(1+RESTITUTION)*rel;
        if(!a.anchored())a.velocity(a.velocity().sub(axis.mul(impulse*sign*(b.anchored()?1:.5))));
        if(!b.anchored())b.velocity(b.velocity().add(axis.mul(impulse*sign*(a.anchored()?1:.5))));
    }

    private static double dot(Vec3 a,Vec3 b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}

    private static Bounds bounds(RobloxPart p){
        Vec3 s=p.size().mul(.5);
        double[][]r=p.cframe().rotation();
        double ex=Math.abs(r[0][0])*s.x()+Math.abs(r[0][1])*s.y()+Math.abs(r[0][2])*s.z();
        double ey=Math.abs(r[1][0])*s.x()+Math.abs(r[1][1])*s.y()+Math.abs(r[1][2])*s.z();
        double ez=Math.abs(r[2][0])*s.x()+Math.abs(r[2][1])*s.y()+Math.abs(r[2][2])*s.z();
        Vec3 c=p.cframe().position();
        return new Bounds(c.x()-ex,c.y()-ey,c.z()-ez,c.x()+ex,c.y()+ey,c.z()+ez);
    }

    private record PartPair(RobloxPart a,RobloxPart b){
        @Override public boolean equals(Object o){return o instanceof PartPair p && ((p.a==a&&p.b==b)||(p.a==b&&p.b==a));}
        @Override public int hashCode(){return System.identityHashCode(a)^System.identityHashCode(b);}
    }
    private record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){}
}
