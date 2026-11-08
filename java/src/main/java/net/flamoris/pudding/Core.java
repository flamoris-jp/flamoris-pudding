package net.flamoris.pudding;

import java.time.*;
import java.util.*;

/** Trusted synthetic-principal domain API, not public authentication. */
public final class Core {
    private Core() {}
    public static final String RULES="research-rules-v0";
    public enum Op { CREATE, PEEL, LID_DISPOSE, SCOOP, TASTE, FINISH, CLEANUP }
    public enum Lid { ATTACHED, DETACHED, IN_BIN }
    public enum Stage { SEALED, PEELING, OPEN, SPOON_LOADED, DEPLETED, DISPOSED }
    public enum Serving { ACTIVE, CLEANUP_REQUIRED, CLOSED }
    public record Command(UUID request,Op op,UUID session,int expectedRevision,int amountQ,int bodyMg,int sauceMg,String presetId) {
        public Command {
            Objects.requireNonNull(request); Objects.requireNonNull(op);
            if(expectedRevision<0||amountQ<0||bodyMg<0||sauceMg<0) throw new IllegalArgumentException("negative argument");
            if(op==Op.CREATE) {
                if(session!=null||expectedRevision!=0)throw new IllegalArgumentException("create target");
                if(presetId==null)presetId="custard-v0";
                Sensory.preset(presetId);
            } else if(session==null||presetId!=null) throw new IllegalArgumentException("session target");
            if(op==Op.PEEL) {if(amountQ<1||amountQ>1000)throw new IllegalArgumentException("peel");}
            else if(amountQ!=0)throw new IllegalArgumentException("unexpected amountQ");
            if(op==Op.SCOOP) {if(bodyMg>100000||sauceMg>100000||bodyMg+sauceMg==0)throw new IllegalArgumentException("mass");}
            else if(bodyMg!=0||sauceMg!=0)throw new IllegalArgumentException("unexpected mass");
        }
        public static Command create(UUID request,String preset) {return new Command(request,Op.CREATE,null,0,0,0,0,preset);}
        public static Command action(Op op,UUID session,int revision,int amount,int body,int sauce) {return new Command(UUID.randomUUID(),op,session,revision,amount,body,sauce,null);}
    }
    public record Portion(int initial,int cup,int spoon,int consumed,int discarded) {
        public Portion {
            if(initial<0||initial>100000||cup<0||spoon<0||consumed<0||discarded<0||
                (long)cup+spoon+consumed+discarded!=initial) throw new IllegalArgumentException("mass conservation");
        }
        Portion scoop(int mg){return new Portion(initial,cup-mg,spoon+mg,consumed,discarded);}
        Portion taste(){return new Portion(initial,cup,0,consumed+spoon,discarded);}
        Portion discard(){return new Portion(initial,0,0,consumed,discarded+cup+spoon);}
    }
    public record Taste(Map<String,Integer> features,int bodyMg,int sauceMg,Instant evaluatedAt,Lifecycle.Age aging) {
        public Taste {features=Sensory.features(features);Objects.requireNonNull(evaluatedAt);}
    }
    public record State(UUID id,Sensory.Preset preset,int revision,int peelQ,Lid lid,Stage stage,Serving serving,
                        Portion body,Portion sauce,int bites,Instant cleanupDue,Lifecycle.Deadlines deadlines,
                        Taste lastTaste,Instant disposalEffective,Instant disposalProcessed) {
        public State {
            Objects.requireNonNull(id);Objects.requireNonNull(preset);Objects.requireNonNull(lid);
            Objects.requireNonNull(stage);Objects.requireNonNull(serving);Objects.requireNonNull(body);Objects.requireNonNull(sauce);
            if(revision<0||peelQ<0||peelQ>1000||bites<0)throw new IllegalArgumentException("state");
            if(serving!=Serving.ACTIVE&&(body.cup+body.spoon+sauce.cup+sauce.spoon!=0||cleanupDue==null))throw new IllegalArgumentException("cleanup state");
        }
        public int remaining(){return body.cup+body.spoon+sauce.cup+sauce.spoon;}
    }
    public record Policy(int threshold,Set<String> obligations) {
        public Policy {
            if(threshold<1||threshold>100||obligations.size()>threshold)throw new IllegalArgumentException("policy");
            obligations=Set.copyOf(obligations);
        }
        public boolean locked(){return obligations.size()>=threshold;}
        public String status(){return locked()?"locked":obligations.isEmpty()?"clear":"blacklisted";}
        Policy violate(String id){var next=new HashSet<>(obligations);next.add(id);return new Policy(threshold,next);}
    }
    public record Response(boolean ok,String error,State session,Policy policy,boolean policyChanged) {
        public Response {Objects.requireNonNull(policy);if(ok!=(error==null)||ok!=(session!=null))throw new IllegalArgumentException("response");}
    }
    public record Transition(State state,Policy policy,Response response) {}
    private static final class Denied extends RuntimeException {final String code;Denied(String s){code=s;}}
    private static void require(boolean condition,String code){if(!condition)throw new Denied(code);}
    public static Transition apply(State current,Policy initial,Command c,Instant now,Lifecycle.Deadlines fixtureDeadlines) {
        Objects.requireNonNull(now);
        Policy policy=initial;
        try {
            if(c.op==Op.CREATE) {
                require(!policy.locked(),"LOCKED");
                if(current!=null&&current.serving!=Serving.CLOSED) {
                    if(current.serving==Serving.CLEANUP_REQUIRED)policy=policy.violate("cleanup:"+current.id);
                    throw new Denied("UNRESOLVED_SESSION");
                }
                var state=new State(UUID.randomUUID(),Sensory.preset(c.presetId),0,0,Lid.ATTACHED,Stage.SEALED,Serving.ACTIVE,
                    new Portion(Sensory.bodyMass(Sensory.preset(c.presetId)),Sensory.bodyMass(Sensory.preset(c.presetId)),0,0,0),new Portion(Sensory.sauceMass(Sensory.preset(c.presetId)),Sensory.sauceMass(Sensory.preset(c.presetId)),0,0,0),0,null,fixtureDeadlines,null,null,null);
                return new Transition(state,policy,new Response(true,null,state,policy,false));
            }
            require(current!=null&&current.id.equals(c.session),"NOT_FOUND");
            require(current.revision==c.expectedRevision,"REVISION_CONFLICT");
            require(current.serving!=Serving.CLOSED,"SESSION_CLOSED");
            var d=new Draft(current);
            boolean eating=c.op==Op.PEEL||c.op==Op.SCOOP||c.op==Op.TASTE;
            if(eating) {
                require(!policy.locked(),"LOCKED");
                require(d.deadlines==null||!Lifecycle.age(d.deadlines,now).status().equals("disposal_due"),"DISPOSAL_DUE");
                require(d.serving==Serving.ACTIVE,"FOOD_FINISHED");
            }
            switch(c.op) {
                case PEEL -> {
                    require(d.peel+c.amountQ<=1000,"INVALID_PEEL");d.peel+=c.amountQ;
                    d.stage=d.peel==1000?Stage.OPEN:Stage.PEELING;
                    if(d.peel==1000)d.lid=Lid.DETACHED;
                }
                case LID_DISPOSE -> {require(d.lid==Lid.DETACHED,"NO_DETACHED_LID");d.lid=Lid.IN_BIN;}
                case SCOOP,TASTE -> {
                    require(d.peel==1000,"LID_NOT_OPEN");
                    if(c.op==Op.SCOOP) {
                        require(d.body.spoon+d.sauce.spoon==0,"SPOON_NOT_EMPTY");
                        require(c.bodyMg<=d.body.cup&&c.sauceMg<=d.sauce.cup,"INSUFFICIENT_MASS");
                    } else require(d.body.spoon+d.sauce.spoon>0,"EMPTY_SPOON");
                    if(d.lid==Lid.DETACHED) {policy=policy.violate("lid:"+d.id);throw new Denied("LID_NOT_DISPOSED");}
                    if(c.op==Op.SCOOP) {d.body=d.body.scoop(c.bodyMg);d.sauce=d.sauce.scoop(c.sauceMg);d.stage=Stage.SPOON_LOADED;}
                    else {
                        var aging=d.deadlines==null?null:Lifecycle.age(d.deadlines,now);
                        if(aging!=null&&aging.status().equals("expired_pending_disposal")) {
                            policy=policy.violate("expired:"+c.request);require(!policy.locked(),"LOCKED");
                        }
                        d.taste=new Taste(Sensory.bite(d.preset,d.body.spoon,d.sauce.spoon,true),d.body.spoon,d.sauce.spoon,now,aging);
                        d.body=d.body.taste();d.sauce=d.sauce.taste();d.bites++;
                        if(d.remaining()==0){d.stage=Stage.DEPLETED;d.cleanup(now);}else d.stage=Stage.OPEN;
                    }
                }
                case FINISH -> {require(d.serving==Serving.ACTIVE,"FOOD_FINISHED");d.discard(now);}
                case CLEANUP -> {
                    require(d.serving==Serving.CLEANUP_REQUIRED&&d.remaining()==0,"FOOD_REMAINS");
                    require(d.lid!=Lid.DETACHED,"LID_NOT_DISPOSED");d.lid=Lid.IN_BIN;d.serving=Serving.CLOSED;
                }
                default -> throw new IllegalArgumentException("operation");
            }
            d.revision=Math.incrementExact(d.revision);State next=d.freeze();
            return new Transition(next,policy,new Response(true,null,next,policy,!policy.equals(initial)));
        } catch(Denied denied) {
            return new Transition(current,policy,new Response(false,denied.code,null,policy,!policy.equals(initial)));
        }
    }
    public static State expire(State state,Instant now) {
        if(state==null||state.deadlines==null||state.remaining()==0||!Lifecycle.age(state.deadlines,now).status().equals("disposal_due"))return state;
        var d=new Draft(state);d.discard(now);d.disposalEffective=state.deadlines.disposeAfter();d.disposalProcessed=now;d.revision=Math.incrementExact(d.revision);return d.freeze();
    }
    public static State rest(State state) {
        var d=new Draft(state);d.revision=Math.incrementExact(d.revision);return d.freeze();
    }
    private static final class Draft {
        UUID id;Sensory.Preset preset;int revision,peel,bites;Lid lid;Stage stage;Serving serving;Portion body,sauce;
        Instant cleanupDue,disposalEffective,disposalProcessed;Lifecycle.Deadlines deadlines;Taste taste;
        Draft(State s){id=s.id;preset=s.preset;revision=s.revision;peel=s.peelQ;bites=s.bites;lid=s.lid;stage=s.stage;serving=s.serving;
            body=s.body;sauce=s.sauce;cleanupDue=s.cleanupDue;deadlines=s.deadlines;taste=s.lastTaste;disposalEffective=s.disposalEffective;disposalProcessed=s.disposalProcessed;}
        int remaining(){return body.cup+body.spoon+sauce.cup+sauce.spoon;}
        void cleanup(Instant now){serving=Serving.CLEANUP_REQUIRED;if(cleanupDue==null)cleanupDue=now.plusSeconds(600);}
        void discard(Instant now){if(remaining()>0)stage=Stage.DISPOSED;body=body.discard();sauce=sauce.discard();cleanup(now);}
        State freeze(){return new State(id,preset,revision,peel,lid,stage,serving,body,sauce,bites,cleanupDue,deadlines,taste,disposalEffective,disposalProcessed);}
    }
}
