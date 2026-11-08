package net.flamoris.pudding.http;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import net.flamoris.pudding.*;

/** Streamable HTTP JSON profile; no SSE, OAuth, sampling, tasks or batch support. */
public final class McpEndpoint {
    public static final String VERSION="2025-11-25";
    private final AgentCredentials credentials;
    private final PuddingTools tools;
    private final Backend backend;
    private final Set<String> hosts,origins;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Session> sessions=new HashMap<>();
    private long activitySequence;
    private final Duration lifetime;
    private static final class Session {
        final AgentCredentials.Identity identity;final Instant expires;final Set<String> ids=new HashSet<>();volatile boolean ready;
        // Leases and recency are guarded by sessions; never acquire a Session monitor there.
        int inFlight;long lastUsed;
        Session(AgentCredentials.Identity identity,Instant expires,Object firstId){this.identity=identity;this.expires=expires;ids.add(idKey(firstId));}
    }
    private static final class RpcFailure extends Exception {
        final int code;final String message;
        RpcFailure(int code,String message){this.code=code;this.message=message;}
    }
    public McpEndpoint(AgentCredentials credentials,Backend backend,Set<String> hosts,Set<String> origins,Clock clock,Duration lifetime) {
        this.credentials=Objects.requireNonNull(credentials);this.backend=Objects.requireNonNull(backend);tools=new PuddingTools(backend);
        if(hosts.isEmpty()||hosts.size()>16||origins.size()>16||lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofMinutes(30))>0)throw new IllegalArgumentException("endpoint configuration");
        for(String host:hosts)if(host.isBlank()||host.length()>253||host.chars().anyMatch(c->c<=32||c>126)||host.contains("/")||host.contains("@"))throw new IllegalArgumentException("allowed host");
        for(String origin:origins) {
            var uri=java.net.URI.create(origin);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().isEmpty())throw new IllegalArgumentException("allowed HTTPS origin");
        }
        this.hosts=Set.copyOf(hosts.stream().map(s->s.toLowerCase(Locale.ROOT)).toList());this.origins=Set.copyOf(origins);this.clock=clock;this.lifetime=lifetime;
    }
    public Reply handle(HttpRequest request) {
        Session leased=null;
        try {
        String host=request.headers().getOrDefault("host","").toLowerCase(Locale.ROOT);
        if(!hosts.contains(host))return Reply.refused(403);
        String origin=request.headers().get("origin");
        if(origin!=null&&!origins.contains(origin))return Reply.refused(403);
        if(request.target().equals("/health/live")||request.target().equals("/health/ready")) {
            if(request.method()!=HttpMethod.GET&&request.method()!=HttpMethod.HEAD)return Reply.refused(405).header("Allow","GET, HEAD");
            boolean ready=request.target().equals("/health/live")||backend.ready();return Reply.json(ready?200:503,Map.of("status",ready?"ok":"not_ready"));
        }
        if(!request.target().equals("/mcp"))return Reply.refused(404);
        var identity=credentials.authenticate(request.headers().get("authorization"));
        if(identity==null)return Reply.refused(401).header("WWW-Authenticate","Bearer realm=\"pudding\"");
        if(request.method()==HttpMethod.GET)return Reply.refused(405).header("Allow","POST, DELETE");
        if(request.method()!=HttpMethod.POST&&request.method()!=HttpMethod.DELETE)return Reply.refused(405).header("Allow","POST, DELETE");
        if(request.method()==HttpMethod.DELETE) {
            Session session=acquire(request,identity);leased=session;
            if(session==null)return Reply.refused(404);
            if(!VERSION.equals(request.headers().get("mcp-protocol-version")))return Reply.refused(400);
            synchronized(sessions){sessions.remove(request.headers().get("mcp-session-id"));}
            return Reply.empty(204);
        }
        String accept=request.headers().getOrDefault("accept","").toLowerCase(Locale.ROOT);
        var media=new HashSet<String>();for(String part:accept.split(","))media.add(part.strip().split(";",2)[0]);
        if(!media.contains("application/json")||!media.contains("text/event-stream"))return Reply.refused(406);
        Object raw;
        try {raw=JsonInput.parse(request.body());}catch(IllegalArgumentException e){return rpc(null,-32700,"Parse error");}
        Object id=null;boolean notification=false;
        try {
            if(!(raw instanceof Map<?,?> root)||!Set.of("jsonrpc","id","method","params").containsAll(root.keySet()))throw new RpcFailure(-32600,"Invalid request");
            Map<String,Object> message=objectAny(raw);
            if(!"2.0".equals(message.get("jsonrpc"))||!(message.get("method") instanceof String method)||method.isEmpty())throw new RpcFailure(-32600,"Invalid request");
            notification=!message.containsKey("id");id=message.get("id");
            if(!notification&&(!(id instanceof String)&&!(id instanceof BigInteger)||id instanceof String s&&s.length()>256||id instanceof BigInteger n&&n.bitLength()>63))throw new RpcFailure(-32600,"Invalid request");
            Map<String,Object> params=objectAny(message.getOrDefault("params",Map.of()));
            if(method.equals("initialize")) {
                if(notification)return Reply.empty(202);
                if(request.headers().containsKey("mcp-session-id"))return Reply.refused(400);
                object(params,Set.of("protocolVersion","capabilities","clientInfo","_meta"),Set.of("protocolVersion","capabilities","clientInfo"));
                if(!(params.get("protocolVersion") instanceof String v)||v.isEmpty()||!(params.get("capabilities") instanceof Map<?,?>)||!(params.get("clientInfo") instanceof Map<?,?> info)||
                    !(info.get("name") instanceof String n)||n.isEmpty()||n.length()>256||!(info.get("version") instanceof String version)||version.isEmpty()||version.length()>256)throw new RpcFailure(-32602,"Invalid initialization");
                String session=create(identity,id);
                if(session==null)return Reply.refused(429);
                return result(id,Map.of("protocolVersion",VERSION,"capabilities",Map.of("tools",Map.of("listChanged",false)),
                    "serverInfo",Map.of("name","flamoris-pudding","version",Version.VALUE),"instructions","Authenticated research servings only; no premium approval or payments.")).header("Mcp-Session-Id",session);
            }
            Session session=acquire(request,identity);leased=session;
            if(session==null)return Reply.refused(404);
            if(!VERSION.equals(request.headers().get("mcp-protocol-version")))return Reply.refused(400);
            // Notification processing never invokes domain actions.
            if(notification) {
                if(method.equals("notifications/initialized")){object(params,Set.of("_meta"),Set.of());synchronized(session){session.ready=true;}}
                return Reply.empty(202);
            }
            synchronized(session) {
                if(session.ids.size()>=1000)return Reply.refused(429);
                if(!session.ids.add(idKey(id)))throw new RpcFailure(-32600,"Request ID already used");
                if(method.equals("ping")){object(params,Set.of("_meta"),Set.of());return result(id,Map.of());}
                if(!session.ready)throw new RpcFailure(-32000,"Initialization incomplete");
            }
            if(method.equals("tools/list")) {
                object(params,Set.of("_meta"),Set.of());return result(id,Map.of("tools",tools.list()));
            }
            if(method.equals("tools/call")) {
                object(params,Set.of("name","arguments","_meta"),Set.of("name"));
                if(!(params.get("name") instanceof String name))throw new RpcFailure(-32602,"Invalid tool name");
                var args=objectAny(params.getOrDefault("arguments",Map.of()));
                Map<String,Object> domain;
                try{domain=tools.call(identity.agentId(),name,args);}catch(IllegalArgumentException e){throw new RpcFailure(-32602,"Invalid tool arguments");}
                return result(id,Map.of("content",List.of(Map.of("type","text","text",Json.write(domain))),"structuredContent",domain,"isError",!Boolean.TRUE.equals(domain.get("ok"))));
            }
            throw new RpcFailure(-32601,"Method not found");
        }catch(RpcFailure e){return notification?Reply.empty(202):rpc(id,e.code,e.message);}
        catch(Exception e){return notification?Reply.empty(202):rpc(id,-32603,"Internal error");}
        }finally {
            if(leased!=null)synchronized(sessions){leased.inFlight--;leased.lastUsed=++activitySequence;}
        }
    }
    private Session acquire(HttpRequest r,AgentCredentials.Identity identity) {
        String id=r.headers().get("mcp-session-id");if(id==null||!id.matches("[A-Za-z0-9_-]{43}"))return null;
        synchronized(sessions){
            Session s=sessions.get(id);if(s==null||!s.identity.equals(identity)||!clock.instant().isBefore(s.expires))return null;
            s.inFlight++;s.lastUsed=++activitySequence;return s;
        }
    }
    private static String idKey(Object id) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Json.write(id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private String create(AgentCredentials.Identity identity,Object firstId) {
        synchronized(sessions) {
            sessions.values().removeIf(s->s.inFlight==0&&!clock.instant().isBefore(s.expires));
            if(sessions.values().stream().filter(s->s.identity.agentId().equals(identity.agentId())).count()>=8) {
                // Short-lived clients may omit DELETE. Recycle only this Agent's oldest
                // initialized, idle transport; never another Agent or an active request.
                var idle=sessions.entrySet().stream().filter(e->e.getValue().identity.agentId().equals(identity.agentId())
                    &&e.getValue().ready&&e.getValue().inFlight==0)
                    .min(Comparator.comparingLong(e->e.getValue().lastUsed));
                if(idle.isEmpty())return null;
                sessions.remove(idle.get().getKey());
            }
            if(sessions.size()>=256)return null;
            byte[] bytes=new byte[32];String id;do{random.nextBytes(bytes);id=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}while(sessions.containsKey(id));
            var session=new Session(identity,clock.instant().plus(lifetime),firstId);session.lastUsed=++activitySequence;
            sessions.put(id,session);return id;
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> objectAny(Object value)throws RpcFailure {
        if(!(value instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw new RpcFailure(-32602,"Invalid parameters");
        return (Map<String,Object>)m;
    }
    private static Map<String,Object> object(Object value,Set<String> allowed,Set<String> required)throws RpcFailure {
        var m=objectAny(value);if(!allowed.containsAll(m.keySet())||!m.keySet().containsAll(required))throw new RpcFailure(-32602,"Invalid parameters");return m;
    }
    private static Reply result(Object id,Object value){return Reply.json(200,Map.of("jsonrpc","2.0","id",id,"result",value));}
    private static Reply rpc(Object id,int code,String message){var m=new LinkedHashMap<String,Object>();m.put("jsonrpc","2.0");m.put("id",id);m.put("error",Map.of("code",code,"message",message));return Reply.json(200,m);}
}

