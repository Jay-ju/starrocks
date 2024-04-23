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

#pragma once

#include <memory>
#include <string>

#include "io/seekable_input_stream.h"

namespace Aws::S3 {
class S3Client;
}

namespace starrocks::io {

class S3InputStream final : public SeekableInputStream {
public:
    explicit S3InputStream(std::shared_ptr<Aws::S3::S3Client> client, std::string bucket, std::string object,
                           int64_t read_ahead_size)
            : _s3client(std::move(client)), _bucket(std::move(bucket)), _object(std::move(object)) {
        _read_ahead_size = read_ahead_size;
        if (_read_ahead_size > 0) {
            _read_buffer = std::make_unique<uint8_t[]>(_read_ahead_size);
        }
    }

    ~S3InputStream() override = default;

    // Disallow copy and assignment
    S3InputStream(const S3InputStream&) = delete;
    void operator=(const S3InputStream&) = delete;

    // Disallow move ctor and move assignment, because no usage now
    S3InputStream(S3InputStream&&) = delete;
    void operator=(S3InputStream&&) = delete;

    StatusOr<int64_t> read(void* data, int64_t count) override;

    Status seek(int64_t offset) override;

    StatusOr<int64_t> position() override;

    StatusOr<int64_t> get_size() override;

    void set_size(int64_t size) override;

    StatusOr<std::string> read_all() override;

private:
    std::shared_ptr<Aws::S3::S3Client> _s3client;
    std::string _bucket;
    std::string _object;
    int64_t _offset{0};
    int64_t _size{-1};
    int64_t _read_ahead_size{-1};
    // _read_buffer start offset, indicate buffer[0]'s offset in s3 file.
    int64_t _buffer_start_offset{-1};
    int64_t _buffer_data_length{-1};
    std::unique_ptr<uint8_t[]> _read_buffer;
};

} // namespace starrocks::io
