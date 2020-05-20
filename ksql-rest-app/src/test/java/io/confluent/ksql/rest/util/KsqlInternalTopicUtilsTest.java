/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.util;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.ksql.exception.KafkaTopicExistsException;
import io.confluent.ksql.services.KafkaTopicClient;
import io.confluent.ksql.util.KsqlConfig;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KsqlInternalTopicUtilsTest {
  private static final String TOPIC_NAME = "topic";
  private static final short NREPLICAS = 2;

  private final Map<String, ?> commandTopicConfig = ImmutableMap.of(
      TopicConfig.RETENTION_MS_CONFIG, Long.MAX_VALUE,
      TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);

  @Mock
  private KafkaTopicClient topicClient;
  @Mock
  private KsqlConfig ksqlConfig;

  @Before
  public void setUp() {
    when(ksqlConfig.originals()).thenReturn(
        ImmutableMap.of(KsqlConfig.SINK_NUMBER_OF_REPLICAS_PROPERTY, NREPLICAS)
    );
    when(ksqlConfig.getShort(KsqlConfig.SINK_NUMBER_OF_REPLICAS_PROPERTY)).thenReturn(NREPLICAS);
    when(topicClient.isTopicExists(TOPIC_NAME)).thenReturn(false);
  }

  private void whenTopicExistsWith(final int nPartitions, final int nReplicas) {
    when(topicClient.isTopicExists(TOPIC_NAME)).thenReturn(true);
    final List<TopicPartitionInfo> partitions = new LinkedList<>();
    for (int p = 0; p < nPartitions; p++) {
      final List<Node> nodes = IntStream.range(0, nReplicas)
          .mapToObj(i -> mock(Node.class))
          .collect(Collectors.toList());
      partitions.add(
          new TopicPartitionInfo(p, nodes.get(0), nodes, nodes)
      );
    }
    final TopicDescription description = new TopicDescription(TOPIC_NAME, false, partitions);
    when(topicClient.describeTopic(TOPIC_NAME)).thenReturn(description);
  }

  @Test
  public void shouldCreateInternalTopicIfItDoesNotExist() {
    // When:
    KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient);

    // Then:
    verify(topicClient).createTopic(TOPIC_NAME, 1, NREPLICAS, commandTopicConfig);
  }

  @Test
  public void shouldNotAttemptToCreateInternalTopicIfItExists() {
    // Given:
    whenTopicExistsWith(1, NREPLICAS);

    // When:
    KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient);

    // Then:
    verify(topicClient, never()).createTopic(any(), anyInt(), anyShort(), anyMap());
  }

  @Test
  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
  public void shouldEnsureInternalTopicHasInfiniteRetention() {
    // Given:
    final Map<String, Object> retentionConfig = ImmutableMap.of(
        TopicConfig.RETENTION_MS_CONFIG, Long.MAX_VALUE
    );
    whenTopicExistsWith(1, NREPLICAS);

    // When:
    KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient);

    // Then:
    verify(topicClient).addTopicConfig(TOPIC_NAME, retentionConfig);
  }

  @Test
  public void shouldCreateInternalTopicWithNumReplicasFromConfig() {
    // Given:
    when(ksqlConfig.getShort(KsqlConfig.SINK_NUMBER_OF_REPLICAS_PROPERTY)).thenReturn((short)3);

    // When:
    KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient);

    // Then:
    verify(topicClient).createTopic(TOPIC_NAME, 1, (short) 3, commandTopicConfig);
  }

  @Test
  public void shouldFailIfTopicExistsOnCreationWithDifferentConfigs() {
    // Given:
    doThrow(new KafkaTopicExistsException("exists"))
        .when(topicClient)
        .createTopic(any(), anyInt(), anyShort(), anyMap());

    // When/Then:
    assertThrows(
        KafkaTopicExistsException.class,
        () -> KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient)
    );
  }

  @Test
  public void shouldFailIfTopicExistsWithInvalidNPartitions() {
    // Given:
    whenTopicExistsWith(2, NREPLICAS);

    // When/Then:
    assertThrows(
        IllegalStateException.class,
        () -> KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient)
    );
  }

  @Test
  public void shouldFailIfTopicExistsWithInvalidNReplicas() {
    // Given:
    whenTopicExistsWith(1, 1);

    // When/Then:
    assertThrows(
        IllegalStateException.class,
        () -> KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient)
    );
  }

  @Test
  public void hsouldNotFailIfTopicIsOverreplicated() {
    // Given:
    whenTopicExistsWith(1, NREPLICAS + 1);

    // When/Then (no error):
    KsqlInternalTopicUtils.ensureTopic(TOPIC_NAME, ksqlConfig, topicClient);
  }
}