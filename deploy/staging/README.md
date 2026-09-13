# Staging GitOps Manifests

`deploy/staging/docker-compose.yml` is the desired state for staging deployment of `catalog-service`.
`deploy/staging/gitops.env` is the tracked GitOps values file (image + port).

GitOps intent:
- image version changes happen via git commits to `CATALOG_SERVICE_IMAGE`;
- rollback is a git revert of the commit that changed the image/tag;
- runtime applies desired state with `docker compose up -d`.

Operational helpers:
- apply + smoke: `scripts/gitops_apply_env.sh staging --smoke`;
- promotion source for prod: `scripts/gitops_promote_staging_to_prod.sh` reads digest from this file by default.

Use `.env.example` as a local-only template. For GitOps commits, update `gitops.env`.
