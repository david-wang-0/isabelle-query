# Makefile for isabelle-query.
#
# Development: create and activate a venv, then `make dev` installs the
# package (editable) plus the PEP 735 `test` dependency group, and
# `make test` runs the suite.
#
# Release: CLI.version is the single source of truth for the Scala
# release version. `make release` reads it, creates an annotated git tag
# v<version> on the current commit, then pushes the current branch and that
# tag to the remote. The release workflow publishes the tag in that repository.
#
# To attach human-readable notes afterwards:
#   gh release create v<version> --title "isabelle-query <version>" --notes "..."

REMOTE ?= origin

# The frozen Python reference has its own unchanged upstream version.
VERSION := $(shell sed -n 's/^  val version = "\([^"]*\)"/\1/p' query_base/src/cli.scala)
PLUGIN_VERSION := $(shell sed -n 's/^plugin.isabelle.jedit_query_plugin.Plugin.version=//p' jedit_query/jedit_query_plugin/plugin.props)
TAG     := v$(VERSION)

.DEFAULT_GOAL := version
.PHONY: version release check-release-version dev test test-scala test-transport test-compat-inputs check-upstream compat

# Install the package (editable) plus the PEP 735 `test` dependency group
# into the active environment.  Create and activate a venv first; then a
# single `make dev` gives a working, test-ready checkout.
dev:
	python3 -m pip install -e .
	python3 -m pip install --group test

# Routine engine regressions run once in a private cached Scala JVM.
test: check-upstream test-scala test-transport test-compat-inputs

test-scala:
	dev/scala-tests.sh

test-transport:
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p test_p12_transport.py

test-compat-inputs:
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p test_compat_inputs.py

check-upstream:
	python3 dev/check-upstream-compat.py

compat:
	python3 dev/check-upstream-compat.py --force

# Print the tag that `make release` would create.
version: check-release-version
	@echo $(TAG)

check-release-version:
	@test -n "$(VERSION)" || { echo "error: could not read CLI.version"; exit 1; }
	@test "$(PLUGIN_VERSION)" = "$(VERSION)" || { echo "error: plugin version $(PLUGIN_VERSION) differs from CLI.version $(VERSION)"; exit 1; }

# Tag the current commit as v<version> (annotated), then push the current
# branch and the tag to $(REMOTE).
release: check-release-version
	@git update-index -q --refresh
	@git diff-index --quiet HEAD -- || { echo "error: uncommitted changes in working tree; commit or stash before releasing"; exit 1; }
	@if git rev-parse -q --verify "refs/tags/$(TAG)" >/dev/null; then \
		echo "error: tag $(TAG) already exists locally"; exit 1; \
	fi
	@if git ls-remote --exit-code --tags $(REMOTE) "refs/tags/$(TAG)" >/dev/null 2>&1; then \
		echo "error: tag $(TAG) already exists on $(REMOTE)"; exit 1; \
	fi
	@echo "Tagging $$(git rev-parse --short HEAD) as $(TAG); pushing branch + tag to $(REMOTE)..."
	@# Release notes come from the *tagged (HEAD) commit's message*: CI
	@# (.github/workflows/release.yml) reads it and publishes it as the GitHub
	@# Release body.  Write the changelog in the version-bump commit, e.g.
	@#   git commit -m "$(VERSION) - changes from <prev>" -m "## Added" -m "- ..."
	@# The tag message itself is just a label.
	@if [ "$$(git log -1 --format=%B HEAD | sed '/^$$/d' | wc -l | tr -d ' ')" -le 1 ]; then \
		echo "warning: HEAD's commit message is a single line, so the Release"; \
		echo "         notes will be just that line.  Amend it with the changelog"; \
		echo "         for fuller notes.  Continuing in 3s (Ctrl-C to abort)..."; \
		sleep 3; \
	fi
	git tag -a "$(TAG)" -m "isabelle-query $(VERSION)"
	git push $(REMOTE) HEAD "$(TAG)"
	@echo "Released $(TAG) to $(REMOTE); the release workflow publishes HEAD's commit message in that repository."
