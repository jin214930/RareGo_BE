package com.bugzero.rarego.product.app;

import static org.assertj.core.api.AssertionsForClassTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bugzero.rarego.product.domain.dto.PresignedUrlRequestDto;
import com.bugzero.rarego.product.domain.dto.PresignedUrlResponseDto;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class ProductImageS3UseCaseTest {

	@InjectMocks
	private ProductImageS3UseCase useCase;

	@Mock
	private S3Presigner s3Presigner;

	@Mock
	private S3Client s3Client;

	private final String BUCKET_NAME = "rarego-bucket";

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(useCase, "bucketName", BUCKET_NAME);
		ReflectionTestUtils.setField(useCase, "expirationMinutes", 5L);
	}

	@Test
	@DisplayName("성공: Presigned URL 발급 시 원본 파일명 대신 UUID 경로를 생성한다.")
	void createPresignedUrl_Success() throws Exception {
		// given
		String originalFileName = "lego_castle.png";
		PresignedUrlRequestDto requestDto = new PresignedUrlRequestDto(originalFileName, "image/png");
		String fakeUrl = "https://rarego-bucket.s3.amazonaws.com/temp/uuid-generated-name.png";

		PresignedPutObjectRequest mockResponse = mock(PresignedPutObjectRequest.class);
		given(mockResponse.url()).willReturn(new URL(fakeUrl));
		given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(mockResponse);

		// when
		PresignedUrlResponseDto result = useCase.createPresignedUrl(requestDto);

		// then
		assertThat(result.url()).isEqualTo(fakeUrl);
		assertThat(result.s3Path()).startsWith("temp/");
		assertThat(result.s3Path()).endsWith(".png"); // 확장자는 유지됨
		assertThat(result.s3Path()).doesNotContain(originalFileName); // [중요] 원본 파일명은 포함되지 않음

		verify(s3Presigner, times(1)).presignPutObject(any(PutObjectPresignRequest.class));
	}

	@Test
	@DisplayName("성공: 이미지 확정 시 temp에서 products로 복사 후 기존 파일을 삭제한다.")
	void confirmImages_Success() {
		// given
		List<String> tempPaths = List.of("temp/image1.png", "temp/image2.png");

		// when
		useCase.confirmImages(tempPaths);

		// then
		verify(s3Client, times(2)).copyObject(any(CopyObjectRequest.class));
		verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
	}

	@Test
	@DisplayName("예외: 이미지 확정 중 S3 에러가 발생해도 로그를 남기고 다음 파일 처리를 계속한다.")
	void confirmImages_HandleException() {
		// given
		List<String> tempPaths = List.of("temp/fail.png", "temp/success.png");

		// 첫 번째 호출에서만 예외 발생 설정
		given(s3Client.copyObject(any(CopyObjectRequest.class)))
			.willThrow(new RuntimeException("S3 Connection Fail")) // 첫 번째 파일 에러
			.willReturn(null); // 두 번째 파일은 정상 처리

		// when
		useCase.confirmImages(tempPaths);

		// then
		// 에러가 나더라도 전체 루프는 돌기 때문에 copyObject는 총 2번 호출되어야 함
		verify(s3Client, times(2)).copyObject(any(CopyObjectRequest.class));
		// 에러가 발생한 첫 번째 파일은 삭제 로직이 호출되지 않으므로 delete는 1번만 호출됨
		verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
	}

	@Test
	@DisplayName("성공: 이미지 일괄 삭제 요청 시 S3 SDK를 정상 호출한다.")
	void deleteS3Image_Success() {
		// given
		List<String> s3Paths = List.of("products/img1.png", "products/img2.png");

		// when
		useCase.deleteS3Image(s3Paths);

		// then
		verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
	}

	@Test
	@DisplayName("예외: 이미지 삭제 중 예외 발생 시 로그를 남기고 종료한다.")
	void deleteS3Image_HandleException() {
		// given
		List<String> s3Paths = List.of("products/img1.png");
		given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
			.willThrow(new RuntimeException("Delete Error"));

		// when & then: 내부에서 catch하므로 예외가 밖으로 던져지지는 않음
		assertDoesNotThrow(() -> useCase.deleteS3Image(s3Paths));
		verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
	}
}
