package net.flamoris.pudding;

import java.time.*;
import java.util.*;
import static net.flamoris.pudding.Core.*;

/** Synthetic fixture/demo only. Production persistence is PostgresStore. */
public final class MemoryStore {
    private record Saved(byte[] command,Response response){}
    private final Map<UUID,Saved> requests=new HashMap<>();
    private final Map<UUID,State> sessions=new HashMap<>();
    private State active;private Policy policy;private final Clock clock;private final Lifecycle.Deadlines fixture;
    public MemoryStore(Clock clock,int threshold,Instant fixturePurchase){this.clock=clock;policy=new Policy(threshold,Set.of());fixture=fixturePurchase==null?null:Lifecycle.deadlines(fixturePurchase);}
    public synchronized Response execute(Command c){
        var payload=Codec.command(c);var replay=requests.get(c.request());
        if(replay!=null)return Arrays.equals(payload,replay.command)?replay.response:new Response(false,"REQUEST_ID_REUSED",null,policy,false);
        var old=c.op()==Op.CREATE?active:sessions.get(c.session());
        var next=Core.apply(old,policy,c,clock.instant(),fixture);policy=next.policy();
        if(next.response().ok()){sessions.put(next.state().id(),next.state());active=next.state();}
        requests.put(c.request(),new Saved(payload,next.response()));return next.response();
    }
    public synchronized State read(UUID id){return sessions.get(id);}
    public synchronized State expire(UUID id){var old=sessions.get(id);var next=Core.expire(old,clock.instant());if(next!=null){sessions.put(id,next);if(active!=null&&active.id().equals(id))active=next;}return next;}
}
