package com.bugzero.rarego.in.dto;

<<<<<<<< HEAD:auction-service/src/main/java/com/bugzero/rarego/in/dto/AuctionSearchCondition.java
========
import com.bugzero.rarego.domain.AuctionStatus;
>>>>>>>> a0f7c824e9a4a956c315d473f7b5543dafc00080:src/main/java/com/bugzero/rarego/in/dto/AuctionSearchCondition.java
import com.bugzero.rarego.shared.auction.dto.AuctionSortType;
import com.bugzero.rarego.shared.auction.type.AuctionStatus;
import com.bugzero.rarego.shared.product.type.Category;

import lombok.Data;
import java.util.List;

@Data
public class AuctionSearchCondition {
	private List<Long> ids;          // 특정 ID 목록 조회 (찜 목록 등)
	private String keyword;          // 검색어 (상품명)
	private Category category;       // 카테고리
	private AuctionStatus status;    // 상태 (IN_PROGRESS 등)
	private AuctionSortType sort;    // 정렬 (CLOSING_SOON, NEWEST 등)
}