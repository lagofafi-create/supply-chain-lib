import importlib.util
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
_spec = importlib.util.spec_from_file_location("wrap", os.path.join(ROOT, "resources/hardening/wrap.py"))
wrap = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(wrap)

CFG = {"Env": ["PATH=/opt/app/bin:/usr/bin", "JAVA_OPTS=-Xmx512m $EXTRA"], "WorkingDir": "/opt/app",
       "ExposedPorts": {"8080/tcp": {}}, "Volumes": {"/data": {}}, "Entrypoint": ["/opt/app/run.sh"],
       "Cmd": ["--serve"], "User": "1001:1001", "StopSignal": "SIGTERM"}
LABELS = {"org.opencontainers.image.vendor": "Acme", "acme.container.governance.image.auid": "AP1"}


def test_config_from_single_and_multiarch_inspect_shapes():
    single = {"architecture": "amd64", "config": CFG}
    multi = {"linux/amd64": single, "linux/arm64": {"architecture": "arm64", "config": CFG}}
    assert wrap.image_config(single) == CFG
    assert wrap.image_config(multi) == CFG
    assert wrap.image_config(CFG) == CFG            # docker inspect .Config shape


def test_dockerfile_hardens_flattens_and_reemits_config():
    df = wrap.render("repo@sha256:abc", CFG, LABELS)
    lines = df.splitlines()
    assert "FROM repo@sha256:abc AS build" in lines
    assert "RUN sh /usr/local/lib/hardening/harden.sh" in lines
    assert lines.index("FROM scratch") > lines.index("RUN sh /usr/local/lib/hardening/harden.sh")
    assert "COPY --from=build / /" in lines
    assert 'ENV PATH="/opt/app/bin:/usr/bin"' in lines
    assert 'ENV JAVA_OPTS="-Xmx512m \\$EXTRA"' in lines        # $ is escaped: no expansion
    assert "WORKDIR /opt/app" in lines
    assert "EXPOSE 8080/tcp" in lines
    assert 'VOLUME ["/data"]' in lines
    assert "STOPSIGNAL SIGTERM" in lines
    assert 'ENTRYPOINT ["/opt/app/run.sh"]' in lines
    assert 'CMD ["--serve"]' in lines
    assert lines[-1].startswith("LABEL ") and "USER 1001:1001" in lines
    assert lines.index("USER 1001:1001") > lines.index("COPY --from=build / /")
    assert 'LABEL "acme.container.governance.image.auid"="AP1"' in lines


def test_root_source_is_flagged_and_path_defaulted():
    df = wrap.render("r@sha256:0", {"Env": []}, {})
    assert "WARNING: the source image runs as root" in df
    assert "USER 0" in df.splitlines()
    assert 'ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"' in df


def test_certs_are_installed_before_hardening_when_asked():
    df = wrap.render("r@sha256:0", CFG, LABELS, certs=True, runtime="java", alias_prefix="acme-internal")
    lines = df.splitlines()
    assert "COPY certs/ /tmp/pki/" in lines
    install = next(l for l in lines if "install-certs.sh" in l)
    assert "--runtime java" in install and "CERT_ALIAS_PREFIX=acme-internal" in install
    assert lines.index(install) < lines.index("RUN sh /usr/local/lib/hardening/harden.sh")
    assert "install-certs" not in wrap.render("r@sha256:0", CFG, LABELS)
