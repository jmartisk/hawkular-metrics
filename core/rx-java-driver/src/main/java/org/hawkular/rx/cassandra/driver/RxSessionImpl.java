/*
 * Copyright 2014-2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hawkular.rx.cassandra.driver;

import java.util.Iterator;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import com.google.common.util.concurrent.ListenableFuture;

import rx.Observable;
import rx.Scheduler;
import rx.observable.ListenableFutureObservable;
import rx.schedulers.Schedulers;

/**
 * @author jsanda
 * @author Michael Burman
 */
public class RxSessionImpl implements RxSession {

    private Session session;
    private LoadBalancingPolicy loadBalancingPolicy;

    private int maxInFlightLocal = 0;
    private int maxInFlightRemote = 0;

    public RxSessionImpl(Session session) {
        this.session = session;
        this.loadBalancingPolicy = session.getCluster().getConfiguration().getPolicies().getLoadBalancingPolicy();

        PoolingOptions poolingOptions = session.getCluster().getConfiguration().getPoolingOptions();

        maxInFlightLocal = poolingOptions.getCoreConnectionsPerHost(HostDistance.LOCAL) *
                poolingOptions.getMaxRequestsPerConnection(HostDistance.LOCAL);

        maxInFlightRemote = poolingOptions.getCoreConnectionsPerHost(HostDistance.REMOTE) *
                poolingOptions.getMaxRequestsPerConnection(HostDistance.REMOTE);
    }

    @Override
    public String getLoggedKeyspace() {
        return session.getLoggedKeyspace();
    }

    @Override
    public RxSession init() {
        session.init();
        return this;
    }

    private boolean availableInFlightSlots(Statement st) {
        boolean available = false;
        Iterator<Host> hostIterator = loadBalancingPolicy.newQueryPlan(session.getLoggedKeyspace(), st);
        hostIter: while(hostIterator.hasNext()) {
            Host host = hostIterator.next();
            int inFlightQueries = session.getState().getInFlightQueries(host);
            switch(loadBalancingPolicy.distance(host)) {
                case LOCAL:
                    if(inFlightQueries < maxInFlightLocal) {
                        available = true;
                        break hostIter;
                    }
                    break;
                case REMOTE:
                    if(inFlightQueries < maxInFlightRemote) {
                        available = true;
                        break hostIter;
                    }
                    break;
                default:
                    // IGNORED is something we're not going to write to
                    break;
            }
        }
        return available;
    }

    private Observable<ResultSet> scheduleStatement(Statement st, Scheduler scheduler) {
        while(true) {
            if(availableInFlightSlots(st)) {
                ResultSetFuture future = session.executeAsync(st);
                return ListenableFutureObservable.from(future, scheduler);
            } else {
                try {
                    Thread.sleep(0, 1);
                } catch (InterruptedException e) {
                    //
                }
            }
        }
    }

    @Override
    public Observable<ResultSet> execute(String query) {
        return scheduleStatement(new SimpleStatement(query), Schedulers.computation());
    }

    @Override
    public Observable<Row> executeAndFetch(String query) {
        return execute(query).compose(new ResultSetToRowsTransformer());
    }

    @Override
    public Observable<ResultSet> execute(String query, Scheduler scheduler) {
        return scheduleStatement(new SimpleStatement(query), scheduler);
    }

    @Override
    public Observable<Row> executeAndFetch(String query, Scheduler scheduler) {
        return execute(query, scheduler).compose(new ResultSetToRowsTransformer(scheduler));
    }

    @Override
    public Observable<ResultSet> execute(String query, Object... values) {
        ResultSetFuture future = session.executeAsync(query, values);
        return ListenableFutureObservable.from(future, Schedulers.computation());
    }

    @Override
    public Observable<Row> executeAndFetch(String query, Object... values) {
        return execute(query, values).compose(new ResultSetToRowsTransformer());
    }

    @Override
    public Observable<ResultSet> execute(String query, Scheduler scheduler, Object... values) {
        ResultSetFuture future = session.executeAsync(query, values, scheduler);
        return ListenableFutureObservable.from(future, scheduler);
    }

    @Override
    public Observable<Row> executeAndFetch(String query, Scheduler scheduler, Object... values) {
        return execute(query, scheduler, values).compose(new ResultSetToRowsTransformer(scheduler));
    }

    @Override
    public Observable<ResultSet> execute(Statement statement) {
        return scheduleStatement(statement, Schedulers.computation());
    }

    @Override
    public Observable<Row> executeAndFetch(Statement statement) {
        return execute(statement).compose(new ResultSetToRowsTransformer());
    }

    @Override
    public Observable<ResultSet> execute(Statement statement, Scheduler scheduler) {
        return scheduleStatement(statement, scheduler);
    }

    @Override
    public Observable<Row> executeAndFetch(Statement statement, Scheduler scheduler) {
        return execute(statement, scheduler).compose(new ResultSetToRowsTransformer(scheduler));
    }

    @Override
    public Observable<PreparedStatement> prepare(String query) {
        ListenableFuture<PreparedStatement> future = session.prepareAsync(query);
        return ListenableFutureObservable.from(future, Schedulers.computation());
    }

    @Override
    public Observable<PreparedStatement> prepare(String query, Scheduler scheduler) {
        ListenableFuture<PreparedStatement> future = session.prepareAsync(query);
        return ListenableFutureObservable.from(future, scheduler);
    }

    @Override
    public Observable<PreparedStatement> prepare(RegularStatement statement) {
        ListenableFuture<PreparedStatement> future = session.prepareAsync(statement);
        return ListenableFutureObservable.from(future, Schedulers.computation());
    }

    @Override
    public Observable<PreparedStatement> prepare(RegularStatement statement, Scheduler scheduler) {
        ListenableFuture<PreparedStatement> future = session.prepareAsync(statement);
        return ListenableFutureObservable.from(future, scheduler);
    }

    @Override
    public void close() {
        session.close();
    }

    @Override
    public boolean isClosed() {
        return session.isClosed();
    }

    @Override
    public Cluster getCluster() {
        return session.getCluster();
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public Session.State getState() {
        return session.getState();
    }
}
