package com.bugzero.rarego.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.bugzero.rarego.app.AuctionFacade;
import com.bugzero.rarego.shared.product.event.AuctionCreateEvent;
import com.bugzero.rarego.shared.product.event.AuctionDeleteEvent;
import com.bugzero.rarego.shared.product.event.AuctionUpdateEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
@KafkaListener(topics = "auction-info-management", groupId = "auction-service-group", containerFactory = "kafkaListenerContainerFactory")
public class AuctionProductEventListener {

	private final AuctionFacade auctionFacade;

	@KafkaHandler
	public void onAuctionEvent(@Payload AuctionCreateEvent event, @Header("messageId") String messageId) {
		log.info("카프카 이벤트 수신: Type={}, ProductId={}, messageId={}",
			event.getClass().getName(), event.productId(), messageId);

		auctionFacade.createAuction(event.productId(), event.publicId(), event.dto());
	}

	@KafkaHandler
	public void onAuctionEvent(@Payload AuctionUpdateEvent event, @Header("messageId") String messageId) {
		log.info("카프카 이벤트 수신: Type={}, ProductId={}, messageId={}",
			event.getClass().getName(), event.productId(), messageId);

		auctionFacade.updateAuction(event.publicId(), event.dto());
	}

	@KafkaHandler
	public void onAuctionEvent(@Payload AuctionDeleteEvent event, @Header("messageId") String messageId) {
		log.info("카프카 이벤트 수신: Type={}, ProductId={}, messageId={}",
			event.getClass().getName(), event.productId(), messageId);

		auctionFacade.deleteAuction(event.publicId(), event.productId());
	}
}
