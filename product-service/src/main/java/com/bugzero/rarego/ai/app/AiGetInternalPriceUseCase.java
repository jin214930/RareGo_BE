package com.bugzero.rarego.ai.app;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.ai.config.AiMetrics;
import com.bugzero.rarego.ai.domain.dto.AiInternalPriceRequestDto;
import com.bugzero.rarego.ai.domain.dto.AiInternalPriceResponseDto;
import com.bugzero.rarego.ai.domain.type.TemporaryCondition;
import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.shared.product.type.Category;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGetInternalPriceUseCase {

	private final ElasticsearchOperations elasticsearchOperations;
	private final EmbeddingModel embeddingModel;
	private final AiMetrics aiMetrics;
	//가져올 유사상품 최대갯수
	private static final int LIST_LIMIT = 3;

	public List<AiInternalPriceResponseDto> findTopSimilarProducts(
		AiInternalPriceRequestDto dto) {
		String searchQuery = String.format(ProductSearchDocument.EMBEDDING_TEMPLATE,
			dto.name(), dto.description(), dto.category(), dto.condition());

		// 임베딩 생성 실패 시 빈 리스트 반환 (AI 시세 추정은 벡터 필수)
		List<Float> vectorList;
		long embeddingStartedAt = System.nanoTime();
		try {
			vectorList = generateEmbeddingToFloat(searchQuery);
			aiMetrics.recordEmbeddingDuration("success", System.nanoTime() - embeddingStartedAt);
		} catch (Exception e) {
			aiMetrics.incrementEmbeddingFailure();
			aiMetrics.recordEmbeddingDuration("fail", System.nanoTime() - embeddingStartedAt);
			log.warn("임베딩 생성 실패로 AI 시세 추정 불가: {}", e.getMessage());
			return List.of();
		}

		NativeQuery nativeQuery = buildNativeQuery(vectorList, dto.category(), dto.condition());

		// 검색 실행 및 결과 매핑
		SearchHits<ProductSearchDocument> hits;
		long searchStartedAt = System.nanoTime();
		try {
			hits = elasticsearchOperations.search(nativeQuery, ProductSearchDocument.class);
			aiMetrics.recordVectorSearchDuration("success", System.nanoTime() - searchStartedAt);
		} catch (RuntimeException e) {
			aiMetrics.incrementVectorSearchFailure();
			aiMetrics.recordVectorSearchDuration("fail", System.nanoTime() - searchStartedAt);
			throw e;
		}

		return hits.getSearchHits().stream()
			.map(hit -> {
				ProductSearchDocument doc = hit.getContent();
				return new AiInternalPriceResponseDto(
					doc.getId(),
					doc.getProductId(),
					doc.getAuctionId(),
					doc.getProductName(),
					doc.getProductCondition(),
					doc.getCategory(),
					doc.getStartPrice(),
					doc.getFinalPrice(),
					doc.getStartedAt(),
					doc.getClosedAt(),
					doc.getImageUrls(),
					hit.getScore() // ES에서 계산된 유사도 점수
				);
			})
			.collect(Collectors.toList());
	}

	private NativeQuery buildNativeQuery(List<Float> vectorList, Category category, TemporaryCondition condition) {
		return NativeQuery.builder()
			.withKnnSearches(KnnSearch.of(ks -> ks
				.field("embedding")
				.queryVector(vectorList)
				.k(LIST_LIMIT)
				.numCandidates(100)
				.similarity(0.8f)
				.filter(f -> f.bool(b -> b
					.filter(ft -> ft.term(t -> t.field("category").value(category.name())))
					.filter(ft -> ft.term(t -> t.field("productCondition").value(condition.name())))
					.filter(ft -> ft.range(r -> r
						.untyped(u -> u
							.field("finalPrice")
							.gt(JsonData.of(0))
						)
					))
				))
			))
			.build();
	}

	private List<Float> generateEmbeddingToFloat(String text) {
		float[] embeddingArray = embeddingModel.embed(text);
		List<Float> floatList = new ArrayList<>();
		for (float v : embeddingArray) {
			floatList.add(v);
		}
		return floatList;
	}
}
