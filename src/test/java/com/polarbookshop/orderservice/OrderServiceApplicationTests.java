package com.polarbookshop.orderservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polarbookshop.orderservice.book.Book;
import com.polarbookshop.orderservice.book.BookClient;
import com.polarbookshop.orderservice.order.domain.Order;
import com.polarbookshop.orderservice.order.domain.OrderStatus;
import com.polarbookshop.orderservice.order.event.OrderAcceptedMessage;
import com.polarbookshop.orderservice.order.web.OrderRequest;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestChannelBinderConfiguration.class)
@Testcontainers
class OrderServiceApplicationTests {

	private static KeycloakToken user1Tokens;
	private static KeycloakToken user2Tokens;

	@Container
	private static final KeycloakContainer keycloakContainer =
		new KeycloakContainer("quay.io/keycloak/keycloak:23.0")
			.withRealmImportFile("test-realm-config.json");

  @Container
  static PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>(
    DockerImageName.parse("postgres:15.4")
  );

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private OutputDestination output;

	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BookClient bookClient;

	@DynamicPropertySource
	static void postgresqlProperties(DynamicPropertyRegistry reg) {
		reg.add("spring.r2dbc.url", OrderServiceApplicationTests::r2dbcUrl);
		reg.add("spring.r2dbc.username", postgresql::getUsername);
		reg.add("spring.r2dbc.password", postgresql::getPassword);
		reg.add("spring.flyway.url", postgresql::getJdbcUrl);

		reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
			() -> keycloakContainer.getAuthServerUrl() + "/realms/PolarBookshop");
	}

	private static String r2dbcUrl() {
		return String.format(
			"r2dbc:postgresql://%s:%s/%s",
			postgresql.getHost(),
			postgresql.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
			postgresql.getDatabaseName()
		);
	}

	@BeforeAll
	static void generateAccessTokens() {
		WebClient webClient = WebClient.builder()
			.baseUrl(keycloakContainer.getAuthServerUrl() + "/realms/PolarBookshop/protocol/openid-connect/token")
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
			.build();

		user1Tokens = authenticateWith("user1", "password", webClient);
		user2Tokens = authenticateWith("user2", "password", webClient);
	}

	private static KeycloakToken authenticateWith(String username, String password, WebClient webClient) {
		return webClient
			.post()
			.body(BodyInserters.fromFormData("grant_type", "password")
				.with("client_id", "polar-test")
				.with("username", username)
				.with("password", password)
			)
			.retrieve()
			.bodyToMono(KeycloakToken.class)
			.block();
	}

	private record KeycloakToken(String accessToken) {
		@JsonCreator
		private KeycloakToken(@JsonProperty("access_token") final String accessToken) {
			this.accessToken = accessToken;
		}
	}

	@Test
	void whenGetOwnOrdersThenReturn() throws IOException {
		var isbn = "1234567893";
		var book = new Book(isbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(isbn)).willReturn(Mono.just(book));
		var orderRequest = new OrderRequest(isbn, 1);

		var expectedOrder = webTestClient.post().uri("/orders")
			.headers(headers -> headers.setBearerAuth(user1Tokens.accessToken()))
			.bodyValue(orderRequest)
			.exchange()
			.expectStatus()
				.is2xxSuccessful()
			.expectBody(Order.class)
			.returnResult()
			.getResponseBody();

		assertThat(expectedOrder)
			.isNotNull();
		assertThat(objectMapper.readValue(output.receive().getPayload(), OrderAcceptedMessage.class))
			.isEqualTo(new OrderAcceptedMessage(expectedOrder.id()));

		webTestClient.get().uri("/orders")
			.headers(headers -> headers.setBearerAuth(user1Tokens.accessToken()))
			.exchange()
			.expectStatus()
				.is2xxSuccessful()
			.expectBodyList(Order.class).value(orders -> {
				assertThat(
					orders.stream()
						.filter(order -> order.bookIsbn().equals(isbn))
						.findAny()
				).isNotEmpty();
			});
	}

	@Test
	void whenGetOrdersForAnotherUserThenNotReturned() throws IOException {
		String bookIsbn = "1234567899";
		Book book = new Book(bookIsbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(bookIsbn)).willReturn(Mono.just(book));
		OrderRequest orderRequest = new OrderRequest(bookIsbn, 1);

		Order orderByUser2 = webTestClient.post().uri("/orders")
				.headers(headers -> headers.setBearerAuth(user2Tokens.accessToken()))
				.bodyValue(orderRequest)
				.exchange()
				.expectStatus()
					.is2xxSuccessful()
				.expectBody(Order.class)
				.returnResult()
				.getResponseBody();

		assertThat(orderByUser2)
			.isNotNull();
		assertThat(
			objectMapper.readValue(
				output.receive().getPayload(),
				OrderAcceptedMessage.class
			)
		).isEqualTo(new OrderAcceptedMessage(orderByUser2.id()));

		Order orderByUser1 = webTestClient.post().uri("/orders")
				.headers(headers -> headers.setBearerAuth(user1Tokens.accessToken()))
				.bodyValue(orderRequest)
				.exchange()
				.expectStatus()
					.is2xxSuccessful()
				.expectBody(Order.class)
				.returnResult()
				.getResponseBody();

		assertThat(orderByUser1)
			.isNotNull();
		assertThat(
			objectMapper.readValue(
				output.receive().getPayload(),
				OrderAcceptedMessage.class)
		).isEqualTo(new OrderAcceptedMessage(orderByUser1.id()));

		webTestClient.get().uri("/orders")
				.headers(headers -> headers.setBearerAuth(user2Tokens.accessToken()))
				.exchange()
				.expectStatus()
					.is2xxSuccessful()
				.expectBodyList(Order.class)
				.value(orders -> {
					List<Long> orderIds = orders.stream()
							.map(Order::id)
							.collect(Collectors.toList());
					assertThat(orderIds)
						.contains(orderByUser2.id());
					assertThat(orderIds)
						.doesNotContain(orderByUser1.id());
				});
	}

	@Test
	void whenPostRequestAndBookExistsThenOrderAccepted() throws IOException {
		var isbn = "1234567899";
		var book = new Book(isbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(isbn)).willReturn(Mono.just(book));
		var orderRequest = new OrderRequest(isbn, 3);

		var createdOrder = webTestClient.post().uri("/orders")
			.headers(headers -> headers.setBearerAuth(user1Tokens.accessToken()))
			.bodyValue(orderRequest)
			.exchange()
			.expectStatus()
				.is2xxSuccessful()
			.expectBody(Order.class)
			.value(order -> {
				assertThat(order.bookIsbn())
					.isEqualTo(orderRequest.isbn());
				assertThat(order.quantity())
					.isEqualTo(orderRequest.quantity());
				assertThat(order.bookName())
					.isEqualTo(book.title() + " - " + book.author());
				assertThat(order.bookPrice())
					.isEqualTo(book.price());
				assertThat(order.status())
					.isEqualTo(OrderStatus.ACCEPTED);
			})
			.returnResult()
			.getResponseBody();

		assertThat(
			objectMapper.readValue(
				output.receive().getPayload(),
				OrderAcceptedMessage.class
			)
		).isEqualTo(new OrderAcceptedMessage(createdOrder.id()));
	}

	@Test
	void whenPostRequestAndBookNotExistsThenOrderRejected() {
		var isbn = "1234567894";
		given(bookClient.getBookByIsbn(isbn)).willReturn(Mono.empty());
		var orderRequest = new OrderRequest(isbn, 3);

		webTestClient.post().uri("/orders")
			.headers(headers -> headers.setBearerAuth(user1Tokens.accessToken()))
			.bodyValue(orderRequest)
			.exchange()
			.expectStatus()
				.is2xxSuccessful()
			.expectBody(Order.class)
			.value(order -> {
				assertThat(order.bookIsbn())
					.isEqualTo(orderRequest.isbn());
				assertThat(order.quantity())
					.isEqualTo(orderRequest.quantity());
				assertThat(order.status())
					.isEqualTo(OrderStatus.REJECTED);
			});
	}
}
