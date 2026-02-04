package com.bugzero.rarego.shared.auction.out;

import java.util.Optional;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.global.response.SuccessResponseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.bugzero.rarego.global.exception.InternalApiErrorHandler;


@Service
public class AuctionApiClient {
    private final RestClient restClient;
    private final InternalApiErrorHandler errorHandler;

    public AuctionApiClient(@Value("${custom.global.internalBackUrl}") String internalBackUrl, InternalApiErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        this.restClient = RestClient.builder()
                .baseUrl(internalBackUrl + "/api/v1/internal/auctions")
                .build();
    }

    /**
     * 진행 중인 입찰이 있는지 확인
     * 종료되지 않은 경매에 입찰이 있으면 true
     */
    public boolean hasActiveBids(String publicId) {
        SuccessResponseDto<Boolean> response = restClient.get()
                .uri("/members/{publicId}/bids/active", publicId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                    throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
                })
                .body(new ParameterizedTypeReference<>() {
                });

        return response != null && Boolean.TRUE.equals(response.data());
    }

    /**
     * 진행 중인 판매가 있는지 확인
     * 검수/경매가 완료되지 않은 상품이 있으면 true
     */
    public boolean hasActiveSales(String publicId) {
        SuccessResponseDto<Boolean> response = restClient.get()
                .uri("/members/{publicId}/sales/active", publicId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                    throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
                })
                .body(new ParameterizedTypeReference<>() {
                });

        return response != null && Boolean.TRUE.equals(response.data());
    }

    public boolean hasProcessingOrders(String publicId) {
        SuccessResponseDto<Boolean> response = restClient.get()
                .uri("/members/{publicId}/orders/processing", publicId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                    throw new CustomException(ErrorType.INTERNAL_SERVER_ERROR);
                })
                .body(new ParameterizedTypeReference<>() {
                });

        return response != null && Boolean.TRUE.equals(response.data());
    }

}
