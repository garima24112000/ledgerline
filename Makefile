COMPOSE := docker compose -f infra/docker-compose.yml
APPS    := gateway-api webhook-dispatcher mock-bank demo-merchant

.PHONY: up down build test run-all seed-payments explain-payments explain-outbox

up: ## Start Postgres, Redis and Kafka; waits until all are healthy
	$(COMPOSE) up -d --wait

down: ## Stop local infra (keeps the Postgres volume)
	$(COMPOSE) down

build: ## Compile and package all apps, skipping tests
	mvn -q clean package -DskipTests

test: ## Unit tests + integration tests (Testcontainers needs Docker)
	mvn -q clean verify

run-all: build ## Run all four apps; logs in logs/<app>.log, Ctrl+C stops them all
	@mkdir -p logs
	@trap 'kill 0' INT TERM; \
	for app in $(APPS); do \
		echo "starting $$app -> logs/$$app.log"; \
		java -jar $$app/target/$$app.jar > logs/$$app.log 2>&1 & \
	done; \
	wait

seed-payments: ## Insert 1M demo payments into the local Postgres, for EXPLAIN measurements
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/seed-payments.sql

explain-payments: ## EXPLAIN ANALYZE the payment list query with and without its index
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-list-payments.sql

explain-outbox: ## EXPLAIN ANALYZE the outbox relay query with and without its index (seeds and rolls back)
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-outbox.sql
