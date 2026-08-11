.PHONY: env image

ENV_OUTPUT ?= .env
IMAGE ?= nexo-connect-lab:local

env:
	@./scripts/generate-local-env.sh "$(ENV_OUTPUT)"

image:
	@docker build --tag "$(IMAGE)" .
