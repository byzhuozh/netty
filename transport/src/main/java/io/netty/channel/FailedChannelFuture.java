/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.PlatformDependent;

/**
 * The {@link CompleteChannelFuture} which is failed already.  It is
 * recommended to use {@link Channel#newFailedFuture(Throwable)}
 * instead of calling the constructor of this future.
 *
 * 注意和 SuccessChannelFuture 的区别
 */
final class FailedChannelFuture extends CompleteChannelFuture {

    private final Throwable cause;

    /**
     * Creates a new instance.
     *
     * @param channel the {@link Channel} associated with this future
     * @param cause   the cause of failure
     */
    FailedChannelFuture(Channel channel, EventExecutor executor, Throwable cause) {
        super(channel, executor);
        if (cause == null) {            // 异常信息不能为空
            throw new NullPointerException("cause");
        }
        this.cause = cause;   // 异常信息
    }

    @Override
    public Throwable cause() {
        return cause;
    }

    /**
     * 失败的 Future, 返回 false
     * @return
     */
    @Override
    public boolean isSuccess() {
        return false;
    }

    @Override
    public ChannelFuture sync() {
        PlatformDependent.throwException(cause); // 抛出异常
        return this;
    }

    @Override
    public ChannelFuture syncUninterruptibly() {
        PlatformDependent.throwException(cause);    // 抛出异常
        return this;
    }
}
