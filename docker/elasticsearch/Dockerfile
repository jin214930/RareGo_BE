# 사용할 엘라스틱서치 버전과 동일하게 설정
FROM docker.elastic.co/elasticsearch/elasticsearch:8.13.4

# 한글 분석기(Nori) 설치
RUN bin/elasticsearch-plugin install --batch analysis-nori