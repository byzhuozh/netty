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

package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 对象池化的 ByteBuf 抽象基类
 * @param <T>
 */
abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * Recycler 处理器，用于回收对象
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    /**
     * Chunk 对象（内存块）
     */
    protected PoolChunk<T> chunk;

    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;

    /**
     * 内存空间。具体什么样的数据，通过子类设置泛型。
     */
    protected T memory;

    /**
     * {@link #memory} 开始位置
     *
     * @see #idx(int)
     */
    protected int offset;

    /**
     * 容量
     *
     * @see #capacity()
     */
    protected int length;

    /**
     * 占用 {@link #memory} 的大小
     *
     * ps：maxLength 属性，不是表示最大容量。maxCapacity 属性，才是真正表示最大容量；
     * 表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )；
     * 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity
     */
    int maxLength;

    PoolThreadCache cache;

    /**
     * 临时 ByteBuff 对象
     *
     * @see #internalNioBuffer()
     */
    private ByteBuffer tmpNioBuf;

    /**
     * ByteBuf 分配器对象
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

   // 一般是基于 pooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
    void init(PoolChunk<T> chunk, long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, handle, offset, length, maxLength, cache);
    }

    // 基于 unPoolooled 的 PoolChunk 对象
    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        // From PoolChunk 对象
        this.chunk = chunk;
        memory = chunk.memory;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
        tmpNioBuf = null;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     *
     * 重用 PooledByteBuf 对象时，需要调用该方法，重置属性
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        // 设置引用数量为 0
        setRefCnt(1);
        // 重置读写索引为 0
        setIndex0(0, 0);
        // 重置读写标记位为 0
        discardMarks();
    }

    // 获得容量
    @Override
    public final int capacity() {
        return length;
    }

    // 调整容量大小
    @Override
    public final ByteBuf capacity(int newCapacity) {
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        // Chunk 内存，非池化
        // If the request capacity does not require reallocation, just update the length of the memory.
        if (chunk.unpooled) {
            if (newCapacity == length) {    // 相等，无需扩容 / 缩容
                return this;
            }
        } else {  // Chunk 内存，是池化
            // 扩容
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            // 缩容
            } else if (newCapacity < length) {
                // 大于 maxLength 的一半
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        // 因为 Netty SubPage 最小是 16 ，如果小于等 16 ，无法缩容
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            // 设置读写索引，避免超过最大容量
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        // 设置读写索引，避免超过最大容量
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                // 相等，无需扩容 / 缩容
                return this;
            }
        }

        // 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间
        // Reallocation required.
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            tmpNioBuf = null;
            chunk.arena.free(chunk, handle, maxLength, cache);
            chunk = null;
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        return offset + index;
    }
}
