package com.bugzero.rarego.shared.auction.out;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.SuccessResponseDto;

@Service
public class AuctionApiClient {
    private final RestClient restClient;

    public AuctionApiClient(@Value("${custom.global.internalBackUrl}") String internalBackUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(internalBackUrl + "/api/v1/internal/auctions")
                .build();
    }

    /**
     * 처리 중인 주문이 있는지 확인
     * PROCESSING 상태 주문이 있으면 true
     */
    public boolean hasProcessingOrders(String publicId) {
        SuccessResponseDto<Boolean> response = restClient.get()
                .uri("/members/{publicId}/orders/processing", publicId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                    throw new CustomException(com.bugzero.rarego.global.response.ErrorType.INTERNAL_SERVER_ERROR);
                })
                .body(new ParameterizedTypeReference<>() {
                });

        return response != null && Boolean.TRUE.equals(response.data());
    }
}
