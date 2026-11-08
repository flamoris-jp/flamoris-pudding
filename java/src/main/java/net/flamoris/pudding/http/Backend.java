package net.flamoris.pudding.http;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import net.flamoris.pudding.Core;

/** The adapter receives only an identity resolved by AgentCredentials. */
public interface Backend {
    record View(Core.State state,Core.Policy policy) {}
    Core.Response execute(String agentId,Core.Command command) throws Exception;
    View read(String agentId,UUID sessionId) throws Exception;
    boolean ready();
    Instant now();
    default boolean sandboxEnabled(){return false;}
    default Map<String,Object> sandboxCall(AgentCredentials.Identity identity,String name,Map<String,Object> input)throws Exception {return null;}
}
