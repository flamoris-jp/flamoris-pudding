package net.flamoris.pudding;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import net.flamoris.pudding.http.*;
import static net.flamoris.pudding.CoreTest.*;
import static net.flamoris.pudding.Input.*;

/** Real PostgreSQL acceptance, using only separately named synthetic identities. */
public final class CommerceTest {
    static final MutableClock clock=new MutableClock();
    static final SandboxPayments payments=new SandboxPayments("synthetic-notification-key-32bytes!".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    static PostgresStore.Connections db;
    static CommerceStore store;
    static int count;
    static final String FP="a".repeat(64),OTHER="b".repeat(64);
    static final CommerceStore.Human HUMAN=new CommerceStore.Human("sandbox-tenant","sandbox-human");
    @FunctionalInterface interface Scenario{void run()throws Exception;}
    static void scenario(String name,Scenario test)throws Exception{clock.now=clock.now.isAfter(NOW)?clock.now.plusSeconds(1):NOW;test.run();count++;System.out.println("PASS sandbox "+name);}
    static Map<String,Object> map(Object... pairs){var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static Map<String,Object> human(String op,Map<String,Object> args)throws Exception{return store.humanCall(HUMAN,op,args);}
    static Map<String,Object> change(String op,Map<String,Object> args)throws Exception{var m=new LinkedHashMap<>(args);m.put("request_id",UUID.randomUUID().toString());return human(op,m);}
    static Map<String,Object> nested(Map<String,Object> result,String key){check(Boolean.TRUE.equals(result.get("ok")));return object(result.get(key));}
    static String freshAgent()throws Exception{String agent="sandbox-agent-"+UUID.randomUUID();store.provision(HUMAN,agent,"fixture:"+agent,digest(agent));return agent;}
    static Map<String,Object> agent(String agent,String op,Map<String,Object> args)throws Exception{return store.agentCall(agent,digest(agent),op,args);}
    static Map<String,Object> agentChange(String agent,String op,Map<String,Object> args)throws Exception{var m=new LinkedHashMap<>(args);m.put("request_id",UUID.randomUUID().toString());return agent(agent,op,m);}
    static Map<String,Object> purchased()throws Exception {
        var order=nested(change("orders.create",map("product_id","sandbox-pudding","product_version","v1")),"order");
        var notification=nested(change("payments.simulate",map("order_id",order.get("id"),"expected_revision",0,"status","paid")),"notification");
        var paid=nested(change("payments.reconcile",map("envelope",notification)),"order");
        var assets=(List<?>)human("assets.list",Map.of()).get("assets");
        return assets.stream().map(Input::object).filter(a->paid.get("asset_id").equals(a.get("id"))).findFirst().orElseThrow();
    }
    static Map<String,Object> assigned(String agent)throws Exception {
        var a=purchased();return nested(change("assets.assign",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"agent_id",agent)),"asset");
    }
    static Map<String,Object> approve(Map<String,Object> a)throws Exception {
        var preview=human("approval.preview",map("asset_id",a.get("id")));
        return nested(change("approval.confirm",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"intent_digest",preview.get("intent_digest"))),"approval");
    }
    static Map<String,Object> begin(String agent,Map<String,Object> approval)throws Exception {
        var a=object(approval.get("asset"));return agentChange(agent,"pudding.premium.begin",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"grant_id",approval.get("grant_id")));
    }
    static Map<String,Object> action(String agent,String operation,Map<String,Object> state,Object... extra)throws Exception {
        var args=map("session_id",state.get("id"),"expected_revision",state.get("revision"));args.putAll(map(extra));return agentChange(agent,operation,args);
    }
    static Map<String,Object> opened(String agent)throws Exception {
        var state=nested(begin(agent,approve(assigned(agent))),"session");
        state=nested(action(agent,"pudding.lid.peel",state,"amount_q",1000),"session");return nested(action(agent,"pudding.lid.dispose",state),"session");
    }
    static void sql(String query)throws SQLException{try(var c=db.open();var s=c.createStatement()){s.execute(query);}}
    static long rows(String table)throws SQLException {if(!Set.of("pudding_assets","pudding_candidates","pudding_api_requests").contains(table))throw new IllegalArgumentException();try(var c=db.open();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}}
    public static void main(String[] args)throws Exception {
        var env=System.getenv();check("true".equals(env.get("PUDDING_TEST_ALLOW_SCHEMA")));
        var props=new Properties();props.setProperty("user",env.get("PUDDING_TEST_DB_USER"));props.setProperty("password",env.get("PUDDING_TEST_DB_PASSWORD"));
        props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","15");db=()->DriverManager.getConnection(env.get("PUDDING_TEST_JDBC_URL"),props);store=new CommerceStore(db,clock,payments);
        scenario("explicit upgrade rejects a second migration and keeps old research records",()->{
            CommerceStore.migrate(db);try{CommerceStore.migrate(db);throw new AssertionError("adopted schema");}catch(SQLException expected){}
            try(var c=db.open();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pudding_sessions")){r.next();check(r.getLong(1)>0);}
        });
        scenario("immutable operator catalogue and stable identity provisioning",()->{
            store.publishProduct(map("id","sandbox-pudding","version","v1","preset_id","custard-v0","price_version","fixture-price-v1","currency","JPY","amount_minor",1100,
                "revenue_policy","sandbox-review-v1","from","2020-01-01T00:00:00Z","until",null,"tax_rule",map("id","fixture-tax","version","v1","numerator",1,"denominator",10,"category","unapproved-fixture","jurisdiction","JP","from","2020-01-01T00:00:00Z","until",null)));
            store.provision(HUMAN,"sandbox-fixed-agent","fixture:fixed",FP);
            try{store.provision(new CommerceStore.Human("other-tenant","other-human"),"sandbox-fixed-agent","fixture:other",OTHER);throw new AssertionError("stole identity");}catch(RuntimeException expected){}
        });
        scenario("signed mock payment is issued once and stale success cannot revive refund",()->{
            var order=nested(change("orders.create",map("product_id","sandbox-pudding","product_version","v1")),"order");
            var success=nested(change("payments.simulate",map("order_id",order.get("id"),"expected_revision",0,"status","paid")),"notification");
            var paid=nested(change("payments.reconcile",map("envelope",success)),"order");long before=rows("pudding_assets");
            nested(change("payments.reconcile",map("envelope",success)),"order");equal(rows("pudding_assets"),before);
            var refund=nested(change("payments.simulate",map("order_id",order.get("id"),"expected_revision",1,"status","refunded")),"notification");
            var refunded=nested(change("payments.reconcile",map("envelope",refund)),"order");equal(refunded.get("status"),"refunded");
            equal(nested(change("payments.reconcile",map("envelope",success)),"order").get("status"),"refunded");equal(rows("pudding_assets"),before);
            var forged=new LinkedHashMap<>(success);forged.put("signature","0".repeat(64));
            try{change("payments.reconcile",map("envelope",forged));throw new AssertionError("forged signature");}catch(IllegalArgumentException expected){}
            var stale=payments.issue(UUID.fromString((String)order.get("id")),1,NOW.minusSeconds(301));
            try{payments.verify(stale,NOW);throw new AssertionError("stale notification");}catch(IllegalArgumentException expected){}
        });
        scenario("out of order refund before success never creates an entitlement",()->{
            var o=nested(change("orders.create",map("product_id","sandbox-pudding","product_version","v1")),"order");
            var notice=nested(change("payments.simulate",map("order_id",o.get("id"),"expected_revision",0,"status","paid")),"notification");
            change("payments.simulate",map("order_id",o.get("id"),"expected_revision",1,"status","refunded"));long before=rows("pudding_assets");
            equal(nested(change("payments.reconcile",map("envelope",notice)),"order").get("status"),"refunded");equal(rows("pudding_assets"),before);
        });
        scenario("tenant and same-human Agent ownership remain separate",()->{
            String first=freshAgent(),second=freshAgent();var a=assigned(first);
            equal(agent(second,"pudding.rewards.status",map("asset_id",a.get("id"))).get("error"),"NOT_FOUND");
            equal(agentChange(second,"pudding.premium.begin",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"grant_id",UUID.randomUUID().toString())).get("error"),"NOT_FOUND");
            equal(store.humanCall(new CommerceStore.Human("sandbox-tenant","intruder"),"assets.list",Map.of()).get("error"),"NOT_FOUND");
        });
        scenario("one-item human intent and fake approval cannot authorize consumption",()->{
            String agent=freshAgent();var a=assigned(agent);
            equal(agentChange(agent,"pudding.premium.begin",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"grant_id",UUID.randomUUID().toString())).get("error"),"APPROVAL_REQUIRED");
            try{change("approval.confirm",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"intent_digest","0".repeat(64),"approved",true));throw new AssertionError("approved argument");}catch(IllegalArgumentException expected){}
            equal(change("approval.confirm",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"intent_digest","0".repeat(64))).get("error"),"INTENT_CHANGED");
            var approval=approve(a);var r=begin(agent,approval);check(Boolean.TRUE.equals(r.get("ok")));equal(object(r.get("asset")).get("id"),a.get("id"));
        });
        scenario("restart replay is a complete immutable response and payload reuse is refused",()->{
            String agent=freshAgent();var approval=approve(assigned(agent));var a=object(approval.get("asset"));
            var input=map("request_id",UUID.randomUUID().toString(),"asset_id",a.get("id"),"expected_revision",a.get("revision"),"grant_id",approval.get("grant_id"));
            var r=agent(agent,"pudding.premium.begin",input);var state=nested(r,"session");action(agent,"pudding.lid.peel",state,"amount_q",250);
            var restarted=new CommerceStore(db,clock,payments);equal(Json.write(restarted.agentCall(agent,digest(agent),"pudding.premium.begin",input)),Json.write(r));
            input.put("expected_revision",99);equal(agent(agent,"pudding.premium.begin",input).get("error"),"REQUEST_ID_REUSED");
        });
        scenario("parallel begin creates one lifetime experience",()->{
            String agent=freshAgent();var approval=approve(assigned(agent));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
            try {
                Callable<Map<String,Object>> call=()->{start.await();return begin(agent,approval);};var x=pool.submit(call);var y=pool.submit(call);start.countDown();
                var first=x.get(20,TimeUnit.SECONDS);var second=y.get(20,TimeUnit.SECONDS);
                equal(List.of(first,second).stream().filter(r->Boolean.TRUE.equals(r.get("ok"))).count(),1L);
                equal(List.of(first,second).stream().filter(r->!Boolean.TRUE.equals(r.get("ok"))).findFirst().orElseThrow().get("error"),"REVISION_CONFLICT");
            }finally{pool.shutdownNow();}
        });
        scenario("partial eating pauses with spoon intact and resumes the same session",()->{
            String agent=freshAgent();var state=opened(agent);state=nested(action(agent,"pudding.spoon.scoop",state,"body_mg",8000,"sauce_mg",2000),"session");
            var reward=nested(agent(agent,"pudding.rewards.status",map("asset_id",assetId(state))),"reward");
            var paused=nested(agentChange(agent,"pudding.premium.pause",map("asset_id",reward.get("id"),"expected_revision",reward.get("revision"))),"asset");
            equal(action(agent,"pudding.spoon.taste",state).get("error"),"APPROVAL_REQUIRED");
            var humanAsset=findAsset((String)paused.get("id"));var resumed=nested(begin(agent,approve(humanAsset)),"session");equal(resumed.get("id"),state.get("id"));equal(resumed.get("components"),state.get("components"));
            var tasted=nested(action(agent,"pudding.spoon.taste",resumed),"session");equal(integer(tasted,"bites",0,100),1);check(object(tasted.get("affect_model")).get("satisfaction_q")!=null);
        });
        scenario("direct research route cannot eat or discard a purchased session",()->{
            String agent=freshAgent();var state=opened(agent);
            equal(action(agent,"pudding.sessions.finish",state).get("error"),"PREMIUM_PAUSE_REQUIRED");
            var direct=new PostgresStore(db,agent,clock,null).execute(Core.Command.action(Core.Op.SCOOP,UUID.fromString((String)state.get("id")),integer(state,"revision",0,100),0,1,0));equal(direct.error(),"PREMIUM_ROUTE_REQUIRED");
            var asset=findAsset(assetId(state));equal(agentChange(agent,"pudding.inventory.discard",map("asset_id",asset.get("id"),"expected_revision",asset.get("revision"))).get("error"),"HUMAN_DISCARD_REQUIRED");
        });
        scenario("revocation and binding epoch changes invalidate old grants and keys",()->{
            String agent=freshAgent();var state=opened(agent);var a=findAsset(assetId(state));
            change("approval.revoke",map("asset_id",a.get("id"),"expected_revision",a.get("revision")));equal(action(agent,"pudding.spoon.scoop",state,"body_mg",1,"sauce_mg",0).get("error"),"APPROVAL_REQUIRED");
            store.provision(HUMAN,agent,"fixture:"+agent,OTHER);
            equal(agent(agent,"pudding.sessions.get",map("session_id",state.get("id"))).get("error"),"AGENT_CREDENTIAL_REVOKED");
        });
        scenario("failed audit rolls back the mock payment and replay record",()->{
            var o=nested(change("orders.create",map("product_id","sandbox-pudding","product_version","v1")),"order");var args2=map("request_id",UUID.randomUUID().toString(),"order_id",o.get("id"),"expected_revision",0,"status","paid");long before=rows("pudding_api_requests");
            sql("CREATE FUNCTION pudding_sandbox_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic audit failure'; END; $$");sql("CREATE TRIGGER pudding_sandbox_reject BEFORE INSERT ON pudding_commerce_events FOR EACH ROW EXECUTE FUNCTION pudding_sandbox_reject()");
            try{try{human("payments.simulate",args2);throw new AssertionError("audit failed open");}catch(SQLException expected){}equal(rows("pudding_api_requests"),before);}
            finally{sql("DROP TRIGGER pudding_sandbox_reject ON pudding_commerce_events");sql("DROP FUNCTION pudding_sandbox_reject()");}
            nested(human("payments.simulate",args2),"notification");
        });
        scenario("wall clock rollback fails closed",()->{
            String agent=freshAgent();var a=assigned(agent);clock.now=NOW.minusSeconds(1);
            equal(agentChange(agent,"pudding.rewards.request",map("asset_id",a.get("id"),"expected_revision",a.get("revision"))).get("error"),"CLOCK_ROLLBACK");
        });
        scenario("monthly expiry catches spoon mass once and never auto-books income",()->{
            String agent=freshAgent();var state=opened(agent);state=nested(action(agent,"pudding.spoon.scoop",state,"body_mg",8000,"sauce_mg",2000),"session");
            String id=assetId(state);var a=findAsset(id);clock.now=Instant.parse((String)a.get("dispose_after_at"));
            equal(action(agent,"pudding.spoon.taste",state).get("error"),"DISPOSAL_DUE");long before=rows("pudding_candidates");
            check(store.expireBatch(100)>0);equal(store.expireBatch(100),0);equal(rows("pudding_candidates"),before);
            var after=nested(agent(agent,"pudding.sessions.get",map("session_id",state.get("id"))),"session");
            var body=object(object(after.get("components")).get("body"));equal(integer(body,"discarded_mg",0,100000),90000);equal(integer(body,"spoon_mg",0,100000),0);
            nested(action(agent,"pudding.sessions.cleanup",after),"session");
            a=findAsset(id);nested(change("accounting.expiry",map("asset_id",id,"expected_revision",a.get("revision"),"reason","fixture obligations reviewed","obligations_cleared",true)),"expiry_candidate");
            equal(rows("pudding_candidates"),before+1);
        });
        scenario("human credentials stay disjoint from Agent MCP and management auth",()->{
            var agents=new AgentCredentials(Json.write(List.of(Map.of("agent_id","sandbox-fixed-agent","token","a".repeat(43)))).getBytes());
            var humans=new HumanCredentials(Json.write(List.of(Map.of("tenant_id",HUMAN.tenantId(),"human_id",HUMAN.humanId(),"token","h".repeat(43)))).getBytes(),agents);
            check(humans.authenticate("Bearer "+"a".repeat(43))==null);check(agents.authenticate("Bearer "+"h".repeat(43))==null);
            rejects(()->new HumanCredentials(Json.write(List.of(Map.of("tenant_id",HUMAN.tenantId(),"human_id",HUMAN.humanId(),"token","a".repeat(43)))).getBytes(),agents));
            var management=new ManagementEndpoint(humans,store);
            var endpoint=new McpEndpoint(agents,new PostgresBackend(db,clock,store),Set.of("pudding.test"),Set.of(),clock,Duration.ofMinutes(5),management);
            var request=new HttpRequest(HttpMethod.POST,"/manage/v1/assets.list","HTTP/1.1",Map.of("host","pudding.test","authorization","Bearer "+"a".repeat(43),"content-type","application/json"),"{}".getBytes());equal(endpoint.handle(request).status(),401);
            equal(endpoint.handle(new HttpRequest(HttpMethod.POST,"/manage/v1/assets.list","HTTP/1.1",Map.of("host","pudding.test","authorization","Bearer "+"h".repeat(43),"content-type","application/json"),"{}".getBytes())).status(),200);
        });
        scenario("cleanup outage hold, grace and review never double-count or revive grants",()->{
            String agent=freshAgent();var state=nested(agentChange(agent,"pudding.sessions.create",map("preset_id","custard-v0")),"session");
            state=nested(action(agent,"pudding.sessions.finish",state),"session");store.heartbeat();clock.now=clock.now.plusSeconds(601);
            equal(store.cleanupBatch(100),0);store.resumeCleanup("fixture outage resolved");
            equal(store.cleanupBatch(100),0);clock.now=clock.now.plusSeconds(601);check(store.cleanupBatch(100)>0);equal(store.cleanupBatch(100),0);
            var view=agent(agent,"pudding.sessions.get",map("session_id",state.get("id")));var policy=object(view.get("policy"));
            equal(integer(policy,"violations",0,100),1);int beforeRevision=integer(policy,"enforcement_revision",0,100);
            var reviewed=nested(change("policy.review",map("agent_id",agent,"obligation_ids",policy.get("obligation_ids"),"reason","fixture correction")),"policy");
            equal(integer(reviewed,"violations",0,100),0);check(integer(reviewed,"enforcement_revision",0,100)>beforeRevision);
            var create=agentChange(agent,"pudding.sessions.create",Map.of());equal(create.get("error"),"UNRESOLVED_SESSION");equal(integer(object(create.get("policy")),"violations",0,100),0);
            nested(action(agent,"pudding.sessions.cleanup",state),"session");
        });
        scenario("refund and revoke cannot be turned into a fresh approval",()->{
            String agent=freshAgent();var a=assigned(agent);var approved=approve(a);var begun=begin(agent,approved);a=findAsset((String)a.get("id"));
            var o=nested(change("refunds.request",map("order_id",a.get("order_id"),"expected_revision",1)),"order");
            a=findAsset((String)a.get("id"));a=nested(change("approval.revoke",map("asset_id",a.get("id"),"expected_revision",a.get("revision"))),"asset");
            var preview=human("approval.preview",map("asset_id",a.get("id")));
            equal(change("approval.confirm",map("asset_id",a.get("id"),"expected_revision",a.get("revision"),"intent_digest",preview.get("intent_digest"))).get("error"),"PAYMENT_NOT_USABLE");
            equal(agentChange(agent,"pudding.premium.pause",map("asset_id",a.get("id"),"expected_revision",a.get("revision"))).get("error"),"ASSET_UNAVAILABLE");
        });
        scenario("least-privilege runtime serves 16 MCP tools and a paid begin",()->{
            sql("CREATE ROLE pudding_sandbox_runtime LOGIN PASSWORD 'synthetic-sandbox-runtime-only'");CommerceStore.grantRuntime(db,"pudding_sandbox_runtime");
            var runtimeProps=new Properties();runtimeProps.putAll(props);runtimeProps.setProperty("user","pudding_sandbox_runtime");runtimeProps.setProperty("password","synthetic-sandbox-runtime-only");
            PostgresStore.Connections runtime=()->DriverManager.getConnection(env.get("PUDDING_TEST_JDBC_URL"),runtimeProps);
            String token="s".repeat(43),agent="sandbox-http-agent";
            var agents=new AgentCredentials(Json.write(List.of(Map.of("agent_id",agent,"token",token))).getBytes());var identity=agents.authenticate("Bearer "+token);
            store.provision(HUMAN,agent,"fixture:http",identity.credentialId());
            var ownerStore=store;store=new CommerceStore(runtime,clock,payments);
            try {
                var backend=new PostgresBackend(runtime,clock,store);check(backend.ready());var approval=approve(assigned(agent));
                var humans=new HumanCredentials(Json.write(List.of(Map.of("tenant_id",HUMAN.tenantId(),"human_id",HUMAN.humanId(),"token","h".repeat(43)))).getBytes(),agents);
                var endpoint=new McpEndpoint(agents,backend,Set.of("pudding.test"),Set.of(),clock,Duration.ofMinutes(5),new ManagementEndpoint(humans,store));
                var headers=new LinkedHashMap<String,String>();headers.put("host","pudding.test");headers.put("authorization","Bearer "+token);headers.put("accept","application/json, text/event-stream");headers.put("content-type","application/json");
                var init=map("jsonrpc","2.0","id",1,"method","initialize","params",map("protocolVersion",McpEndpoint.VERSION,"capabilities",Map.of(),"clientInfo",map("name","sandbox-test","version","1")));
                var reply=endpoint.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",headers,Json.write(init).getBytes()));equal(reply.status(),200);headers.put("mcp-session-id",reply.headers().get("Mcp-Session-Id"));headers.put("mcp-protocol-version",McpEndpoint.VERSION);
                endpoint.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",headers,Json.write(map("jsonrpc","2.0","method","notifications/initialized")).getBytes()));
                var listed=endpoint.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",headers,Json.write(map("jsonrpc","2.0","id",2,"method","tools/list")).getBytes()));
                equal(((List<?>)object(Input.object(JsonInput.parse(listed.body())).get("result")).get("tools")).size(),16);
                var a=object(approval.get("asset"));var args2=map("request_id",UUID.randomUUID().toString(),"asset_id",a.get("id"),"expected_revision",a.get("revision"),"grant_id",approval.get("grant_id"));
                var call=map("jsonrpc","2.0","id",3,"method","tools/call","params",map("name","pudding.premium.begin","arguments",args2));
                var result=endpoint.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",headers,Json.write(call).getBytes()));var domain=object(object(Input.object(JsonInput.parse(result.body())).get("result")).get("structuredContent"));check(Boolean.TRUE.equals(domain.get("ok")));
                try(var c=runtime.open();var q=c.createStatement()){try{q.execute("DELETE FROM pudding_commerce_events");throw new AssertionError("runtime erased audit");}catch(SQLException expected){}}
            }finally{store=ownerStore;}
        });
        System.out.println("Sandbox PostgreSQL scenarios: "+count);
    }
    static String assetId(Map<String,Object> state)throws Exception {
        try(var c=db.open();var s=c.prepareStatement("SELECT id FROM pudding_assets WHERE session_id=?")){s.setObject(1,UUID.fromString((String)state.get("id")));try(var r=s.executeQuery()){check(r.next());return r.getString(1);}}
    }
    static Map<String,Object> findAsset(String id)throws Exception{return ((List<?>)human("assets.list",Map.of()).get("assets")).stream().map(Input::object).filter(a->id.equals(a.get("id"))).findFirst().orElseThrow();}
}
