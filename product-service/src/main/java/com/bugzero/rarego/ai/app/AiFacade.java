package com.bugzero.rarego.ai.app;

import org.springframework.stereotype.Service;

import com.bugzero.rarego.ai.domain.dto.AiExternalPriceRequestDto;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class AiFacade {

	private final AiGetExternalPriceUseCase aiGetExternalPriceUseCase;

	public Flux<String> getExternalPrice(AiExternalPriceRequestDto dto) {
		return aiGetExternalPriceUseCase.execute(dto);
	}
}
