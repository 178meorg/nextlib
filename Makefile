.DEFAULT_GOAL := help
# Both Media3 builds share Gradle and native output directories.
.NOTPARALLEL:

GRADLE ?= ./gradlew
GRADLE_ARGS ?=
# Leave empty to use nextlibVersion from gradle.properties.
VERSION ?=
VERSION_ARG = $(if $(strip $(VERSION)),-PnextlibVersion="$(VERSION)")

.PHONY: help build build-legacy build-modern publish publish-legacy publish-modern \
        publish-local publish-local-legacy publish-local-modern

help:
	@printf '%s\n' \
	  'make build                       Build both Media3 versions sequentially' \
	  'make publish                     Publish both versions to the configured repository' \
	  'make publish-local               Install both versions into local Maven' \
	  'make <target>-legacy             Run only Media3 1.8.0 (API 21+)' \
	  'make <target>-modern             Run only Media3 1.10.1 (API 23+)' \
	  'make publish VERSION=0.9.0-rmvb.3 Override the shared extension version' \
	  'Optional: GRADLE_ARGS="--offline --console=plain"'

build: build-legacy build-modern

build-legacy:
	$(GRADLE) :media3ext:assembleRelease -Pmedia3Version=1.8.0 $(VERSION_ARG) $(GRADLE_ARGS)

build-modern:
	$(GRADLE) :media3ext:assembleRelease -Pmedia3Version=1.10.1 $(VERSION_ARG) $(GRADLE_ARGS)

publish: publish-legacy publish-modern

publish-legacy:
	$(GRADLE) :media3ext:publishAllPublicationsToCompanyRepository -Pmedia3Version=1.8.0 $(VERSION_ARG) $(GRADLE_ARGS)

publish-modern:
	$(GRADLE) :media3ext:publishAllPublicationsToCompanyRepository -Pmedia3Version=1.10.1 $(VERSION_ARG) $(GRADLE_ARGS)

publish-local: publish-local-legacy publish-local-modern

publish-local-legacy:
	$(GRADLE) :media3ext:publishToMavenLocal -Pmedia3Version=1.8.0 $(VERSION_ARG) $(GRADLE_ARGS)

publish-local-modern:
	$(GRADLE) :media3ext:publishToMavenLocal -Pmedia3Version=1.10.1 $(VERSION_ARG) $(GRADLE_ARGS)
