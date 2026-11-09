package net.flamoris.pudding;

import java.util.*;

/** q1000 values are authored hypotheses, not measured sugar or subjective feelings. */
public final class Sensory {
    private Sensory() {}
    public record Preset(String id, Map<String,Integer> body, Map<String,Integer> sauce) {
        public Preset { Objects.requireNonNull(id); body = features(body); sauce = features(sauce); }
    }
    public static Map<String,Integer> features(Map<String,Integer> source) {
        if (source == null || source.size() > 64) throw new IllegalArgumentException("features");
        source.forEach((k,v) -> {
            if (k == null || !k.matches("[a-z][a-z0-9_]{0,63}") || (v != null && (v < 0 || v > 1000)))
                throw new IllegalArgumentException("feature");
        });
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
    public static List<String> presetIds(){return List.of("custard-v0","custard-sweeter-v0","custard-firmer-v0","custard-bitterer-v0","milk-v1","chocolate-v1");}
    public static int bodyMass(Preset p){return p.sauce().isEmpty()?100000:90000;}
    public static int sauceMass(Preset p){return p.sauce().isEmpty()?0:10000;}
    public static Preset preset(String id) {
        var b = base(700, 0, 450, 850, 700, 500, 350, 0);
        var s = base(600, 800, 0, 1000, 0, 0, 0, 800);
        switch (Objects.requireNonNull(id)) {
            case "custard-v0" -> {}
            case "custard-sweeter-v0" -> b.put("sweetness_q",800);
            case "custard-firmer-v0" -> b.put("hardness_q",600);
            case "custard-bitterer-v0" -> s.put("bitterness_q",900);
            case "milk-v1" -> {b=base(600,0,300,950,900,0,250,0);s=new TreeMap<>();}
            case "chocolate-v1" -> {b=base(600,350,500,900,500,0,150,0);b.put("aroma_cocoa_q",900);s=new TreeMap<>();}
            default -> throw new IllegalArgumentException("unknown preset");
        }
        return new Preset(id,b,s);
    }
    private static Map<String,Integer> base(int sweet,int bitter,int hard,int smooth,int milk,int egg,int vanilla,int roast) {
        var m = new TreeMap<>(Map.of("sweetness_q",sweet,"bitterness_q",bitter,"hardness_q",hard,
            "smoothness_q",smooth,"aroma_milk_q",milk,"aroma_egg_q",egg,"aroma_vanilla_q",vanilla,
            "aroma_roasted_sugar_q",roast,"aroma_cocoa_q",0));
        for (String k : List.of("sourness_q","saltiness_q","umami_q","astringency_q")) m.put(k,null);
        return m;
    }
    public static Map<String,Integer> bite(Preset p,int body,int sauce,boolean interaction) {
        if (body<0 || body>bodyMass(p) || sauce<0 || sauce>sauceMass(p) || body+sauce==0) throw new IllegalArgumentException("bite mass");
        int total=body+sauce;
        var values=new TreeMap<String,Rational>();
        var keys=new TreeSet<String>();
        if(body>0) keys.addAll(p.body.keySet()); if(sauce>0) keys.addAll(p.sauce.keySet());
        for(String key:keys) {
            Integer b=p.body.get(key), s=p.sauce.get(key);
            values.put(key, ((body>0 && b==null)||(sauce>0 && s==null)) ? null :
                Rational.of(body==0?0:(long)b*body,total).add(Rational.of(sauce==0?0:(long)s*sauce,total)));
        }
        if(interaction && body>0 && sauce>0) {
            var r=Rational.of(body,total); var g=Rational.of(4).mul(r).mul(Rational.of(1).sub(r));
            var sweet=values.get("sweetness_q"); var bitter=values.get("bitterness_q");
            values.put("sweetness_q",sweet==null||bitter==null?null:sweet.mul(Rational.of(1).sub(Rational.of(1,5).mul(g).mul(bitter).mul(Rational.of(1,1000)))));
            values.put("bitterness_q",sweet==null||bitter==null?null:bitter.mul(Rational.of(1).sub(Rational.of(2,5).mul(g).mul(sweet).mul(Rational.of(1,1000)))));
        }
        var out=new TreeMap<String,Integer>(); values.forEach((k,v)->out.put(k,v==null?null:v.halfUp())); return features(out);
    }
    public record Preference(Integer fitQ,Integer partialFitQ,int coverageQ) {}
    public static Preference preference(Map<String,Integer> input, Map<String,Integer> targets, Map<String,Integer> weights) {
        var f=features(input); var t=features(targets);
        if(t.isEmpty()||t.containsValue(null)||!t.keySet().equals(weights.keySet())) throw new IllegalArgumentException("targets");
        long total=0,known=0,distance=0;
        for(String k:t.keySet()) {
            Integer w=weights.get(k); if(w==null||w<0||w>1000000) throw new IllegalArgumentException("weight");
            total+=w;
            if(f.get(k)!=null) { known+=w; distance+=(long)w*Math.abs(f.get(k)-t.get(k)); }
        }
        if(total==0) throw new IllegalArgumentException("zero weights");
        Integer partial=known==0?null:Rational.of(1000).sub(Rational.of(distance,known)).halfUp();
        return new Preference(known==total?partial:null,partial,Rational.of(1000*known,total).halfUp());
    }
}
