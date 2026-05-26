package com.arbbot.exchange;

public interface Exchange {

  String name();

  void connect();

  void disconnect();

  boolean isConnected();

  /** Phase 2 stub — order placement not implemented in Phase 1. */
  default void placeOrder(String symbol, String side, double qty) {
    throw new UnsupportedOperationException("Phase 2 not implemented");
  }
}
