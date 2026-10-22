package com.wicktech.walletd.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}
