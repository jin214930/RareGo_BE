package com.bugzero.rarego.product.in;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.product.app.ProductSearchService;
import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.product.out.ProductRepository;
import com.bugzero.rarego.shared.auction.out.AuctionApiClient;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchDataInitializer implements ApplicationRunner {
	private final ProductRepository productRepository;
	private final AuctionApiClient auctionApiClient;
	private final ProductSearchService productSearchService;
	private final ElasticsearchOperations elasticsearchOperations;

	@Override
	@Transactional(readOnly = true)
	public void run(ApplicationArguments args) {
		// 1. ES에 데이터가 있는지 확인 (중복 적재 방지)
		long count = elasticsearchOperations.count(Query.findAll(), ProductSearchDocument.class);

		if (count > 0) {
			log.info("ES에 데이터가 존재합니다. 초기화를 건너뜁니다.");
			return;
		}

		log.info("ES 인덱스가 비어 있습니다. DB 기반 초기 동기화를 시작합니다.");

		// 2. 검수 승인(APPROVED) 상태인 상품만 전체 조회
		List<Product> approvedProducts = productRepository.findAll().stream()
			.filter(Product::isApproved)
			.toList();

		if (approvedProducts.isEmpty()) {
			log.info("동기화할 승인된 상품이 없습니다.");
			return;
		}

		// 3. 경매 정보 일괄 조회 (Bulk Read)
		List<Long> productIds = approvedProducts.stream().map(Product::getId).toList();
		List<AuctionInfoResponseDto> auctionInfos = auctionApiClient.getAuctionInfos(productIds);

		Map<Long, AuctionInfoResponseDto> auctionMap = auctionInfos.stream()
			.collect(Collectors.toMap(AuctionInfoResponseDto::productId, Function.identity()));

		// 4. [핵심] Service에 넘겨서 Bulk Save 위임
		// Initializer는 문서를 어떻게 만드는지, 임베딩을 어떻게 하는지 몰라도 됨
		productSearchService.saveAll(approvedProducts, auctionMap);

		log.info("ES 초기화 로직 종료.");
	}
}
