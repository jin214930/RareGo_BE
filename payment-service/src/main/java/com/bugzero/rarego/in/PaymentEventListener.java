package com.bugzero.rarego.in;

import com.bugzero.rarego.app.PaymentFacade;
import com.bugzero.rarego.app.PaymentSettlementProcessor;
import com.bugzero.rarego.event.SettlementFinishedEvent;
import com.bugzero.rarego.shared.member.event.MemberJoinedEvent;
import com.bugzero.rarego.shared.member.event.MemberUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;
import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {
    private final PaymentFacade paymentFacade;
    private final PaymentSettlementProcessor paymentSettlementProcessor;

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)
    public void onMemberCreated(MemberJoinedEvent event) {
        paymentFacade.syncMember(event.memberDto());
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)
    public void onMemberUpdated(MemberUpdatedEvent event) {
        paymentFacade.syncMember(event.memberDto());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettlementFinished(SettlementFinishedEvent event) {
        try {
            paymentSettlementProcessor.processFees(1000);
        } catch (Exception e) {
            log.error("수수료 징수 중 에러 발생 (다음 배치에서 처리됨)", e);
        }
    }
}