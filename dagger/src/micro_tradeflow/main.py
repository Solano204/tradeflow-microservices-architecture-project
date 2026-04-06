import dagger
from dagger import dag, function, object_type, Secret
import asyncio

SERVICES = [
    {"name": "auth-service",      "port": 8080, "quarkus": True},
    {"name": "identity-service",  "port": 8081, "quarkus": False},
    {"name": "catalog-service",   "port": 8082, "quarkus": False},
    {"name": "inventory-service", "port": 8083, "quarkus": False},
    {"name": "order-service",     "port": 8084, "quarkus": False},
    {"name": "payment-service",   "port": 8085, "quarkus": True},
    {"name": "search-service",    "port": 8089, "quarkus": False},
]


@object_type
class MicroTradeflow:

    @function
    async def build_service(
            self,
            source: dagger.Directory,
            service: str,
    ) -> dagger.Container:
        """Build a single service. Fast feedback for local dev."""
        cfg = self._find_service(service)
        if cfg["quarkus"]:
            return await self._build_quarkus(source, cfg)
        return await self._build_spring(source, cfg)

    @function
    async def test_service(
            self,
            source: dagger.Directory,
            service: str,
    ) -> str:
        """Run tests for a single service."""
        cfg = self._find_service(service)
        return await (
            self._maven_base(source, cfg)
            .with_exec(["mvn", "test", "-pl", cfg["name"], "-B", "--no-transfer-progress"])
            .stdout()
        )

    @function
    async def publish_all(
            self,
            source: dagger.Directory,
            docker_username: str,
            docker_password: Secret,
            git_sha: str,
    ) -> str:
        """Build + push all 8 services to Docker Hub in parallel."""
        results = await asyncio.gather(*[
            self._publish_one(source, cfg, docker_username, docker_password, git_sha)
            for cfg in SERVICES
        ])
        return "\n".join(results)

    @function
    async def deploy(
            self,
            source: dagger.Directory,
            docker_username: str,
            docker_password: Secret,
            github_token: Secret,
            git_sha: str,
    ) -> str:
        """Full pipeline: push images + update GitOps repo for ArgoCD."""
        published = await self.publish_all(source, docker_username, docker_password, git_sha)
        gitops = await self._update_gitops(github_token, git_sha)
        return f"Deploy complete @ {git_sha}\n{published}\n{gitops}"

    # ── internals ──────────────────────────────────────────────────────────

    def _maven_base(self, source: dagger.Directory, cfg: dict) -> dagger.Container:
        return (
            dag.container()
            .from_("maven:3.9-eclipse-temurin-21-alpine")
            .with_mounted_directory("/workspace", source)
            .with_mounted_cache(
                "/root/.m2",
                dag.cache_volume(f"tradeflow-maven-{cfg['name']}"),
            )
            .with_workdir("/workspace")
        )

    async def _build_spring(self, source: dagger.Directory, cfg: dict) -> dagger.Container:
        builder = (
            self._maven_base(source, cfg)
            .with_exec([
                "mvn", "package", "-DskipTests", "-B",
                "--no-transfer-progress", "-pl", cfg["name"],
            ])
            # ✅ rename whatever-1.0.0.jar → app.jar so the path is always known
            .with_exec([
                "sh", "-c",
                f"cp /workspace/{cfg['name']}/target/{cfg['name']}-*.jar "
                f"/workspace/{cfg['name']}/target/app.jar"
            ])
        )
        return (
            dag.container()
            .from_("eclipse-temurin:21-jre-alpine")
            .with_workdir("/app")
            .with_file(
                "/app/app.jar",
                builder.file(f"/workspace/{cfg['name']}/target/app.jar")
            )
            .with_exposed_port(cfg["port"])
            .with_entrypoint([
                "java",
                "-XX:+UseContainerSupport",
                "-XX:MaxRAMPercentage=75.0",
                "-jar", "app.jar"
            ])
        )

    async def _build_quarkus(self, source: dagger.Directory, cfg: dict) -> dagger.Container:
        builder = (
            self._maven_base(source, cfg)
            .with_exec([
                "mvn", "package", "-DskipTests", "-B",
                "--no-transfer-progress", "-pl", cfg["name"],
                "-Dquarkus.package.type=fast-jar",
            ])
        )
        target = f"/workspace/{cfg['name']}/target/quarkus-app"
        return (
            dag.container()
            .from_("eclipse-temurin:21-jre-alpine")
            .with_workdir("/work")
            .with_directory("/work/lib",     builder.directory(f"{target}/lib"))
            .with_directory("/work/app",     builder.directory(f"{target}/app"))
            .with_directory("/work/quarkus", builder.directory(f"{target}/quarkus"))
            .with_file("/work/quarkus-run.jar", builder.file(f"{target}/quarkus-run.jar"))
            .with_exposed_port(cfg["port"])
            .with_entrypoint([
                "java",
                "-XX:+UseContainerSupport",
                "-XX:MaxRAMPercentage=75.0",
                "-jar", "quarkus-run.jar"
            ])
        )

    async def _publish_one(
            self,
            source: dagger.Directory,
            cfg: dict,
            docker_username: str,
            docker_password: Secret,
            git_sha: str,
    ) -> str:
        built = (
            await self._build_quarkus(source, cfg)
            if cfg["quarkus"]
            else await self._build_spring(source, cfg)
        )
        image_ref = f"{docker_username}/tradeflow-{cfg['name']}:{git_sha}"
        latest_ref = f"{docker_username}/tradeflow-{cfg['name']}:latest"
        auth = built.with_registry_auth(
            "https://index.docker.io/v1/",
            docker_username,
            docker_password
        )
        await auth.publish(image_ref)
        await auth.publish(latest_ref)
        return image_ref

    async def _update_gitops(self, github_token: Secret, git_sha: str) -> str:
        script = """
set -e
git config --global user.email "ci@tradeflow.io"
git config --global user.name "TradeFlow CI"
git clone https://$GITHUB_TOKEN@github.com/Solano204/tradeflow-k8s-gitops.git /gitops
cd /gitops
for service in auth-service identity-service catalog-service inventory-service \
               order-service payment-service fraud-service search-service; do
    FILE="services/$service/deployment.yaml"
    [ -f "$FILE" ] && sed -i \
        "s|joshua76i/tradeflow-${service}:.*|joshua76i/tradeflow-${service}:${GIT_SHA}|g" \
        "$FILE"
done
git add -A
git diff --staged --quiet || git commit -m "ci: deploy all @ ${GIT_SHA}"
git push origin main
echo "GitOps updated"
"""
        return await (
            dag.container()
            .from_("alpine/git:latest")
            .with_secret_variable("GITHUB_TOKEN", github_token)
            .with_env_variable("GIT_SHA", git_sha)
            .with_exec(["sh", "-c", script])
            .stdout()
        )

    def _find_service(self, name: str) -> dict:
        for s in SERVICES:
            if s["name"] == name:
                return s
        valid = [s["name"] for s in SERVICES]
        raise ValueError(f"Unknown service: {name}. Valid: {valid}")