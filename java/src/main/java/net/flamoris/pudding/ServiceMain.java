package net.flamoris.pudding;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import net.flamoris.pudding.http.*;

/** Runtime process: remote DB, operator-bound Agent credentials, no Hub or auto-DDL. */
public final class ServiceMain {
    private ServiceMain(){}
    public static PostgresStore.Connections connections(Map<String,String> env) {
        String url=Objects.requireNonNull(env.get("PUDDING_JDBC_URL"));
        if(!url.startsWith("jdbc:postgresql:")||url.toLowerCase(Locale.ROOT).contains("password="))throw new IllegalArgumentException("JDBC URL");
        var props=new Properties();props.setProperty("user",Objects.requireNonNull(env.get("PUDDING_DB_USER")));
        props.setProperty("password",Objects.requireNonNull(env.get("PUDDING_DB_PASSWORD")));
        props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","15");props.setProperty("ApplicationName","flamoris-pudding");
        return ()->DriverManager.getConnection(url,props);
    }
    private static String setting(Map<String,String> env,String key,String fallback){return env.getOrDefault(key,fallback);}
    private static Set<String> csv(String text){if(text.isBlank())return Set.of();return Set.copyOf(Arrays.stream(text.split(",",-1)).map(String::strip).toList());}
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("healthcheck")) {
            try {
                int port=Integer.parseInt(System.getenv().getOrDefault("PUDDING_HTTP_PORT","8080"));
                var connection=(HttpURLConnection)new URI("http://127.0.0.1:"+port+"/health/live").toURL().openConnection();connection.setConnectTimeout(2000);connection.setReadTimeout(2000);
                try{if(connection.getResponseCode()!=200)System.exit(1);}finally{connection.disconnect();}
            }catch(Exception e){System.exit(1);}return;
        }
        if(args.length!=0){Main.main(args);return;}
        try {
            var env=System.getenv();var clock=Clock.systemUTC();
            String inline=env.get("PUDDING_AGENT_CREDENTIALS");byte[] credentials;
            if(inline!=null){if(env.containsKey("PUDDING_CREDENTIALS_FILE"))throw new IllegalArgumentException("choose one credential source");credentials=inline.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
            else {
                Path path=Path.of(Objects.requireNonNull(env.get("PUDDING_CREDENTIALS_FILE")));
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)throw new IllegalArgumentException("credential file");credentials=Files.readAllBytes(path);
            }
            int port=Integer.parseInt(setting(env,"PUDDING_HTTP_PORT","8080"));
            var hosts=new HashSet<>(csv(Objects.requireNonNull(env.get("PUDDING_ALLOWED_HOSTS"))));hosts.add("127.0.0.1:"+port);
            var endpoint=new McpEndpoint(new AgentCredentials(credentials),new PostgresBackend(connections(env),clock),hosts,
                csv(setting(env,"PUDDING_ALLOWED_ORIGINS","")),clock,Duration.ofMinutes(30));
            var server=new SocketServer(InetAddress.getByName(setting(env,"PUDDING_HTTP_BIND","127.0.0.1")),port,16,15000,endpoint);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));server.start();
            System.err.println("Pudding HTTP/MCP started; configured secrets are not logged.");
            new CountDownLatch(1).await();
        }catch(Exception e){System.err.println("Pudding startup failed. Check protected configuration and JDBC runtime.");System.exit(1);}
    }
}
