# Prod Canary GitOps Manifests

`deploy/prod-canary/docker-compose.yml` is the desired state for canary deployment of `catalog-service`.
`deploy/prod-canary/gitops.env` is the tracked GitOps values file (image + port).

GitOps intent:
- canary always uses immutable image references (`image@sha256:...`);
- this state is updated before full prod promotion;
- if canary degrades, rollback is a git revert of the image change commit.

Operational helpers:
- canary-only promotion: `TARGET=canary scripts/gitops_promote_staging_to_prod.sh`;
- apply + smoke: `scripts/gitops_apply_env.sh prod-canary --smoke`.

Use `.env.example` as a local-only template. For GitOps commits, update `gitops.env`.
