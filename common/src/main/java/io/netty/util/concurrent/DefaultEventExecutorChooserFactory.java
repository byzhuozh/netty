/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        if (isPowerOfTwo(executors.length)) {   // 是否为 2 的幂次方
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     * 判断是否是 2 的幂次方   n & -n = n
     * @param val
     * @return
     */
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 对应着 io.netty.util.concurrent.EventExecutorGroup.next() 的最终实现
         * @return
         */
        @Override
        public EventExecutor next() {
            // EventExecutor 数组的大小是以 2 为幂次方的数字，那么减一后，除了最高位是 0 ，
            // 剩余位都为 1 ( 例如 8 减一后等于 7 ，而 7 的二进制为 0111 。)，
            // 那么无论 idx 无论如何递增，再进行 & 并操作，都不会超过 EventExecutor 数组的大小。并且，还能保证顺序递增。
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 对应着 io.netty.util.concurrent.EventExecutorGroup.next() 的最终实现
         * @return
         */
        @Override
        public EventExecutor next() {
            // 如果 executors 的数组大小不是 2 的幂次，则进行取余运算，获取对应的 eventExecutor 事件执行器
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
