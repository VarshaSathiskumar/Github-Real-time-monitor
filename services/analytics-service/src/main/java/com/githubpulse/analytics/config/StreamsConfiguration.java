package com.githubpulse.analytics.config;

import com.githubpulse.analytics.store.AggregateWriter;
import com.githubpulse.analytics.store.EventPersistence;
import com.githubpulse.analytics.store.JdbcFailureSink;
import com.githubpulse.analytics.stream.PulseTopology;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafkaStreams
public class StreamsConfiguration {

    @Bean
    PulseTopology pulseTopology(
            StreamsBuilder builder,
            PulseProperties props,
            EventPersistence eventSink,
            AggregateWriter aggregateSink,
            JdbcFailureSink failureSink) {
        PulseTopology topology = new PulseTopology(props, eventSink, aggregateSink, failureSink);
        topology.build(builder);
        return topology;
    }

    /** A crashing stream thread is replaced instead of taking the whole application down. */
    @Bean
    StreamsBuilderFactoryBeanConfigurer streamsExceptionHandler() {
        return factoryBean -> factoryBean.setStreamsUncaughtExceptionHandler(
                ex -> StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD);
    }

    @Bean
    NewTopic rawEventsTopic(PulseProperties props) {
        return TopicBuilder.name(props.topics().raw())
                .partitions(props.topics().rawPartitions())
                .replicas(props.topics().replicationFactor())
                .build();
    }

    @Bean
    NewTopic deadLetterTopic(PulseProperties props) {
        return TopicBuilder.name(props.topics().dlq())
                .partitions(1)
                .replicas(props.topics().replicationFactor())
                .build();
    }
}
