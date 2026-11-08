package net.flamoris.pudding;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static net.flamoris.pudding.CoreTest.*;

/** Verify the real process boundary, including protected credential file rules. */
public final class ServiceConfigTest {
    private static final String TOKEN="C".repeat(43);
    private static int port()throws IOException{try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    private static Process launch(Path file,int port)throws IOException {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"net.flamoris.pudding.ServiceMain");
        var env=process.environment();env.remove("PUDDING_AGENT_CREDENTIALS");
        env.put("PUDDING_CREDENTIALS_FILE",file.toString());env.put("PUDDING_JDBC_URL","jdbc:postgresql://127.0.0.1:1/unused");
        env.put("PUDDING_DB_USER","fixture");env.put("PUDDING_DB_PASSWORD","unused-fixture");env.put("PUDDING_ALLOWED_HOSTS","localhost");
        env.put("PUDDING_HTTP_BIND","127.0.0.1");env.put("PUDDING_HTTP_PORT",Integer.toString(port));return process.start();
    }
    private static void denied(Path file)throws Exception {
        var p=launch(file,port());
        try{check(p.waitFor(5,TimeUnit.SECONDS));equal(p.exitValue(),1);String log=new String(p.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);check(!log.contains(TOKEN));}
        finally{p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("pudding-config-test-");
        Path file=root.resolve("agents.credentials.json");
        Files.writeString(file,Json.write(List.of(Map.of("agent_id","fixture","token",TOKEN))));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        try {
            test("protected credential source starts a real service",()->{
                Process p=null;
                try {
                    int port=port();p=launch(file,port);boolean live=false;long end=System.nanoTime()+5_000_000_000L;
                    while(System.nanoTime()<end) {
                        try(var socket=new Socket(InetAddress.getLoopbackAddress(),port)) {
                            socket.setSoTimeout(1000);socket.getOutputStream().write(("GET /health/live HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            live=new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200");if(live)break;
                        }catch(IOException e){Thread.sleep(25);}
                    }
                    check(live);
                }catch(Exception e){throw new AssertionError(e);}
                finally{if(p!=null){p.destroy();try{if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
            });
            test("public and symlink credential sources are rejected",()->{
                try {
                    Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));denied(file);
                    Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Path link=root.resolve("link.json");Files.createSymbolicLink(link,file);
                    try{denied(link);}finally{Files.delete(link);}
                }catch(Exception e){throw new AssertionError(e);}
            });
            test("writable credential parent is rejected",()->{
                try{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxrwxrwx"));denied(file);}
                catch(Exception e){throw new AssertionError(e);}
                finally{try{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}catch(IOException e){throw new AssertionError(e);}}
            });
            System.out.println("Service configuration scenarios: "+scenarios);
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(root);}
    }
}
