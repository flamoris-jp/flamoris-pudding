package net.flamoris.pudding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import static net.flamoris.pudding.Core.*;

/** JDBC adapter. Principal and fixture clock come from trusted process configuration.
 * Runtime owns DML only; migration is an explicit separate management action.
 */
public final class PostgresStore {
    @FunctionalInterface public interface Connections {Connection open()throws SQLException;}
    private final Connections connections;private final String actor;private final Clock clock;
    private final Lifecycle.Deadlines fixture;
    public PostgresStore(Connections connections,String actor,Clock clock,Instant fixturePurchase){
        if(actor==null||!actor.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("configured synthetic principal");
        this.connections=Objects.requireNonNull(connections);this.actor=actor;this.clock=Objects.requireNonNull(clock);
        fixture=fixturePurchase==null?null:Lifecycle.deadlines(fixturePurchase);
    }
    public static void migrate(Connections connections)throws SQLException,IOException {
        String sql;
        try(var stream=PostgresStore.class.getResourceAsStream("/db/V001__research.sql")){
            if(stream==null)throw new IOException("missing migration");sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        try(var c=connections.open()){
            c.setAutoCommit(false);
            try(var s=c.createStatement()){
                s.execute("SET LOCAL statement_timeout='30s'");s.execute("SET LOCAL lock_timeout='5s'");
                // One-time bootstrap only: never silently adopts an existing schema.
                s.execute(sql);c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    /** Explicit owner-side grants for an existing, unprivileged login role. */
    public static void grantRuntime(Connections connections,String role)throws SQLException {
        if(role==null||!role.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("runtime role");
        try(var c=connections.open()) {
            c.setAutoCommit(false);
            try {
                limits(c);
                try(var s=c.prepareStatement("SELECT rolsuper,rolcreatedb,rolcreaterole,rolcanlogin FROM pg_roles WHERE rolname=?")) {
                    s.setString(1,role);try(var r=s.executeQuery()){if(!r.next()||r.getBoolean(1)||r.getBoolean(2)||r.getBoolean(3)||!r.getBoolean(4))throw new SQLException("runtime role must be an unprivileged existing login");}
                }
                try(var s=c.createStatement()) {
                    String target="\""+role+"\"";
                    s.execute("GRANT USAGE ON SCHEMA public TO "+target);
                    s.execute("GRANT SELECT ON pudding_config TO "+target);
                    s.execute("GRANT SELECT,INSERT,UPDATE ON pudding_agents,pudding_sessions TO "+target);
                    s.execute("GRANT SELECT,INSERT ON pudding_requests,pudding_events TO "+target);
                    s.execute("GRANT USAGE ON SEQUENCE pudding_events_id_seq TO "+target);
                }
                c.commit();
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    private int config(Connection c)throws SQLException {
        try(var s=c.prepareStatement("SELECT schema_version,rules_version,threshold FROM pudding_config WHERE singleton")){
            try(var r=s.executeQuery()){
                if(!r.next()||(r.getInt(1)!=1&&r.getInt(1)!=2)||!RULES.equals(r.getString(2)))throw new SQLException("unsupported pudding schema/rules");
                int threshold=r.getInt(3);if(threshold<1||threshold>100)throw new SQLException("invalid policy");return threshold;
            }
        }
    }
    private static void limits(Connection c)throws SQLException {try(var s=c.createStatement()){s.execute("SET LOCAL lock_timeout='5s'");s.execute("SET LOCAL statement_timeout='10s'");s.execute("SET LOCAL transaction_timeout='12s'");}}
    Policy lockActor(Connection c)throws SQLException {
        int threshold=config(c);
        try(var s=c.prepareStatement("INSERT INTO pudding_agents(id,policy) VALUES(?,?) ON CONFLICT DO NOTHING")){
            s.setString(1,actor);s.setBytes(2,Codec.policy(new Policy(threshold,Set.of())));s.executeUpdate();
        }
        try(var s=c.prepareStatement("SELECT policy FROM pudding_agents WHERE id=? FOR UPDATE")){
            s.setString(1,actor);try(var r=s.executeQuery()){
                if(!r.next())throw new SQLException("missing principal");var p=Codec.policy(r.getBytes(1));
                if(p.threshold()!=threshold)throw new SQLException("policy mismatch");return p;
            }
        }
    }
    State load(Connection c,UUID id,boolean unresolved)throws SQLException {
        String sql=unresolved?"SELECT state FROM pudding_sessions WHERE agent_id=? AND NOT closed":"SELECT state FROM pudding_sessions WHERE agent_id=? AND id=?";
        try(var s=c.prepareStatement(sql)){s.setString(1,actor);if(!unresolved)s.setObject(2,id);try(var r=s.executeQuery()){return r.next()?Codec.state(r.getBytes(1)):null;}}
    }
    public Response execute(Command command)throws SQLException {
        try(var c=connections.open()) {
            c.setAutoCommit(false);
            try {var result=executeInTransaction(c,command,false);c.commit();return result;}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    static int schemaVersion(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT schema_version FROM pudding_config WHERE singleton")){
            if(!r.next())throw new SQLException("missing schema");return r.getInt(1);
        }
    }
    Response executeInTransaction(Connection c,Command command,boolean premiumAuthorized)throws SQLException {
        Objects.requireNonNull(command);byte[] payload=Codec.command(command);
                limits(c);Policy policy=lockActor(c);
                try(var s=c.prepareStatement("SELECT command,response FROM pudding_requests WHERE agent_id=? AND request_id=?")){
                    s.setString(1,actor);s.setObject(2,command.request());try(var r=s.executeQuery()){
                        if(r.next()){
                            var response=Arrays.equals(payload,r.getBytes(1))?Codec.response(r.getBytes(2)):new Response(false,"REQUEST_ID_REUSED",null,policy,false);
                            return response;
                        }
                    }
                }
                if(!premiumAuthorized&&command.session()!=null&&schemaVersion(c)==2) {
                    try(var s=c.prepareStatement("SELECT 1 FROM pudding_assets WHERE session_id=?")) {
                        s.setObject(1,command.session());try(var r=s.executeQuery()){if(r.next())return new Response(false,"PREMIUM_ROUTE_REQUIRED",null,policy,false);}
                    }
                }
                Instant now=clock.instant();State old=load(c,command.session(),command.op()==Op.CREATE);
                var transition=Core.apply(old,policy,command,now,fixture);
                if(schemaVersion(c)==2&&transition.response().policyChanged()) {
                    var active=new HashSet<>(transition.policy().obligations());
                    try(var s=c.prepareStatement("SELECT obligation_id FROM pudding_policy_reviews WHERE agent_id=?")) {
                        s.setString(1,actor);try(var r=s.executeQuery()){while(r.next())active.remove(r.getString(1));}
                    }
                    var corrected=new Policy(transition.policy().threshold(),active);var response=transition.response();
                    transition=new Transition(transition.state(),corrected,new Response(response.ok(),response.error(),response.session(),corrected,!corrected.equals(policy)));
                }
                if(transition.response().ok())save(c,transition.state());
                if(!transition.policy().equals(policy)) {
                    try(var s=c.prepareStatement(schemaVersion(c)==2?"UPDATE pudding_agents SET policy=?,enforcement_revision=enforcement_revision+1 WHERE id=?":"UPDATE pudding_agents SET policy=? WHERE id=?")){
                        s.setBytes(1,Codec.policy(transition.policy()));s.setString(2,actor);s.executeUpdate();
                    }
                }
                byte[] response=Codec.response(transition.response());
                try(var s=c.prepareStatement("INSERT INTO pudding_requests VALUES(?,?,?,?)")){
                    s.setString(1,actor);s.setObject(2,command.request());s.setBytes(3,payload);s.setBytes(4,response);s.executeUpdate();
                }
                event(c,command.request().toString(),now,response);return transition.response();
    }
    void save(Connection c,State state)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO pudding_sessions VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET revision=EXCLUDED.revision,closed=EXCLUDED.closed,state=EXCLUDED.state WHERE pudding_sessions.agent_id=EXCLUDED.agent_id")){
            s.setObject(1,state.id());s.setString(2,actor);s.setInt(3,state.revision());s.setBoolean(4,state.serving()==Serving.CLOSED);s.setBytes(5,Codec.state(state));
            if(s.executeUpdate()!=1)throw new SQLException("session ownership conflict");
        }
    }
    void event(Connection c,String key,Instant now,byte[] response)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO pudding_events(agent_id,event_key,occurred_at,response) VALUES(?,?,?,?)")){
            s.setString(1,actor);s.setString(2,key);s.setObject(3,now.atOffset(ZoneOffset.UTC));s.setBytes(4,response);s.executeUpdate();
        }
    }
    public record View(State state,Policy policy,Lifecycle.Age aging){}
    public View read(UUID id)throws SQLException {
        try(var c=connections.open()){
            c.setAutoCommit(false);c.setReadOnly(true);
            try {
                limits(c);config(c);
                try(var s=c.prepareStatement("SELECT s.state,a.policy FROM pudding_sessions s JOIN pudding_agents a ON a.id=s.agent_id WHERE s.id=? AND s.agent_id=?")){
                    s.setObject(1,id);s.setString(2,actor);try(var r=s.executeQuery()){
                        View view=null;if(r.next()){var state=Codec.state(r.getBytes(1));view=new View(state,Codec.policy(r.getBytes(2)),state.deadlines()==null?null:Lifecycle.age(state.deadlines(),clock.instant()));}
                        c.commit();return view;
                    }
                }
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    /** Operator-invoked fixture disposal, owner-scoped; no scheduler or financial event. */
    public State expire(UUID id)throws SQLException {
        try(var c=connections.open()){
            c.setAutoCommit(false);
            try {
                limits(c);var policy=lockActor(c);var old=load(c,id,false);Instant now=clock.instant();var next=Core.expire(old,now);
                if(next!=null&&!next.equals(old)){save(c,next);event(c,"expiry:"+id,now,Codec.response(new Response(true,null,next,policy,false)));}
                c.commit();return next;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
}
