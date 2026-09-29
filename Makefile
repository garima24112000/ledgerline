COMPOSE := docker compose -f infra/docker-compose.yml
APPS    := gateway-api webhook-dispatcher mock-bank demo-merchant
PORTS   := 8080 8081 8082 8083

.PHONY: up down build test check-ports run-all grafana seed-payments explain-payments explain-outbox explain-audit kind-up kind-down aws-up aws-down

up: ## Start Postgres, Redis, Kafka, Prometheus and Grafana; waits until all are healthy
	$(COMPOSE) up -d --wait

down: ## Stop local infra (keeps the Postgres volume)
	$(COMPOSE) down

build: ## Compile and package all apps, skipping tests
	mvn -q clean package -DskipTests

test: ## Unit tests + integration tests (Testcontainers needs Docker)
	mvn -q clean verify

check-ports: ## Fail if any app port is taken (a stale app would keep serving while the new one exits)
	@busy=0; \
	for port in $(PORTS); do \
		if lsof -nP -iTCP:$$port -sTCP:LISTEN >/dev/null 2>&1; then \
			echo "Port $$port is already in use:"; \
			lsof -nP -iTCP:$$port -sTCP:LISTEN | tail -n +2; \
			busy=1; \
		fi; \
	done; \
	if [ $$busy -eq 1 ]; then \
		echo "Stop the stale process(es) above (e.g. Ctrl+C the old make run-all, or kill <PID>), then run make run-all again."; \
		exit 1; \
	fi

run-all: check-ports build ## Run all four apps; logs in logs/<app>.log, Ctrl+C stops them all
	@mkdir -p logs
	@trap 'kill 0' INT TERM; \
	for app in $(APPS); do \
		echo "starting $$app -> logs/$$app.log"; \
		java -jar $$app/target/$$app.jar > logs/$$app.log 2>&1 & \
	done; \
	wait

grafana: ## Where the dashboard and Prometheus targets are (after make up + make run-all)
	@echo "Grafana dashboard:  http://localhost:3000/d/ledgerline  (anonymous viewer; admin/admin to edit)"
	@echo "Prometheus targets: http://localhost:9090/targets"

seed-payments: ## Insert 1M demo payments into the local Postgres, for EXPLAIN measurements
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/seed-payments.sql

explain-payments: ## EXPLAIN ANALYZE the payment list query with and without its index
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-list-payments.sql

explain-outbox: ## EXPLAIN ANALYZE the outbox relay query with and without its index (seeds and rolls back)
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-outbox.sql

explain-audit: ## EXPLAIN ANALYZE the four ledger-audit Lambda queries (seeds UNKNOWN payments and rolls back)
	$(COMPOSE) exec -T postgres psql -U ledgerline -d ledgerline -f - < infra/scripts/explain-audit.sql

kind-up: ## Run everything on a local kind cluster (ingress, metrics-server, kube-prometheus-stack, chart)
	scripts/kind-up.sh

kind-down: ## Delete the kind cluster and its volumes
	scripts/kind-down.sh

aws-up: ## Terraform + EKS add-ons + secrets in us-east-1 (COSTS MONEY; see docs/DEPLOY.md)
	scripts/aws-up.sh

aws-down: ## Destroy everything in AWS and verify nothing chargeable is left
	scripts/aws-down.sh
