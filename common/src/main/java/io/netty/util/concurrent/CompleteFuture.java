/*
 * Copyright 2013 The Netty Project
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

import java.util.concurrent.TimeUnit;

/**
 * A skeletal {@link Future} implementation which represents a {@link Future} which has been completed already.
 *
 * CompleteFuture 表示已经完成异步操作，该类在异步操作结束时创建
 */
public abstract class CompleteFuture<V> extends AbstractFuture<V> {

    /**
     * 事件执行器，用来执行 listener 添加的监听任务
     */
    private final EventExecutor executor;

    /**
     * Creates a new instance.
     *
     * @param executor the {@link EventExecutor} associated with this future
     *
     * 这有一个构造方法，可知executor是必须的
     */
    protected CompleteFuture(EventExecutor executor) {
        this.executor = executor;
    }

    /**
     * Return the {@link EventExecutor} which is used by this {@link CompleteFuture}.
     */
    protected EventExecutor executor() {
        return executor;
    }

    /**
     * 直接异步执行当前的监听事件
     *
     * 注意 {@link io.netty.util.concurrent.DefaultPromise#addListener0(io.netty.util.concurrent.GenericFutureListener)} 方法的区别，
     * 是先将监听任务添加到 listeners 中，后续再异步去执行唤醒操作
     *
     * @param listener
     * @return
     */
    @Override
    public Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }

        // 由于这是一个已完成的Future，所以立即通知Listener执行
        DefaultPromise.notifyListener(executor(), this, listener);
        return this;
    }

    @Override
    public Future<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        if (listeners == null) {
            throw new NullPointerException("listeners");
        }
        for (GenericFutureListener<? extends Future<? super V>> l: listeners) {
            if (l == null) {
                break;
            }
            DefaultPromise.notifyListener(executor(), this, l);
        }
        return this;
    }

    /**
     * 空实现
     * @param listener
     * @return
     */
    @Override
    public Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // 由于已完成，Listener中的操作已完成，没有需要删除的Listener
        return this;
    }

    @Override
    public Future<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        // NOOP
        return this;
    }

    @Override
    public Future<V> await() throws InterruptedException {
        if (Thread.interrupted()) { // 检验当前线程是否中断
            throw new InterruptedException();
        }
        return this;        // 直接返回
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }

    @Override
    public Future<V> sync() throws InterruptedException {
        return this;
    }

    @Override
    public Future<V> syncUninterruptibly() {
        return this;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }

    @Override
    public Future<V> awaitUninterruptibly() {
        return this;
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        return true;
    }

    /**
     * 执行结束
     * @return
     */
    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public boolean isCancellable() {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     *
     *  当前 future 是一个执行完成的 future, 无法撤销
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }
}
