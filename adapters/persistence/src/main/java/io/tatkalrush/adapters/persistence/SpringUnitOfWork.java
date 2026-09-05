package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.UnitOfWork;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UnitOfWork} on Spring's transaction manager.
 *
 * <p>The port is framework-free because the transaction <em>boundary</em> is part
 * of the design — FR-19's guarantee depends on the idempotency claim and the
 * booking insert committing together. The <em>mechanism</em> is Spring's, and that
 * split is the whole point of having the port: use cases say where a transaction
 * begins and ends; this class knows how.
 *
 * <h2>Propagation is REQUIRED, and that is a correctness choice</h2>
 *
 * <p>A nested {@code inTransaction} joins the caller's transaction rather than
 * opening its own. With {@code REQUIRES_NEW} it would take a <b>second connection
 * from the pool</b> and then ask to {@code SELECT ... FOR UPDATE} a row the outer
 * transaction already holds — and block on itself until the pool's timeout expires.
 *
 * <p>That failure is worth naming precisely because of how it presents: not as an
 * exception at the point of the mistake, but as a hang, under load, in the code
 * path that handles money, on a machine whose connection pool is the resource
 * NFR-11's memory budget already constrains. A pool of twenty serving requests that
 * each want two connections is a pool of ten that deadlocks when eleven arrive.
 *
 * <p>Because every repository resolves its connection through Spring's
 * {@code DataSourceUtils} — which {@code JdbcClient} does — joining the transaction
 * is also what makes them share the connection, and therefore the locks.
 */
public final class SpringUnitOfWork implements UnitOfWork {

    private final TransactionTemplate template;

    public SpringUnitOfWork(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work, Predicate<T> rollbackIf) {
        return template.execute(
                status -> {
                    T result = work.get();
                    if (rollbackIf.test(result)) {
                        // Discard the work, keep the answer. FR-51 calls
                        // SEAT_UNAVAILABLE a correct outcome, so it must not be
                        // signalled by an exception - but the idempotency claim
                        // still has to go, or the client's key is burnt.
                        status.setRollbackOnly();
                    }
                    return result;
                });
        // An exception thrown by work() propagates and TransactionTemplate rolls
        // back, which is the other half of the port's contract and needs no code.
    }
}
