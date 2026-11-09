package net.flamoris.pudding;

import java.util.*;

/** Authored, deterministic expression models; no inference or physiology. */
public final class Experience {
    private Experience(){}
    public static Map<String,Object> initial() {
        var m=new LinkedHashMap<String,Object>();
        m.put("model_version","experience-authored-v1");m.put("basis","authored");
        m.put("anticipated_base_q",400);m.put("expected_liking_q",700);
        m.put("targets",Map.of("sweetness_q",650,"bitterness_q",200,"hardness_q",500,"smoothness_q",850));
        m.put("weights",Map.of("sweetness_q",1,"bitterness_q",1,"hardness_q",1,"smoothness_q",1));
        m.put("evaluated_mg",0);m.put("total_mg",0);m.put("weighted_liking",0L);
        m.put("last_liking_q",null);m.put("last_valence_q",null);m.put("virtual_elapsed_ms",0);
        return m;
    }
    @SuppressWarnings("unchecked") private static Map<String,Integer> integers(Object raw) {
        var out=new TreeMap<String,Integer>();Input.object(raw).forEach((k,v)->{
            out.put(k,v==null?null:Math.toIntExact(Input.number(Map.of("value",v),"value",0,1000000)));
        });return out;
    }
    public static Map<String,Object> advance(Map<String,Object> previous,Core.State old,Core.State next) {
        var m=new LinkedHashMap<>(previous==null?initial():previous);
        if(old!=null&&next.bites()>old.bites()) {
            var taste=Objects.requireNonNull(next.lastTaste());
            var fit=Sensory.preference(taste.features(),integers(m.get("targets")),integers(m.get("weights")));
            Integer liking=fit.fitQ();
            if(liking!=null&&taste.aging()!=null)liking=Lifecycle.agedLiking(liking,taste.aging());
            int mass=taste.bodyMg()+taste.sauceMg();
            long total=Input.number(m,"total_mg",0,100000)+mass;
            long evaluated=Input.number(m,"evaluated_mg",0,100000)+(liking==null?0:mass);
            long weighted=Input.number(m,"weighted_liking",0,100000000)+(liking==null?0:(long)liking*mass);
            m.put("total_mg",total);m.put("evaluated_mg",evaluated);m.put("weighted_liking",weighted);
            m.put("last_liking_q",liking);m.put("last_valence_q",liking==null?null:2*liking-1000);
            m.put("virtual_elapsed_ms",0);m.put("last_features",taste.features());
        }
        return m;
    }
    public static Map<String,Object> rest(Map<String,Object> previous,int milliseconds) {
        var m=new LinkedHashMap<>(previous);m.put("virtual_elapsed_ms",Math.min(60000,Input.integer(m,"virtual_elapsed_ms",0,60000)+milliseconds));return m;
    }
    public static Map<String,Object> view(Map<String,Object> track,Core.State state) {
        var m=new LinkedHashMap<String,Object>();m.put("model_version","experience-authored-v1");m.put("basis","authored");
        m.put("profile_id","research-demo-v1");m.put("profile_is_personal_preference",false);
        int cues=(state.peelQ()>=250?1:0)+(state.peelQ()>=500?1:0)+(state.peelQ()>=1000?1:0);
        m.put("anticipation_q",Math.min(1000,Input.integer(track,"anticipated_base_q",0,1000)+50*cues));
        int expected=Input.integer(track,"expected_liking_q",0,1000);m.put("expected_liking_q",expected);
        m.put("liking_q",track.get("last_liking_q"));m.put("valence_q",track.get("last_valence_q"));
        m.put("expectation_delta_q",track.get("last_liking_q")==null?null:Input.integer(track,"last_liking_q",0,1000)-expected);
        long mass=Input.number(track,"total_mg",0,100000),known=Input.number(track,"evaluated_mg",0,100000);
        m.put("evaluation_coverage_q",mass==0?0:Rational.of(known*1000,mass).halfUp());
        m.put("satisfaction_q",mass==0||known!=mass?null:Rational.of(Input.number(track,"weighted_liking",0,100000000),mass).halfUp());
        int elapsed=Input.integer(track,"virtual_elapsed_ms",0,60000);
        m.put("afterglow_q",track.get("last_valence_q")==null?null:Rational.of(Input.number(track,"last_valence_q",-1000,1000)*(60000-elapsed),60000).halfUp());
        m.put("virtual_elapsed_ms",elapsed);m.put("expression_cues",cues==0?List.of("anticipating"):state.lastTaste()==null?List.of("foil_peel","surface_revealed"):List.of("savor","aftertaste"));
        if(state.lastTaste()!=null) {
            Integer bitterness=state.lastTaste().features().get("bitterness_q");
            m.put("expression_template",Map.of("version","feature-line-authored-v1","basis","authored_template","line",bitterness==null?"味の手掛かりを、もう少し確かめたいね。":bitterness>=300?"ほろ苦さの手掛かりが残るね。":"口当たりを、ゆっくり確かめたいね。"));
            // Temporal curves are expression weights, not measured seconds or extra liking points.
            m.put("perception_phases",List.of(Map.of("phase","onset","sweetness_weight_q",1000,"bitterness_weight_q",400),
                Map.of("phase","middle","sweetness_weight_q",800,"bitterness_weight_q",800),
                Map.of("phase","aftertaste","sweetness_weight_q",400,"bitterness_weight_q",1000)));
        }
        return m;
    }
}
