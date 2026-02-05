package com.bugzero.rarego.out;

import java.util.Optional;

import com.bugzero.rarego.domain.AuctionMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionMemberRepository extends JpaRepository<AuctionMember, Long> {
    Optional<AuctionMember> findByPublicId(String publicId);
}