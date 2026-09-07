package com.robloxium.runtime;

public final class RobloxLighting {
    private int ambient=0xFF7F7F7F;
    private int outdoorAmbient=0xFF7F7F7F;
    private int colorShiftTop=0xFF000000;
    private int colorShiftBottom=0xFF000000;
    private int shadowColor=0xFF333333;
    private double brightness=1.0;
    private double environmentDiffuseScale=0.0;
    private double environmentSpecularScale=0.0;
    private double geographicLatitude=41.0;
    private double timeOfDay=14.0;

    public int ambient(){return ambient;} public void ambient(int value){ambient=value;}
    public int outdoorAmbient(){return outdoorAmbient;} public void outdoorAmbient(int value){outdoorAmbient=value;}
    public int colorShiftTop(){return colorShiftTop;} public void colorShiftTop(int value){colorShiftTop=value;}
    public int colorShiftBottom(){return colorShiftBottom;} public void colorShiftBottom(int value){colorShiftBottom=value;}
    public int shadowColor(){return shadowColor;} public void shadowColor(int value){shadowColor=value;}
    public double brightness(){return brightness;} public void brightness(double value){brightness=Math.max(0,Math.min(10,value));}
    public double environmentDiffuseScale(){return environmentDiffuseScale;} public void environmentDiffuseScale(double value){environmentDiffuseScale=Math.max(0,Math.min(1,value));}
    public double environmentSpecularScale(){return environmentSpecularScale;} public void environmentSpecularScale(double value){environmentSpecularScale=Math.max(0,Math.min(1,value));}
    public double geographicLatitude(){return geographicLatitude;} public void geographicLatitude(double value){geographicLatitude=value;}
    public double timeOfDay(){return timeOfDay;}
    public void timeOfDay(double value){timeOfDay=((value%24)+24)%24;}
    public void timeOfDay(String value){try{String[] parts=value.split(":");timeOfDay(Integer.parseInt(parts[0])+Integer.parseInt(parts[1])/60.0+(parts.length>2?Integer.parseInt(parts[2])/3600.0:0));}catch(Exception ignored){}}

    private static double channel(int c,int shift){return ((c>>shift)&255)/255.0;}
    public double ambientFactor(){
        double r=(channel(ambient,16)+channel(outdoorAmbient,16))*.5;
        double g=(channel(ambient,8)+channel(outdoorAmbient,8))*.5;
        double b=(channel(ambient,0)+channel(outdoorAmbient,0))*.5;
        return Math.max(0,Math.min(1,((r+g+b)/3.0)*brightness));
    }
    public double sunFactor(){return Math.max(0,Math.min(1,brightness));}
}
