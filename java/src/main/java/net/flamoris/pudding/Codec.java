package net.flamoris.pudding;

import java.io.*;
import java.time.Instant;
import java.util.*;
import static net.flamoris.pudding.Core.*;

/** Versioned internal DB snapshots. Never Java native object deserialization. */
public final class Codec {
    private Codec() {}
    private static final int MAX=65536;
    @FunctionalInterface private interface Write {void apply(DataOutputStream o)throws IOException;}
    @FunctionalInterface private interface Read<T> {T apply(DataInputStream i)throws IOException;}
    private static byte[] encode(Write f){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeInt(1);f.apply(o);o.flush();if(b.size()>MAX)throw new IllegalArgumentException("snapshot too large");return b.toByteArray();}catch(IOException e){throw new IllegalArgumentException("snapshot encoding",e);}}
    private static <T>T decode(byte[] b,Read<T> f){if(b==null||b.length>MAX)throw new IllegalArgumentException("snapshot size");try(var i=new DataInputStream(new ByteArrayInputStream(b))){if(i.readInt()!=1)throw new IOException("version");T value=f.apply(i);if(i.available()!=0)throw new IOException("trailing bytes");return value;}catch(IOException|RuntimeException e){throw new IllegalArgumentException("invalid snapshot",e);}}
    private static void time(DataOutputStream o,Instant t)throws IOException{o.writeBoolean(t!=null);if(t!=null){o.writeLong(t.getEpochSecond());o.writeInt(t.getNano());}}
    private static Instant time(DataInputStream i)throws IOException{return i.readBoolean()?Instant.ofEpochSecond(i.readLong(),i.readInt()):null;}
    private static void uuid(DataOutputStream o,UUID id)throws IOException{o.writeLong(id.getMostSignificantBits());o.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream i)throws IOException{return new UUID(i.readLong(),i.readLong());}
    private static void features(DataOutputStream o,Map<String,Integer> m)throws IOException{o.writeInt(m.size());for(var e:m.entrySet()){o.writeUTF(e.getKey());o.writeInt(e.getValue()==null?-1:e.getValue());}}
    private static Map<String,Integer> features(DataInputStream i)throws IOException{int size=i.readInt();if(size<0||size>64)throw new IOException("features");var m=new TreeMap<String,Integer>();for(int n=0;n<size;n++){String k=i.readUTF();int v=i.readInt();if(m.containsKey(k)||v< -1)throw new IOException("feature");m.put(k,v==-1?null:v);}return Sensory.features(m);}
    private static void portion(DataOutputStream o,Portion p)throws IOException{o.writeInt(p.initial());o.writeInt(p.cup());o.writeInt(p.spoon());o.writeInt(p.consumed());o.writeInt(p.discarded());}
    private static Portion portion(DataInputStream i)throws IOException{return new Portion(i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt());}
    private static <E>E enumeration(DataInputStream i,E[] values)throws IOException{int n=i.readInt();if(n<0||n>=values.length)throw new IOException("enum");return values[n];}
    private static void state(DataOutputStream o,State s)throws IOException{
        uuid(o,s.id());o.writeUTF(s.preset().id());features(o,s.preset().body());features(o,s.preset().sauce());
        o.writeInt(s.revision());o.writeInt(s.peelQ());o.writeInt(s.lid().ordinal());o.writeInt(s.stage().ordinal());o.writeInt(s.serving().ordinal());
        portion(o,s.body());portion(o,s.sauce());o.writeInt(s.bites());time(o,s.cleanupDue());
        time(o,s.deadlines()==null?null:s.deadlines().purchased());
        if(s.deadlines()!=null){time(o,s.deadlines().bestBefore());time(o,s.deadlines().disposeAfter());}
        var t=s.lastTaste();o.writeBoolean(t!=null);
        if(t!=null){features(o,t.features());o.writeInt(t.bodyMg());o.writeInt(t.sauceMg());time(o,t.evaluatedAt());
            o.writeBoolean(t.aging()!=null);if(t.aging()!=null){o.writeUTF(t.aging().status());o.writeInt(t.aging().qualityQ());o.writeInt(t.aging().rottenEggLikeQ());}}
        time(o,s.disposalEffective());time(o,s.disposalProcessed());
    }
    private static State state(DataInputStream i)throws IOException{
        UUID id=uuid(i);var p=new Sensory.Preset(i.readUTF(),features(i),features(i));int revision=i.readInt(),peel=i.readInt();
        var lid=enumeration(i,Lid.values());var stage=enumeration(i,Stage.values());var serving=enumeration(i,Serving.values());
        var body=portion(i);var sauce=portion(i);int bites=i.readInt();var due=time(i);var purchase=time(i);Taste taste=null;
        var deadlines=purchase==null?null:new Lifecycle.Deadlines(purchase,time(i),time(i));
        if(i.readBoolean()){var f=features(i);int b=i.readInt(),s=i.readInt();var at=time(i);var a=i.readBoolean()?new Lifecycle.Age(i.readUTF(),i.readInt(),i.readInt()):null;taste=new Taste(f,b,s,at,a);}
        return new State(id,p,revision,peel,lid,stage,serving,body,sauce,bites,due,deadlines,taste,time(i),time(i));
    }
    private static void policy(DataOutputStream o,Policy p)throws IOException{o.writeInt(p.threshold());o.writeInt(p.obligations().size());for(String k:new TreeSet<>(p.obligations()))o.writeUTF(k);}
    private static Policy policy(DataInputStream i)throws IOException{int threshold=i.readInt(),size=i.readInt();if(size<0||size>100)throw new IOException("policy size");var keys=new HashSet<String>();for(int n=0;n<size;n++)if(!keys.add(i.readUTF()))throw new IOException("duplicate obligation");return new Policy(threshold,keys);}
    public static byte[] state(State s){return encode(o->state(o,s));}
    public static State state(byte[] b){return decode(b,Codec::state);}
    public static byte[] policy(Policy p){return encode(o->policy(o,p));}
    public static Policy policy(byte[] b){return decode(b,Codec::policy);}
    public static byte[] response(Response r){return encode(o->{o.writeBoolean(r.ok());if(r.ok())state(o,r.session());else o.writeUTF(r.error());policy(o,r.policy());o.writeBoolean(r.policyChanged());});}
    public static Response response(byte[] b){return decode(b,i->{boolean ok=i.readBoolean();State s=ok?state(i):null;String error=ok?null:i.readUTF();var p=policy(i);return new Response(ok,error,s,p,i.readBoolean());});}
    public static byte[] command(Command c){return encode(o->{uuid(o,c.request());o.writeInt(c.op().ordinal());o.writeBoolean(c.session()!=null);if(c.session()!=null)uuid(o,c.session());o.writeInt(c.expectedRevision());o.writeInt(c.amountQ());o.writeInt(c.bodyMg());o.writeInt(c.sauceMg());o.writeBoolean(c.presetId()!=null);if(c.presetId()!=null)o.writeUTF(c.presetId());});}
}
