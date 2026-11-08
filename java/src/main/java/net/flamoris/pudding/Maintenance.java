package net.flamoris.pudding;

import java.time.*;
import java.util.concurrent.*;

/** Owned, bounded daily catch-up; downtime never extends purchase deadlines. */
public final class Maintenance implements AutoCloseable {
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    private final CommerceStore store;
    private final Clock clock;
    private LocalDate completed;
    public Maintenance(CommerceStore store,Clock clock){this.store=store;this.clock=clock;}
    public void start(){executor.scheduleWithFixedDelay(this::tick,0,30,TimeUnit.SECONDS);}
    void tick() {
        try {
            store.heartbeat();var now=clock.instant().atZone(ZoneId.of("Asia/Tokyo"));
            LocalDate date=now.toLocalTime().isBefore(LocalTime.of(3,0))?now.toLocalDate().minusDays(1):now.toLocalDate();
            if(!date.equals(completed)) {int count=store.expireBatch(100);store.cleanupBatch(100);if(count<100){store.dailyCompleted(date);completed=date;}}
        }catch(Exception e){System.err.println("Pudding maintenance deferred; inspect protected database and readiness.");}
    }
    @Override public void close(){executor.shutdownNow();try{executor.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
