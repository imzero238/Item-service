package com.ecommerce.itemservice.domain.item.service;

import com.ecommerce.itemservice.domain.item.dto.ItemRegisterRequest;
import com.ecommerce.itemservice.exception.ExceptionCode;
import com.ecommerce.itemservice.exception.ItemException;
import com.ecommerce.itemservice.exception.OrderException;
import com.ecommerce.itemservice.exception.StockException;
import com.ecommerce.itemservice.kafka.dto.OrderItemKafkaEvent;
import com.ecommerce.itemservice.kafka.dto.OrderStatus;
import com.ecommerce.itemservice.domain.item.dto.ItemDto;
import com.ecommerce.itemservice.domain.item.Item;
import com.ecommerce.itemservice.domain.item.repository.ItemRedisRepository;
import com.ecommerce.itemservice.domain.item.repository.ItemRepository;
import com.ecommerce.itemservice.domain.item.repository.OrderRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemRedisRepository itemRedisRepository;
    private final OrderRedisRepository orderRedisRepository;

    @Transactional
    public ItemDto create(ItemRegisterRequest request) {
        Item item = Item.of(request);
        item = itemRepository.save(item);
        itemRedisRepository.initializeItemStock(item.getId(), item.getStock());
        return ItemDto.of(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderItemKafkaEvent updateStockByOptimisticLock(OrderItemKafkaEvent orderItemKafkaEvent) {
        try {
            Item item = itemRepository.findByIdWithOptimisticLock(orderItemKafkaEvent.getItemId())
                    .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));
            item.updateStock(orderItemKafkaEvent.getQuantity());
            orderItemKafkaEvent.updateOrderStatus(OrderStatus.SUCCEEDED);
            itemRepository.save(item);
        } catch (ItemException e) {
            log.error(String.valueOf(e.getExceptionCode()));
            orderItemKafkaEvent.updateOrderStatus(OrderStatus.FAILED);
        } catch (StockException e) {
            log.error(String.valueOf(e.getExceptionCode()));
            orderItemKafkaEvent.updateOrderStatus(OrderStatus.OUT_OF_STOCK);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.error(e.getMessage() + " -> ItemId: {}", orderItemKafkaEvent.getItemId());
            orderItemKafkaEvent.updateOrderStatus(OrderStatus.FAILED);
        } catch (Exception e) {
            log.error(e.getMessage());
            orderItemKafkaEvent.updateOrderStatus(OrderStatus.FAILED);
        }
        return orderItemKafkaEvent;
    }

    public OrderStatus findOrderProcessingStatus(String orderEventKey) {
        String orderProcessingStatus = orderRedisRepository.getOrderStatus(orderEventKey);
        if (orderProcessingStatus != null)
            return OrderStatus.getStatus(orderProcessingStatus);
        else
            throw new OrderException(ExceptionCode.NOT_FOUND_ORDER_DETAILS);
    }
}