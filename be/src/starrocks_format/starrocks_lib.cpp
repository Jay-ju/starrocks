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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/service/doris_main.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
#include <unistd.h>
#include <filesystem>
#include <fstream>

#include <aws/core/Aws.h>
#include <aws/core/client/ClientConfiguration.h>

#include "common/config.h"
#include "fs/fs_s3.h"
#include "runtime/time_types.h"
#include "storage/lake/fixed_location_provider.h"
#include "storage/lake/tablet_manager.h"
#include "storage/olap_define.h"
#include "util/timezone_utils.h"

namespace starrocks::lake {

static bool _starrocks_format_inited = false;
Aws::SDKOptions aws_sdk_options;

lake::TabletManager* _lake_tablet_manager = nullptr;

void starrocks_format_initialize(void) {
    if (!_starrocks_format_inited) {
        fprintf(stderr, "starrocks format module start to initialize\n");
        // load config file
        std::string conffile = std::filesystem::current_path();
        conffile += "/starrocks.conf";
        const char* config_file_path = conffile.c_str();
        std::ifstream ifs(config_file_path);
        if (!ifs.good()) {
            config_file_path = nullptr;
        }
        if (!starrocks::config::init(config_file_path, true)) {
            fprintf(stderr, "error read config file. \n");
            return;
        }

        Aws::InitAPI(aws_sdk_options);

        date::init_date_cache();

        TimezoneUtils::init_time_zones();

        auto lake_location_provider = std::make_shared<FixedLocationProvider>("");
        _lake_tablet_manager = new lake::TabletManager(lake_location_provider, config::lake_metadata_cache_limit);
        fprintf(stderr, "starrocks format module has been initialized successfully\n");
        _starrocks_format_inited = true;
    } else {
        fprintf(stderr, "starrocks format module has already been initialized\n");
    }
}

void starrocks_format_deinit(void) {
    if (_starrocks_format_inited) {
        fprintf(stderr, "starrocks format module start to deinitialize");
        Aws::ShutdownAPI(aws_sdk_options);
        SAFE_DELETE(_lake_tablet_manager);
        // SAFE_DELETE(_lake_update_manager);
        fprintf(stderr, "starrocks format module has been deinitialized successfully\n");
    } else {
        fprintf(stderr, "starrocks format module has already been deinitialized\n");
    }
}

} // namespace starrocks::lake
