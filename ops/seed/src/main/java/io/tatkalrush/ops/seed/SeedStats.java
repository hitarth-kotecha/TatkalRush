package io.tatkalrush.ops.seed;

/** Row counts and timing from one seeding run. Reported against AC-0.2. */
public final class SeedStats {

    public int stations;
    public int trains;
    public int hotTrains;
    public int trainStops;
    public int coaches;
    public int berths;
    public int users;
    public int schedules;
    public int quotaPools;
    public int poolBerths;
    public long elapsedMillis;

    @Override
    public String toString() {
        return """
               stations      %,10d
               trains        %,10d  (%d hot, FR-49)
               train_stops   %,10d
               coaches       %,10d
               berths        %,10d  physical
               users         %,10d  (FR-69 floor: 5,000)
               schedules     %,10d
               quota_pools   %,10d
               pool_berths   %,10d  bookable berth-instances
               ------------------------------
               elapsed       %,10d ms  (AC-0.2 budget: 60,000 ms)"""
                .formatted(
                        stations, trains, hotTrains, trainStops, coaches, berths,
                        users, schedules, quotaPools, poolBerths, elapsedMillis);
    }
}
