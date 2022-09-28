package com.linkedin.venice.pubsub.api;

public interface PubSubMessage<K, V, TP extends PubSubTopicPartition, O> {
  /**
   * @return the key part of this message
   */
  K getKey();

  /**
  * @return the value part of this message
  */
  V getValue();

  /**
   * @return the topic-partition this message belongs to
   */
  TP getTopicPartition();

  /**
   * @return the offset of this message in the underlying topic-partition
   */
  O getOffset();

  /**
   * @return the timestamp at which the message was persisted in the pub sub system
   */
  long getPubSubMessageTime();
}
