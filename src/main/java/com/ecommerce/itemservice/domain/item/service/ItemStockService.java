package com.ecommerce.itemservice.domain.item.service;

import com.ecommerce.itemservice.exception.ExceptionCode;
import com.ecommerce.itemservice.exception.ItemException;
import com.ecommerce.itemservice.kafka.config.TopicConfig;
import com.ecommerce.itemservice.kafka.dto.OrderKafkaEvent;
import com.ecommerce.itemservice.kafka.dto.OrderItemKafkaEvent;
import com.ecommerce.itemservice.kafka.dto.OrderStatus;
import com.ecommerce.itemservice.kafka.service.producer.KafkaProducerService;
import com.ecommerce.itemservice.domain.item.Item;
import com.ecommerce.itemservice.domain.item.repository.ItemRepository;
import com.ecommerce.itemservice.domain.item.repository.OrderRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemStockService {

    private final ItemRepository itemRepository;
    private final KafkaProducerService kafkaProducerService;
    private final OrderRedisRepository orderRedisRepository;
    private final StockUpdateService stockUpdateService;

    @Transactional
    public void updateStock(OrderKafkaEvent orderKafkaEvent, boolean isStreamsOnly) {
        if (isFirstEvent(orderKafkaEvent)) {  // 최초 요청만 재고 변경 진행
            List<OrderItemKafkaEvent> result = orderKafkaEvent.getOrderItemKafkaEvents()
                    .stream()
                    .map(o -> {
                        o.convertSign();
                        return stockUpdateService.updateStock(o);
                    })
                    .toList();

            if (isAllSucceeded(result)) {
                orderKafkaEvent.updateOrderStatus(OrderStatus.SUCCEEDED);
            } else {
                List<OrderItemKafkaEvent> orderItemKafkaEvents = undo(result);
                orderKafkaEvent.updateOrderStatus(OrderStatus.FAILED);
                orderKafkaEvent.updateOrderItemDtos(orderItemKafkaEvents);
            }
            orderRedisRepository.setOrderStatus(orderKafkaEvent.getOrderEventId(), orderKafkaEvent.getOrderStatus());
            String topic = (isStreamsOnly) ? TopicConfig.ITEM_UPDATE_RESULT_STREAMS_ONLY_TOPIC : TopicConfig.ITEM_UPDATE_RESULT_TOPIC;
            kafkaProducerService.sendMessage(topic, orderKafkaEvent.getOrderEventId(), orderKafkaEvent);
        }
        else {
            updateOrderStatus(orderKafkaEvent);
            String topic = (isStreamsOnly) ? TopicConfig.ITEM_UPDATE_RESULT_STREAMS_ONLY_TOPIC : TopicConfig.ITEM_UPDATE_RESULT_TOPIC;
            kafkaProducerService.sendMessage(topic, orderKafkaEvent.getOrderEventId(), orderKafkaEvent);
        }
    }

    private boolean isFirstEvent(OrderKafkaEvent orderKafkaEvent) {
        String redisKey = getRedisKey(orderKafkaEvent);
        return orderRedisRepository.addEventId(redisKey, orderKafkaEvent.getOrderEventId()) == 1;
    }

    private String getRedisKey(OrderKafkaEvent orderKafkaEvent) {
        String[] keys;
        if(orderKafkaEvent.getCreatedAt() != null)
            keys = orderKafkaEvent.getCreatedAt().toString().split(":");
        else
            keys = orderKafkaEvent.getRequestedAt().toString().split(":");

        return keys[0]; // yyyy-mm-dd'T'HH
    }

    private boolean isAllSucceeded(List<OrderItemKafkaEvent> orderItemKafkaEvents) {
        return orderItemKafkaEvents.stream()
                .allMatch(o -> Objects.equals(OrderStatus.SUCCEEDED, o.getOrderStatus()));
    }

    private void updateOrderStatus(OrderKafkaEvent orderKafkaEvent) {
        String orderProcessingResult = orderRedisRepository.getOrderStatus(orderKafkaEvent.getOrderEventId());

        OrderStatus orderStatus;
        if(orderProcessingResult == null) orderStatus = OrderStatus.NOT_EXIST;
        else orderStatus = OrderStatus.getStatus(orderProcessingResult);

        orderKafkaEvent.updateOrderStatus(orderStatus);
        orderKafkaEvent.getOrderItemKafkaEvents()
                .forEach(orderItemDto -> orderItemDto.updateOrderStatus(orderStatus));
    }

    private List<OrderItemKafkaEvent> undo(List<OrderItemKafkaEvent> orderItemKafkaEvents) {
        orderItemKafkaEvents.stream()
                .filter(o -> Objects.equals(OrderStatus.SUCCEEDED, o.getOrderStatus()))
                .forEach(o -> {
                    o.convertSign();
                    stockUpdateService.updateStock(o);
                    o.updateOrderStatus(OrderStatus.CANCELED);
                });

        return orderItemKafkaEvents;
    }

    @Transactional
    public void updateStockWithPessimisticLock(Long itemId, Long quantity) {
        Item item = itemRepository.findByIdWithPessimisticLock(itemId)
                .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));

        item.updateStock(quantity);
    }
}
