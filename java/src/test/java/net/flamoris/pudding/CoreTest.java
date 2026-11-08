package net.flamoris.pudding;

import java.time.*;
import java.util.*;
import java.math.BigInteger;
import static net.flamoris.pudding.Core.*;

public final class CoreTest {
    static int scenarios;
    static void check(boolean test){if(!test)throw new AssertionError("check failed");}
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("accepted invalid input");}
    static void test(String name,Runnable r){r.run();scenarios++;System.out.println("PASS "+name);}
    static final Instant NOW=Instant.parse("2026-11-08T01:00:00Z");
    static final class MutableClock extends Clock {
        Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
    static Command action(Op op,State s,int q,int b,int c){return Command.action(op,s.id(),s.revision(),q,b,c);}
    static State open(MemoryStore m){var s=m.execute(Command.create(UUID.randomUUID(),null)).session();s=m.execute(action(Op.PEEL,s,1000,0,0)).session();return m.execute(action(Op.LID_DISPOSE,s,0,0,0)).session();}
    public static void main(String[] args){
        test("exact blend and linear",()->{var p=Sensory.preset("custard-v0");equal(Sensory.bite(p,8000,2000,true).get("sweetness_q"),666);equal(Sensory.bite(p,8000,2000,true).get("bitterness_q"),132);equal(Sensory.bite(p,8000,2000,false).get("sweetness_q"),680);equal(Sensory.bite(p,0,2000,true).get("bitterness_q"),800);});
        test("unknown propagation and absent components",()->{var p=Sensory.preset("custard-v0");var sauce=new TreeMap<>(p.sauce());sauce.put("sweetness_q",null);var custom=new Sensory.Preset("unknown",p.body(),sauce);equal(Sensory.bite(custom,8000,0,true).get("sweetness_q"),700);equal(Sensory.bite(custom,8000,1000,true).get("bitterness_q"),null);});
        test("preference coverage",()->{var f=Sensory.bite(Sensory.preset("custard-v0"),8000,2000,true);var t=Map.of("sweetness_q",816,"bitterness_q",282);equal(Sensory.preference(f,t,Map.of("sweetness_q",1,"bitterness_q",1)).fitQ(),850);var partial=new TreeMap<>(f);partial.put("bitterness_q",null);var score=Sensory.preference(partial,t,Map.of("sweetness_q",1,"bitterness_q",1));equal(score.fitQ(),null);equal(score.coverageQ(),500);});
        test("invalid numeric and preset bounds",()->{rejects(()->Sensory.preset("bad"));rejects(()->Sensory.bite(Sensory.preset("custard-v0"),0,0,true));rejects(()->Sensory.bite(Sensory.preset("custard-v0"),90001,0,true));rejects(()->new Policy(0,Set.of()));rejects(()->Command.action(Op.TASTE,UUID.randomUUID(),-1,0,0,0));});
        test("calendar months and leap year",()->{equal(Lifecycle.deadlines(Instant.parse("2027-01-31T01:00:00Z")).disposeAfter(),Instant.parse("2027-02-28T01:00:00Z"));equal(Lifecycle.deadlines(Instant.parse("2028-01-31T01:00:00Z")).disposeAfter(),Instant.parse("2028-02-29T01:00:00Z"));equal(Lifecycle.deadlines(Instant.parse("2026-12-31T01:00:00Z")).disposeAfter(),Instant.parse("2027-01-31T01:00:00Z"));});
        test("exact expiry boundaries",()->{var d=Lifecycle.deadlines(NOW);equal(Lifecycle.age(d,d.bestBefore().minusNanos(1000)).status(),"fresh");equal(Lifecycle.age(d,d.bestBefore()).status(),"expired_pending_disposal");equal(Lifecycle.age(d,d.disposeAfter()).status(),"disposal_due");equal(Lifecycle.age(d,d.disposeAfter()).qualityQ(),0);});
        test("full serving demo",()->{var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var rows=Main.demo(m::execute);var last=rows.getLast();equal(last.get("revision"),7);equal(last.get("session_stage"),"closed");equal(last.get("body"),List.of(0,0,8000,82000));equal(last.get("sauce"),List.of(0,0,2000,8000));});
        test("invalid state does not strike; lid strikes once",()->{var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var s=m.execute(Command.create(UUID.randomUUID(),null)).session();s=m.execute(action(Op.PEEL,s,1000,0,0)).session();var empty=m.execute(action(Op.TASTE,s,0,0,0));equal(empty.error(),"EMPTY_SPOON");equal(empty.policy().obligations().size(),0);equal(m.execute(action(Op.SCOOP,s,0,90001,0)).error(),"INSUFFICIENT_MASS");for(int n=0;n<3;n++)equal(m.execute(action(Op.SCOOP,s,0,100,0)).policy().obligations().size(),1);});
        test("replay is old response and payload mismatch rejected",()->{var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var c=Command.create(UUID.randomUUID(),null);var first=m.execute(c);m.execute(action(Op.PEEL,first.session(),1000,0,0));equal(m.execute(c),first);equal(m.execute(Command.create(c.request(),"custard-firmer-v0")).error(),"REQUEST_ID_REUSED");});
        test("last bite needs cleanup; cleanup obligation is unique",()->{var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var s=open(m);s=m.execute(action(Op.SCOOP,s,0,90000,10000)).session();s=m.execute(action(Op.TASTE,s,0,0,0)).session();equal(s.serving(),Serving.CLEANUP_REQUIRED);equal(s.stage(),Stage.DEPLETED);for(int n=0;n<3;n++)equal(m.execute(Command.create(UUID.randomUUID(),null)).policy().obligations().size(),1);equal(m.execute(action(Op.CLEANUP,s,0,0,0)).session().serving(),Serving.CLOSED);});
        test("third expired bite locks before consumption",()->{var clock=new MutableClock();var m=new MemoryStore(clock,3,NOW);var s=open(m);clock.now=NOW.plusSeconds(15*86400);for(int n=0;n<2;n++){s=m.execute(action(Op.SCOOP,s,0,1000,0)).session();s=m.execute(action(Op.TASTE,s,0,0,0)).session();}s=m.execute(action(Op.SCOOP,s,0,1000,0)).session();var locked=m.execute(action(Op.TASTE,s,0,0,0));equal(locked.error(),"LOCKED");equal(locked.policy().status(),"locked");equal(m.read(s.id()).body().consumed(),2000);s=m.execute(action(Op.FINISH,s,0,0,0)).session();check(m.execute(action(Op.CLEANUP,s,0,0,0)).ok());});
        test("hard guard, disposal replay and pending cleanup",()->{var clock=new MutableClock();var m=new MemoryStore(clock,3,NOW);var s=open(m);s=m.execute(action(Op.SCOOP,s,0,8000,2000)).session();clock.now=s.deadlines().disposeAfter();equal(m.execute(action(Op.TASTE,s,0,0,0)).error(),"DISPOSAL_DUE");clock.now=clock.now.plusSeconds(86400);var expired=m.expire(s.id());equal(expired.body().discarded(),90000);equal(expired.sauce().discarded(),10000);equal(expired.serving(),Serving.CLEANUP_REQUIRED);equal(m.expire(s.id()),expired);check(expired.disposalProcessed().isAfter(expired.disposalEffective()));});
        test("immutable prior taste",()->{var clock=new MutableClock();var m=new MemoryStore(clock,3,NOW);var s=open(m);s=m.execute(action(Op.SCOOP,s,0,1000,0)).session();s=m.execute(action(Op.TASTE,s,0,0,0)).session();var taste=s.lastTaste();clock.now=NOW.plusSeconds(27*86400);equal(m.read(s.id()).lastTaste(),taste);equal(taste.aging().qualityQ(),1000);});
        test("snapshot round trip and corrupt rejection",()->{var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,NOW);var s=open(m);s=m.execute(action(Op.SCOOP,s,0,8000,2000)).session();var r=m.execute(action(Op.TASTE,s,0,0,0));equal(Codec.response(Codec.response(r)),r);equal(Codec.state(Codec.state(r.session())),r.session());rejects(()->Codec.state(new byte[]{1}));var b=Codec.response(r);b[3]=99;rejects(()->Codec.response(b));});
        test("expiry candidate residual and stable identity",()->{var a=Accounting.expiry("asset","purchase",1100,200,100,NOW,NOW.plusSeconds(1),true,"v0");equal(a.amountMinor(),800L);equal(a.lines().getLast().account(),"期限切れ雑収入");check(!a.externalPosting());equal(a.id(),Accounting.expiry("asset","purchase",1100,200,100,NOW,NOW.plusSeconds(2),true,"v1").id());equal(Accounting.expiry("asset","purchase",1100,1100,0,NOW,NOW,true,"v0"),null);equal(Accounting.expiry("asset","purchase",1100,0,0,NOW,NOW,false,"v0"),null);rejects(()->Accounting.expiry("asset","purchase",1100,1100,1,NOW,NOW,true,"v0"));});
        test("tax schedule and group rounding",()->{var a=new Accounting.TaxRate("rate","v0","fixture","JP",1,10,NOW,NOW.plusSeconds(1));var b=new Accounting.TaxRate("rate","v1","fixture","JP",12,100,NOW.plusSeconds(1),null);equal(Accounting.select(List.of(a,b),"fixture","JP",NOW.plusSeconds(1)),b);equal(Accounting.groupedInclusiveTax(List.of(10L,10L),a),BigInteger.ONE);equal(Accounting.groupedInclusiveTax(List.of(1100L),b),BigInteger.valueOf(117));rejects(()->Accounting.select(List.of(a,a),"fixture","JP",NOW));rejects(()->Accounting.select(List.of(a),"fixture","JP",NOW.minusSeconds(1)));});
        test("milk and chocolate have no sauce and cocoa bitterness stays in body",()->{
            var milk=Sensory.preset("milk-v1");equal(Sensory.bodyMass(milk),100000);equal(Sensory.sauceMass(milk),0);
            rejects(()->Sensory.bite(milk,1,1,true));equal(Sensory.bite(Sensory.preset("chocolate-v1"),100000,0,true).get("bitterness_q"),350);
            var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var state=m.execute(Command.create(UUID.randomUUID(),"milk-v1")).session();equal(state.body().initial(),100000);equal(state.sauce().initial(),0);
        });
        test("authored anticipation and mass-weighted satisfaction preserve unknown coverage",()->{
            var m=new MemoryStore(Clock.fixed(NOW,ZoneOffset.UTC),3,null);var old=m.execute(Command.create(UUID.randomUUID(),null)).session();
            var track=Experience.initial();equal(Experience.view(track,old).get("satisfaction_q"),null);
            var next=m.execute(action(Op.PEEL,old,250,0,0)).session();equal(Experience.view(track,next).get("anticipation_q"),450);
            next=m.execute(action(Op.PEEL,next,750,0,0)).session();next=m.execute(action(Op.LID_DISPOSE,next,0,0,0)).session();
            next=m.execute(action(Op.SCOOP,next,0,8000,2000)).session();old=next;next=m.execute(action(Op.TASTE,next,0,0,0)).session();
            track=Experience.advance(track,old,next);var view=Experience.view(track,next);check(view.get("satisfaction_q")!=null);
            equal(Experience.view(Experience.rest(track,60000),next).get("afterglow_q"),0);
            var unknown=new TreeMap<String,Object>(track);unknown.put("targets",Map.of("saltiness_q",100));unknown.put("weights",Map.of("saltiness_q",1));unknown.put("total_mg",0);unknown.put("evaluated_mg",0);unknown.put("weighted_liking",0);
            equal(Experience.view(Experience.advance(unknown,old,next),next).get("satisfaction_q"),null);
        });
        System.out.println("Java core scenarios: "+scenarios);
    }
}
