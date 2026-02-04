package bugzero.productservice.product.out;

import bugzero.productservice.product.domain.ProductSearchDocument;
import bugzero.productservice.shared.auction.type.AuctionStatus;
import bugzero.productservice.shared.product.type.Category;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductSearchDocument, String> {

    Optional<ProductSearchDocument> findByProductId(Long productId);

    Optional<ProductSearchDocument> findByAuctionId(Long auctionId);

    List<ProductSearchDocument> findByCategoryAndAuctionStatus(Category category, AuctionStatus auctionStatus);

    List<ProductSearchDocument> findByAuctionStatus(AuctionStatus auctionStatus);
}