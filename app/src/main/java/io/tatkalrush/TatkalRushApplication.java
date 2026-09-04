package io.tatkalrush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root. Wires every adapter into the single deployable that runs as
 * {@code app-1} and {@code app-2} behind nginx (SDD §8.3).
 *
 * <p>TatkalRush is a <b>modular monolith deployed as N stateless replicas</b>, and
 * that is a deliberate choice the Architect agent must not override (SDD §8.1). The
 * contention in this system is on a <em>shared resource</em> - overlapping segment
 * ranges on one train's berths - not on a service boundary. Splitting search,
 * booking, payment and charting into separate deployables would add network hops
 * and distributed transactions without reducing that contention at all.
 *
 * <p>What does help is partitioning inventory and giving each partition a single
 * writer (SDD §9.3). That is a data-plane decision, implemented inside this
 * monolith.
 */
@SpringBootApplication(scanBasePackages = "io.tatkalrush")
public class TatkalRushApplication {

    public static void main(String[] args) {
        SpringApplication.run(TatkalRushApplication.class, args);
    }
}
