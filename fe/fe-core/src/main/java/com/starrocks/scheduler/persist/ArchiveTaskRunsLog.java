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

package com.starrocks.scheduler.persist;

import com.google.gson.annotations.SerializedName;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

public class ArchiveTaskRunsLog implements Writable {

    @SerializedName("taskRuns")
    private List<String> taskRuns;

    public ArchiveTaskRunsLog(List<String> taskRuns) {
        this.taskRuns = taskRuns;
    }

    public List<String> getTaskRuns() {
        return taskRuns;
    }

    public static ArchiveTaskRunsLog read(DataInput in) throws IOException {
        return GsonUtils.GSON.fromJson(Text.readString(in), ArchiveTaskRunsLog.class);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        String json = GsonUtils.GSON.toJson(this);
        Text.writeString(out, json);
    }
}
