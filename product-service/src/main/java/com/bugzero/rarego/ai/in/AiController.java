package com.bugzero.rarego.ai.in;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bugzero.rarego.ai.app.AiFacade;
import com.bugzero.rarego.ai.domain.dto.AiExternalPriceRequestDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/recommendations")
public class AiController {

	private final AiFacade aiFacade;

	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "외부시세 기반 AI 시작가 추천", description = "AI 모델을 사용하여 유사 상품이 외부에서 가격이 어느정도에 형성되어있는지 알려줍니다.")
	@PreAuthorize("hasRole('SELLER')")
	@PostMapping(value = "/external-price", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> getExternalPriceGuide(@RequestBody AiExternalPriceRequestDto dto) {
		return aiFacade.getExternalPrice(dto);
	}
}
