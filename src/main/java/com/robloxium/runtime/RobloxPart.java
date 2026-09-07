package com.robloxium.runtime;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;

/** A Part stores exactly what the guest Roblox place says, in Roblox units. */
public final class RobloxPart extends RobloxInstance {
    private final RobloxTouchEvent touched=new RobloxTouchEvent();
    private CFrame cframe=CFrame.identity();
    private Vec3 size=new Vec3(4,1,2);
    private Vec3 meshScale=new Vec3(1,1,1);
    private Vec3 meshOffset=Vec3.ZERO;
    private Vec3 meshVertexColor=new Vec3(1,1,1);
    private int meshType;
    private String meshId="";
    private String textureId="";
    private int brickColor=194, material=256;
    private double reflectance;
    private boolean anchored=false, canCollide=true;
    private double transparency;
    private double friction=0.3, elasticity=0.5;
    private boolean locked;
    private int shape;
    // Legacy Roblox 2010 rigid-body state, stored in studs and studs/second.
    private Vec3 velocity=Vec3.ZERO;
    private Vec3 rotVelocity=Vec3.ZERO;
    private int topSurface,bottomSurface,frontSurface,backSurface,leftSurface,rightSurface;

    public RobloxPart(String className,String name){super(className,name);}
    public RobloxPart(String name){this("Part",name);}
    public CFrame cframe(){return cframe;} public void cframe(CFrame v){cframe=v==null?CFrame.identity():v;}
    public Vec3 size(){return size;} public void size(Vec3 v){size=v==null?Vec3.ZERO:v;}
    public Vec3 meshScale(){return meshScale;} public void meshScale(Vec3 v){meshScale=v==null?new Vec3(1,1,1):v;}
    public Vec3 meshOffset(){return meshOffset;} public void meshOffset(Vec3 v){meshOffset=v==null?Vec3.ZERO:v;}
    public Vec3 meshVertexColor(){return meshVertexColor;} public void meshVertexColor(Vec3 v){meshVertexColor=v==null?new Vec3(1,1,1):v;}
    public int meshType(){return meshType;} public void meshType(int v){meshType=v;}
    public String meshId(){return meshId;} public void meshId(String v){meshId=v==null?"":v;}
    public String textureId(){return textureId;} public void textureId(String v){textureId=v==null?"":v;}
    public int brickColor(){return brickColor;} public void brickColor(int v){brickColor=v;}
    public double reflectance(){return reflectance;} public void reflectance(double v){reflectance=Math.max(0,Math.min(1,v));}
    public int material(){return material;} public void material(int v){material=v;}
    public boolean anchored(){return anchored;} public void anchored(boolean v){anchored=v;}
    public boolean canCollide(){return canCollide;} public void canCollide(boolean v){canCollide=v;}
    public Vec3 velocity(){return velocity;} public void velocity(Vec3 v){velocity=v==null?Vec3.ZERO:v;}
    public Vec3 rotVelocity(){return rotVelocity;} public void rotVelocity(Vec3 v){rotVelocity=v==null?Vec3.ZERO:v;}
    public RobloxTouchEvent touched(){return touched;} public RobloxTouchEvent getTouched(){return touched;}
    void fireTouched(Object hit){touched.fire(hit);}
    public double transparency(){return transparency;} public void transparency(double v){transparency=Math.max(0,Math.min(1,v));}
    public double friction(){return friction;} public void friction(double v){friction=Math.max(0,v);}
    public double elasticity(){return elasticity;} public void elasticity(double v){elasticity=Math.max(0,v);}
    public boolean locked(){return locked;} public void locked(boolean v){locked=v;}
    public int shape(){return shape;} public void shape(int v){shape=v;}
    public int topSurface(){return topSurface;} public int bottomSurface(){return bottomSurface;} public int frontSurface(){return frontSurface;} public int backSurface(){return backSurface;} public int leftSurface(){return leftSurface;} public int rightSurface(){return rightSurface;}
    public void surfaces(int t,int b,int f,int ba,int l,int r){topSurface=t;bottomSurface=b;frontSurface=f;backSurface=ba;leftSurface=l;rightSurface=r;}
    public int colorWithAlpha(){int a=(int)Math.round((1-transparency)*255);int rgb=brickColorArgb(brickColor);return (a<<24)|rgb;}
    public static int colorWithAlphaFor(int id){return 0xFF000000|brickColorArgb(id);}
    public static int brickColorArgb(int id){
        // Roblox's legacy BrickColor table. These are the IDs used by old
        // 2006-2010 places; do not substitute Minecraft colors.
        return switch(id){
            case 1->0xF2F3F3;   // White
            case 2->0xA1A5A2;   // Grey
            case 3->0xF9E999;   // Light yellow
            case 5->0xD7C59A;   // Brick yellow
            case 6->0xC2DAB8;   // Light green (Mint)
            case 9->0xE8BAC8;   // Light reddish violet
            case 11->0x80BBDB;  // Pastel Blue
            case 12->0xCB8442;  // Light orange brown
            case 18->0xCC8E69;  // Nougat
            case 21->0xC4281C;  // Bright red
            case 22->0xC470A0;  // Med. reddish violet
            case 23->0x0D69AC;  // Bright blue
            case 24->0xF5CD30;  // Bright yellow
            case 25->0x624732;  // Earth orange
            case 26->0x1B2A35;  // Black
            case 27->0x6D6E6C;  // Dark grey
            case 28->0x287F47;  // Dark green
            case 29->0xA1C48C;  // Medium green
            case 36->0xF3CF9B;  // Lig. Yellowich orange
            case 37->0x4B974B;  // Bright green
            case 38->0xA05F35;  // Dark orange
            case 39->0xC1CADE;  // Light bluish violet
            case 40->0xECECEC;  // Transparent
            case 41->0xCD544B;  // Tr. Red
            case 42->0xC1DFF0;  // Tr. Lg blue
            case 43->0x7BB6E8;  // Tr. Blue
            case 44->0xF7F18D;  // Tr. Yellow
            case 45->0xB4D2E4;  // Light blue
            case 47->0xD9856C;  // Tr. Flu. Reddish orange
            case 48->0x84B68D;  // Tr. Green
            case 49->0xF8F184;  // Tr. Flu. Green
            case 50->0xECE8DE;  // Phosph. White
            case 100->0xEEC4B6; // Light red
            case 101->0xDA867A; // Medium red
            case 102->0x6E99CA; // Medium blue
            case 103->0xC7C1B7; // Light grey
            case 104->0x6B327C; // Bright violet
            case 105->0xE29B40; // Br. yellowish orange
            case 106->0xDA8541; // Bright orange
            case 107->0x008F9C; // Bright bluish green
            case 108->0x685C43; // Earth yellow
            case 110->0x435493; // Bright bluish violet
            case 111->0xBFB7B1; // Tr. Brown
            case 112->0x6874AC; // Medium bluish violet
            case 113->0xE5ADC8; // Tr. Medi. reddish violet
            case 115->0xC7D23C; // Med. yellowish green
            case 116->0x55A5AF; // Med. bluish green
            case 118->0xB7D7D5; // Light bluish green
            case 119->0xA4BD47; // Br. yellowish green
            case 120->0xD9E4A7; // Lig. yellowish green
            case 121->0xE7AC58; // Med. yellowish orange
            case 123->0xD36F4C; // Br. reddish orange
            case 124->0x923978; // Bright reddish violet
            case 125->0xEAB892; // Light orange
            case 127->0xDCBC81; // Gold
            case 128->0xAE7A59; // Dark nougat
            case 131->0x9CA3A8; // Silver
            case 135->0x74869D; // Sand blue
            case 136->0x877C90; // Sand violet
            case 137->0xE09864; // Medium orange
            case 138->0x958A73; // Sand yellow
            case 140->0x203A56; // Earth blue
            case 141->0x27462D; // Earth green
            case 145->0x7988A1; // Sand blue metallic
            case 146->0x958EA3; // Sand violet metallic
            case 147->0x938767; // Sand yellow metallic
            case 148->0x575857; // Dark grey metallic
            case 149->0x161D32; // Black metallic
            case 150->0xABADAC; // Light grey metallic
            case 151->0x789082; // Sand green
            case 153->0x957977; // Sand red
            case 154->0x7B2E2F; // Dark red
            case 168->0x756C62; // Gun metallic
            case 176->0x97695B; // Red flip/flop
            case 178->0xB48455; // Yellow flip/flop
            case 179->0x898788; // Silver flip/flop
            case 180->0xD7A94B; // Curry
            case 190->0xF9D62E; // Fire Yellow
            case 191->0xE8AB2D; // Flame yellowish orange
            case 192->0x694028; // Reddish brown
            case 193->0xCF6024; // Flame reddish orange
            case 194->0xA3A2A5; // Medium stone grey
            case 195->0x4667A4; // Royal blue
            case 196->0x23478B; // Dark Royal blue
            case 198->0x8E4285; // Bright reddish lilac
            case 199->0x635F62; // Dark stone grey
            case 200->0x828A5D; // Lemon metallic
            case 208->0xE5E4DF; // Light stone grey
            case 209->0xB08E44; // Dark Curry
            case 210->0x709578; // Faded green
            case 211->0x79B5B5; // Turquoise
            case 212->0x9FC3E9; // Light Royal blue
            case 213->0x6C81B7; // Medium Royal blue
            case 216->0x904C2A; // Rust
            case 217->0x7C5C46; // Brown
            default->0xA3A2A5;  // Roblox fallback: Medium stone grey
        };
    }

    /** Legacy material names used by the 2009-2010 renderer. */
    public static String materialName(int id){
        return switch(id){
            case 256->"Plastic";
            case 272->"SmoothPlastic";
            case 288->"Neon";
            case 512->"Wood";
            case 528->"WoodPlanks";
            case 800->"Slate";
            case 816->"Concrete";
            case 1040->"CorrodedMetal";
            case 1056->"DiamondPlate";
            case 1072->"Foil";
            case 1280->"Grass";
            case 1536->"Ice";
            default->"Plastic";
        };
    }
}
