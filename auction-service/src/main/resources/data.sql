-- 1. 진행 중인 경매 (IN_PROGRESS) - Product ID 1번
INSERT INTO auction_auction (
    id,
    created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             1,
             NOW(), NOW(),
             1000, false, 7,
             DATE_ADD(NOW(), INTERVAL 7 DAY), 0,
             1, 1,
             1000, NOW(),
             'IN_PROGRESS', 100
         );

-- 2. 종료된 경매 (FINISHED) - Product ID 2번
INSERT INTO auction_auction (
    id,
    created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             2,
             DATE_SUB(NOW(), INTERVAL 7 DAY), NOW(),
             50000, false, 3,
             DATE_SUB(NOW(), INTERVAL 1 DAY), 5,
             2, 2,
             10000, DATE_SUB(NOW(), INTERVAL 7 DAY),
             'FINISHED', 1000
         );

-- 3. 시작 전인 경매 (PENDING) - Product ID 3번
INSERT INTO auction_auction (
    id,
    created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             3,
             NOW(), NOW(),
             500, false, 7,
             DATE_ADD(NOW(), INTERVAL 10 DAY), 0,
             3, 1,
             500, DATE_ADD(NOW(), INTERVAL 3 DAY),
             'PENDING', 50
         );