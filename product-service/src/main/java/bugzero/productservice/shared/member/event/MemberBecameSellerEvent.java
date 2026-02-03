package bugzero.productservice.shared.member.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MemberBecameSellerEvent {
	private final String publicId;
}
