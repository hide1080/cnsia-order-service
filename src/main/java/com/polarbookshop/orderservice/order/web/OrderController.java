package com.polarbookshop.orderservice.order.web;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.*;

import com.polarbookshop.orderservice.order.domain.Order;
import com.polarbookshop.orderservice.order.domain.OrderService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("orders")
public class OrderController {

  private final OrderService orderService;

  OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @GetMapping
  public Flux<Order> getAllOrders() {
    return orderService.getAllOrders();
  }

  @PostMapping
  public Mono<Order> submitOrder(
    @RequestBody @Valid OrderRequest orderRequest) {

      return orderService.submitOrder(
      orderRequest.isbn(),
      orderRequest.quantity()
    );
  }
}
