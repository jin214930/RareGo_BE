package com.bugzero.rarego.global.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class S3Utils {
	@Value("${aws.s3.bucket:rarego-auction-product-images}")
	private String bucketName;

	@Value("${aws.region:ap-northeast-2}")
	private String region;

	/**
	 * S3 경로를 완전한 Public URL로 변환
	 */
	public String getPublicUrl(String s3Path) {
		if (s3Path == null || s3Path.isBlank()) return null;
		if (s3Path.startsWith("http")) return s3Path;

		return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, s3Path);
	}
}
