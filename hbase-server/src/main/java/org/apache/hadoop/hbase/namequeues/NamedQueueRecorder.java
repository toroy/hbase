/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.namequeues;

import com.google.common.base.Preconditions;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.namequeues.request.NamedQueueGetRequest;
import org.apache.hadoop.hbase.namequeues.response.NamedQueueGetResponse;
import org.apache.hadoop.hbase.util.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NamedQueue recorder that maintains various named queues.
 * The service uses LMAX Disruptor to save queue records which are then consumed by
 * a queue and based on the ring buffer size, the available records are then fetched
 * from the queue in thread-safe manner.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class NamedQueueRecorder implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(NamedQueueRecorder.class);
  private final Disruptor<RingBufferEnvelope> disruptor;
  private final LogEventHandler logEventHandler;
  private final ExecutorService executorService;

  private static NamedQueueRecorder namedQueueRecorder;
  private static boolean isInit = false;
  private static final Object LOCK = new Object();
  private volatile boolean closed = false;

  /**
   * Initialize disruptor with configurable ringbuffer size
   */
  private NamedQueueRecorder(Configuration conf) {

    // This is the 'writer' -- a single threaded executor. This single thread consumes what is
    // put on the ringbuffer.
    final String hostingThreadName = Thread.currentThread().getName();

    int eventCount = conf.getInt("hbase.namedqueue.ringbuffer.size", 1024);

    this.executorService = Executors.newSingleThreadExecutor(Threads.getNamedThreadFactory(
      hostingThreadName + ".slowlog.append-pool"));
    // disruptor initialization with BlockingWaitStrategy
    this.disruptor = new Disruptor<>(getEventFactory(), getEventCount(eventCount), executorService,
      ProducerType.MULTI, new BlockingWaitStrategy());
    this.disruptor.handleExceptionsWith(new DisruptorExceptionHandler());

    // initialize ringbuffer event handler
    this.logEventHandler = new LogEventHandler(conf);
    this.disruptor.handleEventsWith(new LogEventHandler[]{this.logEventHandler});
    this.disruptor.start();
  }

  private EventFactory<RingBufferEnvelope> getEventFactory() {
    return new EventFactory<RingBufferEnvelope>() {
      @Override
      public RingBufferEnvelope newInstance() {
        return new RingBufferEnvelope();
      }
    };
  }

  public static NamedQueueRecorder getInstance(Configuration conf) {
    if (namedQueueRecorder != null) {
      return namedQueueRecorder;
    }
    synchronized (LOCK) {
      if (!isInit) {
        namedQueueRecorder = new NamedQueueRecorder(conf);
        isInit = true;
      }
    }
    return namedQueueRecorder;
  }

  // must be power of 2 for disruptor ringbuffer
  private int getEventCount(int eventCount) {
    Preconditions.checkArgument(eventCount >= 0, "hbase.namedqueue.ringbuffer.size must be > 0");
    int floor = Integer.highestOneBit(eventCount);
    if (floor == eventCount) {
      return floor;
    }
    // max capacity is 1 << 30
    if (floor >= 1 << 29) {
      return 1 << 30;
    }
    return floor << 1;
  }

  /**
   * Retrieve in memory queue records from ringbuffer
   *
   * @param request namedQueue request with event type
   * @return queue records from ringbuffer after filter (if applied)
   */
  public NamedQueueGetResponse getNamedQueueRecords(NamedQueueGetRequest request) {
    return this.logEventHandler.getNamedQueueRecords(request);
  }

  /**
   * clears queue records from ringbuffer
   *
   * @param namedQueueEvent type of queue to clear
   * @return true if slow log payloads are cleaned up or
   *   hbase.regionserver.slowlog.buffer.enabled is not set to true, false if failed to
   *   clean up slow logs
   */
  public boolean clearNamedQueue(NamedQueuePayload.NamedQueueEvent namedQueueEvent) {
    return this.logEventHandler.clearNamedQueue(namedQueueEvent);
  }

  /**
   * Add various NamedQueue records to ringbuffer. Based on the type of the event (e.g slowLog),
   * consumer of disruptor ringbuffer will have specific logic.
   * This method is producer of disruptor ringbuffer which is initialized in NamedQueueRecorder
   * constructor.
   *
   * @param namedQueuePayload namedQueue payload sent by client of ring buffer
   *   service
   */
  public void addRecord(NamedQueuePayload namedQueuePayload) {
    if (!closed) {
      RingBuffer<RingBufferEnvelope> ringBuffer = this.disruptor.getRingBuffer();
      long seqId = ringBuffer.next();
      try {
        ringBuffer.get(seqId).load(namedQueuePayload);
      } finally {
        ringBuffer.publish(seqId);
      }
    }
  }

  /**
   * Add all in memory queue records to system table. The implementors can use system table
   * or direct HDFS file or ZK as persistence system.
   */
  public void persistAll(NamedQueuePayload.NamedQueueEvent namedQueueEvent) {
    if (this.logEventHandler != null) {
      this.logEventHandler.persistAll(namedQueueEvent);
    }
  }

  @Override
  public void close() throws IOException {
    // Setting closed flag to true so that we don't add more events to RingBuffer.
    this.closed = true;
    LOG.info("Closing NamedQueueRecorder");
    if (this.disruptor != null) {
      long timeoutms = 5000;
      try {
        this.disruptor.shutdown(timeoutms, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        LOG.warn("Timed out bringing down disruptor after " + timeoutms + " ms; forcing halt", e);
        this.disruptor.halt();
        this.disruptor.shutdown();
      }
    }
    // With disruptor down, this is safe to let go.
    if (this.executorService !=  null) {
      // This will close the executor threads.
      this.executorService.shutdownNow();
    }
  }
}
