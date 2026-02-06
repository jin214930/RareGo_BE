package com.bugzero.rarego.product.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import com.bugzero.rarego.product.domain.Product;
import com.bugzero.rarego.product.domain.ProductImage;
import com.bugzero.rarego.product.domain.ProductMember;
import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.product.out.ProductSearchRepository;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.ProductCondition;

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

	@BeforeEach
	void setUp() {
		given(embeddingModel.embed(anyString())).willReturn(DUMMY_VECTOR);
	}

	@Test
	@DisplayName("상품을 ES에 적재하면 SCHEDULED 상태로 저장된다")
	void save_shouldSaveProductWithScheduledStatus() {
		// given
		Long productId = 1L;
		Long auctionId = 100L;
		String productName = "다스베이더 레고";
		String description = "상태 아주 좋은 레고입니다.";
		int startPrice = 1000000;
		LocalDateTime startedAt = LocalDateTime.now().minusHours(1);

		Product mockProduct = createMockProduct(productId, productName, description, Category.스타워즈);
		ProductImage mockImage = createMockImage("http://image.url", 0);

		// when
		productSearchService.save(mockProduct, List.of(mockImage), auctionId, startPrice, startedAt);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		ProductSearchDocument savedDoc = captor.getValue();
		assertThat(savedDoc.getProductId()).isEqualTo(productId);
		assertThat(savedDoc.getProductName()).isEqualTo(productName);
		assertThat(savedDoc.getDescription()).isEqualTo(description);
		assertThat(savedDoc.getCategory()).isEqualTo(Category.스타워즈);
		assertThat(savedDoc.getAuctionStatus()).isEqualTo(AuctionStatus.SCHEDULED);
		assertThat(savedDoc.getImageUrl()).isEqualTo("http://image.url");
		assertThat(savedDoc.getAuctionId()).isEqualTo(auctionId);
		assertThat(savedDoc.getStartPrice()).isEqualTo(startPrice);
		assertThat(savedDoc.getFinalPrice()).isEqualTo(0);
		assertThat(savedDoc.getEmbedding()).hasSize(1536);
	}

	@Test
	@DisplayName("여러 이미지 중 sortOrder가 가장 낮은 이미지가 대표 이미지로 저장된다")
	void save_shouldSelectFirstImageBySortOrder() {
		// given
		Product mockProduct = createMockProduct(2L, "테스트 상품", "설명", Category.스타워즈);
		ProductImage image1 = createMockImage("http://second.jpg", 1);
		ProductImage image2 = createMockImage("http://first.jpg", 0);
		ProductImage image3 = createMockImage("http://third.jpg", 2);

		// when
		productSearchService.save(mockProduct, List.of(image1, image2, image3), 101L, 500000, LocalDateTime.now());

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		assertThat(captor.getValue().getImageUrl()).isEqualTo("http://first.jpg");
	}

	@Test
	@DisplayName("상품 삭제 시 ES에서 문서가 제거된다")
	void delete_shouldRemoveDocumentFromES() {
		// given
		Long productId = 3L;
		ProductSearchDocument existingDoc = ProductSearchDocument.builder()
			.id("3")
			.productId(productId)
			.build();

		given(searchRepository.findByProductId(productId)).willReturn(Optional.of(existingDoc));

		// when
		productSearchService.delete(productId);

		// then
		verify(searchRepository).delete(existingDoc);
	}

	@Test
	@DisplayName("낙찰 시 최종 가격과 ENDED 상태로 업데이트된다")
	void updateSoldPrice_shouldUpdateFinalPriceAndStatus() {
		// given
		Long productId = 4L;
		int finalPrice = 750000;

		ProductSearchDocument existingDoc = ProductSearchDocument.builder()
			.id("4")
			.productId(productId)
			.productName("테스트 상품")
			.description("설명")
			.category(Category.스타워즈)
			.auctionStatus(AuctionStatus.IN_PROGRESS)
			.startPrice(500000)
			.finalPrice(0)
			.build();

		given(searchRepository.findByProductId(productId)).willReturn(Optional.of(existingDoc));

		// when
		productSearchService.updateSoldPrice(productId, finalPrice);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		ProductSearchDocument updatedDoc = captor.getValue();
		assertThat(updatedDoc.getFinalPrice()).isEqualTo(finalPrice);
		assertThat(updatedDoc.getAuctionStatus()).isEqualTo(AuctionStatus.ENDED);
		assertThat(updatedDoc.getClosedAt()).isNotNull();
	}

	@Test
	@DisplayName("경매 상태를 IN_PROGRESS로 변경할 수 있다")
	void updateAuctionStatus_shouldUpdateStatus() {
		// given
		Long productId = 5L;

		ProductSearchDocument existingDoc = ProductSearchDocument.builder()
			.id("5")
			.productId(productId)
			.productName("테스트 상품")
			.auctionStatus(AuctionStatus.SCHEDULED)
			.build();

		given(searchRepository.findByProductId(productId)).willReturn(Optional.of(existingDoc));

		// when
		productSearchService.updateAuctionStatus(productId, AuctionStatus.IN_PROGRESS);

		// then
		ArgumentCaptor<ProductSearchDocument> captor = ArgumentCaptor.forClass(ProductSearchDocument.class);
		verify(searchRepository).save(captor.capture());

		assertThat(captor.getValue().getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);
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

		return mockProduct;
	}

	private ProductImage createMockImage(String url, int sortOrder) {
		ProductImage mockImage = mock(ProductImage.class);
		when(mockImage.getImageUrl()).thenReturn(url);
		when(mockImage.getSortOrder()).thenReturn(sortOrder);
		return mockImage;
	}
}
