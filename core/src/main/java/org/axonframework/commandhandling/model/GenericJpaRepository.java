/*
 * Copyright (c) 2010-2015. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.model;

import org.axonframework.commandhandling.model.inspection.AnnotatedAggregate;
import org.axonframework.common.Assert;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.common.lock.NullLockFactory;
import org.axonframework.eventhandling.EventBus;

import javax.persistence.EntityManager;
import java.util.concurrent.Callable;

import static java.lang.String.format;

/**
 * Generic repository implementation that stores JPA annotated aggregates. These aggregates must implement {@link
 * org.axonframework.domain.AggregateRoot} and have the proper JPA Annotations.
 * <p/>
 * Optionally, the repository may be configured with a locking scheme. The repository will always force optimistic
 * locking in the backing data store. The optional lock in the repository is in addition to this optimistic lock. Note
 * that locks on this repository will not be shared with other repository instances.
 * <p/>
 * When this repository is requested to persist changes to an aggregate, it will also flush the EntityManager, to
 * enforce checking of database constraints and optimistic locks.
 *
 * @param <T> The type of aggregate the repository provides access to
 * @author Allard Buijze
 * @since 0.7
 */
public class GenericJpaRepository<T> extends LockingRepository<T, AnnotatedAggregate<T>> {

    private final EntityManagerProvider entityManagerProvider;
    private final EventBus eventBus;
    private boolean forceFlushOnSave = true;

    /**
     * Initialize a repository for storing aggregates of the given <code>aggregateType</code>. No additional locking
     * will be used.
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this EventStore
     * @param aggregateType         the aggregate type this repository manages
     */
    public GenericJpaRepository(EntityManagerProvider entityManagerProvider, Class<T> aggregateType, EventBus eventBus) {
        this(entityManagerProvider, aggregateType, eventBus, new NullLockFactory());
    }

    /**
     * Initialize a repository  for storing aggregates of the given <code>aggregateType</code> with an additional
     * <code> LockFactory</code>.
     *
     * @param entityManagerProvider The EntityManagerProvider providing the EntityManager instance for this repository
     * @param aggregateType         the aggregate type this repository manages
     * @param lockFactory           the additional locking strategy for this repository
     */
    public GenericJpaRepository(EntityManagerProvider entityManagerProvider, Class<T> aggregateType,
                                EventBus eventBus, LockFactory lockFactory) {
        super(aggregateType, lockFactory);
        Assert.notNull(entityManagerProvider, "entityManagerProvider may not be null");
        this.entityManagerProvider = entityManagerProvider;
        this.eventBus = eventBus;
    }

    @Override
    protected AnnotatedAggregate<T> doLoadWithLock(String aggregateIdentifier, Long expectedVersion) {
        T aggregateRoot = entityManagerProvider.getEntityManager().find(getAggregateType(), aggregateIdentifier);
        if (aggregateRoot == null) {
            throw new AggregateNotFoundException(aggregateIdentifier, format(
                    "Aggregate [%s] with identifier [%s] not found",
                    getAggregateType().getSimpleName(),
                    aggregateIdentifier));
        }
        AnnotatedAggregate<T> aggregate = new AnnotatedAggregate<T>(aggregateRoot, aggregateModel(), eventBus);
        if (expectedVersion != null && aggregate.version() != null
                && !expectedVersion.equals(aggregate.version())) {
            throw new ConflictingAggregateVersionException(aggregateIdentifier,
                    expectedVersion,
                    aggregate.version());
        }
        return aggregate;
    }

    @Override
    protected AnnotatedAggregate<T> doCreateNewForLock(Callable<T> factoryMethod) throws Exception {
        return new AnnotatedAggregate<>(factoryMethod.call(), aggregateModel(), eventBus);
    }

    @Override
    protected void doSaveWithLock(AnnotatedAggregate<T> aggregate) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        entityManager.persist(aggregate.getAggregateRoot());
        if (forceFlushOnSave) {
            entityManager.flush();
        }
    }

    @Override
    protected void doDeleteWithLock(AnnotatedAggregate<T> aggregate) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        entityManager.remove(aggregate.getAggregateRoot());
        if (forceFlushOnSave) {
            entityManager.flush();
        }
    }

    /**
     * Indicates whether the EntityManager's state should be flushed each time an aggregate is saved. Defaults to
     * <code>true</code>.
     * <p/>
     * Flushing the EntityManager will force JPA to send state changes to the database. Any key violations and failing
     * optimistic locks will be identified in an early stage.
     *
     * @param forceFlushOnSave whether or not to flush the EntityManager after each save. Defaults to
     *                         <code>true</code>.
     * @see javax.persistence.EntityManager#flush()
     */
    public void setForceFlushOnSave(boolean forceFlushOnSave) {
        this.forceFlushOnSave = forceFlushOnSave;
    }
}
