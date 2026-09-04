# Internal CA

`*.crt` files here are injected into every image hardened by `hardenImage` (OS trust store, plus
the JVM cacerts or certifi when a runtime is detected). A file may be a bundle (root and
intermediate in one PEM); `install-certs.sh` splits it. The names are listed under `certs:` in
`config/supply-chain.yaml`. The bundle shipped here is a placeholder: replace it with the real
internal CA before the first production run.
