package net.flamoris.pudding.http;

import java.util.*;
import net.flamoris.pudding.*;

/** Public research representation. Financial and identity administration stay separate. */
public final class DomainJson {
    private DomainJson() {}
    public static Map<String,Object> policy(Core.Policy p,String agent) {
        return Map.of("agent_id",agent,"status",p.status(),"violations",p.obligations().size(),
            "enforcement_revision",p.obligations().size(),"lockout_threshold",p.threshold(),"rules_version",Core.RULES);
    }
    private static Map<String,Integer> portion(Core.Portion p) {return Map.of("initial_mg",p.initial(),"cup_mg",p.cup(),"spoon_mg",p.spoon(),"consumed_mg",p.consumed(),"discarded_mg",p.discarded());}
    public static Map<String,Object> age(Lifecycle.Age a) {
        return a==null?Map.of("status","not_applicable"):Map.of("status",a.status(),"quality_q",a.qualityQ(),"rotten_egg_like_q",a.rottenEggLikeQ(),"model_version","aging-authored-v0","basis","authored");
    }
    public static Map<String,Object> preset(Sensory.Preset p) {
        var out=new LinkedHashMap<String,Object>();out.put("id",p.id());out.put("basis","authored");out.put("model_version","custard-authored-v0");out.put("source_ids",List.of("S01"));
        out.put("temperature_c",8);out.put("sugar_g_per_100g",null);out.put("brix_percent",null);
        out.put("components",Map.of("body",Map.of("mass_mg",90000,"features",p.body()),"sauce",Map.of("mass_mg",10000,"features",p.sauce())));return out;
    }
    public static Map<String,Object> state(Core.State s,java.time.Instant now) {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",s.id().toString());out.put("revision",s.revision());out.put("preset_id",s.preset().id());out.put("preset",preset(s.preset()));
        out.put("peel_q",s.peelQ());out.put("lid",s.lid().name().toLowerCase(Locale.ROOT));out.put("food_stage",s.stage().name().toLowerCase(Locale.ROOT));out.put("session_stage",s.serving().name().toLowerCase(Locale.ROOT));
        out.put("cleanup",s.serving()==Core.Serving.CLOSED?"done":s.serving()==Core.Serving.CLEANUP_REQUIRED?"required":"not_due");out.put("cleanup_due_at",s.cleanupDue()==null?null:s.cleanupDue().toString());
        out.put("components",Map.of("body",portion(s.body()),"sauce",portion(s.sauce())));out.put("bites",s.bites());out.put("rules_version",Core.RULES);out.put("fixture_only",true);
        out.put("effective_age",age(s.deadlines()==null?null:Lifecycle.age(s.deadlines(),now)));
        out.put("deadlines",s.deadlines()==null?null:Map.of("purchased_at",s.deadlines().purchased().toString(),"best_before_at",s.deadlines().bestBefore().toString(),"dispose_after_at",s.deadlines().disposeAfter().toString(),"timezone","Asia/Tokyo","rules_version","lifecycle-v0"));
        if(s.disposalProcessed()!=null)out.put("disposal",Map.of("reason","monthly_expiry","effective_at",s.disposalEffective().toString(),"processed_at",s.disposalProcessed().toString()));
        Core.Taste taste=s.lastTaste();
        out.put("last_taste",taste==null?null:Map.of("features",taste.features(),"components_mg",Map.of("body",taste.bodyMg(),"sauce",taste.sauceMg()),"evaluated_at",taste.evaluatedAt().toString(),"aging",age(taste.aging()),"model_version","blend-interaction-v0","basis","derived_from_authored"));
        return out;
    }
    public static Map<String,Object> response(Core.Response r,String agent,java.time.Instant evaluatedAt) {
        var out=new LinkedHashMap<String,Object>();out.put("ok",r.ok());
        if(r.ok()) {
            var snapshot=state(r.session(),evaluatedAt);
            // Updates return a durable snapshot; current age is read separately.
            // Otherwise a retry tomorrow would change an already saved response.
            snapshot.remove("effective_age");out.put("session",snapshot);
        } else out.put("error",r.error());
        out.put("policy",policy(r.policy(),agent));out.put("food_effect",r.ok()?"applied":"none");out.put("policy_effect",r.policyChanged()?"recorded":"none");return out;
    }
}
