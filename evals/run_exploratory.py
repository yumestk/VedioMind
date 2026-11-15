#!/usr/bin/env python3
"""Explicit, local note exploration. Stdlib only; paid calls require the run command."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RESULTS = Path.home() / "Documents/vm/eval-results"
DB = None
OUTPUT = None
EXPERIMENT = None
JAVA = Path.home() / "Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin"


def docker_mysql(query):
    return subprocess.run([
        "docker", "exec", "mysql-media", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot --batch --skip-column-names -e "$1"',
        "sh", query,
    ], check=True, capture_output=True).stdout


def dump(*args):
    return subprocess.run([
        "docker", "exec", "mysql-media", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --skip-add-locks --set-gtid-purged=OFF --no-tablespaces --skip-comments "$@"',
        "sh", *args,
    ], check=True, capture_output=True).stdout


def carried_reservation(budget):
    """Carry previous reservations exactly once, including all earlier batches."""
    held = budget["heldMicroCny"]
    prior = budget.get("priorHeldMicroCny", 0)
    if any(type(v) is not int or v < 0 for v in [held, prior]) or held + prior > 10_000_000:
        raise ValueError("Invalid parent reservation")
    total = held + prior
    if budget.get("roundHeldMicroCny", total) != total:
        raise ValueError("Inconsistent parent reservation")
    return total


def self_check():
    assert carried_reservation({"heldMicroCny": 1_748_367}) == 1_748_367
    assert carried_reservation({"heldMicroCny": 1_338_876, "priorHeldMicroCny": 1_748_367,
                                "roundHeldMicroCny": 3_087_243}) == 3_087_243
    for invalid in [{"heldMicroCny": -1}, {"heldMicroCny": 10_000_001},
                    {"heldMicroCny": 1, "roundHeldMicroCny": 2}]:
        try:
            carried_reservation(invalid)
        except ValueError:
            continue
        raise AssertionError("Invalid reservation accepted")


def prepare():
    OUTPUT.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Refuse to replace any previous database or result. Source business tables are read-only.
    if docker_mysql(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{DB}'").strip():
        raise RuntimeError("Exploration database already exists; no automatic reset")
    prior_held = 0
    parent_id = {"notes-exploration-002": "notes-exploration-001",
                 "notes-exploration-003": "notes-exploration-002",
                 "notes-exploration-004": "notes-exploration-003"}.get(EXPERIMENT)
    if parent_id:
        previous = RESULTS / parent_id
        parent = json.loads((previous / "manifest.json").read_text())
        snapshot = (previous / "materials.sql").read_bytes()
        if hashlib.sha256(snapshot).hexdigest() != parent["materialsSha256"]:
            raise RuntimeError("Previous material snapshot was changed")
        parent_budget = (previous / "budget.json").read_bytes()
        prior_held = carried_reservation(json.loads(parent_budget))
        (OUTPUT / "parent-budget.json").write_bytes(parent_budget)
    else:
        schema = dump("--no-data", "media_db")
        data = []
        for table in ["media_files", "transcript_segments", "media_visual_evidence", "video_keyframes", "video_knowledge_chunks"]:
            field = "id" if table == "media_files" else "media_id"
            data.append(dump("--no-create-info", "--skip-triggers", "media_db", table, f"--where={field} IN (42,43,44)"))
        seed = b"INSERT INTO users(id,username,password,nickname) VALUES (1,'eval-local-no-login','!disabled','Eval');\n"
        snapshot = schema + seed + b"\n".join(data)
    batch_limit = min(1_000_000 if EXPERIMENT == "notes-exploration-003" else 2_000_000 if EXPERIMENT == "notes-exploration-004" else 3_000_000,
                      10_000_000 - prior_held)
    if batch_limit <= 0:
        raise RuntimeError("No remaining round budget")
    (OUTPUT / "materials.sql").write_bytes(snapshot)
    docker_mysql(f"CREATE DATABASE {DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    subprocess.run([
        "docker", "exec", "-i", "mysql-media", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot "$1"', "sh", DB,
    ], input=snapshot, check=True, capture_output=True)
    for name in ["METHODOLOGY.md", "PLAN.md", "ExploratoryTrial.java", "run_exploratory.py"]:
        (OUTPUT / name).write_bytes((ROOT / "evals" / name).read_bytes())
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(["git", "diff", "--", "server/src/main"], cwd=ROOT)
    (OUTPUT / "agent.diff").write_bytes(diff)
    manifest = {
        "experimentId": EXPERIMENT, "type": "EXPLORATORY_NOT_BASELINE", "preparedOn": "2026-11-15",
        "methodologyVersion": "1.1.0", "planVersion": re.search(r"计划版本：`([^`]+)`", (ROOT / "evals/PLAN.md").read_text()).group(1), "datasetVersion": "0.1.0-draft",
        "rubricVersion": "NONE_EXPLORATORY", "groundTruthStatus": "UNVERIFIED", "agentSha": sha,
        "agentDirtyDiffSha256": hashlib.sha256(diff).hexdigest(), "materialsSha256": hashlib.sha256(snapshot).hexdigest(),
        "database": DB, "caseOrder": ["N03"] if EXPERIMENT == "notes-exploration-003" else ["N01", "N02", "N03"], "trialsPerCase": 1,
        "currency": "CNY", "roundBudget": 10, "batchBudget": batch_limit / 1_000_000,
        "priorHeldMicroCny": prior_held, "batchLimitMicroCny": batch_limit,
        "parentExperimentId": parent_id,
        "runtimeVersion": re.search(r'"(bounded-study-v\d+)"', (ROOT / "server/src/main/java/com/example/server/service/StudyAgentSpec.java").read_text()).group(1),
        "runnerHashes": {name: hashlib.sha256((ROOT / "evals" / name).read_bytes()).hexdigest()
                         for name in ["run_exploratory.py", "ExploratoryTrial.java"]},
        "ratesCnyPerMillion": {"deepseekInputCacheMissPeak": 2, "deepseekOutputPeak": 8, "embeddingBeijingInput": 0.5},
        "rateSources": ["https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", "https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/"],
        "rateCheckedOn": "2026-11-15", "reservationPolicy": "UTF-8 bytes for estimated input, maximum output; no refunds within batch; stop on unknown usage",
        "execution": "direct serial workflow; no web auth, MQ, scheduling, or automatic trial rerun",
        "learningIsolation": "empty private course per case in isolated MySQL; existing Qdrant index is read-only",
        "rawDataPrivate": True, "judgeCalls": 0,
    }
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Prepared isolated database and private material snapshot; zero paid calls.")


def execute(mode):
    if not (OUTPUT / "manifest.json").exists():
        raise RuntimeError("Run prepare first")
    manifest = json.loads((OUTPUT / "manifest.json").read_text())
    diff = subprocess.check_output(["git", "diff", "--", "server/src/main"], cwd=ROOT)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if sha != manifest["agentSha"] or hashlib.sha256(diff).hexdigest() != manifest["agentDirtyDiffSha256"]:
        raise RuntimeError("Agent changed since preparation; do not mix experiment versions")
    for name, digest in manifest["runnerHashes"].items():
        if hashlib.sha256((ROOT / "evals" / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Runner changed since preparation")
    # Verify existing vector collection without creating, indexing or embedding anything.
    with urllib.request.urlopen("http://localhost:6333/collections/video_knowledge_chunks_v1", timeout=5) as response:
        assert json.load(response)["result"]["status"] == "green"
    cp = str(ROOT / "server/target/classes") + ":" + (ROOT / "server/target/auth-classpath.txt").read_text().strip()
    classes = ROOT / "server/target/eval-classes"
    classes.mkdir(exist_ok=True)
    subprocess.run([str(JAVA / "javac"), "-proc:none", "-cp", cp, "-d", str(classes), str(ROOT / "evals/ExploratoryTrial.java")], check=True, cwd=ROOT)
    env = os.environ.copy()
    env["EVAL_DB_URL"] = f"jdbc:mysql://localhost:3307/{DB}?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true"
    env["EVAL_DB_PASSWORD"] = subprocess.check_output(["docker", "exec", "mysql-media", "printenv", "MYSQL_ROOT_PASSWORD"], text=True).strip()
    env["JAVA_TOOL_OPTIONS"] = "-Duser.timezone=Asia/Shanghai"
    print(f"Starting {mode}; logs and private traces: {OUTPUT}", flush=True)
    with (OUTPUT / f"{mode}.log").open("w") as log:
        subprocess.run([str(JAVA / "java"), "-cp", str(classes) + ":" + cp, "com.example.server.service.ExploratoryTrial", mode, str(OUTPUT)],
                       check=True, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    print(f"{mode} completed", flush=True)


if __name__ == "__main__":
    os.umask(0o077)
    self_check()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "preflight", "run"])
    parser.add_argument("--experiment", required=True, choices=["notes-exploration-001", "notes-exploration-002", "notes-exploration-003", "notes-exploration-004"])
    args = parser.parse_args()
    EXPERIMENT = args.experiment
    DB = "vediomind_eval_" + EXPERIMENT.replace("-", "_")
    OUTPUT = RESULTS / EXPERIMENT
    if args.mode == "prepare":
        prepare()
    else:
        execute(args.mode)
