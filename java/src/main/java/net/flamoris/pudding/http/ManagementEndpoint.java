package net.flamoris.pudding.http;

import java.util.*;
import net.flamoris.pudding.*;

/** POST-only, cookie-free human sandbox API. The surrounding endpoint validates Host/Origin. */
public final class ManagementEndpoint {
    private final HumanCredentials credentials;
    private final CommerceStore store;
    public ManagementEndpoint(HumanCredentials credentials,CommerceStore store){this.credentials=Objects.requireNonNull(credentials);this.store=Objects.requireNonNull(store);}
    public Reply handle(HttpRequest request) {
        var human=credentials.authenticate(request.headers().get("authorization"));
        if(human==null)return Reply.refused(401).header("WWW-Authenticate","Bearer realm=\"pudding-management\"");
        if(request.method()!=HttpMethod.POST)return Reply.refused(405).header("Allow","POST");
        if(!request.headers().getOrDefault("content-type","").split(";",2)[0].strip().equalsIgnoreCase("application/json"))return Reply.refused(415);
        String operation=request.target().substring("/manage/v1/".length());
        if(!operation.matches("[a-z]+\\.[a-z]+"))return Reply.refused(404);
        try {return Reply.json(200,store.humanCall(human,operation,Input.object(JsonInput.parse(request.body()))));}
        catch(IllegalArgumentException e){return Reply.refused(400);}
        catch(Exception e){return Reply.refused(503);}
    }
}
