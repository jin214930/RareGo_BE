package bugzero.productservice.product.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bugzero.productservice.global.response.SuccessResponseDto;
import bugzero.productservice.global.response.SuccessType;
import bugzero.productservice.product.app.ProductImageS3UseCase;
import bugzero.productservice.product.domain.dto.PresignedUrlRequestDto;
import bugzero.productservice.product.domain.dto.PresignedUrlResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/products/images")
@RequiredArgsConstructor
@Tag(name = "Product Image", description = "상품 이미지 관련 API")
public class ProductImageController {

	private final ProductImageS3UseCase s3PresignerUrlUseCase;

	@Operation(summary = "Presigned URL 발급", description = "S3 이미지 업로드용 Presigned URL을 발급합니다")
	@PostMapping("/presigned-url")
	public SuccessResponseDto<PresignedUrlResponseDto> getPresignedUrl(
		@Valid @RequestBody PresignedUrlRequestDto presignedUrlRequestDto
	) {
		return SuccessResponseDto.from(SuccessType.CREATED,
			s3PresignerUrlUseCase.createPresignerUrl(presignedUrlRequestDto));
	}

}
