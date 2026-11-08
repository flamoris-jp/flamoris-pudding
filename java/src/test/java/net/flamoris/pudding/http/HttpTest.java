package net.flamoris.pudding.http;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import net.flamoris.pudding.*;

/** Loopback protocol/adversarial tests using synthetic fixtures, never real Agents. */
public final class HttpTest {
    private static void check(boolean condition){if(!condition)throw new AssertionError("HTTP check failed");}
    private static void equal(Object actual,Object expected){if(!Objects.equals(actual,expected))throw new AssertionError(actual+" != "+expected);}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid input accepted");}
    private static final String TOKEN_A="A".repeat(43),TOKEN_B="B".repeat(43);
    private static final class TestBackend implements Backend {
        final Map<String,MemoryStore> stores=new HashMap<>();int calls;boolean available=true;
        synchronized MemoryStore store(String agent){return stores.computeIfAbsent(agent,k->new MemoryStore(Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"),ZoneOffset.UTC),3,null));}
        @Override public synchronized Core.Response execute(String agent,Core.Command c){calls++;return store(agent).execute(c);}
        @Override public View read(String agent,UUID id){var state=store(agent).read(id);return state==null?null:new View(state,new Core.Policy(3,Set.of()));}
        @Override public boolean ready(){return available;}
        @Override public Instant now(){return Instant.parse("2026-11-08T01:00:00Z");}
    }
    private static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-11-08T01:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId z){return this;}
        @Override public Instant instant(){return now;}
    }
    private static AgentCredentials credentials(){return new AgentCredentials(Json.write(List.of(Map.of("agent_id","fixture-a","token",TOKEN_A),Map.of("agent_id","fixture-b","token",TOKEN_B))).getBytes(StandardCharsets.UTF_8));}
    private static McpEndpoint endpoint(TestBackend backend,Clock clock){return new McpEndpoint(credentials(),backend,Set.of("localhost"),Set.of("https://pudding.example"),clock,Duration.ofMinutes(30));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(byte[] value){return (Map<String,Object>)JsonInput.parse(value);}
    private static HttpRequest request(Object body,String token,String session,String protocol){
        var h=new HashMap<String,String>();h.put("host","localhost");h.put("accept","application/json, text/event-stream");h.put("authorization","Bearer "+token);
        if(session!=null)h.put("mcp-session-id",session);if(protocol!=null)h.put("mcp-protocol-version",protocol);
        return new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",h,Json.write(body).getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,Object> rpc(int id,String method,Map<String,Object> params){return Map.of("jsonrpc","2.0","id",id,"method",method,"params",params);}
    private static Map<String,Object> init(){return rpc(1,"initialize",Map.of("protocolVersion",McpEndpoint.VERSION,"capabilities",Map.of(),"clientInfo",Map.of("name","test","version","1")));}
    private static String initialize(McpEndpoint e,String token){
        Reply r=e.handle(request(init(),token,null,null));equal(r.status(),200);String sid=r.headers().get("Mcp-Session-Id");check(sid!=null);
        Reply n=e.handle(request(Map.of("jsonrpc","2.0","method","notifications/initialized"),token,sid,McpEndpoint.VERSION));equal(n.status(),202);equal(n.body().length,0);return sid;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> call(McpEndpoint e,String token,String sid,int rpcId,String tool,Map<String,Object> args){
        var r=e.handle(request(rpc(rpcId,"tools/call",Map.of("name",tool,"arguments",args)),token,sid,McpEndpoint.VERSION));equal(r.status(),200);var result=(Map<String,Object>)object(r.body()).get("result");check(result!=null);return (Map<String,Object>)result.get("structuredContent");
    }
    private static void ok(boolean value){check(value);}
    private static void scenario(String name,Throwing f)throws Exception{f.run();count++;System.out.println("PASS HTTP "+name);}
    @FunctionalInterface interface Throwing{void run()throws Exception;}
    private static int count;
    public static void main(String[] args)throws Exception {
        scenario("strict JSON and numeric types",()->{
            equal(JsonInput.parse("123".getBytes(StandardCharsets.UTF_8)),BigInteger.valueOf(123));
            for(String s:List.of("{\"a\":null,\"a\":1}","[1,]","01","NaN","\"\\ud800\"","{\"\\udfff\":1}","{} trailing","[".repeat(40)+"]".repeat(40)))rejects(()->JsonInput.parse(s.getBytes(StandardCharsets.UTF_8)));
            rejects(()->JsonInput.parse(new byte[]{(byte)0xff}));equal(JsonInput.parse("\"\\ud83c\\udf6e\"".getBytes(StandardCharsets.UTF_8)),"🍮");
        });
        scenario("operator credential binding and duplicate rejection",()->{
            var c=credentials();equal(c.authenticate("Bearer "+TOKEN_A).agentId(),"fixture-a");equal(c.authenticate("Bearer "+TOKEN_B).agentId(),"fixture-b");equal(c.authenticate("Bearer bad"),null);
            rejects(()->new AgentCredentials(Json.write(List.of(Map.of("agent_id","a","token",TOKEN_A),Map.of("agent_id","b","token",TOKEN_A))).getBytes(StandardCharsets.UTF_8)));
            check(!c.toString().contains(TOKEN_A));
        });
        scenario("auth host and origin reject before domain",()->{
            var b=new TestBackend();var e=endpoint(b,Clock.systemUTC());var r=request(init(),"invalid",null,null);equal(e.handle(r).status(),401);
            var h=new HashMap<>(request(init(),TOKEN_A,null,null).headers());h.put("host","attacker.example");equal(e.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",h,r.body())).status(),403);
            h.put("host","localhost");h.put("origin","https://attacker.example");equal(e.handle(new HttpRequest(HttpMethod.POST,"/mcp","HTTP/1.1",h,r.body())).status(),403);equal(b.calls,0);
        });
        scenario("lifecycle protocol and session ownership",()->{
            var b=new TestBackend();var e=endpoint(b,Clock.systemUTC());String sid=initialize(e,TOKEN_A);
            equal(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_B,sid,McpEndpoint.VERSION)).status(),404);
            equal(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,null)).status(),400);
            Reply result=e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,McpEndpoint.VERSION));equal(((List<?>)((Map<?,?>)object(result.body()).get("result")).get("tools")).size(),9);
            check(object(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,McpEndpoint.VERSION)).body()).containsKey("error"));
            equal(e.handle(request(rpc(3,"tools/list",Map.of("cursor","invented")),TOKEN_A,sid,McpEndpoint.VERSION)).status(),200);equal(b.calls,0);
        });
        scenario("initialization gate, notification silence and no side effects",()->{
            var b=new TestBackend();var e=endpoint(b,Clock.systemUTC());var response=e.handle(request(init(),TOKEN_A,null,null));String sid=response.headers().get("Mcp-Session-Id");
            check(object(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,McpEndpoint.VERSION)).body()).containsKey("error"));
            var notification=Map.of("jsonrpc","2.0","method","tools/call","params",Map.of("name","pudding.sessions.create","arguments",Map.of("request_id",UUID.randomUUID().toString())));
            equal(e.handle(request(notification,TOKEN_A,sid,McpEndpoint.VERSION)).status(),202);
            var cancel=Map.of("jsonrpc","2.0","method","notifications/cancelled","params",Map.of("requestId",2,"reason","fixture"));
            equal(e.handle(request(cancel,TOKEN_A,sid,McpEndpoint.VERSION)).body().length,0);equal(b.calls,0);
        });
        scenario("domain full flow and retry snapshot",()->{
            var b=new TestBackend();var clock=new TestClock();var e=endpoint(b,clock);String sid=initialize(e,TOKEN_A);String request=UUID.randomUUID().toString();
            Map<String,Object> first=call(e,TOKEN_A,sid,2,"pudding.sessions.create",Map.of("request_id",request));equal(first.get("ok"),true);
            var s=(Map<?,?>)first.get("session");String id=(String)s.get("id");int revision=0,rpcId=3;
            for(String op:List.of("pudding.lid.peel","pudding.lid.dispose","pudding.spoon.scoop","pudding.spoon.taste","pudding.sessions.finish","pudding.sessions.cleanup")){
                var input=new HashMap<String,Object>();input.put("request_id",UUID.randomUUID().toString());input.put("session_id",id);input.put("expected_revision",revision++);
                if(op.endsWith("peel"))input.put("amount_q",1000);if(op.endsWith("scoop")){input.put("body_mg",8000);input.put("sauce_mg",2000);}
                var result=call(e,TOKEN_A,sid,rpcId++,op,input);equal(result.get("ok"),true);s=(Map<?,?>)result.get("session");
            }
            equal(s.get("session_stage"),"closed");clock.now=clock.now.plusSeconds(86400);
            String next=initialize(e,TOKEN_A);equal(call(e,TOKEN_A,next,2,"pudding.sessions.create",Map.of("request_id",request)),first);
            equal(call(e,TOKEN_B,initialize(e,TOKEN_B),2,"pudding.sessions.get",Map.of("session_id",id)).get("error"),"NOT_FOUND");
        });
        scenario("unknown, bool, clock and identity arguments never execute",()->{
            var b=new TestBackend();var e=endpoint(b,Clock.systemUTC());String sid=initialize(e,TOKEN_A);int id=2;
            var common=Map.of("request_id",UUID.randomUUID().toString(),"session_id",UUID.randomUUID().toString(),"expected_revision",0);
            for(var extra:List.of(Map.of("agent_id","fixture-b"),Map.of("lab",true),Map.of("now","2100-01-01"),Map.of("expected_revision",true))){
                var input=new HashMap<String,Object>(common);input.putAll(extra);var r=e.handle(request(rpc(id++,"tools/call",Map.of("name","pudding.spoon.taste","arguments",input)),TOKEN_A,sid,McpEndpoint.VERSION));check(object(r.body()).containsKey("error"));
            }equal(b.calls,0);
        });
        scenario("expiry deletion quotas and health",()->{
            var b=new TestBackend();var clock=new TestClock();var e=endpoint(b,clock);String sid=initialize(e,TOKEN_A);
            var delete=new HttpRequest(HttpMethod.DELETE,"/mcp","HTTP/1.1",request(Map.of(),TOKEN_A,sid,McpEndpoint.VERSION).headers(),new byte[0]);equal(e.handle(delete).status(),204);
            equal(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,McpEndpoint.VERSION)).status(),404);
            sid=initialize(e,TOKEN_A);clock.now=clock.now.plusSeconds(1800);equal(e.handle(request(rpc(2,"tools/list",Map.of()),TOKEN_A,sid,McpEndpoint.VERSION)).status(),404);
            for(int i=0;i<8;i++)equal(e.handle(request(init(),TOKEN_A,null,null)).status(),200);equal(e.handle(request(init(),TOKEN_A,null,null)).status(),429);
            b.available=false;equal(e.handle(new HttpRequest(HttpMethod.GET,"/health/ready","HTTP/1.1",Map.of("host","localhost"),new byte[0])).status(),503);
            equal(e.handle(new HttpRequest(HttpMethod.GET,"/health/live","HTTP/1.1",Map.of("host","localhost"),new byte[0])).status(),200);
        });
        scenario("HTTP framing rejects ambiguous and oversized input",()->{
            for(String raw:List.of("POST /mcp HTTP/1.1\nHost: localhost\n\n","GET /mcp HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\n\r\n","POST /mcp HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n","POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Length: 65537\r\n\r\n","GET http://localhost/mcp HTTP/1.1\r\nHost: localhost\r\n\r\n")){
                try{new RequestReader().read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));throw new AssertionError("ambiguous HTTP accepted");}catch(HttpFailure expected){}
            }
        });
        scenario("real socket response, HEAD, timeout and shutdown",()->{
            var b=new TestBackend();var e=endpoint(b,Clock.systemUTC());
            try(var server=new SocketServer(InetAddress.getLoopbackAddress(),0,2,300,e)){
                server.start();byte[] response=exchange(server.port(),"GET /health/live HTTP/1.1\r\nHost: localhost\r\n\r\n");String text=new String(response,StandardCharsets.US_ASCII);check(text.startsWith("HTTP/1.1 200"));check(text.contains("Connection: close"));
                text=new String(exchange(server.port(),"HEAD /health/live HTTP/1.1\r\nHost: localhost\r\n\r\n"),StandardCharsets.US_ASCII);check(text.endsWith("\r\n\r\n"));
                try(var socket=new Socket(InetAddress.getLoopbackAddress(),server.port())){socket.setSoTimeout(2000);socket.getOutputStream().write('G');equal(socket.getInputStream().read(),-1);}
            }
        });
        System.out.println("HTTP/MCP scenarios: "+count);
    }
    private static byte[] exchange(int port,String raw)throws Exception {
        try(var socket=new Socket(InetAddress.getLoopbackAddress(),port)){socket.setSoTimeout(2000);socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));return socket.getInputStream().readAllBytes();}
    }
}
