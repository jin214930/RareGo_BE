package bugzero.productservice.product.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import bugzero.productservice.product.domain.Inspection;
import bugzero.productservice.shared.product.type.InspectionStatus;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {
	Optional<Inspection> findByProductId(Long id);

	// 검수 승인된 상품 ID 목록 조회
	@Query("SELECT i.product.id FROM Inspection i WHERE i.inspectionStatus = :status")
	List<Long> findProductIdsByInspectionStatus(InspectionStatus status);
}
