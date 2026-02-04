package com.bugzero.rarego.product.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import com.bugzero.rarego.product.domain.Inspection;
import com.bugzero.rarego.product.domain.dto.ProductInspectionResponseDto;
import com.bugzero.rarego.product.out.InspectionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductReadInspectionUseCase {
	private final InspectionRepository inspectionRepository;

	@Transactional(readOnly = true)
	public ProductInspectionResponseDto readInspection(Long productId) {
		Inspection inspection = inspectionRepository.findByProductId(productId)
			.orElseThrow(() -> new CustomException(ErrorType.INSPECTION_NOT_FOUND));

		return ProductInspectionResponseDto.builder()
			.inspectionId(inspection.getId())
			.productId(productId)
			.newStatus(inspection.getInspectionStatus())
			.productCondition(inspection.getProductCondition())
			.reason(inspection.getReason())
			.createdAt(inspection.getCreatedAt())
			.updatedAt(inspection.getUpdatedAt())
			.build();
	}
}
