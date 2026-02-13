package com.bugzero.rarego.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bugzero.rarego.domain.AuctionBookmark;

public interface AuctionBookmarkRepository extends JpaRepository<AuctionBookmark, Long> {
	boolean existsByAuctionIdAndMemberId(Long auctionId, Long memberId);

	Optional<AuctionBookmark> findByAuctionIdAndMemberId(Long auctionId, Long memberId);

	Page<AuctionBookmark> findAllByMemberId(Long memberId, Pageable pageable);

	@Query("SELECT ab.memberId FROM AuctionBookmark ab WHERE ab.auctionId = :auctionId")
	List<Long> findMemberIdsByAuctionId(@Param("auctionId") Long auctionId);
}
