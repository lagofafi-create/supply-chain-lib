import copy
import glob
import json
import os

import pytest
import yaml

jsonschema = pytest.importorskip("jsonschema")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SCHEMA = json.load(open(os.path.join(ROOT, "schema/imageimport.schema.json")))


def _load(name):
    with open(os.path.join(ROOT, "examples", name)) as fh:
        return yaml.safe_load(fh)


def _invalid(doc):
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(doc, SCHEMA)


def test_every_example_is_valid():
    for f in glob.glob(os.path.join(ROOT, "examples/*.yaml")):
        jsonschema.validate(yaml.safe_load(open(f)), SCHEMA)


def test_auid_and_destination_are_required():
    d = copy.deepcopy(_load("jboss-eap.yaml")); del d["metadata"]["auid"]; _invalid(d)
    d = copy.deepcopy(_load("jboss-eap.yaml")); del d["spec"]["destination"]; _invalid(d)


def test_labels_may_be_left_to_detection():
    d = copy.deepcopy(_load("internal-app.yaml")); d["spec"].pop("labels")
    jsonschema.validate(d, SCHEMA)


def test_vendor_cannot_be_hardened_but_internal_can():
    d = copy.deepcopy(_load("jboss-eap.yaml")); d["spec"]["harden"] = True; _invalid(d)
    jsonschema.validate(_load("internal-app.yaml"), SCHEMA)      # origin internal + harden true


def test_origin_is_a_closed_enum():
    d = copy.deepcopy(_load("jboss-eap.yaml")); d["spec"]["origin"] = "partner"; _invalid(d)


PROV = json.load(open(os.path.join(ROOT, "resources/slsa/provenance.schema.json")))


def test_provenance_examples_match_the_contract():
    for name in ("provenance.input.example.json", "provenance.input.import.example.json"):
        with open(os.path.join(ROOT, "resources/slsa", name)) as fh:
            doc = json.load(fh)
        doc.pop("_comment", None)
        jsonschema.validate(doc, PROV)


def test_provenance_rejects_missing_digest_and_import_fields():
    with open(os.path.join(ROOT, "resources/slsa/provenance.input.import.example.json")) as fh:
        doc = json.load(fh)
    doc.pop("_comment", None)
    d = copy.deepcopy(doc); d["container"]["digest"] = "sha256:short"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(d, PROV)
    d = copy.deepcopy(doc); del d["import"]["sourceDigest"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(d, PROV)

