-- 1. 진행 중인 경매 (IN_PROGRESS)
INSERT INTO auction_auction (
    id, created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             1, NOW(), NOW(),
             1000, false, 7,
             TIMESTAMPADD(DAY, 7, NOW()), 0,
             1, 1,
             1000, NOW(),
             'IN_PROGRESS', 100
         );

-- 2. 종료된 경매 (FINISHED)
INSERT INTO auction_auction (
    id, created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             2, TIMESTAMPADD(DAY, -7, NOW()), NOW(),
             50000, false, 3,
             TIMESTAMPADD(DAY, -1, NOW()), 5,
             2, 2,
             10000, TIMESTAMPADD(DAY, -7, NOW()),
             'ENDED', 1000
         );

-- 3. 시작 전인 경매 (PENDING)
INSERT INTO auction_auction (
    id, created_at, updated_at,
    current_price, deleted, duration_days,
    end_time, extension_count,
    product_id, seller_id,
    start_price, start_time,
    status, tick_size
) VALUES (
             3, NOW(), NOW(),
             500, false, 7,
             TIMESTAMPADD(DAY, 10, NOW()), 0,
             3, 1,
             500, TIMESTAMPADD(DAY, 3, NOW()),
             'SCHEDULED', 50
         );

ALTER TABLE auction_auction ALTER COLUMN id RESTART WITH 4;