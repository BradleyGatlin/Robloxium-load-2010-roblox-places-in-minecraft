package com.robloxium.runtime;

/** Creates script-created instances without making the XML loader depend on the scripting API. */
final class RobloxInstanceFactory {
    private RobloxInstanceFactory() {}
    static RobloxInstance create(String cls,String name){
        return switch(cls){
            case "Part","SpawnLocation","WedgePart","CornerWedgePart","TrussPart","Seat","VehicleSeat","Platform" -> new RobloxPart(cls,name);
            case "Humanoid" -> new RobloxHumanoid(name);
            case "Player" -> new RobloxPlayer(name);
            case "Model" -> new RobloxModel(name);
            case "Script","LocalScript" -> new RobloxScript(cls,name);
            case "Weld","WeldConstraint","Motor","Glue","Snap","RotateP","RotateV","Motor6D" -> new RobloxWeld(cls,name);
            case "Sound" -> new RobloxSound(name);
            case "Camera" -> new RobloxCamera(name);
            case "Decal","Texture" -> new RobloxDecal(name);
            case "SpecialMesh","BlockMesh","CylinderMesh","FileMesh","BevelMesh" -> new RobloxSpecialMesh(name);
            case "IntValue","BoolValue","NumberValue","StringValue","Vector3Value","CFrameValue","Color3Value","BrickColorValue","ObjectValue" -> new RobloxValue(cls,name);
            case "ScreenGui","BillboardGui","Frame","TextLabel","TextButton","TextBox","ImageLabel","ImageButton","ScrollingFrame" -> new RobloxGuiObject(cls,name);
            default -> new RobloxInstance(cls,name);
        };
    }
}
