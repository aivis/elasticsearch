/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.replication;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.engine.InternalEngineTests;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.RecoveryTarget;
import org.elasticsearch.test.junit.annotations.TestLogging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

public class RecoveryDuringReplicationTests extends ESIndexLevelReplicationTestCase {

    public void testIndexingDuringFileRecovery() throws Exception {
        try (ReplicationGroup shards = createGroup(randomInt(1))) {
            shards.startAll();
            int docs = shards.indexDocs(randomInt(50));
            shards.flush();
            IndexShard replica = shards.addReplica();
            final CountDownLatch recoveryBlocked = new CountDownLatch(1);
            final CountDownLatch releaseRecovery = new CountDownLatch(1);
            final RecoveryState.Stage blockOnStage = randomFrom(BlockingTarget.SUPPORTED_STAGES);
            final Future<Void> recoveryFuture = shards.asyncRecoverReplica(replica, (indexShard, node) ->
                new BlockingTarget(blockOnStage, recoveryBlocked, releaseRecovery, indexShard, node, recoveryListener, logger));

            recoveryBlocked.await();
            docs += shards.indexDocs(randomInt(20));
            releaseRecovery.countDown();
            recoveryFuture.get();

            shards.assertAllEqual(docs);
        }
    }

    public void testRecoveryOfDisconnectedReplica() throws Exception {
        try (ReplicationGroup shards = createGroup(1)) {
            shards.startAll();
            int docs = shards.indexDocs(randomInt(50));
            shards.flush();
            final IndexShard originalReplica = shards.getReplicas().get(0);
            long replicaCommittedLocalCheckpoint = docs - 1;
            boolean replicaHasDocsSinceLastFlushedCheckpoint = false;
            for (int i = 0; i < randomInt(2); i++) {
                final int indexedDocs = shards.indexDocs(randomInt(5));
                docs += indexedDocs;
                if (indexedDocs > 0) {
                    replicaHasDocsSinceLastFlushedCheckpoint = true;
                }

                final boolean flush = randomBoolean();
                if (flush) {
                    originalReplica.flush(new FlushRequest());
                    replicaHasDocsSinceLastFlushedCheckpoint = false;
                    replicaCommittedLocalCheckpoint = docs - 1;
                }
            }

            // simulate a background global checkpoint sync at which point we expect the global checkpoint to advance on the replicas
            shards.syncGlobalCheckpoint();

            shards.removeReplica(originalReplica);

            final int missingOnReplica = shards.indexDocs(randomInt(5));
            docs += missingOnReplica;
            replicaHasDocsSinceLastFlushedCheckpoint |= missingOnReplica > 0;

            final boolean flushPrimary = randomBoolean();
            if (flushPrimary) {
                shards.flush();
            }

            originalReplica.close("disconnected", false);
            IOUtils.close(originalReplica.store());
            final IndexShard recoveredReplica =
                shards.addReplicaWithExistingPath(originalReplica.shardPath(), originalReplica.routingEntry().currentNodeId());
            shards.recoverReplica(recoveredReplica);
            if (flushPrimary && replicaHasDocsSinceLastFlushedCheckpoint) {
                // replica has something to catch up with, but since we flushed the primary, we should fall back to full recovery
                assertThat(recoveredReplica.recoveryState().getIndex().fileDetails(), not(empty()));
            } else {
                assertThat(recoveredReplica.recoveryState().getIndex().fileDetails(), empty());
                assertThat(
                    recoveredReplica.recoveryState().getTranslog().recoveredOperations(),
                    equalTo(Math.toIntExact(docs - (replicaCommittedLocalCheckpoint + 1))));
            }

            docs += shards.indexDocs(randomInt(5));

            shards.assertAllEqual(docs);
        }
    }

    @TestLogging("org.elasticsearch.index.shard:TRACE,org.elasticsearch.indices.recovery:TRACE")
    public void testRecoveryAfterPrimaryPromotion() throws Exception {
        try (ReplicationGroup shards = createGroup(2)) {
            shards.startAll();
            int totalDocs = shards.indexDocs(randomInt(10));
            int committedDocs = 0;
            if (randomBoolean()) {
                shards.flush();
                committedDocs = totalDocs;
            }
            // we need some indexing to happen to transfer local checkpoint information to the primary
            // so it can update the global checkpoint and communicate to replicas
            boolean expectSeqNoRecovery = totalDocs > 0;


            final IndexShard oldPrimary = shards.getPrimary();
            final IndexShard newPrimary = shards.getReplicas().get(0);
            final IndexShard replica = shards.getReplicas().get(1);
            if (randomBoolean()) {
                // simulate docs that were inflight when primary failed, these will be rolled back
                final int rollbackDocs = randomIntBetween(1, 5);
                logger.info("--> indexing {} rollback docs", rollbackDocs);
                for (int i = 0; i < rollbackDocs; i++) {
                    final IndexRequest indexRequest = new IndexRequest(index.getName(), "type", "rollback_" + i)
                            .source("{}", XContentType.JSON);
                    final BulkShardRequest bulkShardRequest = indexOnPrimary(indexRequest, oldPrimary);
                    indexOnReplica(bulkShardRequest, replica);
                }
                if (randomBoolean()) {
                    oldPrimary.flush(new FlushRequest(index.getName()));
                    expectSeqNoRecovery = false;
                }
            }

            shards.promoteReplicaToPrimary(newPrimary);
            // index some more
            totalDocs += shards.indexDocs(randomIntBetween(0, 5));

            oldPrimary.close("demoted", false);
            oldPrimary.store().close();

            IndexShard newReplica = shards.addReplicaWithExistingPath(oldPrimary.shardPath(), oldPrimary.routingEntry().currentNodeId());
            shards.recoverReplica(newReplica);

            if (expectSeqNoRecovery) {
                assertThat(newReplica.recoveryState().getIndex().fileDetails(), empty());
                assertThat(newReplica.recoveryState().getTranslog().recoveredOperations(), equalTo(totalDocs - committedDocs));
            } else {
                assertThat(newReplica.recoveryState().getIndex().fileDetails(), not(empty()));
                assertThat(newReplica.recoveryState().getTranslog().recoveredOperations(), equalTo(totalDocs - committedDocs));
            }

            shards.removeReplica(replica);
            replica.close("resync", false);
            replica.store().close();
            newReplica = shards.addReplicaWithExistingPath(replica.shardPath(), replica.routingEntry().currentNodeId());
            shards.recoverReplica(newReplica);

            shards.assertAllEqual(totalDocs);
        }
    }

    @TestLogging(
            "_root:DEBUG,"
                    + "org.elasticsearch.action.bulk:TRACE,"
                    + "org.elasticsearch.action.get:TRACE,"
                    + "org.elasticsearch.cluster.service:TRACE,"
                    + "org.elasticsearch.discovery:TRACE,"
                    + "org.elasticsearch.indices.cluster:TRACE,"
                    + "org.elasticsearch.indices.recovery:TRACE,"
                    + "org.elasticsearch.index.seqno:TRACE,"
                    + "org.elasticsearch.index.shard:TRACE")
    public void testWaitForPendingSeqNo() throws Exception {
        IndexMetaData metaData = buildIndexMetaData(1);

        final int pendingDocs = randomIntBetween(1, 5);
        final BlockingEngineFactory primaryEngineFactory = new BlockingEngineFactory();

        try (ReplicationGroup shards = new ReplicationGroup(metaData) {
            @Override
            protected EngineFactory getEngineFactory(ShardRouting routing) {
                if (routing.primary()) {
                    return primaryEngineFactory;
                } else {
                    return null;
                }
            }
        }) {
            shards.startAll();
            int docs = shards.indexDocs(randomIntBetween(1, 10));
            // simulate a background global checkpoint sync at which point we expect the global checkpoint to advance on the replicas
            shards.syncGlobalCheckpoint();
            IndexShard replica = shards.getReplicas().get(0);
            shards.removeReplica(replica);
            closeShards(replica);

            docs += pendingDocs;
            primaryEngineFactory.latchIndexers();
            CountDownLatch pendingDocsDone = new CountDownLatch(pendingDocs);
            for (int i = 0; i < pendingDocs; i++) {
                final String id = "pending_" + i;
                threadPool.generic().submit(() -> {
                    try {
                        shards.index(new IndexRequest(index.getName(), "type", id).source("{}", XContentType.JSON));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    } finally {
                        pendingDocsDone.countDown();
                    }
                });
            }

            // wait for the pending ops to "hang"
            primaryEngineFactory.awaitIndexersLatch();

            primaryEngineFactory.allowIndexing();
            // index some more
            docs += shards.indexDocs(randomInt(5));

            IndexShard newReplica = shards.addReplicaWithExistingPath(replica.shardPath(), replica.routingEntry().currentNodeId());

            CountDownLatch recoveryStart = new CountDownLatch(1);
            AtomicBoolean preparedForTranslog = new AtomicBoolean(false);
            final Future<Void> recoveryFuture = shards.asyncRecoverReplica(newReplica, (indexShard, node) -> {
                recoveryStart.countDown();
                return new RecoveryTarget(indexShard, node, recoveryListener, l -> {
                }) {
                    @Override
                    public void prepareForTranslogOperations(int totalTranslogOps) throws IOException {
                        preparedForTranslog.set(true);
                        super.prepareForTranslogOperations(totalTranslogOps);
                    }
                };
            });

            recoveryStart.await();

            // index some more
            docs += shards.indexDocs(randomInt(5));

            assertFalse("recovery should wait on pending docs", preparedForTranslog.get());

            primaryEngineFactory.releaseLatchedIndexers();
            pendingDocsDone.await();

            // now recovery can finish
            recoveryFuture.get();

            assertThat(newReplica.recoveryState().getIndex().fileDetails(), empty());
            assertThat(newReplica.recoveryState().getTranslog().recoveredOperations(), equalTo(docs));

            shards.assertAllEqual(docs);
        } finally {
            primaryEngineFactory.close();
        }
    }

    @TestLogging(
            "_root:DEBUG,"
                    + "org.elasticsearch.action.bulk:TRACE,"
                    + "org.elasticsearch.action.get:TRACE,"
                    + "org.elasticsearch.cluster.service:TRACE,"
                    + "org.elasticsearch.discovery:TRACE,"
                    + "org.elasticsearch.indices.cluster:TRACE,"
                    + "org.elasticsearch.indices.recovery:TRACE,"
                    + "org.elasticsearch.index.seqno:TRACE,"
                    + "org.elasticsearch.index.shard:TRACE")
    public void testCheckpointsAndMarkingInSync() throws Exception {
        final IndexMetaData metaData = buildIndexMetaData(0);
        final BlockingEngineFactory replicaEngineFactory = new BlockingEngineFactory();
        try (
                ReplicationGroup shards = new ReplicationGroup(metaData) {
                    @Override
                    protected EngineFactory getEngineFactory(final ShardRouting routing) {
                        if (routing.primary()) {
                            return null;
                        } else {
                            return replicaEngineFactory;
                        }
                    }
                };
                AutoCloseable ignored = replicaEngineFactory // make sure we release indexers before closing
        ) {
            shards.startPrimary();
            final int docs = shards.indexDocs(randomIntBetween(1, 10));
            logger.info("indexed [{}] docs", docs);
            final CountDownLatch pendingDocDone = new CountDownLatch(1);
            final CountDownLatch pendingDocActiveWithExtraDocIndexed = new CountDownLatch(1);
            final CountDownLatch phaseTwoStartLatch = new CountDownLatch(1);
            final IndexShard replica = shards.addReplica();
            final Future<Void> recoveryFuture = shards.asyncRecoverReplica(
                    replica,
                    (indexShard, node) -> new RecoveryTarget(indexShard, node, recoveryListener, l -> {}) {
                        @Override
                        public long indexTranslogOperations(final List<Translog.Operation> operations, final int totalTranslogOps) {
                            // index a doc which is not part of the snapshot, but also does not complete on replica
                            replicaEngineFactory.latchIndexers();
                            threadPool.generic().submit(() -> {
                                try {
                                    shards.index(new IndexRequest(index.getName(), "type", "pending").source("{}", XContentType.JSON));
                                } catch (final Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    pendingDocDone.countDown();
                                }
                            });
                            try {
                                // the pending doc is latched in the engine
                                replicaEngineFactory.awaitIndexersLatch();
                                // unblock indexing for the next doc
                                replicaEngineFactory.allowIndexing();
                                shards.index(new IndexRequest(index.getName(), "type", "completed").source("{}", XContentType.JSON));
                                pendingDocActiveWithExtraDocIndexed.countDown();
                            } catch (final Exception e) {
                                throw new AssertionError(e);
                            }
                            try {
                                phaseTwoStartLatch.await();
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                            return super.indexTranslogOperations(operations, totalTranslogOps);
                        }
                    });
            pendingDocActiveWithExtraDocIndexed.await();
            assertThat(pendingDocDone.getCount(), equalTo(1L));
            {
                final long expectedDocs = docs + 2L;
                assertThat(shards.getPrimary().getLocalCheckpoint(), equalTo(expectedDocs - 1));
                // recovery has not completed, therefore the global checkpoint can have advanced on the primary
                assertThat(shards.getPrimary().getGlobalCheckpoint(), equalTo(expectedDocs - 1));
                // the pending document is not done, the checkpoints can not have advanced on the replica
                assertThat(replica.getLocalCheckpoint(), lessThan(expectedDocs - 1));
                assertThat(replica.getGlobalCheckpoint(), lessThan(expectedDocs - 1));
            }

            // wait for recovery to enter the translog phase
            phaseTwoStartLatch.countDown();

            // wait for the translog phase to complete and the recovery to block global checkpoint advancement
            awaitBusy(() -> shards.getPrimary().pendingInSync());
            {
                shards.index(new IndexRequest(index.getName(), "type", "last").source("{}", XContentType.JSON));
                final long expectedDocs = docs + 3L;
                assertThat(shards.getPrimary().getLocalCheckpoint(), equalTo(expectedDocs - 1));
                // recovery is now in the process of being completed, therefore the global checkpoint can not have advanced on the primary
                assertThat(shards.getPrimary().getGlobalCheckpoint(), equalTo(expectedDocs - 2));
                assertThat(replica.getLocalCheckpoint(), lessThan(expectedDocs - 2));
                assertThat(replica.getGlobalCheckpoint(), lessThan(expectedDocs - 2));
            }

            replicaEngineFactory.releaseLatchedIndexers();
            pendingDocDone.await();
            recoveryFuture.get();
            {
                final long expectedDocs = docs + 3L;
                assertBusy(() -> {
                    assertThat(shards.getPrimary().getLocalCheckpoint(), equalTo(expectedDocs - 1));
                    assertThat(shards.getPrimary().getGlobalCheckpoint(), equalTo(expectedDocs - 1));
                    assertThat(replica.getLocalCheckpoint(), equalTo(expectedDocs - 1));
                    // the global checkpoint advances can only advance here if a background global checkpoint sync fires
                    assertThat(replica.getGlobalCheckpoint(), anyOf(equalTo(expectedDocs - 1), equalTo(expectedDocs - 2)));
                });
            }
        }
    }

    private static class BlockingTarget extends RecoveryTarget {

        private final CountDownLatch recoveryBlocked;
        private final CountDownLatch releaseRecovery;
        private final RecoveryState.Stage stageToBlock;
        static final EnumSet<RecoveryState.Stage> SUPPORTED_STAGES =
            EnumSet.of(RecoveryState.Stage.INDEX, RecoveryState.Stage.TRANSLOG, RecoveryState.Stage.FINALIZE);
        private final Logger logger;

        BlockingTarget(RecoveryState.Stage stageToBlock, CountDownLatch recoveryBlocked, CountDownLatch releaseRecovery, IndexShard shard,
                       DiscoveryNode sourceNode, PeerRecoveryTargetService.RecoveryListener listener, Logger logger) {
            super(shard, sourceNode, listener, version -> {});
            this.recoveryBlocked = recoveryBlocked;
            this.releaseRecovery = releaseRecovery;
            this.stageToBlock = stageToBlock;
            this.logger = logger;
            if (SUPPORTED_STAGES.contains(stageToBlock) == false) {
                throw new UnsupportedOperationException(stageToBlock + " is not supported");
            }
        }

        private boolean hasBlocked() {
            return recoveryBlocked.getCount() == 0;
        }

        private void blockIfNeeded(RecoveryState.Stage currentStage) {
            if (currentStage == stageToBlock) {
                logger.info("--> blocking recovery on stage [{}]", currentStage);
                recoveryBlocked.countDown();
                try {
                    releaseRecovery.await();
                    logger.info("--> recovery continues from stage [{}]", currentStage);
                } catch (InterruptedException e) {
                    throw new RuntimeException("blockage released");
                }
            }
        }

        @Override
        public long indexTranslogOperations(List<Translog.Operation> operations, int totalTranslogOps) {
            if (hasBlocked() == false) {
                blockIfNeeded(RecoveryState.Stage.TRANSLOG);
            }
            return super.indexTranslogOperations(operations, totalTranslogOps);
        }

        @Override
        public void cleanFiles(int totalTranslogOps, Store.MetadataSnapshot sourceMetaData) throws IOException {
            blockIfNeeded(RecoveryState.Stage.INDEX);
            super.cleanFiles(totalTranslogOps, sourceMetaData);
        }

        @Override
        public void finalizeRecovery(long globalCheckpoint) {
            if (hasBlocked() == false) {
                // it maybe that not ops have been transferred, block now
                blockIfNeeded(RecoveryState.Stage.TRANSLOG);
            }
            blockIfNeeded(RecoveryState.Stage.FINALIZE);
            super.finalizeRecovery(globalCheckpoint);
        }

    }

    static class BlockingEngineFactory implements EngineFactory, AutoCloseable {

        private final List<CountDownLatch> blocks = new ArrayList<>();

        private final AtomicReference<CountDownLatch> blockReference = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> blockedIndexers = new AtomicReference<>();

        public synchronized void latchIndexers() {
            final CountDownLatch block = new CountDownLatch(1);
            blocks.add(block);
            blockedIndexers.set(new CountDownLatch(1));
            assert blockReference.compareAndSet(null, block);
        }

        public void awaitIndexersLatch() throws InterruptedException {
            blockedIndexers.get().await();
        }

        public synchronized void allowIndexing() {
            final CountDownLatch previous = blockReference.getAndSet(null);
            assert previous == null || blocks.contains(previous);
        }

        public synchronized void releaseLatchedIndexers() {
            allowIndexing();
            blocks.forEach(CountDownLatch::countDown);
            blocks.clear();
        }

        @Override
        public Engine newReadWriteEngine(final EngineConfig config) {
            return InternalEngineTests.createInternalEngine(
                    (directory, writerConfig) ->
                            new IndexWriter(directory, writerConfig) {
                                @Override
                                public long addDocument(final Iterable<? extends IndexableField> doc) throws IOException {
                                    final CountDownLatch block = blockReference.get();
                                    if (block != null) {
                                        final CountDownLatch latch = blockedIndexers.get();
                                        if (latch != null) {
                                            latch.countDown();
                                        }
                                        try {
                                            block.await();
                                        } catch (InterruptedException e) {
                                            throw new AssertionError(e);
                                        }
                                    }
                                    return super.addDocument(doc);
                                }
                            },
                    null,
                    config);
        }

        @Override
        public void close() throws Exception {
            releaseLatchedIndexers();
        }

    }

}
