package net.flamoris.pudding.http;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.flamoris.pudding.JsonInput;

/** Operator-provisioned bearer-to-stable-Agent binding, independent of Hub. */
public final class AgentCredentials {
    public record Identity(String agentId, String credentialId) {}
    private record Entry(Identity identity,byte[] digest) {}
    private final List<Entry> entries;
    public AgentCredentials(byte[] json) {
        if (json.length > 65536) throw new IllegalArgumentException("credential configuration size");
        Object parsed=JsonInput.parse(json);
        if (!(parsed instanceof List<?> list)||list.isEmpty()||list.size()>64) throw new IllegalArgumentException("credential configuration");
        var values=new ArrayList<Entry>();var ids=new HashSet<String>();
        for (Object raw:list) {
            if (!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("agent_id","token"))||
                !(m.get("agent_id") instanceof String agent)||!agent.matches("[A-Za-z0-9_.-]{1,80}")||
                !(m.get("token") instanceof String token)||!token.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("credential configuration");
            byte[] digest=hash(token);String id=HexFormat.of().formatHex(digest);
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate credential");
            values.add(new Entry(new Identity(agent,id),digest));
        }
        entries=List.copyOf(values);
    }
    public Identity authenticate(String header) {
        if(header==null||!header.startsWith("Bearer "))return null;
        String token=header.substring(7);
        if(!token.matches("[A-Za-z0-9_-]{43,128}"))return null;
        byte[] candidate=hash(token);Identity result=null;
        for(Entry entry:entries) if(MessageDigest.isEqual(entry.digest,candidate))result=entry.identity;
        return result;
    }
    private static byte[] hash(String token) {
        try {return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public String toString(){return "AgentCredentials[redacted]";}
}
