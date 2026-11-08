package net.flamoris.pudding;

import java.time.*;
import java.util.*;
import static net.flamoris.pudding.Core.*;

public final class ParityVectors {
    static List<Map<String,Object>> core(){
        var m=new MemoryStore(Clock.fixed(CoreTest.NOW,ZoneOffset.UTC),3,null);var rows=new ArrayList<Map<String,Object>>();
        var r=m.execute(Command.create(UUID.randomUUID(),null));rows.add(Main.summary(r));State s=r.session();
        r=m.execute(CoreTest.action(Op.PEEL,s,250,0,0));rows.add(Main.summary(r));s=r.session();
        r=m.execute(CoreTest.action(Op.PEEL,s,750,0,0));rows.add(Main.summary(r));s=r.session();
        rows.add(Main.summary(m.execute(CoreTest.action(Op.TASTE,s,0,0,0))));
        rows.add(Main.summary(m.execute(CoreTest.action(Op.SCOOP,s,0,90001,0))));
        rows.add(Main.summary(m.execute(CoreTest.action(Op.SCOOP,s,0,8000,2000))));
        r=m.execute(CoreTest.action(Op.LID_DISPOSE,s,0,0,0));rows.add(Main.summary(r));s=r.session();
        r=m.execute(CoreTest.action(Op.SCOOP,s,0,8000,2000));rows.add(Main.summary(r));s=r.session();
        rows.add(Main.summary(m.execute(CoreTest.action(Op.SCOOP,s,0,100,0))));
        rows.add(Main.summary(m.execute(Command.action(Op.TASTE,s.id(),0,0,0,0))));
        var bite=CoreTest.action(Op.TASTE,s,0,0,0);r=m.execute(bite);rows.add(Main.summary(r));s=r.session();
        rows.add(Main.summary(m.execute(bite)));
        r=m.execute(CoreTest.action(Op.FINISH,s,0,0,0));rows.add(Main.summary(r));s=r.session();
        r=m.execute(CoreTest.action(Op.CLEANUP,s,0,0,0));rows.add(Main.summary(r));return rows;
    }
    public static void main(String[] args){
        var vectors=new ArrayList<Object>();
        for(String id:List.of("custard-v0","custard-sweeter-v0","custard-firmer-v0","custard-bitterer-v0"))
            for(int b:new int[]{0,1,100,500,8000,90000})for(int s:new int[]{0,1,7,2000,10000})for(boolean interaction:new boolean[]{false,true})
                if(b+s>0)vectors.add(Map.of("id",id,"body",b,"sauce",s,"interaction",interaction,"features",Sensory.bite(Sensory.preset(id),b,s,interaction)));
        for(String id:List.of("milk-v1","chocolate-v1"))for(int body:new int[]{1,8000,100000})
            vectors.add(Map.of("id",id,"body",body,"sauce",0,"interaction",true,"features",Sensory.bite(Sensory.preset(id),body,0,true)));
        var dates=new ArrayList<Object>();
        for(String purchase:List.of("2026-11-08T01:00:00Z","2027-01-31T01:00:00Z","2028-01-31T01:00:00Z","2026-12-31T01:00:00Z","2026-01-30T18:00:00Z")){
            var d=Lifecycle.deadlines(Instant.parse(purchase));
            for(Instant now:List.of(d.bestBefore().minusNanos(1000),d.bestBefore(),d.bestBefore().plusSeconds(86400),d.disposeAfter().minusNanos(1000),d.disposeAfter())){
                var a=Lifecycle.age(d,now);dates.add(Map.of("purchase",purchase,"now",now.toString(),"best",d.bestBefore().toString(),"hard",d.disposeAfter().toString(),"status",a.status(),"quality",a.qualityQ(),"rotten",a.rottenEggLikeQ()));
            }
        }
        var a=Accounting.expiry("asset🍮\n\"","purchase",1100,200,100,CoreTest.NOW,CoreTest.NOW,true,"v0");
        System.out.println(Json.write(Map.of("sensory",vectors,"lifecycle",dates,"core",core(),"candidate_id",a.id())));
    }
}
