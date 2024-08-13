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

package com.starrocks.server;

import com.google.common.base.Preconditions;
import com.staros.client.StarClientException;
import com.staros.proto.ShardInfo;
import com.staros.util.LockCloseable;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.UserException;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.lake.LakeTablet;
import com.starrocks.lake.StarOSAgent;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.SystemInfoService;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.Warehouse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class WarehouseManager implements Writable {
    private static final Logger LOG = LogManager.getLogger(WarehouseManager.class);

    public static final String DEFAULT_WAREHOUSE_NAME = "default_warehouse";
    public static final long DEFAULT_WAREHOUSE_ID = 0L;

    protected final Map<Long, Warehouse> idToWh = new HashMap<>();
    protected final Map<String, Warehouse> nameToWh = new HashMap<>();

    protected final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public WarehouseManager() {
    }

    public void initDefaultWarehouse() {
        // gen a default warehouse
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            Warehouse wh = new DefaultWarehouse(DEFAULT_WAREHOUSE_ID, DEFAULT_WAREHOUSE_NAME);
            nameToWh.put(wh.getName(), wh);
            idToWh.put(wh.getId(), wh);
        }
    }

    public List<Warehouse> getAllWarehouses() {
        return new ArrayList<>(nameToWh.values());
    }

    public Warehouse getWarehouse(String warehouseName) {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            Warehouse warehouse = nameToWh.get(warehouseName);
            if (warehouse == null) {
                throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("name: %s", warehouseName));
            }
            return warehouse;
        }
    }

    public Warehouse getWarehouse(long warehouseId) {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            Warehouse warehouse = idToWh.get(warehouseId);
            if (warehouse == null) {
                throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("id: %d", warehouseId));
            }
            return warehouse;
        }
    }

    public Warehouse getWarehouseAllowNull(String warehouseName) {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            return nameToWh.get(warehouseName);
        }
    }

    public Warehouse getWarehouseAllowNull(long warehouseId) {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            return idToWh.get(warehouseId);
        }
    }

    public boolean warehouseExists(String warehouseName) {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            return nameToWh.containsKey(warehouseName);
        }
    }

    public List<Long> getAllComputeNodeIds(String warehouseName) {
        Warehouse warehouse = nameToWh.get(warehouseName);
        if (warehouse == null) {
            throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("name: %s", warehouseName));
        }

        return getAllComputeNodeIds(warehouse.getId());
    }

    public List<Long> getAllComputeNodeIds(long warehouseId) {
        long workerGroupId = selectWorkerGroupInternal(warehouseId)
                .orElse(StarOSAgent.DEFAULT_WORKER_GROUP_ID);
        return getAllComputeNodeIds(warehouseId, workerGroupId);
    }

    private List<Long> getAllComputeNodeIds(long warehouseId, long workerGroupId) {
        Warehouse warehouse = idToWh.get(warehouseId);
        if (warehouse == null) {
            throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("name: %s", warehouse.getName()));
        }

        try {
            return GlobalStateMgr.getCurrentState().getStarOSAgent().getWorkersByWorkerGroup(workerGroupId);
        } catch (UserException e) {
            LOG.warn("Fail to get compute node ids from starMgr : {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<ComputeNode> getAliveComputeNodes(long warehouseId) {
        Optional<Long> workerGroupId = selectWorkerGroupInternal(warehouseId);
        if (workerGroupId.isEmpty()) {
            return new ArrayList<>();
        }
        return getAliveComputeNodes(warehouseId, workerGroupId.get());
    }

    private List<ComputeNode> getAliveComputeNodes(long warehouseId, long workerGroupId) {
        List<Long> computeNodeIds = getAllComputeNodeIds(warehouseId, workerGroupId);
        SystemInfoService systemInfoService = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo();
        List<ComputeNode> nodes = computeNodeIds.stream()
                .map(id -> systemInfoService.getBackendOrComputeNode(id))
                .filter(ComputeNode::isAlive).collect(Collectors.toList());
        return nodes;
    }

    public Long getComputeNodeId(Long warehouseId, LakeTablet tablet) {
        Warehouse warehouse = idToWh.get(warehouseId);
        if (warehouse == null) {
            throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("id: %d", warehouseId));
        }

        try {
            long workerGroupId = selectWorkerGroupInternal(warehouseId)
                    .orElse(StarOSAgent.DEFAULT_WORKER_GROUP_ID);
            ShardInfo shardInfo = GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getShardInfo(tablet.getShardId(), workerGroupId);

            Long nodeId;
            Set<Long> ids = GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getAllNodeIdsByShard(shardInfo, true);
            if (!ids.isEmpty()) {
                nodeId = ids.iterator().next();
                return nodeId;
            } else {
                return null;
            }
        } catch (StarClientException e) {
            return null;
        }
    }

    public Long getComputeNodeId(String warehouseName, LakeTablet tablet) {
        Warehouse warehouse = nameToWh.get(warehouseName);
        if (warehouse == null) {
            throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("name: %s", warehouseName));
        }

        try {
            long workerGroupId = selectWorkerGroupInternal(warehouse.getId()).orElse(StarOSAgent.DEFAULT_WORKER_GROUP_ID);
            ShardInfo shardInfo = GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getShardInfo(tablet.getShardId(), workerGroupId);

            Long nodeId;
            Set<Long> ids = GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getAllNodeIdsByShard(shardInfo, true);
            if (!ids.isEmpty()) {
                nodeId = ids.iterator().next();
                return nodeId;
            } else {
                return null;
            }
        } catch (StarClientException e) {
            return null;
        }
    }

    public Set<Long> getAllComputeNodeIdsAssignToTablet(Long warehouseId, LakeTablet tablet) {
        try {
            long workerGroupId = selectWorkerGroupInternal(warehouseId).orElse(StarOSAgent.DEFAULT_WORKER_GROUP_ID);
            ShardInfo shardInfo = GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getShardInfo(tablet.getShardId(), workerGroupId);

            return GlobalStateMgr.getCurrentState().getStarOSAgent()
                    .getAllNodeIdsByShard(shardInfo, true);
        } catch (StarClientException e) {
            return null;
        }
    }

    public ComputeNode getComputeNodeAssignedToTablet(String warehouseName, LakeTablet tablet) {
        Warehouse warehouse = getWarehouse(warehouseName);
        return getComputeNodeAssignedToTablet(warehouse.getId(), tablet);
    }

    public ComputeNode getComputeNodeAssignedToTablet(Long warehouseId, LakeTablet tablet) {
        Long computeNodeId = getComputeNodeId(warehouseId, tablet);
        if (computeNodeId == null) {
            Warehouse warehouse = idToWh.get(warehouseId);
            throw ErrorReportException.report(ErrorCode.ERR_NO_NODES_IN_WAREHOUSE,
                    String.format("name: %s", warehouse.getName()));
        }
        Preconditions.checkNotNull(computeNodeId);
        return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendOrComputeNode(computeNodeId);
    }

    private final AtomicInteger nextComputeNodeIndex = new AtomicInteger(0);

    public AtomicInteger getNextComputeNodeIndexFromWarehouse(long warehouseId) {
        return nextComputeNodeIndex;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        String json = GsonUtils.GSON.toJson(this);
        Text.writeString(out, json);
    }

    public Warehouse getCompactionWarehouse() {
        return getWarehouse(DEFAULT_WAREHOUSE_ID);
    }

    public Warehouse getBackgroundWarehouse() {
        return getWarehouse(DEFAULT_WAREHOUSE_ID);
    }

    public Optional<Long> selectWorkerGroupByWarehouseId(long warehouseId) {
        Optional<Long> workerGroupId = selectWorkerGroupInternal(warehouseId);
        if (workerGroupId.isEmpty())  {
            return workerGroupId;
        }

        List<ComputeNode> aliveNodes = getAliveComputeNodes(warehouseId, workerGroupId.get());
        if (CollectionUtils.isEmpty(aliveNodes)) {
            Warehouse warehouse = getWarehouse(warehouseId);
            LOG.warn("there is no alive workers in warehouse: " + warehouse.getName());
            return Optional.empty();
        }

        return workerGroupId;
    }

    private Optional<Long> selectWorkerGroupInternal(long warehouseId) {
        Warehouse warehouse = getWarehouse(warehouseId);
        if (warehouse == null)  {
            LOG.warn("failed to get warehouse by id {}", warehouseId);
            return Optional.empty();
        }

        List<Long> ids = warehouse.getWorkerGroupIds();
        if (CollectionUtils.isEmpty(ids)) {
            LOG.warn("failed to get worker group id from warehouse {}", warehouse);
            return Optional.empty();
        }

        return Optional.of(ids.get(0));
    }
}
