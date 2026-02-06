package com.bugzero.rarego.ai.app;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.bugzero.rarego.ai.domain.dto.AiInternalPriceRequestDto;
import com.bugzero.rarego.ai.domain.dto.AiInternalPriceResponseDto;
import com.bugzero.rarego.ai.domain.type.TemporaryCondition;
import com.bugzero.rarego.product.domain.ProductSearchDocument;
import com.bugzero.rarego.shared.product.type.Category;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiGetInternalPriceUseCase {

	private final ElasticsearchOperations elasticsearchOperations;
	private final EmbeddingModel embeddingModel;
	//가져올 유사상품 최대갯수
	private static final int LIST_LIMIT = 3;
	private static final String TEMPLATE = "상품명: %s, 상세내용: %s";


	public List<AiInternalPriceResponseDto> findTopSimilarProducts(
		AiInternalPriceRequestDto dto) {
		String searchQuery = String.format(TEMPLATE,
			dto.name(), dto.description());

		NativeQuery nativeQuery = buildNativeQuery(searchQuery, dto.category(), dto.condition(), LIST_LIMIT);

		// 검색 실행 및 결과 매핑
		SearchHits<ProductSearchDocument> hits = elasticsearchOperations.search(nativeQuery,
			ProductSearchDocument.class);

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
					doc.getImageUrl(),
					hit.getScore() // ES에서 계산된 유사도 점수
				);
			})
			.collect(Collectors.toList());
	}

	private NativeQuery buildNativeQuery(String query, Category category, TemporaryCondition condition, int limit) {

		// 1. 사용자 입력 텍스트를 벡터로 변환 (AI 호출)
		float[] queryVector = embeddingModel.embed(query);

		List<Float> vectorList = new ArrayList<>();
		for (float f : queryVector) {
			vectorList.add(f);
		}

		// 2. ES 쿼리 조립 (Filter + KNN)
		// 카테고리와 상태가 일치하는 것들 중, 벡터 유사도가 높은 순으로 정렬

		return NativeQuery.builder()
			.withQuery(q -> q.bool(b -> b
				.filter(f -> f.term(t -> t.field("category").value(category.name())))
				// 필드명 변경 반영: productCondition
				.filter(f -> f.term(t -> t.field("productCondition").value(condition.name())))
				// finalPrice가 존재하는(낙찰된) 상품만
				.filter(f -> f.exists(e -> e.field("finalPrice")))
			))
			.withKnnSearches(KnnSearch.of(ks -> ks
				.field("embedding") // 필드명 변경 반영: embedding
				.queryVector(vectorList)
				.k(limit)
				.numCandidates(100)
				//이 점수보다 낮은 결과는 아예 가져오지 않음
				.similarity(0.7f)
			))
			.build();
	}
}
