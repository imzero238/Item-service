package com.ecommerce.itemservice.domain.item.service;

import com.ecommerce.itemservice.kafka.dto.OrderItemDto;
import com.ecommerce.itemservice.kafka.dto.OrderItemStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * Redis Distributed Lock + Optimistic Lock 사용
 * Redis Distributed Lock을 획득한 요청만이 DB 접근 가능
 *
 * Distributed Lock에 lease time 설정
 * -> DB 락을 획득한 노드가 죽는 경우 발생할 수 있는 문제 해결 (활성 상태가 될 때까지 모든 요청 대기)
 * Optimistic Lock을 사용해 DB 반영 시 충돌 감지해 동시성 문제 해결
 * -> 분산락 lease time 보다 transaction 처리가 더 길면 동시성 문제 발생 (여러 요청이 Distributed Lock 주인으로 착각하고 쿼리 날리는 경우)
 */
@Service @Primary
@RequiredArgsConstructor
@Slf4j
public class StockUpdateByRedissonService implements StockUpdate {

    private final RedissonClient redissonClient;
    private final ItemService itemService;

    private final Long RLOCK_WAIT_TIME = 10000L;
    private final Long RLOCK_LEASE_TIME = 3000L;
    private final String REDISSON_ITEM_LOCK_PREFIX = "ITEM:";


    @Override
    @Transactional
    public OrderItemDto updateStock(OrderItemDto orderItemDto, String eventId) {
        RLock lock = redissonClient.getLock(generateKey(orderItemDto.getItemId()));
        try {
            boolean available = lock.tryLock(RLOCK_WAIT_TIME, RLOCK_LEASE_TIME, TimeUnit.MILLISECONDS);
            if(available) {
                log.info("Acquired the RLock -> Event Id: {}, Redisson Lock: {}", eventId, lock.getName());
                // Transaction Propagation.REQUIRES_NEW
                return itemService.updateStockByOptimisticLock(orderItemDto, eventId);
            }
            else {
                log.error("Failed to acquire RLock -> Event Id: {}, Redisson Lock: {}", eventId, lock.getName());
                orderItemDto.setOrderItemStatus(OrderItemStatus.FAILED);
                return orderItemDto;
            }
        } catch (InterruptedException e) {
            log.error(e.getMessage());
            orderItemDto.setOrderItemStatus(OrderItemStatus.FAILED);
        } finally {
            if(lock.isHeldByCurrentThread()) {
                log.info("Unlock -> Event Id: {}, Redisson Lock: {}", eventId, lock.getName());
                lock.unlock();
            }
        }
        return orderItemDto;
    }

    private String generateKey(Long key) {
        return REDISSON_ITEM_LOCK_PREFIX + key.toString();
    }
}