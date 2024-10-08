package com.ecommerce.itemservice.kafka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

public class TopicConfig {

    public static final String ITEM_UPDATE_LOG_TOPIC = "e-commerce.item.item-update-log";
    public static final String ITEM_STOCK_AGGREGATION_RESULTS_TOPIC = "e-commerce.item.item-stock-aggregation-results";
    public static final String ITEM_UPDATE_RESULT_TOPIC = "e-commerce.item.item-update-result";
    public static final String ITEM_UPDATE_RESULT_STREAMS_ONLY_TOPIC = "e-commerce.item.item-update-result-streams-only";

    public static final String REQUESTED_ORDER_TOPIC = "e-commerce.order.requested-order-details";
    public static final String REQUESTED_ORDER_STREAMS_ONLY_TOPIC = "e-commerce.order.requested-order-details-streams-only";

    @Bean
    public KafkaAdmin.NewTopics newTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(ITEM_UPDATE_RESULT_TOPIC)
                        .partitions(1)
                        .replicas(1)
                        .build()
        );
    }
}
