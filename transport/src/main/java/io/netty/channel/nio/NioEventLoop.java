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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    /**
     * TODO 1007 NioEventLoop cancel
     */
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认开启
     */
    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该 N 值，不开启空轮询，重建新的 Selector 对象的功能
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    /**
     * NIO Selector 空轮询该 N 次后，重建新的 Selector 对象
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();  //无阻塞 返回 Channel 新增的感兴趣的就绪 IO 事件数量
        }
    };
    private final Callable<Integer> pendingTasksCallable = new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
            return NioEventLoop.super.pendingTasks();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        // 解决 Selector#open() 方法 // <1>
        final String key = "sun.nio.ch.bugLevel";
        final String buglevel = SystemPropertyUtil.get(key);
        if (buglevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        // 初始化
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     *
     * 包装的 Selector 对象，经过优化
     * {@link #openSelector()}
     */
    private Selector selector;
    /**
     * 未包装的 Selector 对象
     */
    private Selector unwrappedSelector;
    /**
     * 注册的 SelectionKey 集合。Netty 自己实现，经过优化。
     */
    private SelectedSelectionKeySet selectedKeys;
    /**
     * SelectorProvider 对象，用于创建 Selector 对象
     */
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     *
     * 唤醒标记。因为唤醒方法 {@link Selector#wakeup()} 开销比较大，通过该标识，减少调用
     * @see #wakeup(boolean)
     * @see #run()
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();
    /**
     * Select 策略
     *
     * @see #select(boolean)
     */
    private final SelectStrategy selectStrategy;
    /**
     * 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例
     */
    private volatile int ioRatio = 50;
    /**
     * 取消 SelectionKey 的数量
     *
     * TODO 1007 NioEventLoop cancel
     */
    private int cancelledKeys;
    /**
     * 是否需要再次 select Selector 对象
     *
     * TODO 1007 NioEventLoop cancel
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        // 创建 Selector 对象 <1>
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    /**
     * Selector 元组
     */
    private static final class SelectorTuple {
        /**
         * 未包装的 Selector 对象
         */
        final Selector unwrappedSelector;
        /**
         * 包装的 Selector 对象
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        // 创建 Selector 对象，作为 unwrappedSelector
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
        if (DISABLE_KEYSET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 获得 SelectorImpl 类
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",  // window 下对应 {@link sun.nio.ch.WindowsSelectorImpl.WindowsSelectorImpl}
                            false,
                            PlatformDependent.getSystemClassLoader());  // 成功，则返回该类
                } catch (Throwable cause) {
                    return cause;   // 失败，则返回该异常
                }
            }
        });

        // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        // 创建 SelectedSelectionKeySet 对象
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 获得 "selectedKeys" "publicSelectedKeys" 的 Field
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // 设置 Field 可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;   // 失败，则返回该异常
                } catch (IllegalAccessException e) {
                    return e;   // 失败，则返回该异常
                }
            }
        });

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }

        // 设置 SelectedSelectionKeySet 对象到 selectedKeys 中
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);

        // 创建 SelectedSelectionKeySetSelector 对象
        // 创建 SelectorTuple 对象。即，selector 也使用 SelectedSelectionKeySetSelector 对象。
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * 重写了父类的方法  {@link io.netty.util.concurrent.SingleThreadEventExecutor#newTaskQueue(int)} --》使用无界阻塞队列
     *
     * @param maxPendingTasks
     * @return
     */
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        /**
         * mpsc 是 multiple producers and a single consumer 的缩写
         * mpsc 是对多线程生产任务，单线程消费任务的消费，恰好符合 NioEventLoop 的情况。
         */
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * 获得待执行的任务数量
     * @return
     */
    @Override
    public int pendingTasks() {
        // As we use a MpscQueue we need to ensure pendingTasks() is only executed from within the EventLoop as
        // otherwise we may see unexpected behavior (as size() is only allowed to be called by a single consumer).
        // See https://github.com/netty/netty/issues/5297
        // MpscQueue 仅允许单消费，所以获得队列的大小，仅允许在 EventLoop 的线程中调用
        if (inEventLoop()) {
            return super.pendingTasks();
        } else {
            return submit(pendingTasksCallable).syncUninterruptibly().getNow();
        }
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     *
     * 注册 Java NIO Channel ( 不一定需要通过 Netty 创建的 Channel )到 Selector 上,相当于说，也注册到了 EventLoop 上
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
            //注册 Java NIO Channel 到 Selector 上。这里我们可以看到，attachment 为 NioTask 对象，而不是 Netty Channel 对象
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     *
     * 线程分配给 IO 操作所占的时间比(即运行 processSelectedKeys 耗时在整个循环中所占用的时间).
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        // 只允许在 EventLoop 的线程中执行
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            // 创建新的 Selector 对象
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;  // 计算重新注册成功的 Channel 数量
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();  // 每个 key 都带有附件 {@link io.netty.channel.nio.AbstractNioChannel.doRegister}
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                // 取消老的 SelectionKey
                key.cancel();
                // 将 Channel 注册到新的 Selector 对象上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                // 修改 Channel 的 selectionKey 指向新的 SelectionKey 上
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                // 计数 ++
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                // 关闭发生异常的 Channel
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    // 调用 NioTask 的取消注册事件
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 修改 selector 和 unwrappedSelector 指向新的 Selector 对象
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        // 关闭老的 Selector 对象
        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        for (;;) {
            try {
                // 调用select()查询是否有就绪的IO事件
                switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:   // 默认实现下，不存在这个情况
                        continue;
                    case SelectStrategy.SELECT:  // 阻塞策略
                        // 重置 wakenUp 标记为 false
                        // 选择( 查询 )任务
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated  在调用selector.wakeup()之前，总是对它进行校验，以减少唤醒开销
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)   Selector.wakeup()是一项开销昂贵的操作
                        //
                        // However, there is a race condition in this approach.  然而,这种方法有竞态条件。
                        // The race condition is triggered when 'wakenUp' is set to   当‘wakenUp’被过早地设置为true时，竞态条件被触发。
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:  （wakeUp 被过早唤醒，有两种情况：）
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        // 在第一种情况下,“wakenUp”设置为真,以下的selector.select(…)”将立即醒来。直到“wakenUp” 在下一轮中再次设置为false,
                        // “wakenUp.compareAndSet(false,true)将会失败,因此任何尝试唤醒选择器都会失败,也导致以下的selector.select(…)的调用阻止不必要的。

                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).
                        // 为了解决这个问题，如果在selector.select(…)之后wakeUp为真,我们再次唤醒选择器。
                        // 其效率低是因为它为第一种情况(需要唤醒)和第二种情况(没有需要唤醒)唤醒了选择器。

                        /**
                         * 1）在 wakenUp.getAndSet(false) 和 #select(boolean oldWakeUp) 之间，
                         *   在标识 wakeUp 设置为 false 时，在 #select(boolean oldWakeUp) 方法中，正在调用 Selector#select(...) 方法，处于阻塞中。
                         * 2）此时，有另外的线程调用了 #wakeup() 方法，会将标记 wakeUp 设置为 true ，并唤醒 Selector#select(...) 方法的阻塞等待。
                         * 3）标识 wakeUp 为 true ，所以再有另外的线程调用 #wakeup() 方法，都无法唤醒 Selector#select(...) 。
                         *   为什么呢？因为 #wakeup() 的 CAS 修改 false => true 会失败，导致无法调用 Selector#wakeup() 方法。
                         */
                        //唤醒
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }

                // TODO 1007 NioEventLoop cancel 方法
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {   // 不考虑时间占比的分配
                    try {
                        // 处理就绪的IO事件
                        processSelectedKeys();
                    } finally {
                        // 运行所有普通任务和定时任务，不限制时间
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理就绪的IO事件
                        processSelectedKeys();
                    } finally {
                        // 运行所有普通任务和定时任务，限制时间
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // TODO 1006 EventLoop 优雅关闭
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);   // 防止连续异常过度消耗CPU
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector ，
     * 所以调用 #processSelectedKeysOptimized() 方法；否则，调用 #processSelectedKeysPlain() 方法。
     */
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized(); //优化后处理  IO 方式
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    /**
     * 当取消的选择键达到一定数目时，这个数目在Netty中时CLEANUP_INTERVAL，值为256。
     * 也就是每取消256个选择键，Netty重新执行一个selectAgain()操作
     * @param key
     */
    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        // 遍历 SelectionKey 迭代器
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            // 获得 SelectionKey 对象
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            // 从迭代器中移除
            i.remove();

            // 处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 无下一个节点，结束
            if (!i.hasNext()) {
                break;
            }

            // TODO 1007 NioEventLoop cancel 方法
            //当取消的选择键达到一定数目时，这个数目在Netty中时CLEANUP_INTERVAL，值为256。
            // 也就是每取消256个选择键，Netty重新执行一个selectAgain()操作, {@link io.netty.channel.nio.NioEventLoop.cancel}
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 优化后的处理 IO 方式
     */
    private void processSelectedKeysOptimized() {
        // 遍历数组
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;    //去除 SelectedSelectionKeySet 对 SelectionKey 的强引用

            final Object a = k.attachment();

            // 处理一个 Channel 就绪的 IO 事件, IO事件由Netty框架处理
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);    // IO事件由用户自定义任务处理
            }

            //当取消的选择键达到一定数目时，这个数目在Netty中时CLEANUP_INTERVAL，值为256。
            // 也就是每取消256个选择键，Netty重新执行一个selectAgain()操作, {@link io.netty.channel.nio.NioEventLoop.cancel}
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 如果 SelectionKey 是不合法的，则关闭 Channel
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch ifregist ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops
            int readyOps = k.readyOps();
            // OP_CONNECT 事件就绪
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // 移除对 OP_CONNECT 感兴趣
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);  // 移除对 OP_CONNECT 的感兴趣，即不再监听连接事件, 但连接完成后客户端除了连接事件都感兴趣
                // 完成连接
                unsafe.finishConnect();
            }

            // OP_WRITE 事件就绪
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 向 Channel 写入数据
                ch.unsafe().forceFlush();
            }

            // SelectionKey.OP_READ 或 SelectionKey.OP_ACCEPT 就绪    处理读或者者接受客户端连接的事件
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            // 发生异常，关闭 Channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;  // 未执行
        try {
            // 调用 NioTask 的 Channel 就绪事件
            task.channelReady(k.channel(), k);
            state = 1;  // 执行成功
        } catch (Exception e) {
            // SelectionKey 取消
            k.cancel();
            // 执行 Channel 取消注册
            invokeChannelUnregistered(task, k, e);
            state = 2;   // 执行异常
        } finally {
            switch (state) {
            case 0:
                // SelectionKey 取消
                k.cancel();
                // 执行 Channel 取消注册
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                // SelectionKey 不合法，则执行 Channel 取消注册
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    /**
     * 执行 Channel 取消注册
     */
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            // NioTask#channelUnregistered() 方法，执行 Channel 取消注册。
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {   // <2>
            selector.wakeup();  // <1>
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            return selector.selectNow();   // 非阻塞的，如果没有通道就绪就直接返回0。
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {   // 若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector ,唤醒下一次select()操作
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        // 记录下 Selector 对象
        Selector selector = this.selector;
        try {
            // select 计数器
            int selectCnt = 0;
            // 记录当前时间，单位：纳秒
            long currentTimeNanos = System.nanoTime();
            // 计算 select 截止时间，单位：纳秒。
            // delayNanos返回的是最近的一个调度任务的到期时间，没有调度任务返回1秒
            // selectDeadLineNanos 指可以进行select 操作的截止时间点
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            for (;;) {
                // 计算本次 select 的超时时长，单位：毫秒。
                // + 500000L 是为了四舍五入
                // / 1000000L 是为了纳秒转为毫秒
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {    // 如果是首次 select ，selectNow 一次，非阻塞
                    if (selectCnt == 0) {       // 如果一次select操作没有进行
                        selector.selectNow();    // selecNow()之后返回
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // 此时有任务进入队列且唤醒标志为假
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    // selectNow 一次，非阻塞
                    selector.selectNow();
                    // 重置 select 计数器
                    selectCnt = 1;
                    break;
                }

                // 阻塞 select ，查询 Channel 是否有就绪的 IO 事件
                int selectedKeys = selector.select(timeoutMillis);
                // select 计数器 ++
                selectCnt ++;

                // 结束 select ，如果满足下面任一一个条件
                // 有就绪的IO事件，参数oldWakenUp为真，外部设置wakenUp为真, 有待执行普通任务，有待执行调度任务
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }

                // 线程被打断。一般情况下不会出现，出现基本是 bug ，或者错误使用。
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                // 记录当前时间
                long time = System.nanoTime();
                // 符合 select 超时条件，重置 selectCnt 为 1
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;

                // 不符合 select 超时的提交，若 select 次数到达重建 Selector 对象的上限，进行重建
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {  // 默认是 512
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);

                    // 重建 Selector 对象   这里是对JDK BUG的处理
                    rebuildSelector();
                    // 修改下 Selector 对象
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    // 立即 selectNow 一次，非阻塞
                    selector.selectNow();
                    // 重置 selectCnt 为 1
                    selectCnt = 1;
                    // 结束 select
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
