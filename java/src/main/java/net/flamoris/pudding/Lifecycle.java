package net.flamoris.pudding;

import java.time.*;
import java.time.temporal.ChronoUnit;

public final class Lifecycle {
    private Lifecycle() {}
    public record Deadlines(Instant purchased,Instant bestBefore,Instant disposeAfter) {
        public Deadlines {
            if(!bestBefore.equals(purchased.plus(14,ChronoUnit.DAYS)) || !disposeAfter.equals(
                purchased.atZone(ZoneId.of("Asia/Tokyo")).plusMonths(1).toInstant())) throw new IllegalArgumentException("deadlines");
        }
    }
    public record Age(String status,int qualityQ,int rottenEggLikeQ) {
        public Age {
            if(!java.util.Set.of("fresh","expired_pending_disposal","disposal_due").contains(status)||qualityQ<0||qualityQ>1000||rottenEggLikeQ<0||rottenEggLikeQ>1000)throw new IllegalArgumentException("age");
        }
    }
    public static Deadlines deadlines(Instant purchase) {
        return new Deadlines(purchase,purchase.plus(14,ChronoUnit.DAYS),purchase.atZone(ZoneId.of("Asia/Tokyo")).plusMonths(1).toInstant());
    }
    public static Age age(Deadlines d,Instant now) {
        if(now.isBefore(d.bestBefore)) return new Age("fresh",1000,0);
        if(!now.isBefore(d.disposeAfter)) return new Age("disposal_due",0,1000);
        // Calendar deadlines are at most one month apart, so nanoseconds fit long.
        var u=Rational.of(Duration.between(d.bestBefore,now).toNanos(),Duration.between(d.bestBefore,d.disposeAfter).toNanos());
        var rotten=u.sub(Rational.of(3,4)).mul(Rational.of(4));
        return new Age("expired_pending_disposal",Rational.of(1).sub(u).mul(Rational.of(1000)).halfUp(),
            rotten.n().signum()<0?0:rotten.mul(Rational.of(1000)).halfUp());
    }
}
