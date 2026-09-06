# The operator must supply a prebuilt base image containing the repository's approved JDK/build tools.
# Pin BASE_IMAGE to a digest; this Dockerfile intentionally performs no package installation or network access.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

USER 65532:65532
WORKDIR /workspace

# DockerWorkerIsolation overrides this command with its task-container keeper and executes approved commands via exec.
CMD ["sh", "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600; done"]
