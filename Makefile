.PHONY: env

ENV_OUTPUT ?= .env

env:
	@./scripts/generate-local-env.sh "$(ENV_OUTPUT)"
