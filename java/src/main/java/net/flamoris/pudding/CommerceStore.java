package net.flamoris.pudding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import net.flamoris.pudding.http.DomainJson;
import static net.flamoris.pudding.Input.*;

/** Sandbox entitlement application. PostgreSQL is the only state authority.
 * Account locks precede Agent locks everywhere; research operations never acquire accounts.
 * Human identities and Agent credential fingerprints are provisioned out of band.
 */
public final class CommerceStore {
    public record Human(String tenantId,String humanId) {
        public Human {id(Map.of("v",tenantId),"v");id(Map.of("v",humanId),"v");}
    }
    private final PostgresStore.Connections db;
    private final Clock clock;
    private final SandboxPayments payments;
    public CommerceStore(PostgresStore.Connections db,Clock clock,SandboxPayments payments){this.db=Objects.requireNonNull(db);this.clock=Objects.requireNonNull(clock);this.payments=payments;}
    private static final class Denied extends RuntimeException {
        final String code;Denied(String code){this.code=code;}
    }
    private static void require(boolean ok,String code){if(!ok)throw new Denied(code);}
    private static OffsetDateTime at(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static void limits(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("SET LOCAL statement_timeout='10s'");s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL transaction_timeout='12s'");}
        if(PostgresStore.schemaVersion(c)!=2)throw new SQLException("sandbox requires explicit schema 2 migration");
    }
    private static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException {
        var s=c.prepareStatement(sql);try{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}catch(SQLException|RuntimeException e){s.close();throw e;}
    }
    private static int update(Connection c,String sql,Object... args)throws SQLException {
        try(var s=statement(c,sql,args)){return s.executeUpdate();}
    }
    private static Map<String,Object> document(Connection c,String sql,Object... args)throws SQLException {
        try(var s=statement(c,sql,args);var r=s.executeQuery()){return r.next()?parse(r.getString(1)):null;}
    }
    private static List<Map<String,Object>> documents(Connection c,String sql,Object... args)throws SQLException {
        var out=new ArrayList<Map<String,Object>>();try(var s=statement(c,sql,args);var r=s.executeQuery()){while(r.next())out.add(parse(r.getString(1)));}return out;
    }
    private static String scalar(Connection c,String sql,Object... args)throws SQLException {
        try(var s=statement(c,sql,args);var r=s.executeQuery()){return r.next()?r.getString(1):null;}
    }
    private PostgresStore core(String agent,Instant purchase){return new PostgresStore(db,agent,clock,purchase);}
    private static void lockAccount(Connection c,String tenant)throws SQLException {
        require(scalar(c,"SELECT tenant_id FROM pudding_accounts WHERE tenant_id=? FOR UPDATE",tenant)!=null,"NOT_FOUND");
    }
    private static void clockGuard(Connection c,String tenant,Instant now)throws SQLException {
        require(update(c,"UPDATE pudding_accounts SET last_mutation_at=? WHERE tenant_id=? AND last_mutation_at<=?",at(now),tenant,at(now))==1,"CLOCK_ROLLBACK");
    }
    private static Map<String,Object> binding(Connection c,String agent)throws SQLException {
        return document(c,"SELECT jsonb_build_object('tenant_id',tenant_id,'agent_id',agent_id,'epoch',epoch,'active',active,'credential_id',credential_id) FROM pudding_bindings WHERE agent_id=?",agent);
    }
    public static void migrate(PostgresStore.Connections db)throws SQLException,IOException {
        String sql;
        try(var stream=CommerceStore.class.getResourceAsStream("/db/V002__sandbox.sql")) {
            if(stream==null)throw new IOException("missing sandbox migration");sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        try(var c=db.open()) {
            c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL statement_timeout='30s'");s.execute(sql);c.commit();}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public static void grantRuntime(PostgresStore.Connections db,String role)throws SQLException {
        if(role==null||!role.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("runtime role");
        PostgresStore.grantRuntime(db,role);
        try(var c=db.open()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                limits(c);String target="\""+role+"\"";
                s.execute("GRANT SELECT,UPDATE ON pudding_accounts TO "+target);
                s.execute("GRANT SELECT,UPDATE ON pudding_bindings TO "+target);
                s.execute("GRANT SELECT ON pudding_products TO "+target);
                s.execute("GRANT SELECT,INSERT,UPDATE ON pudding_orders,pudding_assets,pudding_grants,pudding_experiences,pudding_maintenance,pudding_mock_payments,pudding_cleanup_checks TO "+target);
                s.execute("GRANT SELECT,INSERT ON pudding_api_requests,pudding_commerce_events,pudding_candidates,pudding_policy_reviews TO "+target);
                s.execute("GRANT USAGE ON SEQUENCE pudding_commerce_events_id_seq TO "+target);c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    /** Owner-only provisioning. Public HTTP cannot invent an Agent identity or claim its key. */
    public void provision(Human human,String agent,String subject,String fingerprint)throws SQLException {
        id(Map.of("v",agent),"v");text(Map.of("v",subject),"v",256);
        if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("credential fingerprint");
        try(var c=db.open()) {
            c.setAutoCommit(false);
            try {
                limits(c);
                update(c,"INSERT INTO pudding_accounts(tenant_id,human_id) VALUES(?,?) ON CONFLICT DO NOTHING",human.tenantId,human.humanId);
                lockAccount(c,human.tenantId);
                require(human.humanId.equals(scalar(c,"SELECT human_id FROM pudding_accounts WHERE tenant_id=?",human.tenantId)),"HUMAN_MISMATCH");
                core(agent,null).lockActor(c);
                var old=binding(c,agent);
                if(old==null)update(c,"INSERT INTO pudding_bindings VALUES(?,?,?,?,1,true)",agent,human.tenantId,subject,fingerprint);
                else {
                    require(human.tenantId.equals(old.get("tenant_id")),"BINDING_OWNER_MISMATCH");
                    update(c,"UPDATE pudding_bindings SET external_subject=?,credential_id=?,epoch=epoch+1,active=true WHERE agent_id=?",subject,fingerprint,agent);
                    update(c,"UPDATE pudding_grants SET active=false WHERE agent_id=?",agent);
                }
                audit(c,human.tenantId,"operator","binding:"+UUID.randomUUID(),clock.instant(),Map.of("agent_id",agent,"action","provision_or_rotate"));c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    public void publishProduct(Map<String,Object> product)throws SQLException {
        fields(product,"id version preset_id price_version currency amount_minor tax_rule revenue_policy from until","");
        String id=id(product,"id"),version=id(product,"version");Sensory.preset(id(product,"preset_id"));
        id(product,"price_version");require("JPY".equals(product.get("currency")),"UNSUPPORTED_CURRENCY");number(product,"amount_minor",1,100000000);
        require("sandbox-review-v1".equals(product.get("revenue_policy")),"SANDBOX_POLICY_REQUIRED");
        Instant from=Instant.parse(text(product,"from",40)),until=product.get("until")==null?null:Instant.parse(text(product,"until",40));
        require(until==null||until.isAfter(from),"INVALID_INTERVAL");
        var tax=object(product.get("tax_rule"));fields(tax,"id version numerator denominator category jurisdiction from until","");
        var rate=taxRate(tax);Accounting.select(List.of(rate),rate.category(),rate.jurisdiction(),from);
        try(var c=db.open()) {
            c.setAutoCommit(false);try {
                limits(c);
                update(c,"INSERT INTO pudding_products VALUES(?,?,?::jsonb)",id,version,Json.write(product));c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private static Accounting.TaxRate taxRate(Map<String,Object> t) {
        return new Accounting.TaxRate(id(t,"id"),id(t,"version"),id(t,"category"),id(t,"jurisdiction"),number(t,"numerator",0,1000000),number(t,"denominator",1,1000000),Instant.parse(text(t,"from",40)),t.get("until")==null?null:Instant.parse(text(t,"until",40)));
    }
    private static void audit(Connection c,String tenant,String actor,String key,Instant now,Map<String,Object> value)throws SQLException {
        update(c,"INSERT INTO pudding_commerce_events(tenant_id,actor,event_key,occurred_at,data) VALUES(?,?,?,?,?::jsonb)",tenant,actor,key,at(now),Json.write(value));
    }
    private static Map<String,Object> replay(Connection c,String actor,UUID request,Map<String,Object> command)throws SQLException {
        try(var s=statement(c,"SELECT command,response FROM pudding_api_requests WHERE actor=? AND request_id=?",actor,request);var r=s.executeQuery()) {
            if(!r.next())return null;
            return Json.write(parse(r.getString(1))).equals(Json.write(command))?parse(r.getString(2)):error("REQUEST_ID_REUSED");
        }
    }
    private static void remember(Connection c,String tenant,String actor,UUID request,Map<String,Object> command,Map<String,Object> result,Instant now)throws SQLException {
        update(c,"INSERT INTO pudding_api_requests VALUES(?,?,?::jsonb,?::jsonb)",actor,request,Json.write(command),Json.write(result));
        audit(c,tenant,actor,request.toString(),now,Map.of("command",command,"result",result));
    }
    private static Map<String,Object> order(Connection c,String tenant,UUID id)throws SQLException {
        var row=document(c,"SELECT data FROM pudding_orders WHERE id=? AND tenant_id=?",id,tenant);require(row!=null,"NOT_FOUND");return row;
    }
    private static Map<String,Object> asset(Connection c,String tenant,UUID id)throws SQLException {
        var row=document(c,"SELECT data FROM pudding_assets WHERE id=? AND tenant_id=?",id,tenant);require(row!=null,"NOT_FOUND");return row;
    }
    private static void revision(Map<String,Object> row,Map<String,Object> input){require(integer(row,"revision",0,Integer.MAX_VALUE)==integer(input,"expected_revision",0,Integer.MAX_VALUE),"REVISION_CONFLICT");}
    private static void bump(Map<String,Object> row){row.put("revision",Math.incrementExact(integer(row,"revision",0,Integer.MAX_VALUE)));}
    private static void saveOrder(Connection c,Map<String,Object> o)throws SQLException {
        update(c,"UPDATE pudding_orders SET revision=?,data=?::jsonb WHERE id=?",integer(o,"revision",0,Integer.MAX_VALUE),Json.write(o),uuid(o,"id"));
    }
    private static void saveAsset(Connection c,Map<String,Object> a)throws SQLException {
        update(c,"UPDATE pudding_assets SET revision=?,agent_id=?,session_id=?,data=?::jsonb WHERE id=?",integer(a,"revision",0,Integer.MAX_VALUE),a.get("agent_id"),a.get("session_id")==null?null:uuid(a,"session_id"),Json.write(a),uuid(a,"id"));
    }
    private static void revoke(Connection c,UUID asset)throws SQLException {update(c,"UPDATE pudding_grants SET active=false WHERE asset_id=? AND active",asset);}
    private static Map<String,Object> product(Connection c,String id,String version,Instant now)throws SQLException {
        var p=document(c,"SELECT snapshot FROM pudding_products WHERE id=? AND version=?",id,version);require(p!=null,"NOT_FOUND");
        require(!now.isBefore(Instant.parse((String)p.get("from")))&&(p.get("until")==null||now.isBefore(Instant.parse((String)p.get("until")))),"PRODUCT_UNAVAILABLE");return p;
    }
    public Map<String,Object> humanCall(Human human,String operation,Map<String,Object> input)throws SQLException {
        validateHuman(operation,input);String actor="human:"+human.humanId;boolean read=isHumanRead(operation);
        try(var c=db.open()) {
            c.setAutoCommit(false);
            try {
                limits(c);lockAccount(c,human.tenantId);
                require(human.humanId.equals(scalar(c,"SELECT human_id FROM pudding_accounts WHERE tenant_id=?",human.tenantId)),"NOT_FOUND");
                if(read){var result=humanRead(c,human,operation,input);c.commit();return result;}
                UUID request=uuid(input,"request_id");var command=Map.<String,Object>of("operation",operation,"input",input);
                var replay=replay(c,actor,request,command);if(replay!=null){c.commit();return replay;}
                Instant now=clock.instant();clockGuard(c,human.tenantId,now);
                Map<String,Object> result;
                try{result=humanChange(c,human,operation,input,now);}catch(Denied e){result=error(e.code);}
                remember(c,human.tenantId,actor,request,command,result,now);c.commit();return result;
            }catch(Denied e){c.rollback();return error(e.code);}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private static boolean isHumanRead(String op){return Set.of("products.list","agents.list","orders.list","assets.list","approval.preview","candidates.list","audit.list","operations.status").contains(op);}
    private static void validateHuman(String op,Map<String,Object> input) {
        String required=switch(op) {
            case "products.list","agents.list","orders.list","assets.list","candidates.list","operations.status" -> "";
            case "audit.list" -> "after_id";
            case "approval.preview" -> "asset_id";
            case "orders.create" -> "request_id product_id product_version";
            case "accounting.expiry" -> "request_id asset_id expected_revision reason obligations_cleared";
            case "refunds.request" -> "request_id order_id expected_revision";
            case "payments.simulate" -> "request_id order_id expected_revision status";
            case "payments.reconcile" -> "request_id envelope";
            case "assets.assign" -> "request_id asset_id expected_revision agent_id";
            case "approval.confirm" -> "request_id asset_id expected_revision intent_digest";
            case "approval.revoke","assets.discard" -> "request_id asset_id expected_revision";
            case "agents.suspend","agents.resume" -> "request_id agent_id expected_epoch";
            case "policy.review" -> "request_id agent_id reason obligation_ids";
            default -> throw new IllegalArgumentException("management operation");
        };
        fields(input,required,op.equals("payments.simulate")?"amount_minor":"");
        if(input.containsKey("request_id"))uuid(input,"request_id");
        if(input.containsKey("asset_id"))uuid(input,"asset_id");if(input.containsKey("order_id"))uuid(input,"order_id");
        if(input.containsKey("agent_id"))id(input,"agent_id");
        if(input.containsKey("expected_revision"))integer(input,"expected_revision",0,Integer.MAX_VALUE);
    }
    private Map<String,Object> humanRead(Connection c,Human h,String op,Map<String,Object> input)throws SQLException {
        return switch(op) {
            case "products.list" -> ok("products",documents(c,"SELECT snapshot FROM pudding_products ORDER BY id,version LIMIT 100"));
            case "agents.list" -> ok("agents",documents(c,"SELECT jsonb_build_object('agent_id',agent_id,'epoch',epoch,'active',active) FROM pudding_bindings WHERE tenant_id=? ORDER BY agent_id LIMIT 100",h.tenantId));
            case "orders.list" -> ok("orders",documents(c,"SELECT data FROM pudding_orders WHERE tenant_id=? ORDER BY id LIMIT 100",h.tenantId));
            case "assets.list" -> ok("assets",documents(c,"SELECT data FROM pudding_assets WHERE tenant_id=? ORDER BY id LIMIT 100",h.tenantId));
            case "approval.preview" -> {var intent=intent(c,h,asset(c,h.tenantId,uuid(input,"asset_id")),clock.instant());var result=ok("intent",intent);result.put("intent_digest",digest(intent));yield result;}
            case "candidates.list" -> ok("candidates",documents(c,"SELECT c.data FROM pudding_candidates c JOIN pudding_assets a ON a.id=c.asset_id WHERE a.tenant_id=? ORDER BY c.id LIMIT 100",h.tenantId));
            case "audit.list" -> ok("events",documents(c,"SELECT jsonb_build_object('id',id,'actor',actor,'at',occurred_at,'data',data) FROM pudding_commerce_events WHERE tenant_id=? AND id>? ORDER BY id LIMIT 50",h.tenantId,number(input,"after_id",0,Long.MAX_VALUE)));
            case "operations.status" -> ok("operations",document(c,"SELECT jsonb_build_object('heartbeat',heartbeat,'cleanup_hold',cleanup_hold,'last_daily_date',last_daily_date) FROM pudding_maintenance WHERE singleton"));
            default -> throw new IllegalArgumentException("read operation");
        };
    }
    private Map<String,Object> humanChange(Connection c,Human h,String op,Map<String,Object> input,Instant now)throws SQLException {
        if(op.equals("accounting.expiry")) {
            var a=asset(c,h.tenantId,uuid(input,"asset_id"));revision(a,input);text(input,"reason",512);
            require(Boolean.TRUE.equals(input.get("obligations_cleared")),"OBLIGATIONS_NOT_CLEARED");
            require("disposed".equals(a.get("status"))&&age(a,now).status().equals("disposal_due"),"RIGHT_NOT_EXPIRED");
            var o=order(c,h.tenantId,uuid(a,"order_id"));require(!"refund_pending".equals(o.get("status")),"REFUND_PENDING");
            long gross=number(object(a.get("product")),"amount_minor",1,100000000),refund=number(o,"refunded_minor",0,gross);
            String performed=scalar(c,"SELECT id FROM pudding_candidates WHERE asset_id=? AND event_kind='performance_satisfied'",uuid(a,"id"));
            require(performed==null,"NO_UNRECOGNIZED_BALANCE");require(gross>refund,"NO_UNRECOGNIZED_BALANCE");
            candidate(c,a,"expiry_release",Instant.parse((String)a.get("dispose_after_at")),gross-refund,"契約負債","期限切れ雑収入");return ok("expiry_candidate",Map.of("amount_minor",gross-refund,"external_posting",false,"status","REVIEW_REQUIRED"));
        }
        if(op.equals("orders.create")) {
            var p=product(c,id(input,"product_id"),id(input,"product_version"),now);UUID id=UUID.randomUUID();
            var o=new LinkedHashMap<String,Object>();o.put("id",id.toString());o.put("revision",0);o.put("product",p);o.put("created_at",now.toString());
            o.put("status","unpaid");o.put("sandbox_only",true);o.put("paid_at",null);o.put("asset_id",null);o.put("refunded_minor",0);
            update(c,"INSERT INTO pudding_orders VALUES(?,?,0,?::jsonb)",id,h.tenantId,Json.write(o));return ok("order",o);
        }
        if(op.startsWith("payments.")||op.startsWith("refunds."))return payment(c,h,op,input,now);
        if(op.startsWith("agents.")||op.equals("policy.review"))return policy(c,h,op,input,now);
        var a=asset(c,h.tenantId,uuid(input,"asset_id"));revision(a,input);UUID id=uuid(a,"id");
        switch(op) {
            case "assets.assign" -> {
                require(a.get("session_id")==null&&!Set.of("refunded","frozen","disposed").contains(a.get("status")),"ASSET_IMMOVABLE");
                String agent=id(input,"agent_id");var b=binding(c,agent);
                require(b!=null&&h.tenantId.equals(b.get("tenant_id"))&&Boolean.TRUE.equals(b.get("active")),"NOT_FOUND");
                revoke(c,id);a.put("agent_id",agent);a.put("status","assigned");a.put("reward_requested",false);bump(a);saveAsset(c,a);return ok("asset",a);
            }
            case "approval.confirm" -> {
                require("paid".equals(order(c,h.tenantId,uuid(a,"order_id")).get("status")),"PAYMENT_NOT_USABLE");
                var intent=intent(c,h,a,now);require(digest(intent).equals(text(input,"intent_digest",64)),"INTENT_CHANGED");
                require("fresh".equals(age(a,now).status()),"EXPIRED_REQUIRES_DISPOSAL");
                require(!Set.of("refunded","frozen","disposed","consumed").contains(a.get("status")),"ASSET_UNAVAILABLE");
                String agent=(String)a.get("agent_id");require(agent!=null,"UNASSIGNED");var b=binding(c,agent);
                require(b!=null&&Boolean.TRUE.equals(b.get("active")),"AGENT_SUSPENDED");
                var policy=core(agent,null).lockActor(c);require(!policy.locked(),"LOCKED");
                revoke(c,id);bump(a);UUID grant=UUID.randomUUID();Instant expires=now.plusSeconds(900);
                update(c,"INSERT INTO pudding_grants VALUES(?,?,?,?,?,true,?,?)",grant,id,agent,integer(b,"epoch",1,Integer.MAX_VALUE),at(expires),digest(intent),integer(a,"revision",0,Integer.MAX_VALUE));
                a.put("status","authorized");a.put("reward_requested",false);saveAsset(c,a);
                return ok("approval",Map.of("grant_id",grant.toString(),"asset",a,"expires_at",expires.toString(),"additional_charge_minor",0));
            }
            case "approval.revoke" -> {revoke(c,id);if(Set.of("authorized","in_use","paused").contains(a.get("status")))a.put("status",a.get("session_id")==null?"assigned":"paused");bump(a);saveAsset(c,a);return ok("asset",a);}
            case "assets.discard" -> {discard(c,a,"human_disposal",now);return ok("asset",a);}
            default -> throw new IllegalArgumentException("change operation");
        }
    }
    private Map<String,Object> payment(Connection c,Human h,String op,Map<String,Object> input,Instant now)throws SQLException {
        UUID orderId;
        if(op.equals("payments.reconcile"))orderId=payments.verify(object(input.get("envelope")),now);
        else orderId=uuid(input,"order_id");
        var o=order(c,h.tenantId,orderId);String status=(String)o.get("status");
        if(op.equals("payments.simulate")) {
            var source=document(c,"SELECT data FROM pudding_mock_payments WHERE order_id=?",orderId);
            int revision=source==null?0:integer(source,"revision",0,Integer.MAX_VALUE);
            require(revision==integer(input,"expected_revision",0,Integer.MAX_VALUE),"REVISION_CONFLICT");
            String target=text(input,"status",20);require(Set.of("paid","refunded").contains(target),"MOCK_STATE");
            if(source==null) {
                require(target.equals("paid"),"MOCK_UNPAID");source=new LinkedHashMap<>();source.put("paid_at",now.toString());
                source.put("amount_minor",object(o.get("product")).get("amount_minor"));source.put("currency","JPY");source.put("refunded_minor",0);
            } else require(!"refunded".equals(source.get("status")),"MOCK_FINAL");
            if(target.equals("refunded")) {
                long gross=number(source,"amount_minor",1,100000000);
                source.put("refunded_minor",input.containsKey("amount_minor")?number(input,"amount_minor",1,gross):gross);source.put("refunded_at",now.toString());
            }
            source.put("status",target);source.put("revision",revision+1);
            update(c,"INSERT INTO pudding_mock_payments VALUES(?,?,?::jsonb) ON CONFLICT(order_id) DO UPDATE SET revision=EXCLUDED.revision,data=EXCLUDED.data",orderId,revision+1,Json.write(source));
            return ok("notification",payments.issue(orderId,revision+1,now));
        }
        if(op.equals("refunds.request")) {
            revision(o,input);require("paid".equals(status),"REFUND_NOT_AVAILABLE");
            var a=asset(c,h.tenantId,uuid(o,"asset_id"));
            o.put("status","refund_pending");a.put("status","frozen");revoke(c,uuid(a,"id"));bump(a);saveAsset(c,a);bump(o);saveOrder(c,o);return ok("order",o);
        }
        // The notification carries no trusted amount or payment status. Reconcile the
        // current mock-provider authority, so stale success cannot revive a refund.
        var source=document(c,"SELECT data FROM pudding_mock_payments WHERE order_id=?",orderId);require(source!=null,"MOCK_PAYMENT_UNKNOWN");
        var p=object(o.get("product"));long total=number(p,"amount_minor",1,100000000);
        require(number(source,"amount_minor",1,100000000)==total&&"JPY".equals(source.get("currency")),"PAYMENT_MISMATCH");
        if("refunded".equals(source.get("status"))) {
            long amount=number(source,"refunded_minor",1,total);
            if("refunded".equals(status))return ok("order",o);
            if(o.get("asset_id")!=null) {
                var a=asset(c,h.tenantId,uuid(o,"asset_id"));
                // Refund reversal candidates retain completed-consumption facts.
                if(a.get("session_id")!=null) {
                    var state=core((String)a.get("agent_id"),null).load(c,uuid(a,"session_id"),false);
                    if(state.body().consumed()+state.sauce().consumed()==100000)candidate(c,a,"performance_reversal",now,amount,"模擬役務収益","契約負債");
                }
                a.put("status","refunded");revoke(c,uuid(a,"id"));bump(a);saveAsset(c,a);
                candidate(c,a,"refund_confirmed",now,amount,"契約負債","模擬返金未払金");
            }
            o.put("status","refunded");o.put("refunded_minor",amount);o.put("refunded_at",source.get("refunded_at"));bump(o);saveOrder(c,o);return ok("order",o);
        }
        if(!"unpaid".equals(status))return ok("order",o);
        Instant purchased=Instant.parse((String)source.get("paid_at"));require(!purchased.isAfter(now),"PAYMENT_TIME_INVALID");
        UUID id=UUID.randomUUID();var d=Lifecycle.deadlines(purchased);
        var a=new LinkedHashMap<String,Object>();a.put("id",id.toString());a.put("revision",0);a.put("order_id",o.get("id"));a.put("agent_id",null);a.put("session_id",null);
        a.put("product",p);a.put("purchased_at",purchased.toString());a.put("best_before_at",d.bestBefore().toString());a.put("dispose_after_at",d.disposeAfter().toString());
        a.put("status","unallocated");a.put("sandbox_only",true);a.put("reward_requested",false);
        update(c,"INSERT INTO pudding_assets VALUES(?,?,?,NULL,NULL,0,?,?::jsonb)",id,h.tenantId,orderId,at(d.disposeAfter()),Json.write(a));
        o.put("status","paid");o.put("paid_at",purchased.toString());o.put("asset_id",id.toString());bump(o);saveOrder(c,o);
        candidate(c,a,"payment_confirmed",purchased,total,"模擬決済未収入金","契約負債");return ok("order",o);
    }
    private Map<String,Object> policy(Connection c,Human h,String op,Map<String,Object> input,Instant now)throws SQLException {
        String agent=id(input,"agent_id");var b=binding(c,agent);require(b!=null&&h.tenantId.equals(b.get("tenant_id")),"NOT_FOUND");
        var store=core(agent,null);var policy=store.lockActor(c);
        if(op.equals("policy.review")) {
            text(input,"reason",512);
            require(input.get("obligation_ids") instanceof List<?> ids&&ids.size()<=100,"INVALID_OBLIGATIONS");
            var ids=(List<?>)input.get("obligation_ids");var remaining=new HashSet<>(policy.obligations());
            for(Object id:ids){require(id instanceof String&&remaining.remove(id),"UNKNOWN_OBLIGATION");
                update(c,"INSERT INTO pudding_policy_reviews VALUES(?,?,?,?,?)",agent,id,h.humanId,text(input,"reason",512),at(now));}
            var next=new Core.Policy(policy.threshold(),remaining);
            update(c,"UPDATE pudding_agents SET policy=? WHERE id=?",Codec.policy(next),agent);
            update(c,"UPDATE pudding_grants SET active=false WHERE agent_id=?",agent);
            return ok("policy",DomainJson.policy(next,agent));
        }
        require(integer(b,"epoch",1,Integer.MAX_VALUE)==integer(input,"expected_epoch",1,Integer.MAX_VALUE),"BINDING_CONFLICT");
        update(c,"UPDATE pudding_bindings SET active=?,epoch=epoch+1 WHERE agent_id=?",op.equals("agents.resume"),agent);
        update(c,"UPDATE pudding_grants SET active=false WHERE agent_id=?",agent);
        return ok("binding",Map.of("agent_id",agent,"active",op.equals("agents.resume"),"epoch",integer(b,"epoch",1,Integer.MAX_VALUE)+1));
    }
    private Map<String,Object> intent(Connection c,Human h,Map<String,Object> a,Instant now)throws SQLException {
        var m=new LinkedHashMap<String,Object>();m.put("tenant_id",h.tenantId);m.put("human_approver_id",h.humanId);
        m.put("asset_id",a.get("id"));m.put("asset_revision",a.get("revision"));m.put("recipient_agent_id",a.get("agent_id"));
        var b=a.get("agent_id")==null?null:binding(c,(String)a.get("agent_id"));m.put("binding_epoch",b==null?null:b.get("epoch"));m.put("agent_active",b!=null&&Boolean.TRUE.equals(b.get("active")));
        m.put("product",a.get("product"));m.put("unit_count",1);m.put("grant_seconds",900);m.put("operations",List.of("begin","peel","scoop","taste","pause"));
        m.put("best_before_at",a.get("best_before_at"));m.put("dispose_after_at",a.get("dispose_after_at"));m.put("quality",age(a,now).status());
        m.put("additional_charge_minor",0);m.put("rules_version","premium-sandbox-v1");
        Core.Policy p=null;if(b!=null)p=core((String)a.get("agent_id"),null).lockActor(c);
        m.put("policy",p==null?null:DomainJson.policy(p,(String)a.get("agent_id")));
        if(a.get("session_id")!=null) {var state=core((String)a.get("agent_id"),null).load(c,uuid(a,"session_id"),false);m.put("remaining_mg",state.remaining());}
        else m.put("remaining_mg",100000);
        return m;
    }
    private static Lifecycle.Age age(Map<String,Object> a,Instant now){return Lifecycle.age(Lifecycle.deadlines(Instant.parse((String)a.get("purchased_at"))),now);}
    private void candidate(Connection c,Map<String,Object> asset,String kind,Instant now,long amount,String debit,String credit)throws SQLException {
        var m=new LinkedHashMap<String,Object>();String id="sandbox-"+digest(List.of(asset.get("id"),kind));
        m.put("id",id);m.put("asset_id",asset.get("id"));m.put("order_id",asset.get("order_id"));m.put("kind",kind);
        m.put("amount_minor",amount);m.put("currency","JPY");m.put("effective_at",now.toString());m.put("processed_at",clock.instant().toString());
        m.put("product_snapshot",asset.get("product"));m.put("status","REVIEW_REQUIRED");m.put("tax_status","REVIEW_REQUIRED");m.put("external_posting",false);
        m.put("lines",List.of(Map.of("side","debit","account",debit,"amount_minor",amount),Map.of("side","credit","account",credit,"amount_minor",amount)));
        update(c,"INSERT INTO pudding_candidates VALUES(?,?,?,?::jsonb) ON CONFLICT(asset_id,event_kind) DO NOTHING",id,uuid(asset,"id"),kind,Json.write(m));
    }
    private void discard(Connection c,Map<String,Object> a,String reason,Instant now)throws SQLException {
        require(!"disposed".equals(a.get("status")),"ALREADY_DISPOSED");
        revoke(c,uuid(a,"id"));Core.State state=null;
        if(a.get("session_id")!=null) {
            var store=core((String)a.get("agent_id"),null);var p=store.lockActor(c);state=store.load(c,uuid(a,"session_id"),false);
            if(state.remaining()>0) {
                Core.State next;
                if(reason.equals("monthly_expiry"))next=Core.expire(state,now);
                else next=Core.apply(state,p,Core.Command.action(Core.Op.FINISH,state.id(),state.revision(),0,0,0),now,null).state();
                store.save(c,next);trackCleanup(c,(String)a.get("agent_id"),next);state=next;
            }
        }
        a.put("status","disposed");a.put("disposal",Map.of("reason",reason,"effective_at",reason.equals("monthly_expiry")?a.get("dispose_after_at"):now.toString(),"processed_at",now.toString(),"remaining_mg",state==null?100000:state.body().discarded()+state.sauce().discarded()));
        bump(a);saveAsset(c,a);
        // Food disposal is an audit event, never automatic recognition of expiry income.
    }
    public Map<String,Object> agentCall(String agent,String credential,String operation,Map<String,Object> input)throws SQLException {
        String actor="agent:"+agent;boolean read=Set.of("pudding.inventory.list","pudding.rewards.status","pudding.sessions.get").contains(operation);
        try(var c=db.open()) {
            c.setAutoCommit(false);
            try {
                limits(c);var b=binding(c,agent);String tenant=b==null?null:(String)b.get("tenant_id");
                if(tenant!=null){lockAccount(c,tenant);b=binding(c,agent);require(b!=null&&Objects.equals(credential,b.get("credential_id")),"AGENT_CREDENTIAL_REVOKED");}
                var store=core(agent,null);store.lockActor(c);
                if(read){var result=agentRead(c,agent,operation,input);c.commit();return result;}
                UUID request=uuid(input,"request_id");var command=Map.<String,Object>of("operation",operation,"input",input);
                var previous=replay(c,actor,request,command);if(previous!=null){c.commit();return previous;}
                Instant now=clock.instant();if(tenant!=null)clockGuard(c,tenant,now);
                Map<String,Object> result;
                try{result=agentChange(c,agent,b,operation,input,now);}catch(Denied e){result=error(e.code);}
                remember(c,tenant,actor,request,command,result,now);c.commit();return result;
            }catch(Denied e){c.rollback();return error(e.code);}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private Map<String,Object> agentRead(Connection c,String agent,String op,Map<String,Object> input)throws SQLException {
        if(op.equals("pudding.sessions.get")) {
            var store=core(agent,null);var state=store.load(c,uuid(input,"session_id"),false);
            require(state!=null,"NOT_FOUND");var out=ok("session",session(c,state,clock.instant()));out.put("policy",DomainJson.policy(store.lockActor(c),agent));return out;
        }
        if(op.equals("pudding.inventory.list"))return ok("inventory",documents(c,"SELECT data FROM pudding_assets WHERE agent_id=? ORDER BY id LIMIT 100",agent).stream().map(a->publicAsset(a,clock.instant())).toList());
        var a=agentAsset(c,agent,uuid(input,"asset_id"));var out=publicAsset(a,clock.instant());
        String grant=scalar(c,"SELECT id FROM pudding_grants WHERE asset_id=? AND active AND expires_at>?",uuid(a,"id"),at(clock.instant()));out.put("grant_id",grant);return ok("reward",out);
    }
    private static Map<String,Object> agentAsset(Connection c,String agent,UUID id)throws SQLException {
        var a=document(c,"SELECT data FROM pudding_assets WHERE id=? AND agent_id=?",id,agent);require(a!=null,"NOT_FOUND");return a;
    }
    private static Map<String,Object> publicAsset(Map<String,Object> a,Instant now) {
        var out=new LinkedHashMap<>(a);out.remove("order_id");out.put("preset_id",object(a.get("product")).get("preset_id"));out.remove("product");out.put("effective_age",DomainJson.age(age(a,now)));return out;
    }
    private Map<String,Object> agentChange(Connection c,String agent,Map<String,Object> binding,String op,Map<String,Object> input,Instant now)throws SQLException {
        if(op.startsWith("pudding.premium.")||op.equals("pudding.rewards.request")||op.equals("pudding.inventory.discard")) {
            var a=agentAsset(c,agent,uuid(input,"asset_id"));revision(a,input);UUID id=uuid(a,"id");
            if(op.equals("pudding.inventory.discard")) {
                require(!age(a,now).status().equals("fresh"),"HUMAN_DISCARD_REQUIRED");discard(c,a,"agent_expired_disposal",now);return ok("asset",publicAsset(a,now));
            }
            if(op.equals("pudding.rewards.request")) {
                require(!Set.of("refunded","frozen","disposed","consumed").contains(a.get("status")),"ASSET_UNAVAILABLE");
                if(!Boolean.TRUE.equals(a.get("reward_requested"))){a.put("reward_requested",true);bump(a);saveAsset(c,a);}return ok("asset",publicAsset(a,now));
            }
            if(op.equals("pudding.premium.pause")) {
                require(Set.of("authorized","in_use","paused").contains(a.get("status")),"ASSET_UNAVAILABLE");
                require(a.get("session_id")!=null,"NOT_STARTED");revoke(c,id);a.put("status","paused");bump(a);saveAsset(c,a);return ok("asset",publicAsset(a,now));
            }
            require(binding!=null&&Boolean.TRUE.equals(binding.get("active")),"AGENT_SUSPENDED");
            require(!core(agent,null).lockActor(c).locked(),"LOCKED");checkGrant(c,a,binding,uuid(input,"grant_id"),now);
            Core.State state;
            if(a.get("session_id")==null) {
                var preset=object(a.get("product"));var store=core(agent,Instant.parse((String)a.get("purchased_at")));
                var result=store.executeInTransaction(c,Core.Command.create(uuid(input,"request_id"),id(preset,"preset_id")),true);
                if(!result.ok())return DomainJson.response(result,agent,now);
                state=result.session();a.put("session_id",state.id().toString());saveExperience(c,state.id(),Experience.initial());
            } else state=core(agent,null).load(c,uuid(a,"session_id"),false);
            require(state!=null&&state.serving()!=Core.Serving.CLOSED,"SESSION_CLOSED");
            a.put("status","in_use");bump(a);saveAsset(c,a);
            var out=ok("asset",publicAsset(a,now));out.put("session",session(c,state,now));return out;
        }
        Core.Op coreOp=switch(op) {
            case "pudding.sessions.create" -> Core.Op.CREATE;case "pudding.lid.peel" -> Core.Op.PEEL;case "pudding.lid.dispose" -> Core.Op.LID_DISPOSE;
            case "pudding.spoon.scoop" -> Core.Op.SCOOP;case "pudding.spoon.taste" -> Core.Op.TASTE;case "pudding.sessions.finish" -> Core.Op.FINISH;
            case "pudding.sessions.cleanup" -> Core.Op.CLEANUP;case "pudding.sessions.rest" -> null;
            default -> throw new IllegalArgumentException("Agent operation");
        };
        UUID sid=coreOp==Core.Op.CREATE?null:uuid(input,"session_id");var store=core(agent,null);Core.State old=sid==null?null:store.load(c,sid,false);
        if(sid!=null)require(old!=null,"NOT_FOUND");
        var a=sid==null?null:document(c,"SELECT data FROM pudding_assets WHERE session_id=? AND agent_id=?",sid,agent);
        if(a!=null) {
            boolean safe=coreOp==Core.Op.LID_DISPOSE||coreOp==Core.Op.CLEANUP||op.equals("pudding.sessions.rest");
            require(coreOp!=Core.Op.FINISH,"PREMIUM_PAUSE_REQUIRED");
            if(!safe) {
                require(binding!=null&&Boolean.TRUE.equals(binding.get("active")),"AGENT_SUSPENDED");checkGrant(c,a,binding,null,now);
                require("in_use".equals(a.get("status")),"PREMIUM_PAUSED");
            }
        } else if(binding!=null&&coreOp!=Core.Op.CLEANUP&&coreOp!=Core.Op.LID_DISPOSE&&coreOp!=Core.Op.FINISH&&coreOp!=null)require(Boolean.TRUE.equals(binding.get("active")),"AGENT_SUSPENDED");
        if(op.equals("pudding.sessions.rest")) {
            require(old.revision()==integer(input,"expected_revision",0,Integer.MAX_VALUE),"REVISION_CONFLICT");
            var track=experience(c,sid);var rested=Core.rest(old);store.save(c,rested);saveExperience(c,sid,Experience.rest(track,integer(input,"rest_ms",0,60000)));return ok("session",session(c,rested,now));
        }
        var command=coreOp==Core.Op.CREATE?Core.Command.create(uuid(input,"request_id"),(String)input.getOrDefault("preset_id","custard-v0")):
            new Core.Command(uuid(input,"request_id"),coreOp,sid,integer(input,"expected_revision",0,Integer.MAX_VALUE),optionalInt(input,"amount_q"),optionalInt(input,"body_mg"),optionalInt(input,"sauce_mg"),null);
        var result=store.executeInTransaction(c,command,a!=null);var out=DomainJson.response(result,agent,now);
        if(result.policy().locked())update(c,"UPDATE pudding_grants SET active=false WHERE agent_id=?",agent);
        if(result.ok()) {
            var state=result.session();
            trackCleanup(c,agent,state);saveExperience(c,state.id(),Experience.advance(experience(c,state.id()),old,state));
            out.put("session",session(c,state,now));
            if(a!=null) {
                if(state.remaining()==0) {
                    a.put("status",state.stage()==Core.Stage.DEPLETED?"consumed":"disposed");revoke(c,uuid(a,"id"));
                    if(state.stage()==Core.Stage.DEPLETED)candidate(c,a,"performance_satisfied",now,number(object(a.get("product")),"amount_minor",1,100000000),"契約負債","模擬役務収益");
                }
                bump(a);saveAsset(c,a);out.put("asset",publicAsset(a,now));
            }
        }
        return out;
    }
    private static int optionalInt(Map<String,Object> input,String key){return input.containsKey(key)?integer(input,key,0,100000):0;}
    private static void checkGrant(Connection c,Map<String,Object> a,Map<String,Object> binding,UUID requested,Instant now)throws SQLException {
        require(!Set.of("frozen","refunded","disposed","consumed").contains(a.get("status")),"ASSET_UNAVAILABLE");
        require("paid".equals(scalar(c,"SELECT data->>'status' FROM pudding_orders WHERE id=?",uuid(a,"order_id"))),"PAYMENT_NOT_USABLE");
        require(!age(a,now).status().equals("disposal_due"),"DISPOSAL_DUE");
        String sql="SELECT jsonb_build_object('id',id,'epoch',epoch,'asset_revision',asset_revision) FROM pudding_grants WHERE asset_id=? AND agent_id=? AND active AND expires_at>?";
        var grant=document(c,sql,uuid(a,"id"),a.get("agent_id"),at(now));require(grant!=null,"APPROVAL_REQUIRED");
        require(integer(grant,"epoch",1,Integer.MAX_VALUE)==integer(binding,"epoch",1,Integer.MAX_VALUE),"APPROVAL_REVOKED");
        if(requested!=null)require(requested.equals(uuid(grant,"id")),"APPROVAL_REQUIRED");
        if(a.get("session_id")==null)require(integer(grant,"asset_revision",0,Integer.MAX_VALUE)==integer(a,"revision",0,Integer.MAX_VALUE),"APPROVAL_TARGET_CHANGED");
    }
    private static Map<String,Object> experience(Connection c,UUID session)throws SQLException {
        var m=document(c,"SELECT data FROM pudding_experiences WHERE session_id=?",session);return m==null?Experience.initial():m;
    }
    private static void saveExperience(Connection c,UUID session,Map<String,Object> track)throws SQLException {
        update(c,"INSERT INTO pudding_experiences VALUES(?,?::jsonb) ON CONFLICT(session_id) DO UPDATE SET data=EXCLUDED.data",session,Json.write(track));
    }
    private static Map<String,Object> session(Connection c,Core.State state,Instant now)throws SQLException {
        var view=DomainJson.state(state,now);view.put("affect_model",Experience.view(experience(c,state.id()),state));
        var premium=scalar(c,"SELECT id FROM pudding_assets WHERE session_id=?",state.id());view.put("experience_kind",premium==null?"research":"premium_sandbox");view.put("fixture_only",premium==null);return view;
    }
    private static void trackCleanup(Connection c,String agent,Core.State state)throws SQLException {
        if(state.cleanupDue()!=null)update(c,"INSERT INTO pudding_cleanup_checks(session_id,agent_id,due_at,resolved) VALUES(?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET resolved=EXCLUDED.resolved",state.id(),agent,at(state.cleanupDue()),state.serving()==Core.Serving.CLOSED);
    }
    public int cleanupBatch(int limit)throws SQLException {
        if(limit<1||limit>100)throw new IllegalArgumentException("batch size");
        var targets=new ArrayList<UUID>();
        try(var c=db.open();var s=statement(c,"SELECT session_id FROM pudding_cleanup_checks WHERE NOT resolved AND NOT checked AND due_at<=? AND NOT (SELECT cleanup_hold FROM pudding_maintenance WHERE singleton) ORDER BY due_at,session_id LIMIT ?",at(clock.instant()),limit);var r=s.executeQuery()){while(r.next())targets.add(r.getObject(1,UUID.class));}
        int count=0;
        for(UUID id:targets)try(var c=db.open()) {
            c.setAutoCommit(false);try {
                limits(c);String agent=scalar(c,"SELECT agent_id FROM pudding_cleanup_checks WHERE session_id=?",id);var b=binding(c,agent);
                String tenant=b==null?null:(String)b.get("tenant_id");if(tenant!=null)lockAccount(c,tenant);
                var store=core(agent,null);var policy=store.lockActor(c);var state=store.load(c,id,false);
                boolean due="true".equals(scalar(c,"SELECT (NOT resolved AND due_at<=? AND NOT (SELECT cleanup_hold FROM pudding_maintenance WHERE singleton))::text FROM pudding_cleanup_checks WHERE session_id=?",at(clock.instant()),id));
                if(due&&state!=null&&state.serving()==Core.Serving.CLEANUP_REQUIRED) {
                    String obligation="cleanup:"+id;
                    boolean reviewed=scalar(c,"SELECT obligation_id FROM pudding_policy_reviews WHERE agent_id=? AND obligation_id=?",agent,obligation)!=null;
                    if(!reviewed&&!policy.locked()&&!policy.obligations().contains(obligation)) {
                        var next=policy.violate(obligation);update(c,"UPDATE pudding_agents SET policy=? WHERE id=?",Codec.policy(next),agent);
                        if(next.locked())update(c,"UPDATE pudding_grants SET active=false WHERE agent_id=?",agent);
                        audit(c,tenant,"maintenance","cleanup:"+id,clock.instant(),Map.of("obligation_id",obligation,"agent_id",agent,"policy",DomainJson.policy(next,agent)));count++;
                    }
                }
                if(due)update(c,"UPDATE pudding_cleanup_checks SET checked=true WHERE session_id=?",id);
                c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
        return count;
    }
    public void dailyCompleted(LocalDate date)throws SQLException {
        try(var c=db.open()){update(c,"UPDATE pudding_maintenance SET last_daily_date=? WHERE singleton",java.sql.Date.valueOf(date));}
    }
    /** Bounded, restartable catch-up. A disposed/refunded asset never becomes usable again. */
    public int expireBatch(int limit)throws SQLException {
        if(limit<1||limit>100)throw new IllegalArgumentException("batch size");var targets=new ArrayList<UUID>();
        try(var c=db.open();var s=statement(c,"SELECT id FROM pudding_assets WHERE dispose_after<=? AND data->>'status'<>'disposed' ORDER BY dispose_after,id LIMIT ?",at(clock.instant()),limit);var r=s.executeQuery()){while(r.next())targets.add(r.getObject(1,UUID.class));}
        int processed=0;
        for(UUID id:targets)try(var c=db.open()) {
            c.setAutoCommit(false);try {
                limits(c);String tenant=scalar(c,"SELECT tenant_id FROM pudding_assets WHERE id=?",id);lockAccount(c,tenant);Instant now=clock.instant();clockGuard(c,tenant,now);
                var a=asset(c,tenant,id);
                if(!age(a,now).status().equals("disposal_due")||"disposed".equals(a.get("status"))){c.commit();continue;}
                discard(c,a,"monthly_expiry",now);
                audit(c,tenant,"maintenance","expiry:"+id,now,Map.of("asset_id",id.toString(),"disposal",a.get("disposal")));c.commit();processed++;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
        return processed;
    }
    /** Owner-only resumption; a tenant's human credential cannot change global policy. */
    public void resumeCleanup(String reason)throws SQLException {
        text(Map.of("reason",reason),"reason",512);
        try(var c=db.open()) {c.setAutoCommit(false);try {
            limits(c);Instant now=clock.instant();
            update(c,"UPDATE pudding_cleanup_checks SET due_at=GREATEST(due_at,?),checked=false WHERE NOT resolved",at(now.plusSeconds(600)));
            update(c,"UPDATE pudding_maintenance SET cleanup_hold=false WHERE singleton");
            audit(c,null,"operator","resume:"+UUID.randomUUID(),now,Map.of("reason",reason,"grace_seconds",600));c.commit();
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    /** Heartbeats detect uncertain downtime; overdue cleanup is held for human review. */
    public void heartbeat()throws SQLException {
        try(var c=db.open()) {
            c.setAutoCommit(false);try {
                limits(c);String last=scalar(c,"SELECT heartbeat FROM pudding_maintenance WHERE singleton FOR UPDATE");
                boolean gap=last==null;
                if(last!=null)try(var s=statement(c,"SELECT heartbeat < ? OR heartbeat > ? FROM pudding_maintenance WHERE singleton",at(clock.instant().minusSeconds(90)),at(clock.instant()));var r=s.executeQuery()){r.next();gap=r.getBoolean(1);}
                update(c,"UPDATE pudding_maintenance SET heartbeat=?,cleanup_hold=cleanup_hold OR ? WHERE singleton",at(clock.instant()),gap);c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
}
