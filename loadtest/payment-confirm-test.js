import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';

// ── 커스텀 메트릭 ──
const chargeDuration = new Trend('charge_request_duration');
const confirmDuration = new Trend('confirm_request_duration');
const confirmSuccess = new Counter('confirm_success');
const confirmBusinessReject = new Counter('confirm_business_reject');
const confirmServerError = new Counter('confirm_server_error');

export const options = {
    stages: [
        {duration: '5s', target: 30},
        {duration: '30s', target: 30},
        {duration: '5s', target: 0},
    ],
    thresholds: {
        'confirm_request_duration': ['p(95)<2000'],
        'confirm_server_error': ['count<1'],
        'checks': ['rate>0.95'],
    },
};

// ── 설정 ──
const BASE_URL = 'http://localhost:8082';
const JWT_SECRET = 'secret-key';
const CHARGE_AMOUNT = 10000; // 최소 결제 금액

// ── JWT 생성 ──
function toBase64Url(b64string) {
    return b64string.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function createJwt(publicId) {
    const header = JSON.stringify({alg: 'HS256', typ: 'JWT'});
    const payload = JSON.stringify({
        publicId: publicId,
        role: 'USER',
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600,
    });

    const encodedHeader = toBase64Url(encoding.b64encode(header));
    const encodedPayload = toBase64Url(encoding.b64encode(payload));
    const unsignedToken = `${encodedHeader}.${encodedPayload}`;
    const signatureBinary = crypto.hmac('sha256', JWT_SECRET, unsignedToken, 'binary');
    const encodedSignature = toBase64Url(encoding.b64encode(signatureBinary));

    return `${unsignedToken}.${encodedSignature}`;
}

export default function () {
    const vuId = __VU;
    const publicId = `tester_${vuId}`;
    const token = createJwt(publicId);

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
        },
    };

    // ── Step 1: 결제 요청 (Payment 레코드 생성, orderId 발급) ──
    const chargePayload = JSON.stringify({
        amount: CHARGE_AMOUNT,
    });

    const chargeRes = http.post(
        `${BASE_URL}/api/v1/payments/charges`,
        chargePayload,
        params
    );

    chargeDuration.add(chargeRes.timings.duration);

    // 결제 요청 실패 시 confirm 진행 불가
    if (chargeRes.status !== 200 && chargeRes.status !== 201) {
        console.log(`[CHARGE FAIL] VU:${vuId} Status: ${chargeRes.status}, Body: ${chargeRes.body}`);
        confirmBusinessReject.add(1);
        sleep(Math.random());
        return;
    }

    const chargeData = JSON.parse(chargeRes.body);
    const orderId = chargeData.data.orderId;

    // ── Step 2: 결제 승인 (Toss Mock → Wallet 잔액 증가) ──
    const confirmPayload = JSON.stringify({
        paymentKey: `PKEY_${publicId}_${Date.now()}`,
        orderId: orderId,
        amount: CHARGE_AMOUNT,
    });

    const confirmRes = http.post(
        `${BASE_URL}/api/v1/payments/charges/confirm`,
        confirmPayload,
        params
    );

    confirmDuration.add(confirmRes.timings.duration);

    if (confirmRes.status === 200 || confirmRes.status === 201) {
        confirmSuccess.add(1);
    } else if (confirmRes.status === 400 || confirmRes.status === 409) {
        confirmBusinessReject.add(1);
    } else if (confirmRes.status >= 500) {
        confirmServerError.add(1);
        console.log(`[SERVER ERROR] VU:${vuId} Status: ${confirmRes.status}, Body: ${confirmRes.body}`);
    }

    check(confirmRes, {
        'no server error': (r) => r.status < 500,
        'confirm success': (r) => r.status === 200 || r.status === 201,
    });

    sleep(Math.random());
}
