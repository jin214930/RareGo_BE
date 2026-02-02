package com.bugzero.rarego.shared.auction.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.shared.auction.dto.AuctionOrderDto;
import com.bugzero.rarego.shared.auction.port.AuctionOrderPort;

@Component
public class AuctionOrderPortAdapter implements AuctionOrderPort {

    @Override
    public Optional<AuctionOrderDto> findByAuctionId(Long auctionId) {
        return Optional.empty();
    }

    @Override
    public Optional<AuctionOrderDto> findByAuctionIdForUpdate(Long auctionId) {
        return Optional.empty();
    }

    @Override
    public void completeOrder(Long auctionId) {
        throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
    }

    @Override
    public void failOrder(Long auctionId) {
        throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
    }

    @Override
    public AuctionOrderDto refundOrderWithLock(Long auctionId) {
        throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
    }

    @Override
    public Slice<AuctionOrderDto> findTimeoutOrders(LocalDateTime deadline, Pageable pageable) {
        return new SliceImpl<>(List.of(), pageable, false);
    }
}
