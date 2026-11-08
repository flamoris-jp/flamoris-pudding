package net.flamoris.pudding;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit operator commands. No runtime migration or automatic identity registration. */
public final class SandboxMain {
    private SandboxMain(){}
    public static Object run(String command,Map<String,String> env)throws Exception {
        var db=ServiceMain.connections(env);
        if(command.equals("upgrade-sandbox-postgres")){CommerceStore.migrate(db);return Map.of("schema_version",2);}
        if(command.equals("grant-sandbox-runtime-postgres")){CommerceStore.grantRuntime(db,env.get("PUDDING_RUNTIME_ROLE"));return Map.of("sandbox_grants",true);}
        // Provisioning and catalogue commands do not need or expose a payment signing key.
        // No notification can be issued by these owner-side commands.
        var store=new CommerceStore(db,Clock.systemUTC(),null);
        switch(command) {
            case "provision-sandbox-postgres" -> store.provision(new CommerceStore.Human(env.get("PUDDING_TENANT_ID"),env.get("PUDDING_HUMAN_ID")),env.get("PUDDING_AGENT_ID"),env.get("PUDDING_EXTERNAL_SUBJECT"),env.get("PUDDING_AGENT_FINGERPRINT"));
            case "catalog-sandbox-postgres" -> {
                Path path=Path.of(Objects.requireNonNull(env.get("PUDDING_CATALOG_FILE")));
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>32768)throw new IllegalArgumentException("catalogue file");
                store.publishProduct(Input.object(JsonInput.parse(Files.readAllBytes(path))));
            }
            case "resume-cleanup-postgres" -> store.resumeCleanup(Objects.requireNonNull(env.get("PUDDING_OPERATOR_REASON")));
            case "maintenance-sandbox-postgres" -> {return Map.of("disposed",store.expireBatch(100),"cleanup_violations",store.cleanupBatch(100));}
            default -> throw new IllegalArgumentException("sandbox command");
        }
        return Map.of("ok",true,"sandbox_only",true);
    }
}
