import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';
import {scenario} from 'k6/execution';

// 커스텀 메트릭
const bidDuration = new Trend('bid_request_duration');
const bidSuccess = new Counter('bid_success');
const bidBusinessReject = new Counter('bid_business_reject');
const bidServerError = new Counter('bid_server_error');

export const options = {
    stages: [
        {duration: '5s', target: 50},
        {duration: '30s', target: 50},
        {duration: '5s', target: 0},
    ],
    thresholds: {
        'bid_request_duration': ['p(95)<1000'],
        'bid_server_error': ['count<1'],
        'checks': ['rate>0.95'],
    },
};

const BASE_URL = 'http://localhost:8083';
const AUCTION_ID = 9999;
const TICK_SIZE = 100;
const JWT_SECRET = 'secret-key';

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
    // VU 번호(__VU)를 유저 ID로 사용하여 VU당 고정 유저 할당
    // __VU는 1~50 범위이므로, 사전에 tester_1 ~ tester_50만 등록하면 됨
    const vuId = __VU;
    const publicId = `tester_${vuId}`;
    const token = createJwt(publicId);

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
        },
    };

    const globalIter = scenario.iterationInTest;
    const bidAmount = 1000 + (globalIter * TICK_SIZE);

    const payload = JSON.stringify({
        bidAmount: bidAmount,
        auctionId: AUCTION_ID,
    });

    // [수정] 백틱(`) → 괄호()로 변경. 기존 코드는 Tagged Template Literal로 해석되어 실행 오류 발생
    const res = http.post(
        `${BASE_URL}/api/v1/auctions/${AUCTION_ID}/bids`,
        payload,
        params
    );

    bidDuration.add(res.timings.duration);

    // statud → status 오타 수정. 기존 코드는 201 응답을 성공으로 카운트하지 못함
    if (res.status === 200 || res.status === 201) {
        bidSuccess.add(1);
    } else if (res.status === 400 || res.status === 409) {
        bidBusinessReject.add(1);
    } else if (res.status >= 500) {
        bidServerError.add(1);
        // [수정] 백틱(`) → 괄호()로 변경
        console.log(`[SERVER ERROR] Status: ${res.status}, Body: ${res.body}`);
    }

    check(res, {
        'no server error': (r) => r.status < 500,
        'valid response': (r) => r.status === 200 || r.status === 201 || r.status === 400 || r.status === 409,
    });

    sleep(Math.random());
}
