package com.robloxium.client;

import net.minecraft.network.chat.Component;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.TimeUnit;

/** Extracts and optionally launches the supplied normal 2010 Roblox client. */
public final class Roblox2010Sidecar {
    private static final Path CONFIG_DIR=Path.of("config","robloxium");
    private static final Path CONFIG_FILE=CONFIG_DIR.resolve("roblox2010.properties");
    private static final Path CLIENT_DIR=Path.of("robloxium","client2010");
    private static final String ROOT="/robloxium/client2010/";
    private static volatile Path exe;
    private Roblox2010Sidecar(){}
    public static synchronized Result start(){if(!windows())return Result.error("2010 sidecar is Windows-only.");try{Properties p=config();Path root=extract();String configured=p.getProperty("executable","RobloxApp.exe");if(configured.equalsIgnoreCase("NostroApp.exe")){configured="RobloxApp.exe";p.setProperty("executable",configured);store(p);}Path e=resolve(configured,root);if(e==null)return Result.error("RobloxApp.exe not found in extracted 2010 client: "+root);if(running())return Result.ok("2010 client already running: "+e);String cmd="Start-Process -FilePath '"+e.toString().replace("'","''")+"' -WorkingDirectory '"+e.getParent().toString().replace("'","''")+"' -WindowStyle "+(Boolean.parseBoolean(p.getProperty("hidden","false"))?"Hidden":"Normal");new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",cmd).redirectErrorStream(true).start().waitFor(5,TimeUnit.SECONDS);exe=e;Thread.sleep(150);return running()?Result.ok("Started 2010 Roblox client: "+e):Result.error("RobloxApp.exe did not appear after launch.");}catch(Exception x){return Result.error("Failed to start 2010 client: "+x.getMessage());}}
    public static synchronized Result stop(){if(!windows())return Result.error("2010 sidecar is Windows-only.");try{new ProcessBuilder("taskkill","/IM","RobloxApp.exe","/T","/F").redirectErrorStream(true).start().waitFor(5,TimeUnit.SECONDS);exe=null;return Result.ok("Stopped RobloxApp.exe.");}catch(Exception x){return Result.error("Failed to stop 2010 client: "+x.getMessage());}}
    public static boolean running(){return process("RobloxApp.exe");}
    public static String status(){return windows()?(running()?"running":"stopped"):"Windows-only sidecar";}
    public static boolean autoStartEnabled(){try{return Boolean.parseBoolean(config().getProperty("autoStart","true"));}catch(Exception e){return true;}}
    public static Path configFile(){return CONFIG_FILE.toAbsolutePath().normalize();}
    private static Properties config()throws IOException{Files.createDirectories(CONFIG_DIR);Properties p=new Properties();if(Files.isRegularFile(CONFIG_FILE)){try(var r=Files.newBufferedReader(CONFIG_FILE,StandardCharsets.UTF_8)){p.load(r);}}else{p.setProperty("executable","RobloxApp.exe");p.setProperty("hidden","false");p.setProperty("autoStart","true");store(p);}return p;}
    private static void store(Properties p)throws IOException{Files.createDirectories(CONFIG_DIR);try(var w=Files.newBufferedWriter(CONFIG_FILE,StandardCharsets.UTF_8)){p.store(w,"Robloxium 2010 sidecar");}}
    private static Path extract()throws IOException{Path out=CLIENT_DIR.toAbsolutePath().normalize();Files.createDirectories(out);try(InputStream m=Roblox2010Sidecar.class.getResourceAsStream(ROOT+"files.list")){if(m==null)throw new IOException("bundled client manifest missing");for(String rel:new String(m.readAllBytes(),StandardCharsets.UTF_8).lines().map(String::trim).filter(s->!s.isBlank()).toList()){Path t=out.resolve(rel).normalize();if(!t.startsWith(out))throw new IOException("invalid resource path: "+rel);String res=ROOT+rel.replace('\\','/');try(InputStream in=Roblox2010Sidecar.class.getResourceAsStream(res)){if(in==null)throw new IOException("missing bundled file: "+rel);Files.createDirectories(t.getParent());byte[] data=in.readAllBytes();boolean replace=!Files.isRegularFile(t)||Files.size(t)!=data.length;if(replace)Files.write(t,data);}}}return out;}
    private static Path resolve(String n,Path root){Path p=Path.of(n);List<Path>c=new ArrayList<>();if(p.isAbsolute())c.add(p);else{c.add(root.resolve(p));c.add(Path.of(n));}for(Path x:c){Path q=x.toAbsolutePath().normalize();if(Files.isRegularFile(q))return q;}return null;}
    private static boolean process(String name){try{Process p=new ProcessBuilder("tasklist","/FI","IMAGENAME eq "+name).redirectErrorStream(true).start();String s=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);p.waitFor(2,TimeUnit.SECONDS);return s.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));}catch(Exception e){return false;}}
    private static boolean windows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    public record Result(boolean success,String message){public Component component(){return Component.literal(message);}static Result ok(String m){return new Result(true,m);}static Result error(String m){return new Result(false,m);}}
}
