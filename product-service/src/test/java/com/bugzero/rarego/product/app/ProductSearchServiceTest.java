package com.bugzero.rarego.product.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.embedding.EmbeddingModel;

import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductImage;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.product.out.ProductSearchRepository;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.dto.AuctionInfoResponseDto;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;
import com.bugzero.rarego.shared.product.type.ProductCondition;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductSearchServiceTest {

	@Mock
	private ProductSearchRepository searchRepository;
	@Mock
	private ElasticsearchClient elasticsearchClient;
	@Mock
	private EmbeddingModel embeddingModel;

	@InjectMocks
	private ProductSearchService productSearchService;

	private static final float[] DUMMY_VECTOR = new float[1536];
	private final Long PRODUCT_ID = 1L;
	private final Long AUCTION_ID = 100L;

	@BeforeEach
	void setUp() {
		given(embeddingModel.embed(anyString())).willReturn(DUMMY_VECTOR);
	}

	@Test
	@DisplayName("성공: 검수 전 저장 시 임베딩 없이 SCHEDULED 상태로 저장된다")
	void saveBeforeInspection_Success() {
		// given
		Product mockProduct = createMockProduct(PRODUCT_ID, "레고", "설명", Category.TECHNIC);
		ProductImage mockImage = createMockImage("http://image.jpg", 0);

		// when
		productSearchService.saveBeforeInspection(mockProduct, List.of(mockImage), 10000, 7);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		ProductSearchDocument savedDoc = captor.getValue();
		assertThat(savedDoc.getId()).isEqualTo(String.valueOf(PRODUCT_ID));
		assertThat(savedDoc.getEmbedding()).isNull();
		assertThat(savedDoc.getAuctionStatus()).isEqualTo(AuctionStatus.SCHEDULED);
		assertThat(savedDoc.getInspectionStatus()).isEqualTo(InspectionStatus.PENDING);
	}

	@Test
	@DisplayName("성공: 검수 승인 후 데이터 적재 시 복합 ID와 임베딩이 포함된다")
	void save_Success_AfterInspection() {
		// given
		Product mockProduct = createMockProduct(PRODUCT_ID, "승인된 레고", "설명", Category.TECHNIC);
		ProductImage mockImage = createMockImage("http://image.jpg", 0);
		AuctionInfoResponseDto auctionInfo = new AuctionInfoResponseDto(
			PRODUCT_ID, AUCTION_ID, 500000, 0, AuctionStatus.SCHEDULED, LocalDateTime.now(), null
		);

		// when
		productSearchService.save(mockProduct, List.of(mockImage), auctionInfo);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		ProductSearchDocument savedDoc = captor.getValue();
		assertThat(savedDoc.getId()).isEqualTo(PRODUCT_ID + "_" + AUCTION_ID);
		assertThat(savedDoc.getEmbedding()).isNotNull();
		assertThat(savedDoc.getAuctionStatus()).isEqualTo(AuctionStatus.SCHEDULED);
	}

	@Test
	@DisplayName("성공: 검수 전 데이터 수정 시 문서 존재 여부를 확인하고 덮어쓴다")
	void updatedBeforeInspection_Success() {
		// given
		Product mockProduct = createMockProduct(PRODUCT_ID, "수정 레고", "수정 설명", Category.TECHNIC);
		String docId = String.valueOf(PRODUCT_ID);
		given(searchRepository.existsById(docId)).willReturn(true);

		// when
		productSearchService.updatedBeforeInspection(mockProduct, List.of(), 20000, 5);

		// then
		verify(searchRepository).existsById(docId);
		verify(searchRepository).save(any(ProductSearchDocument.class));
	}

	@Test
	@DisplayName("성공: 벌크 저장 시 경매 상태가 null인 항목은 제외하고 인덱싱한다")
	void saveAll_shouldSkipNullAuctionStatus() {
		// given
		Product p1 = createMockProduct(21L, "상품1", "설명1", Category.STARWARS);
		Product p2 = createMockProduct(22L, "상품2", "설명2", Category.STARWARS);

		AuctionInfoResponseDto invalidInfo = new AuctionInfoResponseDto(
			21L, 201L, 10000, 0, null, LocalDateTime.now(), null); // 상태 null
		AuctionInfoResponseDto validInfo = new AuctionInfoResponseDto(
			22L, 202L, 20000, 0, AuctionStatus.SCHEDULED, LocalDateTime.now(), null);

		// when
		productSearchService.saveAll(List.of(p1, p2), Map.of(21L, invalidInfo, 22L, validInfo));

		// then
		ArgumentCaptor<List<ProductSearchDocument>> captor = ArgumentCaptor.forClass(List.class);
		verify(searchRepository).saveAll(captor.capture());

		List<ProductSearchDocument> docs = captor.getValue();
		assertThat(docs).hasSize(1);
		assertThat(docs.get(0).getProductId()).isEqualTo(22L);
	}

	@Test
	@DisplayName("성공: 임베딩 생성 실패 시 에러를 던지지 않고 embedding 필드만 null로 저장한다")
	void save_shouldHandleEmbeddingErrorGracefully() {
		// given
		given(embeddingModel.embed(anyString())).willThrow(new RuntimeException("OpenAI Timeout"));
		Product mockProduct = createMockProduct(PRODUCT_ID, "에러 테스트", "설명", Category.TECHNIC);
		AuctionInfoResponseDto auctionInfo = new AuctionInfoResponseDto(
			PRODUCT_ID, AUCTION_ID, 10000, 0, AuctionStatus.SCHEDULED, LocalDateTime.now(), null
		);

		// when & then
		assertThatCode(() -> productSearchService.save(mockProduct, List.of(), auctionInfo))
			.doesNotThrowAnyException();

		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());
		assertThat(captor.getValue().getEmbedding()).isNull();
	}

	@Test
	@DisplayName("성공: 낙찰 시 복합 ID로 조회하여 ENDED 상태와 최종가를 업데이트한다")
	void updateSoldPrice_Success() {
		// given
		String docId = PRODUCT_ID + "_" + AUCTION_ID;
		ProductSearchDocument existingDoc = ProductSearchDocument.builder()
			.id(docId).productId(PRODUCT_ID).auctionId(AUCTION_ID).build();

		given(searchRepository.findById(docId)).willReturn(Optional.of(existingDoc));

		// when
		productSearchService.updateSoldPrice(PRODUCT_ID, AUCTION_ID, 777000);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		ProductSearchDocument updated = captor.getValue();
		assertThat(updated.getAuctionStatus()).isEqualTo(AuctionStatus.ENDED);
		assertThat(updated.getFinalPrice()).isEqualTo(777000);
		assertThat(updated.getClosedAt()).isNotNull();
	}

	@Test
	@DisplayName("성공: 검수 반려 시 상태를 REJECTED로 변경하고 종료 시간을 기록한다")
	void rejectedInspection_Success() {
		// given
		String docId = String.valueOf(PRODUCT_ID);
		ProductSearchDocument existingDoc = ProductSearchDocument.builder()
			.id(docId).productId(PRODUCT_ID).build();

		given(searchRepository.findById(docId)).willReturn(Optional.of(existingDoc));

		// when
		productSearchService.rejectedInspection(PRODUCT_ID);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		assertThat(captor.getValue().getInspectionStatus()).isEqualTo(InspectionStatus.REJECTED);
		assertThat(captor.getValue().getClosedAt()).isNotNull();
	}

	// === Helper Methods ===

	private Product createMockProduct(Long id, String name, String description, Category category) {
		Product mockProduct = mock(Product.class);
		ProductMember mockSeller = mock(ProductMember.class);

		when(mockProduct.getId()).thenReturn(id);
		when(mockProduct.getName()).thenReturn(name);
		when(mockProduct.getDescription()).thenReturn(description);
		when(mockProduct.getCategory()).thenReturn(category);
		when(mockProduct.getProductCondition()).thenReturn(ProductCondition.MISB);
		when(mockProduct.getSeller()).thenReturn(mockSeller);
		when(mockSeller.getId()).thenReturn(999L);
		when(mockProduct.getImages()).thenReturn(new ArrayList<>());

		return mockProduct;
	}

	private ProductImage createMockImage(String url, int sortOrder) {
		ProductImage mockImage = mock(ProductImage.class);
		when(mockImage.getImageUrl()).thenReturn(url);
		when(mockImage.getSortOrder()).thenReturn(sortOrder);
		return mockImage;
	}
}
