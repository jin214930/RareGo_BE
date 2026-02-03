package bugzero.productservice.product.out;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import bugzero.productservice.product.domain.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
	List<ProductImage> findAllByProductId(Long productId);

	List<ProductImage> findAllByProductIdIn(Set<Long> productIds);
}
