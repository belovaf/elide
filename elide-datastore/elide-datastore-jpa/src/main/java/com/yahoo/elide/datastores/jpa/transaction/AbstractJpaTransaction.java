/*
 * Copyright 2018, Oath Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.datastores.jpa.transaction;

import com.yahoo.elide.core.DataStoreTransaction;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.exceptions.TransactionException;
import com.yahoo.elide.core.filter.FilterPredicate;
import com.yahoo.elide.core.filter.Operator;
import com.yahoo.elide.core.filter.expression.AndFilterExpression;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.hibernate.hql.*;
import com.yahoo.elide.core.pagination.Pagination;
import com.yahoo.elide.core.sort.Sorting;
import com.yahoo.elide.datastores.jpa.porting.EntityManagerWrapper;
import com.yahoo.elide.datastores.jpa.porting.QueryWrapper;
import com.yahoo.elide.datastores.jpa.transaction.checker.PersistentCollectionChecker;
import com.yahoo.elide.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceException;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Predicate;


public abstract class AbstractJpaTransaction implements JpaTransaction {
    private static final Logger log = LoggerFactory.getLogger(AbstractJpaTransaction.class);
    private static final Predicate<Collection<?>> isPersistentCollection =
            new PersistentCollectionChecker();

    protected final EntityManager entityManager;
    private final EntityManagerWrapper entityManagerWrapper;
    private final LinkedHashSet<Runnable> deferredTasks = new LinkedHashSet<>();

    protected AbstractJpaTransaction(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.entityManagerWrapper = new EntityManagerWrapper(entityManager);
    }

    @Override
    public void delete(Object object, RequestScope scope) {
        deferredTasks.add(() -> entityManager.remove(object));
    }

    @Override
    public void save(Object object, RequestScope scope) {
        deferredTasks.add(() -> entityManager.merge(object));
    }

    @Override
    public void flush(RequestScope requestScope) {
        try {
            deferredTasks.forEach(Runnable::run);
            deferredTasks.clear();
            FlushModeType flushMode = entityManager.getFlushMode();
            if (flushMode == FlushModeType.AUTO) {
                entityManager.flush();
            }
        } catch (PersistenceException e) {
            log.error("Caught entity manager exception during flush", e);
            throw new TransactionException(e);
        }
    }

    @Override
    public void commit(RequestScope scope) {
        flush(scope);
    }

    @Override
    public void rollback() {
        deferredTasks.clear();
    }

    @Override
    public void close() throws IOException {
        if (deferredTasks.size() > 0) {
            rollback();
            throw new IOException("Transaction not closed");
        }
    }

    @Override
    public void createObject(Object entity, RequestScope scope) {
        deferredTasks.add(() -> entityManager.persist(entity));
    }

    /**
     * load a single record with id and filter.
     *
     * @param entityClass      class of query object
     * @param id               id of the query object
     * @param filterExpression FilterExpression contains the predicates
     * @param scope            Request scope associated with specific request
     */
    @Override
    public Object loadObject(Class<?> entityClass,
                             Serializable id,
                             Optional<FilterExpression> filterExpression,
                             RequestScope scope) {

        try {
            EntityDictionary dictionary = scope.getDictionary();
            Class<?> idType = dictionary.getIdType(entityClass);
            String idField = dictionary.getIdFieldName(entityClass);

            //Construct a predicate that selects an individual element of the relationship's parent (Author.id = 3).
            FilterPredicate idExpression;
            Path.PathElement idPath = new Path.PathElement(entityClass, idType, idField);
            if (id != null) {
                idExpression = new FilterPredicate(idPath, Operator.IN, Collections.singletonList(id));
            } else {
                idExpression = new FilterPredicate(idPath, Operator.FALSE, Collections.emptyList());
            }

            FilterExpression joinedExpression = filterExpression
                    .map(fe -> (FilterExpression) new AndFilterExpression(fe, idExpression))
                    .orElse(idExpression);

            QueryWrapper query =
                    (QueryWrapper) new RootCollectionFetchQueryBuilder(entityClass, dictionary, entityManagerWrapper)
                            .withPossibleFilterExpression(Optional.of(joinedExpression))
                            .build();

            return query.getQuery().getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Override
    public Iterable<Object> loadObjects(
            Class<?> entityClass,
            Optional<FilterExpression> filterExpression,
            Optional<Sorting> sorting,
            Optional<Pagination> pagination,
            RequestScope scope) {

        pagination.ifPresent(p -> {
            if (p.isGenerateTotals()) {
                p.setPageTotals(getTotalRecords(entityClass, filterExpression, scope.getDictionary()));
            }
        });

        final QueryWrapper query =
                (QueryWrapper) new RootCollectionFetchQueryBuilder(entityClass, scope.getDictionary(), entityManagerWrapper)
                        .withPossibleFilterExpression(filterExpression)
                        .withPossibleSorting(sorting)
                        .withPossiblePagination(pagination)
                        .build();

        return query.getQuery().getResultList();
    }

    @Override
    public Object getRelation(
            DataStoreTransaction relationTx,
            Object entity,
            String relationName,
            Optional<FilterExpression> filterExpression,
            Optional<Sorting> sorting,
            Optional<Pagination> pagination,
            RequestScope scope) {

        EntityDictionary dictionary = scope.getDictionary();
        Object val = com.yahoo.elide.core.PersistentResource.getValue(entity, relationName, scope);
        if (val instanceof Collection) {
            Collection filteredVal = (Collection) val;
            if (isPersistentCollection.test(filteredVal)) {
                Class<?> relationClass = dictionary.getParameterizedType(entity, relationName);

                RelationshipImpl relationship = new RelationshipImpl(
                        dictionary.lookupEntityClass(entity.getClass()),
                        relationClass,
                        relationName,
                        entity,
                        filteredVal);

                pagination.ifPresent(p -> {
                    if (p.isGenerateTotals()) {
                        p.setPageTotals(getTotalRecords(relationship, filterExpression, dictionary));
                    }
                });

                final QueryWrapper query = (QueryWrapper)
                        new SubCollectionFetchQueryBuilder(relationship, dictionary, entityManagerWrapper)
                                .withPossibleFilterExpression(filterExpression)
                                .withPossibleSorting(sorting)
                                .withPossiblePagination(pagination)
                                .build();

                if (query != null) {
                    return query.getQuery().getResultList();
                }
            }
        }
        return val;
    }

    /**
     * Returns the total record count for a root entity and an optional filter expression.
     *
     * @param entityClass      The entity type to count
     * @param filterExpression optional security and request filters
     * @param dictionary       the entity dictionary
     * @param <T>              The type of entity
     * @return The total row count.
     */
    private <T> Long getTotalRecords(Class<T> entityClass,
                                     Optional<FilterExpression> filterExpression,
                                     EntityDictionary dictionary) {


        QueryWrapper query = (QueryWrapper)
                new RootCollectionPageTotalsQueryBuilder(entityClass, dictionary, entityManagerWrapper)
                        .withPossibleFilterExpression(filterExpression)
                        .build();

        return (Long) query.getQuery().getSingleResult();
    }

    /**
     * Returns the total record count for a entity relationship
     *
     * @param relationship     The relationship
     * @param filterExpression optional security and request filters
     * @param dictionary       the entity dictionary
     * @param <T>              The type of entity
     * @return The total row count.
     */
    private <T> Long getTotalRecords(AbstractHQLQueryBuilder.Relationship relationship,
                                     Optional<FilterExpression> filterExpression,
                                     EntityDictionary dictionary) {

        QueryWrapper query = (QueryWrapper)
                new SubCollectionPageTotalsQueryBuilder(relationship, dictionary, entityManagerWrapper)
                        .withPossibleFilterExpression(filterExpression)
                        .build();

        return (Long) query.getQuery().getSingleResult();
    }

    @Override
    public User accessUser(Object opaqueUser) {
        return new User(opaqueUser);
    }
}
