package com.easys;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// WebSocketConfig의 ServletServerContainerFactoryBean은 실제 서블릿 컨테이너(ServerContainer)가
// 있어야 생성되므로, MOCK 웹 환경 대신 임베디드 서버를 랜덤 포트로 띄워 컨텍스트를 검증한다.
// 실행하려면 application-local.properties(또는 환경변수)에 개발용 DB 접속 정보가 필요하다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EasysApplicationTests {

	@Test
	void contextLoads() {
	}

}
