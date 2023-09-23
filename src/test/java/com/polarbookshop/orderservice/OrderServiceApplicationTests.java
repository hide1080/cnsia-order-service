package com.polarbookshop.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.polarbookshop.orderservice.book.Book;
import com.polarbookshop.orderservice.book.BookClient;
import com.polarbookshop.orderservice.order.domain.Order;
import com.polarbookshop.orderservice.order.domain.OrderStatus;
import com.polarbookshop.orderservice.order.web.OrderRequest;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Objects;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceApplicationTests {

  @Container
  static PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>(
    DockerImageName.parse("postgres:15.3")
  );

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
	}

	private static String r2dbcUrl() {
		return String.format(
			"r2dbc:postgresql://%s:%s/%s",
			postgresql.getHost(),
			postgresql.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
			postgresql.getDatabaseName()
		);
	}

	@Test
	void whenGetOrdersThenReturn() {
		var isbn = "1234567893";
		var book = new Book(isbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(isbn)).willReturn(Mono.just(book));
		var orderRequest = new OrderRequest(isbn, 1);

		var expectedOrder = webTestClient.post().uri("/orders")
			.bodyValue(orderRequest)
			.exchange()
			.expectStatus().is2xxSuccessful()
			.expectBody(Order.class).returnResult().getResponseBody();
		assertThat(expectedOrder).isNotNull();

		webTestClient.get().uri("/orders")
			.exchange()
			.expectStatus().is2xxSuccessful()
			.expectBodyList(Order.class).value(orders -> {
				assertThat(orders.stream().filter(order -> order.bookIsbn().equals(isbn)).findAny()).isNotEmpty();
			});
	}

	@Test
	void whenPostRequestAndBookExistsThenOrderAccepted() {
		var isbn = "1234567899";
		var book = new Book(isbn, "Title", "Author", 9.90);
		given(bookClient.getBookByIsbn(isbn)).willReturn(Mono.just(book));
		var orderRequest = new OrderRequest(isbn, 3);

		var createdOrder = webTestClient.post().uri("/orders")
			.bodyValue(orderRequest)
			.exchange()
			.expectStatus().is2xxSuccessful()
			.expectBody(Order.class).returnResult().getResponseBody();

		assertThat(createdOrder).isNotNull();
		assertThat(Objects.requireNonNull(createdOrder).bookIsbn())
			.isEqualTo(orderRequest.isbn());
		assertThat(createdOrder.quantity())
			.isEqualTo(orderRequest.quantity());
		assertThat(createdOrder.bookName())
			.isEqualTo(book.title() + " - " + book.author());
		assertThat(createdOrder.bookPrice())
			.isEqualTo(book.price());
		assertThat(createdOrder.status())
			.isEqualTo(OrderStatus.ACCEPTED);
	}
}
