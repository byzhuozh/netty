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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 *
 * Recycler 用来实现对象池, 其中对应堆内存和直接内存的池化实现分别是 PooledHeapByteBuf 和 PooledDirectByteBuf
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    /**
     * 不做回收处理：
     *      表示一个不需要回收的包装对象，用于在禁止使用Recycler功能时进行占位的功能
     * 仅当 io.netty.recycler.maxCapacityPerThread<=0 时用到
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };

    /**
     * 唯一ID生成器
     * 用在两处：
     *  1、当前线程ID
     *  2、WeakOrderQueue 的 id
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);

    /**
     * static变量, 生成并获取一个唯一id.
     * 用于pushNow()中的item.recycleId 和 item.lastRecycleId 的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();

    /**
     * 每个Stack默认的最大容量
     * 注意：
     *  1、当io.netty.recycler.maxCapacityPerThread<=0时，禁用回收功能（在netty中，只有=0可以禁用，<0默认使用4k）
     *  2、Recycler中有且只有两个地方存储DefaultHandle对象（Stack和Link），
     * 最多可存储MAX_CAPACITY_PER_THREAD + 最大可共享容量 = 4k + 4k/2 = 6k
     *
     * 实际上，在netty中，Recycler提供了两种设置属性的方式
     *  第一种：-Dio.netty.recycler.ratio等jvm启动参数方式
     *  第二种：Recycler(int maxCapacityPerThread)构造器传入方式
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;

    /**
     * 每个Stack默认的初始容量，默认为256
     * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
     */
    private static final int INITIAL_CAPACITY;

    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;

    /**
     * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
     * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;

    /**
     * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
     * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
     * 所有的Link加起来的容量要<=最大可共享容量。
     */
    private static final int LINK_CAPACITY;

    /**
     * 回收因子，默认为8。
     * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
     */
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        /**
         * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
         * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
         */
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        /**
         * WeakOrderQueue中的Link中的数组DefaultHandle<?>[] elements容量，默认为16，
         * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
         * 所有的Link加起来的容量要<=最大可共享容量。
         */
        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        /**
         * 回收因子，默认为8。
         * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
         */
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        /**
         * 每个Stack默认的初始容量，默认为256
         * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
         */
        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    /**
     * 最大容量
     */
    private final int maxCapacityPerThread;

    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private final int maxSharedCapacityFactor;

    // 用来控制对象回收的频率，并不是每次对象都会被回收的，通过ratioMask来控制对象回收的比率
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;


    /**
     * 1、每个Recycler对象都有一个threadLocal
     *    原因：因为一个Stack要指明存储的对象泛型T，而不同的Recycler<T>对象的T可能不同，所以此处的FastThreadLocal是对象级别
     * 2、每条线程都有一个Stack<T>对象
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            //初始化每个线程对应的 stack
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 获取对象
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        /**
         * 如果maxCapacityPerThread == 0，禁用回收功能
         * 每次都是重建一个对象，其 Recycler.Handle<User> handle 属性为 NOOP_HANDLE，
         * 该对象的 recycle(Object object) 不做任何事情，即不做回收
         */
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }

        /**
         * 获取当前线程的Stack<T>对象
         */
        Stack<T> stack = threadLocal.get();

        /**
         * 从Stack<T>对象中获取DefaultHandle<T>
         */
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            /**
             * 新建一个DefaultHandle对象 -> 然后新建T对象 -> 存储到DefaultHandle对象
             * 此处会发现一个DefaultHandle对象对应一个Object对象，二者相互包含。
             */
            handle = stack.newHandle();
            //对象和处理器之间相互包含
            handle.value = newObject(handle);
        }

        /**
         * 返回value
         */
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**
     * 创建一个对象
     * 1、由子类进行复写，所以使用protected修饰
     * 2、传入Handle对象，对创建出来的对象进行回收操作
     */
    protected abstract T newObject(Handle<T> handle);

    /**
     * 提供对象的回收功能，由子类进行复写
     * 目前该接口只有两个实现：NOOP_HANDLE和DefaultHandle
     */
    public interface Handle<T> {
        void recycle(T object);
    }

    /**
     * DefaultHandle:对象的包装类，在Recycler中缓存的对象都会包装成DefaultHandle类
     *
     * @param <T>
     */
    static final class DefaultHandle<T> implements Handle<T> {

        /**
         * pushNow() = OWN_THREAD_ID
         * 在pushLater中的 add(DefaultHandle handle)操作中 == id（当前的WeakOrderQueue的唯一ID）
         * 在poll()中置位0
         */
        private int lastRecycledId;

        /**
         * 只有在pushNow()中会设置值 OWN_THREAD_ID
         * 在poll()中置位0
         */
        private int recycleId;

        /**
         * 标记是否已经被回收：
         * 该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
         * 重复回收的操作由item.recycleId | item.lastRecycledId来阻止
         */
        boolean hasBeenRecycled;

        //Stack 容器实际管理的是Handle，Handle也会持有该Stack，用于对象回收使用
        private Stack<?> stack;

        /**
         * handle 管理的实际对象，value 与 Handle一一对应
         */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 回收
         * @param object
         */
        @Override
        public void recycle(Object object) {
            // 防护性判断，回收的对象必须和处理器中的对象保持相同
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            // 回收对象，this指的是当前的 DefaultHandle对象
            stack.push(this);
        }
    }

    /**
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    // WeakOrderQueue:存储其它线程回收到本线程stack的对象
    private static final class WeakOrderQueue {

        /**
         * 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，
         * 对于后续的Stack，其对应的WeakOrderQueue设置为DUMMY，
         * 后续如果检测到DELAYED_RECYCLED中对应的Stack的value是WeakOrderQueue.DUMMY时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Link: WeakOrderQueue中包含1个Link链表，回收对象存储在链表某个Link节点里，
        // 当Link节点存储的回收对象满了时会新建1个Link放在Link链表尾
        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;

            /**
             * Link的下一个节点
             */
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        // Head仅仅作为head-Link的占位符，仅用于ObjectCleaner回收操作
        static final class Head {
            private final AtomicInteger availableSharedCapacity;

            /**
             * 指定读操作的Link节点，
             * eg. Head -> Link1 -> Link2
             * 假设此时的读操作在Link2上进行时，则此处的link == Link2，见transfer(Stack dst),
             * 实际上此时Link1已经被读完了，Link1变成了垃圾（一旦一个Link的读指针指向了最后，则该Link不会被重复利用，而是被GC掉，
             * 之后回收空间，新建Link再进行操作）
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * 在该对象被真正的回收前，执行该方法
             * 循环释放当前的WeakOrderQueue中的Link链表所占用的所有共享空间availableSharedCapacity，
             * 如果不释放，否则该值将不会增加，影响后续的Link的创建
             */
            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    Link head = link;
                    link = null;
                    while (head != null) {
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * 这里可以理解为一次内存的批量分配，每次从availableSharedCapacity中分配space个大小的内存。
             * 如果一个Link中不是放置一个DefaultHandle[]，而是只放置一个DefaultHandle，那么此处的space==1，这样的话就需要频繁的进行内存分配
             */
            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                // cas + 无限重试进行 分配
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    // 注意：这里使用的是AtomicInteger，当这里的availableSharedCapacity发生变化时，
                    // 实际上就是改变的stack.availableSharedCapacity的int value属性的值
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;

        /**
         * 1、why WeakReference？与Stack相同。
         * 2、作用是在poll的时候，如果owner不存在了，则需要将该线程所包含的WeakOrderQueue的元素释放，然后从链表中删除该Queue。
         */
        private final WeakReference<Thread> owner;

        /**
         * WeakOrderQueue的唯一标记
         */
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            // 创建有效Link节点，恰好是尾节点
            tail = new Link();

            // 创建Link链表头节点，只是占位符
            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            owner = new WeakReference<Thread>(thread);
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // 创建WeakOrderQueue
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);

            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 将该queue赋值给stack的head属性
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;

            // 判断一个Link对象是否已经满了：
            // 如果没满，直接添加；
            // 如果已经满了，创建一个新的Link对象，之后重组Link链表，然后添加元素的末尾的Link（除了这个Link，前边的Link全部已经满了）
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }

                // We allocate a Link so reserve the space
                /**
                 * 此处创建一个Link，会将该Link作为新的tail-Link，之前的tail-Link已经满了，成为正常的Link了。重组Link链表
                 * 之前是HEAD -> tail-Link，重组后HEAD -> 之前的tail-Link -> 新的tail-Link
                 */
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;

            /**
             * 如果使用者在将DefaultHandle对象压入队列后，
             * 将Stack设置为null，但是此处的DefaultHandle是持有stack的强引用的，则Stack对象无法回收；
             * 而且由于此处DefaultHandle是持有stack的强引用，WeakHashMap中对应stack的WeakOrderQueue也无法被回收掉了，导致内存泄漏。
             */
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // tail本身继承于AtomicInteger，所以此处直接对tail进行+1操作
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            // 寻找第一个Link（Head不是Link）
            Link head = this.head.link;
            // head == null，表示只有Head一个节点，没有存储数据的节点，直接返回
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            // 获取Link节点的readIndex,即当前的Link节点的第一个有效元素的位置
            final int srcStart = head.readIndex;
            // 获取Link节点的writeIndex，即当前的Link节点的最后一个有效元素的位置
            int srcEnd = head.get();
            // 计算Link节点中可以被转移的元素个数
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            // 获取转移元素的目的地Stack中当前的元素个数
            final int dstSize = dst.size;
            // 计算期盼的容量
            final int expectedCapacity = dstSize + srcSize;

            /**
             * 如果expectedCapacity大于目的地Stack的长度
             * 1、对目的地Stack进行扩容
             * 2、计算Link中最终的可转移的最后一个元素的下标
             */
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                // 获取Link节点的DefaultHandle[]
                final DefaultHandle[] srcElems = head.elements;
                // 获取目的地Stack的DefaultHandle[]
                final DefaultHandle[] dstElems = dst.elements;
                // dst数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }

                    // 置空Link节点的DefaultHandle[i]
                    srcElems[i] = null;

                    /**
                     * 设置element.recycleId 或者 进行防护性判断
                     * 扔掉放弃7/8的元素
                     */
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    // 将可转移成功的DefaultHandle元素的stack属性设置为目的地Stack
                    element.stack = dst;
                    // 将DefaultHandle元素转移到目的地Stack的DefaultHandle[newDstSize ++]中
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    // 将Head指向下一个Link，也就是将当前的Link给回收掉了
                    // 假设之前为Head -> Link1 -> Link2，回收之后为Head -> Link2
                    this.head.link = head.next;
                }

                head.readIndex = srcEnd;
                head.readIndex = srcEnd; // 表示没有被回收任何一个对象，直接返回 // 表示没有被回收任何一个对象，直接返回
                if (dst.size == newDstSize) {
                    return false;
                }
                // 将新的newDstSize赋值给目的地Stack的size
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /* 该Stack所属的线程
         * why WeakReference?
         * 假设该线程对象在外界已经没有强引用了，那么实际上该线程对象就可以被回收了。但是如果此处用的是强引用，那么虽然外界不再对该线程有强引用，
         * 但是该stack对象还持有强引用（假设用户存储了DefaultHandle对象，然后一直不释放，而DefaultHandle对象又持有stack引用），导致该线程对象无法释放。
         */
        final WeakReference<Thread> threadRef;

        /**
         * 可用的共享内存大小，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的Stack是线程A的，则其他线程B~X等去回收线程A创建的对象时，可回收最多A创建的多少个对象
         * 注意：那么实际上线程A创建的对象最终最多可以被回收maxCapacity + availableSharedCapacity个，默认为6k个
         *
         * why AtomicInteger?
         * 当线程B和线程C同时创建线程A的WeakOrderQueue的时候，会同时分配内存，需要同时操作availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        final AtomicInteger availableSharedCapacity;

        // 线程1创建的对象，可能被线程2释放，线程2释放的时候会创建一个WeakOrderQueue，WeakOrderQueue中就存放着线程1中创建的对，
        // 到线程2中去释放，后续分析再详细讲解。这个参数就表示线程1创建的对象，能够释放的线程数有多少
        /**
         * DELAYED_RECYCLED中最多可存储的{Stack，WeakOrderQueue}键值对个数
         */
        final int maxDelayedQueues;

        // 表示Handle数组的最大的大小,  elements最大的容量：默认最大为4k，4096
        private final int maxCapacity;

        /**
         * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉
         */
        private final int ratioMask;

        /**
         * Stack底层数据结构，真正的用来存储数据
         */
        private DefaultHandle<?>[] elements;

        /**
         * elements中的元素个数，同时也可作为操作数组的下标
         * 数组只有elements.length来计算数组容量的函数，没有计算当前数组中的元素个数的函数，所以需要我们去记录，不然需要每次都去计算
         */
        private int size;

        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的handleRecycleCount=handleRecycleCount+1=0
         * 与ratioMask配合，用来决定当前的元素是被回收还是被drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被drop
         */
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        private WeakOrderQueue cursor, prev;

        /**
         * 该值是当线程B回收线程A创建的对象时，线程B会为线程A的Stack对象创建一个WeakOrderQueue对象，
         * 该WeakOrderQueue指向这里的head，用于后续线程A对对象的查找操作
         * Q: why volatile?
         * A: 假设线程A正要读取对象X，此时需要从其他线程的WeakOrderQueue中读取，假设此时线程B正好创建Queue，并向Queue中放入一个对象X；假设恰好次Queue就是线程A的Stack的head
         * 使用volatile可以立即读取到该queue。
         *
         * 对于head的设置，具有同步问题。具体见此处的volatile和synchronized void setHead(WeakOrderQueue queue)
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            //threadRef用于记录该Stack被哪个Thread持有
            threadRef = new WeakReference<Thread>(thread);
            //该对象池最大可持有的对象数量
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        /**
         * 假设线程B和线程C同时回收线程A的对象时，有可能会同时newQueue，就可能同时setHead，所以这里需要加锁
         * 以head==null的时候为例，
         *
         * 加锁：
         *      线程B先执行，则head = 线程B的queue；之后线程C执行，此时将当前的head也就是线程B的queue作为线程C的queue的next，组成链表，之后设置head为线程C的queue
         *
         * 不加锁：
         *      线程B先执行queue.setNext(head);此时线程B的queue.next=null->线程C执行queue.setNext(head);线程C的queue.next=null
         * -> 线程B执行head = queue;设置head为线程B的queue -> 线程C执行head = queue;设置head为线程C的queue
         *
         * 注意：此时线程B和线程C的queue没有连起来，则之后的poll()就不会从B进行查询。（B就是资源泄露）
         */
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length; // 获取旧数组长度
            int maxCapacity = this.maxCapacity;   // 获取最大长度
            // 不断扩容（每次扩容2倍），直到达到expectedCapacity或者新容量已经大于等于maxCapacity
            do {
                // 扩容2倍
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            // 上述的扩容有可能使新容量newCapacity>maxCapacity，这里取最小值
            newCapacity = min(newCapacity, maxCapacity);
            // 如果新旧容量不相等，进行实际扩容
            if (newCapacity != elements.length) {
                // 创建新数组，复制旧数组元素到新数组，并将新数组赋值给Stack.elements
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                /**
                 * 由于在transfer(Stack<?> dst)的过程中，可能会将其他线程的WeakOrderQueue中的DefaultHandle对象传递到当前的Stack,
                 * 所以size发生了变化，需要重新赋值
                 */
                size = this.size;
            }
            size --;
            DefaultHandle ret = elements[size];
            // 置空
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            // 置位
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                // 如果head==null，表示当前的Stack对象没有WeakOrderQueue，直接返回
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                // 遍历下一个WeakOrderQueue
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 须判断是否是 stack 对应的线程做的回收
         * @param item
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                //如果该stack就是本线程的stack，那么直接把DefaultHandle放到该stack的数组里
                pushNow(item);
            } else {
                //如果该stack不是本线程的stack，那么把该DefaultHandle放到该stack的WeakOrderQueue中
                pushLater(item, currentThread);
            }
        }

        /**
         * 直接把 DefaultHandle放到stack的数组里，如果数组满了那么扩展该数组为当前2倍大小
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            // (item.recycleId | item.lastRecycleId) != 0 等价于 item.recycleId!=0 && item.lastRecycleId!=0
            //   当item开始创建时item.recycleId==0 && item.lastRecycleId==0
            //   当item被recycle时，item.recycleId==x，item.lastRecycleId==y 进行赋值
            //   当item被poll之后， item.recycleId = item.lastRecycleId = 0
            //   所以当item.recycleId 和 item.lastRecycleId 任何一个不为0，则表示回收过
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;

            //如果当前Stack已经超过了其定义的最大容量，则不进行归还操作
            //删除该Handle
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }

            // stack 中的 elements 扩容两倍，复制元素，将新数组赋值给stack.elements
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            //实际归还，也就是把Handle放入数组中，然后计数器加一
            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 先将item元素加入WeakOrderQueue，后续再从WeakOrderQueue中将元素压入Stack中
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            /**
             * Recycler 有1个 stack->WeakOrderQueue 映射，每个stack会映射到1个WeakOrderQueue，
             * 这个WeakOrderQueue是该stack关联的其它线程WeakOrderQueue链表的head WeakOrderQueue。
             * 当其它线程回收对象到该stack时会创建1个WeakOrderQueue中并加到stack的WeakOrderQueue链表中。
             */
            //每个线程都在自己的ThreadLocal中为每个其他所有者线程维护了一个 WeakOrderQueue，归还从该所有者线程对象池取出的对象时
            //其实就是将归还的对象方法到该队列中
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);

            //WeakOrderQueue 队列为空
            if (queue == null) {
                /**
                 * 如果delayedRecycled满了，那么将1个伪造的WeakOrderQueue（DUMMY）放到delayedRecycled中，并丢弃该对象（DefaultHandle）
                 * */
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }

                /**
                 * 创建1个WeakOrderQueue
                 */
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }


                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                //如果取出的队列为上面初始化的DUMMY队列，则直接放回
                // drop object
                return;
            }

            /**
             * 将对象放入到该stack对应的WeakOrderQueue中
             */
            queue.add(item);
        }

        /**
         * 两个 drop 的时机
         * 1、pushNow：当前线程将数据push到Stack中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                // 每8个对象：扔掉7个，回收一个
                // 回收的索引：handleRecycleCount - 0/8/16/24/32/...
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }

                // 设置已经被回收了的标志，实际上此处还没有被回收，在pushNow(DefaultHandle<T> item)接下来的逻辑就会进行回收
                // 对于pushNow(DefaultHandle<T> item)：该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0
                // 这段逻辑，而不会用于阻止重复回收的操作，重复回收的操作由item.recycleId | item.lastRecycledId来阻止
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        // 新建一个处理器
        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
