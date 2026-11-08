package net.flamoris.pudding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Pure review-only fixtures. No financial allocation ledger or posting authority. */
public final class Accounting {
    private Accounting() {}
    public record Line(String side,String account,long amountMinor) {}
    public record Candidate(String id,String asset,String purchase,long amountMinor,Instant effectiveAt,
                            Instant processedAt,String policyVersion,List<Line> lines) {
        public String status(){return "REVIEW_REQUIRED";}
        public String taxStatus(){return "REVIEW_REQUIRED";}
        public boolean externalPosting(){return false;}
    }
    public static Candidate expiry(String asset,String purchase,long amount,long recognized,long refunded,
                                   Instant effective,Instant processed,boolean cleared,String policy) {
        for(String s:List.of(asset,purchase,policy)) if(s.isBlank()||!s.equals(s.strip())||s.length()>256) throw new IllegalArgumentException("identifier");
        if(amount<0||recognized<0||refunded<0||recognized>amount||refunded>amount-recognized||processed.isBefore(effective)) throw new IllegalArgumentException("allocation");
        long residual=amount-recognized-refunded; if(!cleared||residual==0) return null;
        String identity="[\"pudding-experimental-jpy\","+Json.write(asset)+",\"expiry_release\"]";
        String id;
        try {id="expiry-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new Candidate(id,asset,purchase,residual,effective,processed,policy,List.of(
            new Line("debit","契約負債",residual),new Line("credit","期限切れ雑収入",residual)));
    }
    public record TaxRate(String id,String version,String category,String jurisdiction,long numerator,long denominator,Instant from,Instant until) {
        public TaxRate {
            for(String s:List.of(id,version,category,jurisdiction)) if(s.isBlank()||s.length()>256) throw new IllegalArgumentException("tax id");
            if(numerator<0||denominator<=0||from==null||(until!=null&&!until.isAfter(from))) throw new IllegalArgumentException("rate");
        }
    }
    public static TaxRate select(List<TaxRate> rates,String category,String jurisdiction,Instant at) {
        var selected=rates.stream().filter(r->r.category.equals(category)&&r.jurisdiction.equals(jurisdiction)).sorted(Comparator.comparing(TaxRate::from)).toList();
        for(int i=1;i<selected.size();i++) {
            var a=selected.get(i-1); var b=selected.get(i);
            if(a.until==null||a.until.isAfter(b.from)) throw new IllegalArgumentException("overlap");
        }
        return selected.stream().filter(r->!at.isBefore(r.from)&&(r.until==null||at.isBefore(r.until))).findFirst().orElseThrow(()->new IllegalArgumentException("gap"));
    }
    public static BigInteger groupedInclusiveTax(List<Long> gross,TaxRate rate) {
        if(gross.isEmpty()) throw new IllegalArgumentException("empty invoice");
        BigInteger sum=BigInteger.ZERO;
        for(long n:gross) {if(n<0)throw new IllegalArgumentException("amount");sum=sum.add(BigInteger.valueOf(n));}
        return sum.multiply(BigInteger.valueOf(rate.numerator)).divide(BigInteger.valueOf(rate.denominator).add(BigInteger.valueOf(rate.numerator)));
    }
}
