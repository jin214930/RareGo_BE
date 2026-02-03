package bugzero.productservice.product.in;

import static org.springframework.transaction.annotation.Propagation.*;
import static org.springframework.transaction.event.TransactionPhase.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import bugzero.productservice.product.app.ProductFacade;
import bugzero.productservice.shared.member.event.MemberJoinedEvent;
import bugzero.productservice.shared.member.event.MemberUpdatedEvent;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProductEventListener {
	private final ProductFacade productFacade;

	@TransactionalEventListener(phase = AFTER_COMMIT)
	@Transactional(propagation = REQUIRES_NEW)
	public void onMemberCreated(MemberJoinedEvent event) {
		productFacade.syncMember(event.memberDto());
	}

	@TransactionalEventListener(phase = AFTER_COMMIT)
	@Transactional(propagation = REQUIRES_NEW)
	public void onMemberUpdated(MemberUpdatedEvent event) {
		productFacade.syncMember(event.memberDto());
	}
}
