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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 * 基于单线程的 EventExecutor 抽象类，即一个 EventExecutor 对应一个线程
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    /**
     * 线程状态常量
     * {@link #state}
     */
    private static final int ST_NOT_STARTED = 1;    // 未开始
    private static final int ST_STARTED = 2;        // 已开始
    private static final int ST_SHUTTING_DOWN = 3;  // 正在关闭中
    private static final int ST_SHUTDOWN = 4;       // 已关闭
    private static final int ST_TERMINATED = 5;     // 已经终止

    private static final Runnable WAKEUP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };
    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    /**
     * {@link #state} 字段的原子更新器
     */
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");

    /**
     * {@link #thread} 字段的原子更新器
     */
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    /**
     * 任务队列
     * @see #newTaskQueue(int)
     */
    private final Queue<Runnable> taskQueue;
    /**
     * 线程
     */
    private volatile Thread thread;
    /**
     * 线程属性
     */
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;
    /**
     * 执行器
     */
    private final Executor executor;
    /**
     * 线程是否已经打断
     *
     * @see #interruptThread()
     */
    private volatile boolean interrupted;

    /**
     * 线程锁信号量   一个信号量，注意初始值为0
     */
    private final Semaphore threadLock = new Semaphore(0);
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    /**
     * 添加任务时，是否唤醒线程{@link #thread}
     */
    private final boolean addTaskWakesUp;
    /**
     * 最大等待执行任务数量，即 {@link #taskQueue} 的队列大小
     */
    private final int maxPendingTasks;
    /**
     * 拒绝执行处理器
     *
     * @see #reject()
     * @see #reject(Runnable)
     */
    private final RejectedExecutionHandler rejectedExecutionHandler;
    /**
     * 最后执行时间
     */
    private long lastExecutionTime;
    /**
     * 状态
     */
    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    /**
     * 优雅关闭静默时间
     */
    private volatile long gracefulShutdownQuietPeriod;
    /**
     * 优雅关闭超时时间，单位：毫秒
     */
    private volatile long gracefulShutdownTimeout;
    /**
     * 优雅关闭开始时间，单位：毫秒
     */
    private long gracefulShutdownStartTime;

    /**
     * 线程终止异步结果
     */
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ObjectUtil.checkNotNull(executor, "executor");
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     *
     * 创建任务队列   默认返回的是 LinkedBlockingQueue 阻塞队列
     * 子类可重载实现，自选阻塞队列 {@see io.netty.channel.nio.NioEventLoop#newTaskQueue(int) }
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        Thread currentThread = thread;

        if (currentThread == null) {
            interrupted = true;
        } else {
            // 打断线程
            currentThread.interrupt();
        }
    }

    /**
     * @see Queue#poll()
     *
     *  获得队头的任务
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        for (;;) {
            // 获得并移除队首元素。如果获得不到，返回 null
            Runnable task = taskQueue.poll();   // take 会阻塞
            // 忽略 WAKEUP_TASK 任务，因为是空任务
            if (task == WAKEUP_TASK) {
                continue;
            }
            return task;
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {     // 任务队列必须是阻塞队列
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            // 取得调度任务队列的头部任务，注意peek并不移除
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {     // 没有调度任务
                Runnable task = null;
                try {
                    task = taskQueue.take();    // 取得并移除任务队列的头部任务，没有则阻塞
                    // 当线程需要关闭是，如果线程在take()方法上阻塞，就需要添加一个标记任务WAKEUP_TASK到任务队列，是线程从take()返回，从而正确关闭线程。
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();   // 调度任务的到期时间间隔
                Runnable task = null;
                if (delayNanos > 0) {
                    try {     // 调度任务未到期，则从任务队列取一个任务，可能为null
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }

                // 注意这里执行有两种情况：1.任务队列中没有待执行任务，2.调度任务已到期
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 将定时任务队列 scheduledTaskQueue 到达可执行的任务，添加到任务队列 taskQueue 中
     * @return
     */
    private boolean fetchFromScheduledTaskQueue() {
        // 获得当前时间   等价于ScheduledFutureTask.nanoTime()
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        // 获得指定时间内，定时任务队列**首个**可执行的任务，并且从队列中移除。
        Runnable scheduledTask  = pollScheduledTask(nanoTime);
        // 不断从定时任务队列中，获得
        while (scheduledTask != null) {
            // 将定时任务添加到 taskQueue 中。若添加失败，则结束循环，返回 false ，表示未获取完所有课执行的定时任务
            if (!taskQueue.offer(scheduledTask)) {
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                // 任务队列已满，则重新添加到调度任务队列
                scheduledTaskQueue().add((ScheduledFutureTask<?>) scheduledTask);
                return false;
            }
            // 获得指定时间内，定时任务队列**首个**可执行的任务，并且从队列中移除。
            scheduledTask  = pollScheduledTask(nanoTime);
        }
        // 返回 true ，表示获取完所有可执行的定时任务
        return true;
    }

    /**
     * @see Queue#peek()
     *
     * 返回队头的任务，但是不移除
     */
    protected Runnable peekTask() {
        assert inEventLoop();   // 仅允许在 EventLoop 线程中执行
        return taskQueue.peek();
    }

    /**
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() {
        assert inEventLoop();       // 仅允许在 EventLoop 线程中执行
        return !taskQueue.isEmpty();
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     *
     * 添加任务到队列中失败，remo则进行拒绝任务
     */
    protected void addTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        // 添加任务到队列
        if (!offerTask(task)) {
            // 添加失败，则拒绝任务
            reject(task);
        }
    }

    /**
     * 添加任务到队列中。若添加失败，则返回 false
     * @param task
     * @return
     */
    final boolean offerTask(Runnable task) {
        // 关闭时，拒绝任务
        if (isShutdown()) {
            reject();
        }
        // 添加任务到队列
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     *
     * 移除指定任务
     */
    protected boolean removeTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        return taskQueue.remove(task);
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        boolean fetchedAll;
        boolean ranAtLeastOne = false;  // 是否执行过任务

        do {
            // 从调度任务的队列中取出一个任务放到执行的队列中
            fetchedAll = fetchFromScheduledTaskQueue();
            // 执行任务队列中的所有任务
            if (runAllTasksFrom(taskQueue)) {
                // 若有任务执行，则标记为 true
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        // 如果执行过任务，则设置最后执行时间
        if (ranAtLeastOne) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        // 执行所有任务完成的后续方法
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    /**
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        // 获得队头的任务
        Runnable task = pollTaskFrom(taskQueue);

        // 获取不到，结束执行，返回 false
        if (task == null) {
            return false;
        }
        for (;;) {
            // 执行任务
            safeExecute(task);
            // 获得队头的任务
            task = pollTaskFrom(taskQueue);
            // 获取不到，结束执行，返回 true
            if (task == null) {
                return true;
            }
        }
    }

    /**
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) {
        // 将调度任务队列中到期的任务移到任务队列
        fetchFromScheduledTaskQueue();
        // 获得队头的任务
        Runnable task = pollTask();   // 从任务队列头部取出一个任务
        // 获取不到，结束执行
        if (task == null) {
            // 执行所有任务完成的后续方法, 执行的任务来源是 tailTasks 队列
            afterRunningAllTasks();
            return false;
        }

        // 计算执行任务截止时间
        final long deadline = ScheduledFutureTask.nanoTime() + timeoutNanos;
        long runTasks = 0;  // 执行任务计数
        long lastExecutionTime;
        // 循环执行任务
        for (;;) {
            // 执行任务
            safeExecute(task);

            // 计数 +1
            runTasks ++;

            // 每隔 64 个任务检查一次时间，因为 nanoTime() 是相对费时的操作
            // 64 这个值当前是硬编码的，无法配置，可能会成为一个问题。
            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            if ((runTasks & 0x3F) == 0) {
                // 重新获得时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                // 超过任务截止时间，结束
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            // 获得队头的任务
            task = pollTask();
            // 获取不到，结束执行
            if (task == null) {
                // 重新获得时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                break;
            }
        }

        // 执行所有任务完成的后续方法
        afterRunningAllTasks();

        // 设置最后执行时间
        this.lastExecutionTime = lastExecutionTime;
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }
    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     *
     */
    protected abstract void run();

    /**
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    // 将一个空任务添加到队列中，用于唤醒基于 taskQueue 阻塞拉取的 EventLoop 实现类
    protected void wakeup(boolean inEventLoop) {
        // 判断是否z在 EventLoop  的线程中，如果在 EventLoop 线程中，意味着线程就在执行中，不必要唤醒
        if (!inEventLoop || state == ST_SHUTTING_DOWN) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            // 非本类原生线程或者本类原生线程需要关闭时，添加一个标记任务使线程从take()返回。
            // offer失败表明任务队列已有任务，从而线程可以从take()返回故不处理
            taskQueue.offer(WAKEUP_TASK);   // {@link takeTask()}
        }
    }

    /**
     * 判断指定线程是否是 EventLoop 线程
     * @param thread
     * @return
     */
    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            // 使用 copy 是因为 shutdwonHook 任务中可以添加或删除 shutdwonHook 任务
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            shutdownHooks.clear();
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    ran = true;
                }
            }
        }

        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        return ran;
    }

    /**
     *
     * @param quietPeriod (静默时间) the quiet period as described in the documentation
     * @param timeout  (截止时间)   the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
     *                    regardless if a task was submitted during the quiet period
     * @param unit        the unit of {@code quietPeriod} and {@code timeout}
     *
     * @return
     */
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        if (quietPeriod < 0) {
            throw new IllegalArgumentException("quietPeriod: " + quietPeriod + " (expected >= 0)");
        }
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (isShuttingDown()) {
            return terminationFuture();   // 正在关闭阻止其他线程
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {  // for() 循环是为了保证修改 state 的线程（原生线程或者外部线程）有且只有一个
            if (isShuttingDown()) {
                return terminationFuture(); // 正在关闭阻止其他线程
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:    // 未开启状态
                    case ST_STARTED:     // 已开启状态
                        newState = ST_SHUTTING_DOWN;  // 将线程状态置为 关闭中
                        break;
                    default:    // 一个线程已修改好线程状态，此时这个线程才执行16行代码
                        newState = oldState;
                        wakeup = false;   // 已经有线程唤醒，所以不用再唤醒
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;  // 保证只有一个线程将oldState修改为newState
            }
            // 隐含STATE_UPDATER 已被修改，则在下一次循环返回
        }

        // 在default情况下会更新这两个值
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        if (oldState == ST_NOT_STARTED) {  //线程状态时未开始状态
            try {
                doStartThread();    // 可以完整走一遍正常流程并且可以处理添加到队列中的任务以及IO事件
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return terminationFuture;
            }
        }

        if (wakeup) {
            wakeup(inEventLoop);    // 唤醒阻塞在阻塞点上的线程，使其从阻塞状态退出
        }

        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (oldState == ST_NOT_STARTED) {
            try {
                doStartThread();
            } catch (Throwable cause) {
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return;
            }
        }

        if (wakeup) {
            wakeup(inEventLoop);
        }
    }

    //###################  查询线程状态的方法   #############
    //   {@linkplain #isShuttingDown()}  判断线程是否正在关闭或已经关闭
    //   {@linkplain #isShutdown()}   判断线程是否已经关闭
    //   {@linkplain #isTerminated()}   判断线程是否已经终止
    //######################################################

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        if (!isShuttingDown()) {  // 如果线程状态还是开始或是未开始状态，则直接返回
            return false;
        }

        if (!inEventLoop()) {    // 必须是原生线程
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks();  // 取消调度任务

        if (gracefulShutdownStartTime == 0) {    // 优雅关闭开始时间，这也是一个标记
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // 执行完普通任务或者没有普通任务时执行完shutdownHook任务
        if (runAllTasks() || runShutdownHooks()) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;     // 调用shutdown()方法直接退出
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {   // 优雅关闭静默时间为0也直接退出
                return true;
            }
            wakeup(true);    // 优雅关闭但有未执行任务，唤醒线程执行
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        // shutdown()方法调用直接返回，优雅关闭截止时间到也返回
        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        // 在静默期间每100ms 唤醒线程执行期间提交的任务
        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            wakeup(true);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        // 静默时间内没有任务提交，可以优雅关闭，此时若用户又提交任务则不会被执行
        return true;
    }

    /**
     * 用来使其他线程阻塞等待原生线程关闭
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        // 由于tryAcquire()永远不会成功，所以必定阻塞timeout时间
        if (threadLock.tryAcquire(timeout, unit)) {
            threadLock.release();
        }

        return isTerminated(); // 线程状态是否已经终止
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }

        // 获得当前是否在 EventLoop 的线程中
        boolean inEventLoop = inEventLoop();
        // 添加到任务队列
        addTask(task);
        if (!inEventLoop) {
            // 创建线程
            startThread();
            // 若已经关闭，移除任务，并进行拒绝
            if (isShutdown() && removeTask(task)) {
                reject();
            }
        }

        // 唤醒线程  addTaskWakesUp 实际理解：添加任务后，任务是否会自动导致线程唤
        if (!addTaskWakesUp && wakesUpForTask(task)) {
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");   // 若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    /**
     * 在 EventExecutor 中执行多个普通任务
     */
    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");    // 若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until the
     * it is fully started.
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        if (threadProperties == null) {
            Thread thread = this.thread;
            if (thread == null) {
                assert !inEventLoop();
                // 提交空任务，促使 execute 方法执行
                submit(NOOP_TASK).syncUninterruptibly();
                // 获得线程
                thread = this.thread;
                assert thread != null;
            }

            // 创建 DefaultThreadProperties 对象
            threadProperties = new DefaultThreadProperties(thread);
            // CAS 修改 threadProperties 属性
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        return threadProperties;
    }

    @SuppressWarnings("unused")
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    /**
     * 拒绝任何任务，用于 SingleThreadEventExecutor 已关闭( #isShutdown() 方法返回的结果为 true )的情况
     */
    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     *
     * 拒绝任务
     */
    protected final void reject(Runnable task) {
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    /**
     * 启动线程
     */
    private void startThread() {
        if (state == ST_NOT_STARTED) {
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                try {
                    doStartThread();
                } catch (Throwable cause) {
                    STATE_UPDATER.set(this, ST_NOT_STARTED);
                    PlatformDependent.throwException(cause);
                }
            }
        }
    }

    private void doStartThread() {
        assert thread == null;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 记录当前线程
                thread = Thread.currentThread();

                // 如果当前线程已经被标记打断，则进行打断操作。
                if (interrupted) {
                    thread.interrupt();
                }

                boolean success = false;    // 是否执行成功

                // 更新最后执行时间
                updateLastExecutionTime();
                try {
                    // 执行任务
                    SingleThreadEventExecutor.this.run();   // 模板方法，EventLoop实现
                    success = true;     // 标记执行成功
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {
                    // 更新当前线程状态：state,  置为关闭中 {@link ST_SHUTTING_DOWN}
                    for (;;) {
                        int oldState = state;
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;  // 抛出异常也将状态置为ST_SHUTTING_DOWN
                        }
                    }

                    // 检查确认是否在循环结束时调用了 confirmShutdown()
                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {     // time=0，说明confirmShutdown()方法没有调用，记录日志
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks.
                        for (;;) {
                            // 抛出异常时，将普通任务和shutdownHook任务执行完毕
                            // 正常关闭时，结合前述的循环跳出条件
                            if (confirmShutdown()) {
                                break;
                            }
                        }
                    } finally {
                        try {
                            cleanup();  // 清理，释放资源
                        } finally {
                            // 线程状态设置为ST_TERMINATED，线程终止
                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            threadLock.release();
                            if (!taskQueue.isEmpty()) {
                                // 关闭时，任务队列中添加了任务，记录日志
                                if (logger.isWarnEnabled()) {
                                    logger.warn("An event executor terminated with " +
                                            "non-empty task queue (" + taskQueue.size() + ')');
                                }
                            }

                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    /**
     * 封装的 DefaultThreadProperties 结果更多是为了安全性，不对外暴露当前线程更多的信息
     */
    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
