package com.githubpulse.ingestion.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic rawEventsTopic(IngestionProperties props) {
        return TopicBuilder.name(props.rawTopic())
                .partitions(props.rawTopicPartitions())
                .replicas(props.topicReplicationFactor())
                .build();
    }

    @Bean
    NewTopic deadLetterTopic(IngestionProperties props) {
        return TopicBuilder.name(props.dlqTopic())
                .partitions(1)
                .replicas(props.topicReplicationFactor())
                .build();
    }
}
