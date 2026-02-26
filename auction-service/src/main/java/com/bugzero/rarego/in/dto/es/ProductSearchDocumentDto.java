package com.bugzero.rarego.in.dto.es;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSearchDocumentDto(

	String id,

	@JsonProperty("productId")
	Long productId,

	@JsonProperty("productName")
	String productName,

	@JsonProperty("description")
	String description,

	@JsonProperty("imageUrls")
	List<String> imageUrls,

	@JsonProperty("startPrice")
	int startPrice,

	@JsonProperty("sellerId")
	Long sellerId,

	@JsonProperty("category")
	String category,

	@JsonProperty("inspectionStatus")
	String inspectionStatus

) {

}
