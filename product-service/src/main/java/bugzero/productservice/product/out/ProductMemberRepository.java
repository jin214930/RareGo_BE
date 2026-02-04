package bugzero.productservice.product.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import bugzero.productservice.product.domain.ProductMember;

public interface ProductMemberRepository extends JpaRepository<ProductMember, Long> {
	Optional<ProductMember> findByPublicIdAndDeletedIsFalse(String publicId);
}
