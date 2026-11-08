package net.flamoris.pudding;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import static net.flamoris.pudding.Core.*;

public final class Main {
    private Main(){}
    public static Map<String,Object> summary(Response r){
        var out=new LinkedHashMap<String,Object>();out.put("ok",r.ok());out.put("error",r.error());out.put("violations",r.policy().obligations().size());out.put("policy_status",r.policy().status());
        if(r.session()!=null){var s=r.session();out.put("revision",s.revision());out.put("food_stage",s.stage().name().toLowerCase(Locale.ROOT));out.put("session_stage",s.serving().name().toLowerCase(Locale.ROOT));out.put("peel_q",s.peelQ());out.put("lid",s.lid().name().toLowerCase(Locale.ROOT));out.put("bites",s.bites());
            out.put("body",List.of(s.body().cup(),s.body().spoon(),s.body().consumed(),s.body().discarded()));out.put("sauce",List.of(s.sauce().cup(),s.sauce().spoon(),s.sauce().consumed(),s.sauce().discarded()));
            if(s.lastTaste()!=null)out.put("features",s.lastTaste().features());}
        return out;
    }
    public static List<Map<String,Object>> demo(Function<Command,Response> execute){
        var rows=new ArrayList<Map<String,Object>>();Response r=execute.apply(Command.create(UUID.randomUUID(),"custard-v0"));
        if(!r.ok())throw new IllegalStateException("unresolved synthetic serving");rows.add(summary(r));
        Op[] ops={Op.PEEL,Op.PEEL,Op.LID_DISPOSE,Op.SCOOP,Op.TASTE,Op.FINISH,Op.CLEANUP};
        for(int n=0;n<ops.length;n++){
            var s=r.session();r=execute.apply(Command.action(ops[n],s.id(),s.revision(),n==0?250:n==1?750:0,n==3?8000:0,n==3?2000:0));
            if(!r.ok())throw new IllegalStateException("demo operation rejected");rows.add(summary(r));
        }return rows;
    }
    public static void main(String[] args){
        try{
            if(args.length!=1)throw new IllegalArgumentException("command");
            Object report;
            switch(args[0]){
                case "demo" -> {var store=new MemoryStore(Clock.systemUTC(),3,null);report=Map.of("fixture_only",true,"steps",demo(store::execute));}
                case "migrate-postgres","grant-runtime-postgres","demo-postgres" -> {
                    var env=System.getenv();String url=Objects.requireNonNull(env.get("PUDDING_JDBC_URL"));
                    if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("JDBC URL");
                    var properties=new Properties();properties.setProperty("user",Objects.requireNonNull(env.get("PUDDING_DB_USER")));
                    properties.setProperty("password",Objects.requireNonNull(env.get("PUDDING_DB_PASSWORD")));
                    properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","15");
                    PostgresStore.Connections connections=()->DriverManager.getConnection(url,properties);
                    if(args[0].equals("migrate-postgres")){PostgresStore.migrate(connections);report=Map.of("schema_version",1);}
                    else if(args[0].equals("grant-runtime-postgres")){PostgresStore.grantRuntime(connections,env.get("PUDDING_RUNTIME_ROLE"));report=Map.of("runtime_grants",true);}
                    else {var store=new PostgresStore(connections,env.getOrDefault("PUDDING_SYNTHETIC_AGENT","synthetic-agent"),Clock.systemUTC(),null);
                        report=Map.of("fixture_only",true,"steps",demo(c->{try{return store.execute(c);}catch(SQLException e){throw new IllegalStateException("DB transaction failed");}}));}
                }
                default -> report=SandboxMain.run(args[0],System.getenv());
            }
            System.out.write((Json.write(report)+"\n").getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){System.err.println("Pudding research command failed. Check command, database configuration and serving state.");System.exit(1);}
    }
}
