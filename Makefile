COMPOSE := docker compose -f infra/docker-compose.yml
APPS    := gateway-api webhook-dispatcher mock-bank demo-merchant

.PHONY: up down build test run-all

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
