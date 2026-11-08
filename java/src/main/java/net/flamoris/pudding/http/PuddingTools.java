package net.flamoris.pudding.http;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import net.flamoris.pudding.*;

public final class PuddingTools {
    private static final Map<String,Core.Op> OPS=Map.of(
        "pudding.sessions.create",Core.Op.CREATE,"pudding.lid.peel",Core.Op.PEEL,
        "pudding.lid.dispose",Core.Op.LID_DISPOSE,"pudding.spoon.scoop",Core.Op.SCOOP,
        "pudding.spoon.taste",Core.Op.TASTE,"pudding.sessions.finish",Core.Op.FINISH,
        "pudding.sessions.cleanup",Core.Op.CLEANUP);
    private final Backend backend;
    private final List<?> tools;
    private final Map<String,Map<?,?>> schemas;
    public PuddingTools(Backend backend) {
        this.backend=backend;
        try(var stream=PuddingTools.class.getResourceAsStream("/mcp/tools.json")) {
            if(stream==null)throw new IOException("tool definitions missing");
            tools=(List<?>)JsonInput.parse(stream.readAllBytes());
        }catch(IOException|ClassCastException e){throw new IllegalStateException("tool configuration",e);}
        var index=new HashMap<String,Map<?,?>>();
        for(Object raw:tools) {var t=(Map<?,?>)raw;index.put((String)t.get("name"),(Map<?,?>)t.get("inputSchema"));}
        if(index.size()!=9)throw new IllegalStateException("tool configuration");schemas=Map.copyOf(index);
    }
    public List<?> list(){return tools;}
    public Map<String,Object> call(String agent,String name,Map<String,Object> input)throws Exception {
        Map<?,?> schema=schemas.get(name);
        if(schema==null)throw new IllegalArgumentException("unknown tool");
        var properties=(Map<?,?>)schema.get("properties");var required=(List<?>)schema.get("required");
        if(!properties.keySet().containsAll(input.keySet())||!input.keySet().containsAll(required))throw new IllegalArgumentException("tool arguments");
        for(var e:input.entrySet()) {
            var rule=(Map<?,?>)properties.get(e.getKey());Object v=e.getValue();
            if("integer".equals(rule.get("type"))) {
                if(!(v instanceof BigInteger number)||number.compareTo((BigInteger)rule.get("minimum"))<0||number.compareTo((BigInteger)rule.get("maximum"))>0)throw new IllegalArgumentException("integer argument");
            } else {
                if(!(v instanceof String s)||s.isEmpty()||s.length()>((BigInteger)rule.get("maxLength")).intValueExact())throw new IllegalArgumentException("string argument");
                if("uuid".equals(rule.get("format")))uuid(s);
            }
        }
        if(name.equals("pudding.presets.list")) {
            var values=new LinkedHashMap<String,Object>();
            for(String id:List.of("custard-v0","custard-sweeter-v0","custard-firmer-v0","custard-bitterer-v0"))values.put(id,DomainJson.preset(Sensory.preset(id)));
            return Map.of("ok",true,"presets",values);
        }
        if(name.equals("pudding.sessions.get")) {
            var view=backend.read(agent,uuid((String)input.get("session_id")));
            if(view==null)return Map.of("ok",false,"error","NOT_FOUND","food_effect","none");
            return Map.of("ok",true,"session",DomainJson.state(view.state(),backend.now()),"policy",DomainJson.policy(view.policy(),agent));
        }
        Core.Command command;
        UUID request=uuid((String)input.get("request_id"));
        if(name.equals("pudding.sessions.create")) {
            String preset=(String)input.getOrDefault("preset_id","custard-v0");Sensory.preset(preset);
            command=Core.Command.create(request,preset);
        } else command=new Core.Command(request,OPS.get(name),uuid((String)input.get("session_id")),integer(input,"expected_revision"),integer(input,"amount_q"),integer(input,"body_mg"),integer(input,"sauce_mg"),null);
        return DomainJson.response(backend.execute(agent,command),agent,backend.now());
    }
    private static int integer(Map<String,Object> input,String name){return ((BigInteger)input.getOrDefault(name,BigInteger.ZERO)).intValueExact();}
    private static UUID uuid(String s) {
        if(s.length()!=36)throw new IllegalArgumentException("UUID");
        UUID uuid=UUID.fromString(s);if(!uuid.toString().equals(s))throw new IllegalArgumentException("UUID");return uuid;
    }
}
