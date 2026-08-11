.PHONY: env image preflight test up smoke down

ENV_OUTPUT ?= .env
IMAGE ?= nexo-connect-lab:local
COMPOSE = docker compose --env-file .env --file compose.yaml

env:
	@./scripts/generate-local-env.sh "$(ENV_OUTPUT)"

image:
	@docker build --tag "$(IMAGE)" .

preflight:
	@test -f .env
	@$(COMPOSE) --profile setup config --quiet

test:
	@./gradlew clean test --console=plain

up: preflight
	@$(COMPOSE) up -d --build --wait --wait-timeout 180

smoke: preflight
	@./scripts/smoke-local-stack.sh

down: preflight
	@$(COMPOSE) --profile setup down --remove-orphans
