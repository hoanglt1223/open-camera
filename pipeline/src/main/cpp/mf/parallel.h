/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <algorithm>
#include <atomic>
#include <thread>
#include <vector>

namespace mf {

inline int workerCount() {
    unsigned n = std::thread::hardware_concurrency();
    return int(std::clamp(n, 1u, 8u));
}

// Runs fn(i) for i in [begin, end) on a few threads with dynamic work stealing.
template <typename Fn>
void parallelFor(int begin, int end, Fn &&fn) {
    int count = end - begin;
    if (count <= 0) return;
    int threads = std::min(workerCount(), count);
    if (threads == 1) {
        for (int i = begin; i < end; ++i) fn(i);
        return;
    }
    std::atomic<int> next{begin};
    auto worker = [&]() {
        for (int i = next.fetch_add(1); i < end; i = next.fetch_add(1)) fn(i);
    };
    std::vector<std::thread> pool;
    pool.reserve(threads - 1);
    for (int t = 1; t < threads; ++t) pool.emplace_back(worker);
    worker();
    for (auto &t : pool) t.join();
}

}  // namespace mf
