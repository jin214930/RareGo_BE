import http from 'k6/http';
import {check, sleep} from 'k6';
import {Counter} from 'k6/metrics';
import {randomIntBetween} from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

// 디버깅용 커스텀 메트릭
const listEmpty = new Counter('list_empty_response');       // 목록이 비어서 온 횟수
const listParseFail = new Counter('list_parse_fail');       // JSON 파싱 실패
const detailFallback = new Counter('detail_used_fallback'); // 기본값(9999)으로 상세 조회한 횟수
const detailFromList = new Counter('detail_from_list');     // 목록에서 추출한 ID로 상세 조회한 횟수

export const options = {
    stages: [
        {duration: '10s', target: 100},
        {duration: '1m', target: 100},
        {duration: '10s', target: 0},
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.95'],
    },
};

const BASE_URL = 'http://localhost:8083';

export default function () {
    // 1. 경매 목록 조회 (페이징 랜덤)
    const page = 0;
    const listRes = http.get(`${BASE_URL}/api/v1/auctions?page=${page}&size=20&sort=NEWEST`);

    const listOk = check(listRes, {
        'list status is 200': (r) => r.status === 200,
    });

    // 2. 목록에서 랜덤한 경매 ID 추출
    let auctionId = 9999;
    let usedFallback = true;

    if (listOk) {
        try {
            const body = JSON.parse(listRes.body);
            const auctions = body.data;

            check(body, {
                'list has data array': (b) => Array.isArray(b.data),
                'list is not empty': (b) => Array.isArray(b.data) && b.data.length > 0,
            });

            if (auctions && auctions.length > 0) {
                const randomIndex = randomIntBetween(0, auctions.length - 1);
                const picked = auctions[randomIndex];

                check(picked, {
                    'auction has auctionId': (a) => a.auctionId !== undefined && a.auctionId !== null,
                });

                if (picked.auctionId) {
                    auctionId = picked.auctionId;
                    usedFallback = false;
                    detailFromList.add(1);
                }
            } else {
                listEmpty.add(1);
            }
        } catch (e) {
            listParseFail.add(1);
        }
    }

    if (usedFallback) {
        detailFallback.add(1);
    }

    sleep(randomIntBetween(1, 3));

    // 3. 상세 조회
    const detailRes = http.get(`${BASE_URL}/api/v1/auctions/${auctionId}`);

    check(detailRes, {
        'detail status is 200': (r) => r.status === 200,
        'detail has body': (r) => r.body && r.body.length > 10,
    });

    // 첫 번째 VU의 첫 반복에서만 응답 구조 로그 출력 (디버깅용)
    if (__ITER === 0 && __VU === 1) {
        console.log(`[DEBUG] List response status: ${listRes.status}`);
        console.log(`[DEBUG] List body (first 500 chars): ${listRes.body.substring(0, 500)}`);
        console.log(`[DEBUG] Detail auctionId: ${auctionId}, status: ${detailRes.status}`);
        console.log(`[DEBUG] Detail body (first 500 chars): ${detailRes.body.substring(0, 500)}`);
    }

    sleep(randomIntBetween(2, 5));
}
