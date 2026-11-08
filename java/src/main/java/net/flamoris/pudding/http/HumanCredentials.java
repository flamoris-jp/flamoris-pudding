package net.flamoris.pudding.http;

import java.util.*;
import net.flamoris.pudding.*;

/** Separately provisioned management credentials for sandbox operators, not public login. */
public final class HumanCredentials {
    private final AgentCredentials credentials;
    private final Map<String,CommerceStore.Human> humans;
    public HumanCredentials(byte[] bytes,AgentCredentials agents) {
        Object parsed=JsonInput.parse(bytes);
        if(!(parsed instanceof List<?> list)||list.isEmpty()||list.size()>64)throw new IllegalArgumentException("human credentials");
        var entries=new ArrayList<Object>();var owners=new HashMap<String,CommerceStore.Human>();
        for(Object raw:list) {
            var m=Input.object(raw);Input.fields(m,"tenant_id human_id token","");
            String human=Input.id(m,"human_id"),tenant=Input.id(m,"tenant_id"),token=Input.text(m,"token",128);
            if(agents.authenticate("Bearer "+token)!=null)throw new IllegalArgumentException("human and Agent credentials must be disjoint");
            var identity=new CommerceStore.Human(tenant,human);
            if(owners.containsKey(human)&&!owners.get(human).equals(identity))throw new IllegalArgumentException("human tenant mismatch");
            owners.put(human,identity);entries.add(Map.of("agent_id",human,"token",token));
        }
        credentials=new AgentCredentials(Json.write(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));humans=Map.copyOf(owners);
    }
    public CommerceStore.Human authenticate(String header){var identity=credentials.authenticate(header);return identity==null?null:humans.get(identity.agentId());}
    @Override public String toString(){return "HumanCredentials[redacted]";}
}
