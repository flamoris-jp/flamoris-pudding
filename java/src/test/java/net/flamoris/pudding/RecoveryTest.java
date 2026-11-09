package net.flamoris.pudding;

import java.sql.*;
import java.time.*;
import java.util.*;
import static net.flamoris.pudding.CoreTest.*;

/** Verify a separately restored disposable database, never a production database. */
public final class RecoveryTest {
    private RecoveryTest(){}
    private static Map<String,String> snapshot(PostgresStore.Connections db)throws SQLException {
        var result=new TreeMap<String,String>();
        try(var c=db.open();var s=c.createStatement();var r=s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'pudding_%' ORDER BY tablename")) {
            var names=new ArrayList<String>();while(r.next())names.add(r.getString(1));
            for(String name:names) {
                if(!name.matches("pudding_[a-z_]+"))throw new IllegalArgumentException("test table");
                try(var q=c.createStatement();var rows=q.executeQuery("SELECT COALESCE(jsonb_agg(v ORDER BY v::text),'[]')::text FROM (SELECT to_jsonb(t) v FROM "+name+" t) src")) {rows.next();result.put(name,Input.digest(rows.getString(1)));}
            }
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT jsonb_agg(to_jsonb(s) ORDER BY sequencename)::text FROM pg_sequences s WHERE schemaname='public' AND sequencename LIKE 'pudding_%'")){rows.next();result.put("sequences",Input.digest(rows.getString(1)));}
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        var env=System.getenv();check("true".equals(env.get("PUDDING_TEST_ALLOW_SCHEMA")));
        String original=env.get("PUDDING_TEST_JDBC_URL"),restored=env.get("PUDDING_RESTORED_JDBC_URL");
        check(original!=null&&restored!=null&&!original.equals(restored)&&restored.matches("jdbc:postgresql://[^/]+/pudding_restore_[a-f0-9]{32}"));
        var props=new Properties();props.setProperty("user",env.get("PUDDING_TEST_DB_USER"));props.setProperty("password",env.get("PUDDING_TEST_DB_PASSWORD"));
        PostgresStore.Connections first=()->DriverManager.getConnection(original,props),second=()->DriverManager.getConnection(restored,props);
        var before=snapshot(first);equal(before,snapshot(second));
        CommerceStore.grantRuntime(second,"pudding_sandbox_runtime");
        var runtimeProps=new Properties();runtimeProps.putAll(props);runtimeProps.setProperty("user","pudding_sandbox_runtime");runtimeProps.setProperty("password","synthetic-sandbox-runtime-only");
        PostgresStore.Connections runtime=()->DriverManager.getConnection(restored,runtimeProps);
        var paymentsForProbe=new SandboxPayments("synthetic-notification-key-32bytes!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        check(new net.flamoris.pudding.http.PostgresBackend(runtime,Clock.systemUTC(),new CommerceStore(runtime,Clock.systemUTC(),paymentsForProbe)).ready());
        try(var c=second.open();var s=c.createStatement();var r=s.executeQuery("SELECT b.agent_id,b.credential_id,q.command,q.response FROM pudding_bindings b JOIN pudding_api_requests q ON q.actor='agent:'||b.agent_id WHERE q.command->>'operation'='pudding.premium.begin' AND (q.response->>'ok')::boolean ORDER BY q.request_id LIMIT 1")) {
            check(r.next());String agent=r.getString(1),fingerprint=r.getString(2);var command=Input.parse(r.getString(3));var expected=Input.parse(r.getString(4));
            var payments=new SandboxPayments("synthetic-notification-key-32bytes!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var store=new CommerceStore(second,Clock.fixed(CoreTest.NOW.plusSeconds(40L*86400),ZoneOffset.UTC),payments);
            equal(Json.write(store.agentCall(agent,fingerprint,(String)command.get("operation"),Input.object(command.get("input")))),Json.write(expected));
        }
        equal(before,snapshot(second));
        System.out.println("PASS recovery: all "+(before.size()-1)+" tables and sequences match; immutable replay changes no restored row");
    }
}
