import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter, Gauge, Trend} from 'k6/metrics';

// 커스텀 메트릭
const sseConnectSuccess = new Counter('sse_connect_success');
const sseConnectFail = new Counter('sse_connect_fail');
const sseConnectDuration = new Trend('sse_connect_duration');
const sseEventsReceived = new Counter('sse_events_received');
const activeSubscribers = new Gauge('active_subscribers');

export const options = {
    scenarios: {
        sse_connect: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                {duration: '5s', target: 50},
                {duration: '30s', target: 50},
                {duration: '5s', target: 0},
            ],
            gracefulStop: '5s',
        },
    },
    thresholds: {
        'checks': ['rate>0.90'],
        'sse_connect_fail': ['count<10'],
    },
};

const BASE_URL = 'http://localhost:8083';
const AUCTION_ID = 9999;

export default function () {
    // SSE 구독 요청
    // k6의 http.get은 응답이 완료될 때까지 대기함
    // SSE는 서버가 연결을 유지하므로 timeout을 짧게 설정하여 connect 이벤트만 받고 끊기게 함
    const res = http.get(
        `${BASE_URL}/api/v1/auctions/${AUCTION_ID}/subscribe`,
        {
            headers: {
                'Accept': 'text/event-stream',
                'Cache-Control': 'no-cache',
            },
            timeout: '5s',
        }
    );

    sseConnectDuration.add(res.timings.duration);

    // SSE는 서버가 연결을 유지하므로 k6 timeout으로 끊김 → status=0이 정상
    // 실제 연결 성공 여부는 body에 connect 이벤트가 있는지로 판단
    const isConnected = check(res, {
        'SSE received connect event': (r) => r.body && r.body.includes('event:connect'),
        'SSE has auction data': (r) => r.body && r.body.includes('auctionId'),
    });

    if (isConnected) {
        sseConnectSuccess.add(1);

        // 수신된 이벤트 수 카운트
        const lines = res.body.split('\n');
        let eventCount = 0;
        for (const line of lines) {
            if (line.startsWith('event:')) {
                eventCount++;
            }
        }
        sseEventsReceived.add(eventCount);
    } else {
        sseConnectFail.add(1);
        if (__ITER === 0) {
            console.log(`[DEBUG] VU ${__VU} SSE failed - status: ${res.status}`);
            console.log(`[DEBUG] Body: ${res.body ? res.body.substring(0, 300) : 'empty'}`);
        }
    }

    // VU 1번만 구독자 수 모니터링
    if (__VU === 1) {
        const countRes = http.get(`${BASE_URL}/api/v1/auctions/${AUCTION_ID}/subscribers/count`);
        if (countRes.status === 200) {
            const count = parseInt(countRes.body);
            activeSubscribers.add(count);
            if (__ITER % 5 === 0) {
                console.log(`[MONITOR] Auction ${AUCTION_ID} subscribers: ${count}`);
            }
        }
    }

    sleep(1);
}

// 테스트 종료 후 최종 구독자 수 확인
export function teardown() {
    const countRes = http.get(`${BASE_URL}/api/v1/auctions/${AUCTION_ID}/subscribers/count`);
    const totalRes = http.get(`${BASE_URL}/api/v1/auctions/subscribers/count`);
    console.log(`[FINAL] Auction ${AUCTION_ID} subscribers: ${countRes.body}`);
    console.log(`[FINAL] Total subscribers: ${totalRes.body}`);
}
