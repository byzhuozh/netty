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

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoopGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup implements EventLoopGroup {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MultithreadEventLoopGroup.class);

    /**
     * 默认 EventLoop 线程数
     * EventLoopGroup 默认拥有的 EventLoop 数量。 一个 EventLoop 对应一个线程
     */
    private static final int DEFAULT_EVENT_LOOP_THREADS;

    static {
        // 目前 CPU 基本都是超线程，一个 CPU 可对应 2 个线程
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args);
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, ThreadFactory, Object...)
     *
     * <p>
     *  args 参数：
     *      selectorProvider      java.nio.channels.spi.SelectorProvider ，用于创建 Java NIO Selector 对象。
     *      selectStrategyFactory     io.netty.channel.SelectStrategyFactory ，选择策略工厂
     *      rejectedExecutionHandler  io.netty.channel.SelectStrategyFactory ，拒绝执行处理器
     * </p>
     */
    protected MultithreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, threadFactory, args);
    }

    /**
     * @see MultithreadEventExecutorGroup#MultithreadEventExecutorGroup(int, Executor,
     * EventExecutorChooserFactory, Object...)
     */
    protected MultithreadEventLoopGroup(int nThreads, Executor executor, EventExecutorChooserFactory chooserFactory,
                                     Object... args) {
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, chooserFactory, args);
    }

    /**
     * 创建线程工厂对象
     * 覆盖父类方法，增加了线程优先级为 Thread.MAX_PRIORITY 。
     * @return
     */
    @Override
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass(), Thread.MAX_PRIORITY); // 父类中采用的是默认的，线程优先级为 Thread.NORM_PRIORITY
    }

    /**
     * 设计模式： 适配器模式（类的适配）
     * MultithreadEventExecutorGroup#next 适配 EventLoopGroup#next 方法
     * @return
     */
    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    /**
     * 覆盖父类方法，返回值改为 EventLoop 类。
     */
    @Override
    protected abstract EventLoop newChild(Executor executor, Object... args) throws Exception;

    /**
     * 注册 Channel 到 EventLoopGroup 中
     * EventLoopGroup 会分配一个 EventLoop(EventLoop) 给该 Channel 注册
     *
     * next 返回的对象，实际就是 newChild 方法初始化返回的 EventLoop 对象
     *
     * @param channel
     * @return
     */
    @Override
    public ChannelFuture register(Channel channel) {
        return next().register(channel);
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) {
        return next().register(promise);
    }

    @Deprecated
    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        return next().register(channel, promise);
    }
}
