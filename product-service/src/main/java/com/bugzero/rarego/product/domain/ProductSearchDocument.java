package com.bugzero.rarego.product.domain;

import bugzero.productservice.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.type.Category;
import com.bugzero.rarego.shared.product.type.ProductCondition;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Document(indexName = "product_search")
@Setting(settingPath = "elasticsearch-settings.json")
public class ProductSearchDocument {

    @Id
    private String id; // {productId}_{auctionId} 형태로 구성

    @Field(type = FieldType.Long)
    private Long productId; // 원본 참조

    @Field(type = FieldType.Long)
    private Long auctionId; // 원본 참조 (미등록 시 null)

    @Field(type = FieldType.Text, analyzer = "nori")
    private String productName;

    @Field(type = FieldType.Keyword)
    private ProductCondition productCondition;

    @Field(type = FieldType.Dense_Vector, dims = 1536)
    private float[] descriptionVector; // 유사도 검색

    @Field(type = FieldType.Keyword)
    private Category category;

    @Field(type = FieldType.Keyword)
    private AuctionStatus auctionStatus;

    @Field(type = FieldType.Long)
    private Long startPrice;

    @Field(type = FieldType.Long)
    private Long finalPrice;

    @Field(type = FieldType.Date)
    private LocalDateTime startedAt;

    @Field(type = FieldType.Date)
    private LocalDateTime closedAt;
}