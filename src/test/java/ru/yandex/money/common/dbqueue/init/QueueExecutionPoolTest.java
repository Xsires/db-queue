package ru.yandex.money.common.dbqueue.init;

import com.google.common.util.concurrent.MoreExecutors;
import org.junit.Assert;
import org.junit.Test;
import ru.yandex.money.common.dbqueue.api.QueueConsumer;
import ru.yandex.money.common.dbqueue.api.QueueExternalExecutor;
import ru.yandex.money.common.dbqueue.api.QueueShardId;
import ru.yandex.money.common.dbqueue.api.QueueShardRouter;
import ru.yandex.money.common.dbqueue.api.TaskLifecycleListener;
import ru.yandex.money.common.dbqueue.api.ThreadLifecycleListener;
import ru.yandex.money.common.dbqueue.dao.QueueDao;
import ru.yandex.money.common.dbqueue.internal.QueueLoop;
import ru.yandex.money.common.dbqueue.internal.runner.QueueRunner;
import ru.yandex.money.common.dbqueue.settings.QueueConfig;
import ru.yandex.money.common.dbqueue.settings.QueueId;
import ru.yandex.money.common.dbqueue.settings.QueueLocation;
import ru.yandex.money.common.dbqueue.settings.QueueSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class QueueExecutionPoolTest {

    @Test
    public void should_not_start_stop_when_not_initialized() throws Exception {
        QueueExecutionPool queueExecutionPool = new QueueExecutionPool(
                mock(QueueRegistry.class), mock(TaskLifecycleListener.class), mock(ThreadLifecycleListener.class));
        try {
            queueExecutionPool.shutdown();
            Assert.fail("should not shutdown");
        } catch (Exception e) {
            assertThat(e.getMessage(), equalTo("pool is not initialized"));
        }

        try {
            queueExecutionPool.start();
            Assert.fail("should not start");
        } catch (Exception e) {
            assertThat(e.getMessage(), equalTo("pool is not initialized"));
        }
    }

    @Test
    public void should_not_start_stop_when_invoked_twice() throws Exception {
        QueueRegistry queueRegistry = mock(QueueRegistry.class);
        when(queueRegistry.getConsumers()).thenReturn(new ArrayList<>());
        when(queueRegistry.getExternalExecutors()).thenReturn(new HashMap<>());
        QueueExecutionPool queueExecutionPool = new QueueExecutionPool(
                queueRegistry, mock(TaskLifecycleListener.class), mock(ThreadLifecycleListener.class));
        queueExecutionPool.init();
        try {
            queueExecutionPool.start();
            queueExecutionPool.start();
            Assert.fail("should not start twice");
        } catch (Exception e) {
            assertThat(e.getMessage(), equalTo("queues already started"));
        }

        try {
            queueExecutionPool.shutdown();
            queueExecutionPool.shutdown();
            Assert.fail("should not shutdown twice");
        } catch (Exception e) {
            assertThat(e.getMessage(), equalTo("queues already stopped"));
        }
    }

    @Test
    public void should_not_start_queue_when_thread_count_is_zero() throws Exception {
        QueueId queueId1 = new QueueId("testQueue1");
        QueueLocation location1 = QueueLocation.builder().withTableName("testTable")
                .withQueueId(queueId1).build();
        QueueShardId shardId1 = new QueueShardId("s1");
        QueueDao queueDao1 = mock(QueueDao.class);
        when(queueDao1.getShardId()).thenReturn(shardId1);

        QueueRegistry queueRegistry = mock(QueueRegistry.class);
        QueueConsumer queueConsumer = mock(QueueConsumer.class);
        when(queueConsumer.getQueueConfig()).thenReturn(new QueueConfig(
                location1,
                QueueSettings.builder()
                        .withNoTaskTimeout(Duration.ZERO)
                        .withThreadCount(0)
                        .withBetweenTaskTimeout(Duration.ZERO).build()));
        QueueShardRouter shardRouter = mock(QueueShardRouter.class);
        when(shardRouter.getShardsId()).thenReturn(new ArrayList() {{
            add(shardId1);
        }});
        TaskLifecycleListener queueShardListener = mock(TaskLifecycleListener.class);
        QueueExternalExecutor externalExecutor = mock(QueueExternalExecutor.class);

        when(queueConsumer.getShardRouter()).thenReturn(shardRouter);
        when(queueRegistry.getConsumers()).thenReturn(Collections.singletonList(queueConsumer));
        when(queueRegistry.getTaskListeners()).thenReturn(
                Collections.singletonMap(queueId1, queueShardListener));
        when(queueRegistry.getExternalExecutors()).thenReturn(
                Collections.singletonMap(queueId1, externalExecutor));
        when(queueRegistry.getShards()).thenReturn(new HashMap<QueueShardId, QueueDao>() {{
            put(shardId1, queueDao1);
        }});

        ThreadFactory threadFactory = mock(ThreadFactory.class);
        TaskLifecycleListener defaultTaskListener = mock(TaskLifecycleListener.class);
        ThreadLifecycleListener threadListener = mock(ThreadLifecycleListener.class);
        ExecutorService queueThreadExecutor = spy(MoreExecutors.newDirectExecutorService());

        QueueLoop queueLoop = mock(QueueLoop.class);
        QueueRunner queueRunner = mock(QueueRunner.class);

        QueueExecutionPool queueExecutionPool = new QueueExecutionPool(queueRegistry,
                defaultTaskListener, threadListener,
                (location, shardId) -> threadFactory,
                (threadCount, factory) -> {
                    new ArrayBlockingQueue<>(threadCount);
                    assertThat(factory, sameInstance(threadFactory));
                    assertThat(threadCount, equalTo(0));
                    return queueThreadExecutor;
                },
                listener -> {
                    assertThat(listener, sameInstance(threadListener));
                    return queueLoop;
                },
                poolInstance -> {
                    assertThat(poolInstance.queueConsumer, sameInstance(queueConsumer));
                    assertThat(poolInstance.externalExecutor, sameInstance(externalExecutor));
                    assertThat(poolInstance.taskListener, sameInstance(queueShardListener));
                    return queueRunner;
                });
        queueExecutionPool.init();
        queueExecutionPool.start();

        verifyZeroInteractions(queueThreadExecutor);
        verifyZeroInteractions(queueLoop);
        queueExecutionPool.shutdown();

        verify(externalExecutor, times(1)).shutdownQueueExecutor();
        verifyZeroInteractions(queueThreadExecutor);
    }

    @Test
    public void should_start_and_stop_queue_on_two_shards_and_three_threads() throws Exception {
        QueueId queueId1 = new QueueId("testQueue1");
        QueueLocation location1 = QueueLocation.builder().withTableName("testTable")
                .withQueueId(queueId1).build();
        QueueShardId shardId1 = new QueueShardId("s1");
        QueueShardId shardId2 = new QueueShardId("s2");
        QueueDao queueDao1 = mock(QueueDao.class);
        when(queueDao1.getShardId()).thenReturn(shardId1);
        QueueDao queueDao2 = mock(QueueDao.class);
        when(queueDao2.getShardId()).thenReturn(shardId2);

        QueueRegistry queueRegistry = mock(QueueRegistry.class);
        QueueConsumer queueConsumer = mock(QueueConsumer.class);
        int targetThreadCount = 3;
        when(queueConsumer.getQueueConfig()).thenReturn(new QueueConfig(
                location1,
                QueueSettings.builder()
                        .withNoTaskTimeout(Duration.ZERO)
                        .withThreadCount(targetThreadCount)
                        .withBetweenTaskTimeout(Duration.ZERO).build()));
        QueueShardRouter shardRouter = mock(QueueShardRouter.class);
        when(shardRouter.getShardsId()).thenReturn(new ArrayList() {{
            add(shardId1);
            add(shardId2);
        }});
        TaskLifecycleListener taskListener = mock(TaskLifecycleListener.class);
        ThreadLifecycleListener threadListener = mock(ThreadLifecycleListener.class);
        QueueExternalExecutor externalExecutor = mock(QueueExternalExecutor.class);

        when(queueConsumer.getShardRouter()).thenReturn(shardRouter);
        when(queueRegistry.getConsumers()).thenReturn(Collections.singletonList(queueConsumer));
        when(queueRegistry.getTaskListeners()).thenReturn(
                Collections.singletonMap(queueId1, taskListener));
        when(queueRegistry.getThreadListeners()).thenReturn(
                Collections.singletonMap(queueId1, threadListener));
        when(queueRegistry.getExternalExecutors()).thenReturn(
                Collections.singletonMap(queueId1, externalExecutor));
        when(queueRegistry.getShards()).thenReturn(new HashMap<QueueShardId, QueueDao>() {{
            put(shardId1, queueDao1);
            put(shardId2, queueDao2);
        }});

        ThreadFactory threadFactory = mock(ThreadFactory.class);
        TaskLifecycleListener defaultTaskListener = mock(TaskLifecycleListener.class);
        ThreadLifecycleListener defaltThreadListener = mock(ThreadLifecycleListener.class);
        ExecutorService queueThreadExecutor = spy(MoreExecutors.newDirectExecutorService());

        QueueLoop queueLoop = mock(QueueLoop.class);
        QueueRunner queueRunner = mock(QueueRunner.class);

        QueueExecutionPool queueExecutionPool = new QueueExecutionPool(queueRegistry,
                defaultTaskListener, defaltThreadListener,
                (location, shardId) -> threadFactory,
                (threadCount, factory) -> {
                    new ArrayBlockingQueue<>(threadCount);
                    assertThat(factory, sameInstance(threadFactory));
                    assertThat(threadCount, equalTo(targetThreadCount));
                    return queueThreadExecutor;
                },
                listener -> {
                    assertThat(listener, sameInstance(threadListener));
                    return queueLoop;
                },
                poolInstance -> {
                    assertThat(poolInstance.queueConsumer, sameInstance(queueConsumer));
                    assertThat(poolInstance.externalExecutor, sameInstance(externalExecutor));
                    assertThat(poolInstance.taskListener, sameInstance(taskListener));
                    assertThat(poolInstance.threadListener, sameInstance(threadListener));
                    return queueRunner;
                });
        queueExecutionPool.init();
        queueExecutionPool.start();

        verify(queueThreadExecutor, times(2 * targetThreadCount)).execute(any());
        verify(queueLoop, times(targetThreadCount)).start(shardId1, queueConsumer, queueRunner);
        verify(queueLoop, times(targetThreadCount)).start(shardId2, queueConsumer, queueRunner);

        queueExecutionPool.shutdown();

        verify(externalExecutor, times(1)).shutdownQueueExecutor();
        verify(queueThreadExecutor, times(2)).shutdownNow();
        verify(queueThreadExecutor, times(2)).awaitTermination(30L, TimeUnit.SECONDS);
    }
}