package com.bugzero.rarego.product.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import com.bugzero.rarego.product.domain.document.ProductSearchDocument;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.InspectionStatus;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductSearchDocument, String> {

	Optional<ProductSearchDocument> findByProductId(Long productId);

}
