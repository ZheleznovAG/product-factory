# Prod GitOps Manifests

`deploy/prod/docker-compose.yml` is the desired state for production deployment of `catalog-service`.
`deploy/prod/gitops.env` is the tracked GitOps values file (image + port).

GitOps intent:
- only immutable image references are allowed in prod (`image@sha256:...`);
- promotion to prod uses the same digest that already passed staging;
- rollback is a git revert of the commit that changed `CATALOG_SERVICE_IMAGE`.

Operational helpers:
- promote into prod only: `scripts/gitops_promote_staging_to_prod.sh` (default target is `prod`);
- apply + smoke: `scripts/gitops_apply_env.sh prod --smoke`.

Use `.env.example` as a local-only template. For GitOps commits, update `gitops.env`.
