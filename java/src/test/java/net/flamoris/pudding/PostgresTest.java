package net.flamoris.pudding;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static net.flamoris.pudding.Core.*;
import static net.flamoris.pudding.CoreTest.*;

/** Runs only against an explicitly opted-in, disposable empty test database. */
public final class PostgresTest {
    static PostgresStore.Connections db;
    static PostgresStore store(String actor,Clock clock,Instant purchase){return new PostgresStore(db,actor,clock,purchase);}
    static State create(PostgresStore s)throws SQLException{return s.execute(Command.create(UUID.randomUUID(),null)).session();}
    static State opened(PostgresStore s)throws SQLException{var state=create(s);state=s.execute(action(Op.PEEL,state,1000,0,0)).session();return s.execute(action(Op.LID_DISPOSE,state,0,0,0)).session();}
    @FunctionalInterface interface SqlTest{void run()throws Exception;}
    static int count;
    static void scenario(String name,SqlTest f)throws Exception{f.run();count++;System.out.println("PASS PostgreSQL "+name);}
    static void sql(String value)throws SQLException{try(var c=db.open();var s=c.createStatement()){s.execute(value);}}
    static long count(String table,String actor)throws SQLException{
        // Callers use fixed test table literals, never user input.
        if(!Set.of("pudding_events","pudding_requests","pudding_sessions").contains(table))throw new IllegalArgumentException();
        try(var c=db.open();var s=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE agent_id=?")){s.setString(1,actor);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
    }
    static List<Response> concurrent(Callable<Response> a,Callable<Response> b)throws Exception{
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<Response> first=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return a.call();};
            Callable<Response> second=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return b.call();};
            var x=pool.submit(first);var y=pool.submit(second);check(ready.await(10,TimeUnit.SECONDS));start.countDown();return List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();check(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    public static void main(String[] args)throws Exception{
        var env=System.getenv();
        if(!"true".equals(env.get("PUDDING_TEST_ALLOW_SCHEMA")))throw new IllegalStateException("explicit disposable database opt-in required");
        String url=Objects.requireNonNull(env.get("PUDDING_TEST_JDBC_URL"));if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("PostgreSQL required");
        var props=new Properties();props.setProperty("user",Objects.requireNonNull(env.get("PUDDING_TEST_DB_USER")));props.setProperty("password",Objects.requireNonNull(env.get("PUDDING_TEST_DB_PASSWORD")));
        props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","15");
        db=()->DriverManager.getConnection(url,props);
        var clock=new MutableClock();
        scenario("explicit atomic schema migration",()->{
            PostgresStore.migrate(db);
            try{PostgresStore.migrate(db);throw new AssertionError("adopted existing schema");}catch(SQLException expected){}
        });
        scenario("restart replay and payload fence",()->{
            String actor="restart-"+UUID.randomUUID();var s=store(actor,clock,null);var command=Command.create(UUID.randomUUID(),null);var first=s.execute(command);
            s.execute(action(Op.PEEL,first.session(),1000,0,0));
            var restarted=store(actor,clock,null);equal(restarted.execute(command),first);equal(restarted.read(first.session().id()).state().revision(),1);
            equal(restarted.execute(Command.create(command.request(),"custard-firmer-v0")).error(),"REQUEST_ID_REUSED");equal(count("pudding_sessions",actor),1L);
        });
        scenario("real full serving and immutable snapshots",()->{
            var s=store("flow-"+UUID.randomUUID(),clock,null);var rows=Main.demo(c->{try{return s.execute(c);}catch(SQLException e){throw new IllegalStateException(e);}});
            equal(rows.getLast().get("revision"),7);equal(rows.getLast().get("session_stage"),"closed");
        });
        scenario("another principal cannot read or mutate",()->{
            var s=store("owner-"+UUID.randomUUID(),clock,null);var state=create(s);var other=store("other-"+UUID.randomUUID(),clock,null);
            equal(other.read(state.id()),null);var r=other.execute(action(Op.PEEL,state,1000,0,0));equal(r.error(),"NOT_FOUND");equal(r.policy().obligations().size(),0);equal(s.read(state.id()).state(),state);
        });
        scenario("simultaneous same request has one durable result",()->{
            String actor="same-"+UUID.randomUUID();var command=Command.create(UUID.randomUUID(),null);
            var results=concurrent(()->store(actor,clock,null).execute(command),()->store(actor,clock,null).execute(command));
            equal(results.get(0),results.get(1));check(results.getFirst().ok());equal(count("pudding_sessions",actor),1L);equal(count("pudding_events",actor),1L);
        });
        scenario("simultaneous revision has one winner",()->{
            String actor="revision-"+UUID.randomUUID();var s=store(actor,clock,null);var state=opened(s);
            var a=action(Op.SCOOP,state,0,1000,0);var b=action(Op.SCOOP,state,0,2000,0);
            var results=concurrent(()->store(actor,clock,null).execute(a),()->store(actor,clock,null).execute(b));
            equal(results.stream().filter(Response::ok).count(),1L);equal(results.stream().filter(r->!r.ok()).findFirst().orElseThrow().error(),"REVISION_CONFLICT");
            equal(s.read(state.id()).state(),results.stream().filter(Response::ok).findFirst().orElseThrow().session());
        });
        scenario("failed audit rolls back violation and replay fence",()->{
            String actor="rollback-"+UUID.randomUUID();var s=store(actor,clock,null);var state=create(s);state=s.execute(action(Op.PEEL,state,1000,0,0)).session();
            var command=action(Op.SCOOP,state,0,1000,0);long before=count("pudding_requests",actor);
            sql("CREATE FUNCTION pudding_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic audit failure'; END; $$");
            sql("CREATE TRIGGER pudding_test_reject BEFORE INSERT ON pudding_events FOR EACH ROW EXECUTE FUNCTION pudding_test_reject()");
            try{
                try{s.execute(command);throw new AssertionError("audit failure accepted");}catch(SQLException expected){}
                equal(s.read(state.id()).policy().obligations().size(),0);equal(s.read(state.id()).state(),state);equal(count("pudding_requests",actor),before);
            }finally{sql("DROP TRIGGER pudding_test_reject ON pudding_events");sql("DROP FUNCTION pudding_test_reject()");}
            var result=s.execute(command);equal(result.error(),"LID_NOT_DISPOSED");equal(result.policy().obligations().size(),1);equal(store(actor,clock,null).execute(command),result);
        });
        scenario("expired lockout survives restart and allows cleanup",()->{
            var c=new MutableClock();String actor="locked-"+UUID.randomUUID();var s=store(actor,c,NOW);var state=opened(s);c.now=NOW.plusSeconds(15*86400);
            for(int n=0;n<2;n++){state=s.execute(action(Op.SCOOP,state,0,1000,0)).session();state=s.execute(action(Op.TASTE,state,0,0,0)).session();}
            state=s.execute(action(Op.SCOOP,state,0,1000,0)).session();equal(s.execute(action(Op.TASTE,state,0,0,0)).error(),"LOCKED");
            var restarted=store(actor,c,NOW);equal(restarted.read(state.id()).state().body().consumed(),2000);equal(restarted.read(state.id()).policy().status(),"locked");
            state=restarted.execute(action(Op.FINISH,state,0,0,0)).session();check(restarted.execute(action(Op.CLEANUP,state,0,0,0)).ok());
        });
        scenario("hard expiry reads are pure and disposal is idempotent",()->{
            var c=new MutableClock();String actor="expiry-"+UUID.randomUUID();var s=store(actor,c,NOW);var state=opened(s);state=s.execute(action(Op.SCOOP,state,0,8000,2000)).session();
            c.now=state.deadlines().disposeAfter();long before=count("pudding_events",actor);equal(s.read(state.id()).aging().status(),"disposal_due");equal(count("pudding_events",actor),before);
            equal(s.execute(action(Op.TASTE,state,0,0,0)).error(),"DISPOSAL_DUE");c.now=c.now.plusSeconds(86400);
            var expired=s.expire(state.id());equal(expired.body().discarded(),90000);equal(expired.sauce().discarded(),10000);
            before=count("pudding_events",actor);equal(store(actor,c,NOW).expire(state.id()),expired);equal(count("pudding_events",actor),before);equal(expired.serving(),Serving.CLEANUP_REQUIRED);
        });
        scenario("unsupported schema fails closed",()->{
            sql("UPDATE pudding_config SET schema_version=99 WHERE singleton");
            try{try{store("invalid-version",clock,null).execute(Command.create(UUID.randomUUID(),null));throw new AssertionError("schema accepted");}catch(SQLException expected){}}
            finally{sql("UPDATE pudding_config SET schema_version=1 WHERE singleton");}
        });
        System.out.println("PostgreSQL integration scenarios: "+count);
    }
}
