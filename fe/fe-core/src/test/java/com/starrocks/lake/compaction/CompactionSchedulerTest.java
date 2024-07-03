// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.lake.compaction;

import com.starrocks.catalog.Database;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.lake.LakeTable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.transaction.DatabaseTransactionMgr;
import com.starrocks.transaction.TransactionState;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;

public class CompactionSchedulerTest {

    private final long dbId = 9000L;
    private final long transactionId = 12345L;

    @Mocked
    private DatabaseTransactionMgr dbTransactionMgr;

    @Before
    public void setUp() {
    }

    @Test
    public void testBeginTransactionSucceedWithSmallerStreamLoadTimeout() {
        GlobalStateMgr.getCurrentGlobalTransactionMgr().addDatabaseTransactionMgr(dbId);
        new Expectations() {
            {
                try {
                    dbTransactionMgr.beginTransaction(
                            (List<Long>) any, anyString, (TUniqueId) any, (TransactionState.TxnCoordinator) any,
                            (TransactionState.LoadJobSourceType) any, anyLong, anyLong
                    );
                } catch (Exception e) {
                    // skip
                }
                result = transactionId;
            }
        };

        // default value
        Config.lake_compaction_default_timeout_second = 86400;
        // value smaller than `lake_compaction_default_timeout_second`
        // expect not affect lake compaction's  transaction operation
        Config.max_stream_load_timeout_second = 64800;
        CompactionMgr compactionManager = new CompactionMgr();
        CompactionScheduler compactionScheduler =
                new CompactionScheduler(compactionManager, GlobalStateMgr.getCurrentSystemInfo(),
                        GlobalStateMgr.getCurrentGlobalTransactionMgr(), GlobalStateMgr.getCurrentState(), "");
        PartitionIdentifier partitionIdentifier = new PartitionIdentifier(dbId, 2, 3);
        try {
            assertEquals(transactionId, compactionScheduler.beginTransaction(partitionIdentifier));
        } catch (Exception e) {
            Assert.fail("Transaction failed for lake compaction");
        }
    }

    @Test
    public void testDisableTableCompaction() {
        CompactionMgr compactionManager = new CompactionMgr();
        CompactionScheduler compactionScheduler =
                new CompactionScheduler(compactionManager, GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                        GlobalStateMgr.getCurrentState().getGlobalTransactionMgr(), GlobalStateMgr.getCurrentState(), "12345");

        Assert.assertTrue(compactionScheduler.isTableDisabled(12345L));

        compactionScheduler.disableTables("23456;34567;45678");

        Assert.assertFalse(compactionScheduler.isTableDisabled(12345L));
        Assert.assertTrue(compactionScheduler.isTableDisabled(23456L));
        Assert.assertTrue(compactionScheduler.isTableDisabled(34567L));
        Assert.assertTrue(compactionScheduler.isTableDisabled(45678L));

        compactionScheduler.disableTables("");
        Assert.assertFalse(compactionScheduler.isTableDisabled(23456L));
    }

    @Test
    public void testGetHistory() {
        CompactionMgr compactionManager = new CompactionMgr();
        CompactionScheduler compactionScheduler =
                new CompactionScheduler(compactionManager, GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo(),
                        GlobalStateMgr.getCurrentState().getGlobalTransactionMgr(), GlobalStateMgr.getCurrentState(), "");
        new MockUp<CompactionScheduler>() {
            @Mock
            public ConcurrentHashMap<PartitionIdentifier, CompactionJob> getRunningCompactions() {
                ConcurrentHashMap<PartitionIdentifier, CompactionJob> r = new ConcurrentHashMap<>();
                Database db = new Database();
                Table table = new LakeTable();
                PartitionIdentifier partitionIdentifier1 = new PartitionIdentifier(1, 2, 3);
                PartitionIdentifier partitionIdentifier2 = new PartitionIdentifier(1, 2, 4);
                PhysicalPartition partition1 = new Partition(123, "aaa", null, null);
                PhysicalPartition partition2 = new Partition(124, "bbb", null, null);
                CompactionJob job1 = new CompactionJob(db, table, partition1, 100, false);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
                CompactionJob job2 = new CompactionJob(db, table, partition2, 101, false);
                r.put(partitionIdentifier1, job1);
                r.put(partitionIdentifier2, job2);
                return r;
            }
        };

        List<CompactionRecord> list = compactionScheduler.getHistory();
        Assert.assertEquals(2, list.size());
        Assert.assertTrue(list.get(0).getStartTs() >= list.get(1).getStartTs());
    }
}
