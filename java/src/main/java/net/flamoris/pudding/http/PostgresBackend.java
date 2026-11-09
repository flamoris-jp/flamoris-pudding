package net.flamoris.pudding.http;

import java.sql.*;
import java.time.*;
import java.util.*;
import net.flamoris.pudding.*;

public final class PostgresBackend implements Backend {
    private final PostgresStore.Connections connections;
    private final Clock clock;
    private final CommerceStore sandbox;
    private volatile long readinessExpires;
    private volatile boolean lastReadiness;
    public PostgresBackend(PostgresStore.Connections connections,Clock clock){this(connections,clock,null);}
    public PostgresBackend(PostgresStore.Connections connections,Clock clock,CommerceStore sandbox){this.connections=Objects.requireNonNull(connections);this.clock=Objects.requireNonNull(clock);this.sandbox=sandbox;}
    @Override public boolean sandboxEnabled(){return sandbox!=null;}
    @Override public Map<String,Object> sandboxCall(AgentCredentials.Identity identity,String name,Map<String,Object> input)throws Exception {
        if(sandbox==null||name.equals("pudding.presets.list"))return null;
        if(!ready())throw new SQLException("database not ready");
        return sandbox.agentCall(identity.agentId(),identity.credentialId(),name,input);
    }
    private PostgresStore store(String agent){return new PostgresStore(connections,agent,clock,null);}
    @Override public Core.Response execute(String agent,Core.Command c)throws SQLException{if(!ready())throw new SQLException("database not ready");return store(agent).execute(c);}
    @Override public View read(String agent,UUID id)throws SQLException {if(!ready())throw new SQLException("database not ready");var view=store(agent).read(id);return view==null?null:new View(view.state(),view.policy());}
    @Override public Instant now(){return clock.instant();}
    @Override public synchronized boolean ready(){
        if(readinessExpires!=0&&System.nanoTime()-readinessExpires<0)return lastReadiness;
        boolean value=probe();lastReadiness=value;readinessExpires=System.nanoTime()+3_000_000_000L;return value;
    }
    private boolean probe(){
        try(var c=connections.open();var s=c.prepareStatement("SELECT schema_version,rules_version,threshold FROM pudding_config WHERE singleton")){
            if(c.getMetaData().getDatabaseMajorVersion()<18)return false;
            s.setQueryTimeout(5);
            try(var r=s.executeQuery()){if(!r.next()||(r.getInt(1)!=1&&r.getInt(1)!=2)||!Core.RULES.equals(r.getString(2))||r.getInt(3)<1||r.getInt(3)>100)return false;}
            if(sandbox!=null) {
                try(var q=c.createStatement();var r=q.executeQuery("SELECT schema_version=2 AND has_table_privilege(current_user,'pudding_accounts','UPDATE') AND has_table_privilege(current_user,'pudding_assets','UPDATE') AND has_table_privilege(current_user,'pudding_api_requests','INSERT') AND has_table_privilege(current_user,'pudding_candidates','INSERT') FROM pudding_config WHERE singleton")){if(!r.next()||!r.getBoolean(1))return false;}
            }
            // Probe required columns without writing a row or creating an Agent.
            try(var q=c.createStatement()) {
                q.setQueryTimeout(5);q.executeQuery("SELECT a.id,a.policy,s.id,s.state,s.revision,s.closed,r.command,r.response,e.event_key FROM pudding_agents a LEFT JOIN pudding_sessions s ON s.agent_id=a.id LEFT JOIN pudding_requests r ON r.agent_id=a.id LEFT JOIN pudding_events e ON e.agent_id=a.id WHERE false").close();
            }
            try(var q=c.createStatement()) {
                q.setQueryTimeout(5);
                try(var r=q.executeQuery("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR has_schema_privilege(current_user,'public','CREATE') OR pg_has_role(current_user,pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid='pudding_config'::regclass)),'USAGE') FROM pg_roles WHERE rolname=current_user")) {
                    if(!r.next()||r.getBoolean(1))return false;
                }
                try(var r=q.executeQuery("SELECT has_table_privilege(current_user,'pudding_agents','INSERT') AND has_table_privilege(current_user,'pudding_agents','UPDATE') AND has_table_privilege(current_user,'pudding_sessions','INSERT') AND has_table_privilege(current_user,'pudding_sessions','UPDATE') AND has_table_privilege(current_user,'pudding_requests','INSERT') AND has_table_privilege(current_user,'pudding_events','INSERT') AND has_sequence_privilege(current_user,'pudding_events_id_seq','USAGE')")) {
                    if(!r.next()||!r.getBoolean(1))return false;
                }
            }
            return true;
        }catch(SQLException|RuntimeException e){return false;}
    }
}
